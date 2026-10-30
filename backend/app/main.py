import logging
import threading
from contextlib import asynccontextmanager
from datetime import datetime, timezone

from fastapi import Depends, FastAPI, File, HTTPException, Query, Request, UploadFile
from fastapi.responses import JSONResponse
from slowapi.errors import RateLimitExceeded
from slowapi.middleware import SlowAPIMiddleware
from sqlalchemy import func, or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from . import ats as ats_mod
from . import matching
from . import taxonomy as tx
from .config import get_settings
from .connectors import CONNECTORS
from .connectors.deeplinks import DEEPLINK_SOURCES, build_links
from .db import get_db
from .ingest import seed_companies
from .models import Company, Job, SyncRun
from .resume_parser import ResumeRejected, from_text, parse_resume
from .scheduler import is_running, start_scheduler, sync_job
from .schemas import AnalyseIn, CompanyIn, CompanyOut, JobOut, JobPage, SyncRunOut
from .security import limiter, require_api_key

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")


@asynccontextmanager
async def lifespan(app: FastAPI):
    from .db import SessionLocal
    with SessionLocal() as db:
        seed_companies(db)
    sched = start_scheduler() if get_settings().enable_scheduler else None
    yield
    if sched:
        sched.shutdown(wait=False)


app = FastAPI(title="JobIntel API", version="1.0.0", lifespan=lifespan,
              docs_url=None, redoc_url=None, openapi_url=None)
app.state.limiter = limiter
app.add_middleware(SlowAPIMiddleware)


@app.exception_handler(RateLimitExceeded)
async def _rate_limited(request: Request, exc: RateLimitExceeded):
    return JSONResponse({"detail": "rate limit exceeded"}, status_code=429)


@app.middleware("http")
async def _security_headers(request: Request, call_next):
    cl = request.headers.get("content-length")
    if cl and cl.isdigit() and int(cl) > get_settings().max_upload_bytes + 64 * 1024:
        return JSONResponse({"detail": "request too large"}, status_code=413)
    resp = await call_next(request)
    resp.headers["X-Content-Type-Options"] = "nosniff"
    resp.headers["X-Frame-Options"] = "DENY"
    resp.headers["Referrer-Policy"] = "no-referrer"
    resp.headers["Cache-Control"] = "no-store"
    resp.headers["Content-Security-Policy"] = "default-src 'none'; frame-ancestors 'none'"
    resp.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"
    return resp


Auth = Depends(require_api_key)


@app.get("/health")
@limiter.limit("30/minute")
def health(request: Request):
    return {"status": "ok", "time": datetime.now(timezone.utc)}


@app.get("/v1/jobs", response_model=JobPage, dependencies=[Auth])
def list_jobs(
    request: Request,
    db: Session = Depends(get_db),
    q: str | None = Query(None, max_length=100),
    role_group: str | None = Query(None, pattern="^(cyber|fde)$"),
    role: str | None = Query(None, max_length=32, pattern=r"^[a-z]+$"),
    country: str | None = Query(None, pattern="^[A-Z]{2}$"),
    work_mode: str | None = Query(None, pattern="^(remote|hybrid|onsite|unknown)$"),
    seniority: str | None = Query(None, max_length=16, pattern=r"^[a-z]+$"),
    company: str | None = Query(None, max_length=200),
    updated_since: datetime | None = None,
    include_inactive: bool = False,
    include_duplicates: bool = False,
    page: int = Query(1, ge=1, le=10_000),
    page_size: int = Query(50, ge=1, le=200),
):
    stmt = select(Job)
    if not include_inactive:
        stmt = stmt.where(Job.is_active.is_(True))
    if not include_duplicates:
        stmt = stmt.where(Job.duplicate_of.is_(None))
    if q:
        like = f"%{q.lower()}%"
        stmt = stmt.where(or_(func.lower(Job.title).like(like), func.lower(Job.company_name).like(like),
                              func.lower(Job.skills_csv).like(like)))
    if role_group:
        stmt = stmt.where(Job.role_group == role_group)
    if role:
        stmt = stmt.where(Job.role_category == role)
    if country:
        stmt = stmt.where(Job.country == country)
    if work_mode:
        stmt = stmt.where(Job.work_mode == work_mode)
    if seniority:
        stmt = stmt.where(Job.seniority == seniority)
    if company:
        stmt = stmt.where(Job.company_name == company)
    if updated_since:
        stmt = stmt.where(Job.updated_at > updated_since)
    total = db.scalar(select(func.count()).select_from(stmt.subquery())) or 0
    rows = db.scalars(stmt.order_by(Job.first_seen_at.desc(), Job.id.desc())
                      .offset((page - 1) * page_size).limit(page_size)).all()
    return JobPage(items=[JobOut.model_validate(r) for r in rows], page=page, page_size=page_size,
                   total=total, server_time=datetime.now(timezone.utc))


@app.get("/v1/jobs/{job_id}", response_model=JobOut, dependencies=[Auth])
def get_job(request: Request, job_id: int, db: Session = Depends(get_db)):
    job = db.get(Job, job_id)
    if not job:
        raise HTTPException(404, "job not found")
    return job


@app.post("/v1/sync", dependencies=[Auth], status_code=202)
@limiter.limit(get_settings().sync_rate_limit)
def trigger_sync(request: Request):
    if is_running():
        return {"status": "already_running"}
    threading.Thread(target=sync_job, args=("manual",), daemon=True).start()
    return {"status": "started"}


@app.get("/v1/sync/runs", response_model=list[SyncRunOut], dependencies=[Auth])
def sync_runs(request: Request, db: Session = Depends(get_db), limit: int = Query(20, ge=1, le=100)):
    return db.scalars(select(SyncRun).order_by(SyncRun.id.desc()).limit(limit)).all()


@app.get("/v1/companies", response_model=list[CompanyOut], dependencies=[Auth])
def companies(request: Request, db: Session = Depends(get_db)):
    return db.scalars(select(Company).order_by(Company.name)).all()


@app.post("/v1/companies", response_model=CompanyOut, dependencies=[Auth], status_code=201)
@limiter.limit("30/hour")
def add_company(request: Request, body: CompanyIn, db: Session = Depends(get_db)):
    c = Company(name=body.name, ats=body.ats, slug=body.slug, careers_url=body.careers_url)
    db.add(c)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(409, "company board already exists") from None
    return c


@app.get("/v1/sources", dependencies=[Auth])
def sources(request: Request):
    return {
        "ingested": [{"source": k, "type": "public_ats_api"} for k in CONNECTORS],
        "deeplink_only": [{"source": k, **v} for k, v in DEEPLINK_SOURCES.items()],
    }


@app.get("/v1/deeplinks", dependencies=[Auth])
def deeplinks(request: Request, q: str = Query(..., min_length=2, max_length=120),
              location: str = Query("", max_length=80), remote: bool = False):
    return build_links(q, location, remote)


@app.get("/v1/taxonomy", dependencies=[Auth])
def taxonomy(request: Request):
    return tx.load()


@app.post("/v1/resume/parse", dependencies=[Auth])
@limiter.limit(get_settings().upload_rate_limit)
async def resume_parse(request: Request, file: UploadFile = File(...)):
    """Stateless: the file is parsed in memory and discarded. Nothing is stored or logged."""
    max_b = get_settings().max_upload_bytes
    data = await file.read(max_b + 1)
    try:
        doc = parse_resume(data, max_b)
    except ResumeRejected as exc:
        raise HTTPException(422, str(exc)) from None
    return {
        "file_type": doc.file_type, "pages": doc.pages, "skills": doc.skills,
        "years_experience": doc.years_experience, "titles": doc.titles,
        "sections": sorted(k for k, v in doc.sections.items() if v and k != "header"),
        "text": doc.text,
    }


def _job_text(body: AnalyseIn, db: Session) -> tuple[str, str, int | None]:
    if body.job_id is not None:
        job = db.get(Job, body.job_id)
        if not job:
            raise HTTPException(404, "job not found")
        return job.title, job.description_text, job.min_years
    if not body.title or not body.description:
        raise HTTPException(422, "provide job_id or title+description")
    return body.title, body.description, None


@app.post("/v1/match", dependencies=[Auth])
@limiter.limit("120/hour")
def match(request: Request, body: AnalyseIn, db: Session = Depends(get_db)):
    title, desc, yrs = _job_text(body, db)
    return matching.match(from_text(body.resume_text), title, desc, yrs).to_dict()


@app.post("/v1/ats", dependencies=[Auth])
@limiter.limit("120/hour")
def ats(request: Request, body: AnalyseIn, db: Session = Depends(get_db)):
    title, desc, yrs = _job_text(body, db)
    return ats_mod.check(from_text(body.resume_text), title, desc, yrs).to_dict()

"""Sync engine: fetch -> filter to target roles -> enrich -> upsert -> dedup -> expire."""
from __future__ import annotations

import hashlib
import json
import logging
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from . import taxonomy as tx
from .config import get_settings
from .connectors import RawJob, make_connector
from .models import Company, Job, SyncRun
from .netsafe import SafeClient, UnsafeRequest, safe_slug

log = logging.getLogger("jobintel.ingest")


def _norm(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", s.lower()).strip()


def dedup_key(company: str, title: str, location: str) -> str:
    title_n = re.sub(r"\b(sr|snr)\b", "senior", _norm(title))
    title_n = re.sub(r"\s*\(?(remote|hybrid)\)?$", "", title_n)
    loc_n = _norm(location.split("/")[0].split(";")[0])
    return hashlib.sha256(f"{_norm(company)}|{title_n}|{loc_n}".encode()).hexdigest()


def content_hash(raw: RawJob) -> str:
    return hashlib.sha256(f"{raw.title}|{raw.location}|{raw.description_text}".encode()).hexdigest()


def is_target_role(title: str) -> bool:
    return tx.classify_role(title)[0] is not None


def seed_companies(db: Session) -> int:
    s = get_settings()
    with open(s.companies_file, encoding="utf-8") as f:
        data = json.load(f)
    added = 0
    for c in data["companies"]:
        safe_slug(c["slug"])
        exists = db.scalar(select(Company).where(Company.ats == c["ats"], Company.slug == c["slug"]))
        if not exists:
            db.add(Company(name=c["name"], ats=c["ats"], slug=c["slug"], careers_url=c.get("careers")))
            added += 1
    db.commit()
    return added


@dataclass
class SyncResult:
    new: int = 0
    updated: int = 0
    expired: int = 0
    errors: list[str] = field(default_factory=list)


def _apply(job: Job, raw: RawJob, company: Company) -> None:
    desc = raw.description_text
    role, group = tx.classify_role(raw.title, desc)
    smin, smax, cur = raw.salary_min, raw.salary_max, raw.salary_currency
    if smin is None:
        smin, smax, cur = tx.parse_salary(desc)
    job.company_id = company.id
    job.company_name = raw.company_name
    job.title = raw.title
    job.location = raw.location
    job.country = tx.classify_country(raw.location)
    job.work_mode = tx.classify_work_mode(raw.location, desc, raw.workplace_hint)
    job.employment_type = raw.employment_type
    job.seniority = tx.classify_seniority(raw.title)
    job.role_category, job.role_group = role, group
    job.department = raw.department
    job.description_text = desc
    job.skills_csv = ",".join(tx.extract_skills(desc))
    job.min_years = tx.min_years_required(desc)
    job.salary_min, job.salary_max, job.salary_currency = smin, smax, cur
    job.apply_url = raw.apply_url
    job.posted_at = raw.posted_at
    job.content_hash = content_hash(raw)
    job.dedup_key = dedup_key(raw.company_name, raw.title, raw.location)


def sync_company(db: Session, company: Company, client: SafeClient, now: datetime, res: SyncResult) -> None:
    conn = make_connector(company.ats, client)
    try:
        raws = [r for r in conn.list_titles(company.slug, company.name) if r.title and is_target_role(r.title)]
    except (UnsafeRequest, Exception) as exc:  # noqa: BLE001 - one bad board must not stop the run
        msg = f"{company.ats}/{company.slug}: {type(exc).__name__}: {str(exc)[:160]}"
        company.last_status = msg[:200]
        res.errors.append(msg)
        log.warning(msg)
        return

    existing = {
        j.source_job_id: j for j in db.scalars(
            select(Job).where(Job.source == company.ats, Job.company_id == company.id))
    }
    seen: set[str] = set()
    for raw in raws:
        seen.add(raw.source_job_id)
        job = existing.get(raw.source_job_id)
        new_hash = content_hash(raw) if raw.description_text else None
        if job and job.is_active and new_hash == job.content_hash:
            job.last_seen_at, job.missed_syncs = now, 0
            continue
        if not raw.description_text:
            try:
                raw = conn.fill_details(company.slug, raw)
            except Exception as exc:  # noqa: BLE001
                res.errors.append(f"{company.ats}/{company.slug}/{raw.source_job_id}: details: {type(exc).__name__}")
                continue
            if job and job.is_active and content_hash(raw) == job.content_hash:
                job.last_seen_at, job.missed_syncs = now, 0
                continue
        if job is None:
            job = Job(source=company.ats, source_job_id=raw.source_job_id, first_seen_at=now)
            db.add(job)
            res.new += 1
        else:
            res.updated += 1
        _apply(job, raw, company)
        job.last_seen_at, job.updated_at, job.missed_syncs, job.is_active = now, now, 0, True

    max_missed = get_settings().expire_after_missed_syncs
    for sid, job in existing.items():
        if sid not in seen and job.is_active:
            job.missed_syncs += 1
            if job.missed_syncs >= max_missed:
                job.is_active = False
                res.expired += 1
    company.last_status = f"ok: {len(raws)} relevant postings"
    company.last_synced_at = now


def mark_duplicates(db: Session) -> None:
    """Keep the earliest-seen active posting per dedup key as canonical."""
    jobs = db.scalars(select(Job).where(Job.is_active.is_(True)).order_by(Job.first_seen_at, Job.id)).all()
    canonical: dict[str, int] = {}
    for j in jobs:
        if j.dedup_key in canonical:
            j.duplicate_of = canonical[j.dedup_key]
        else:
            canonical[j.dedup_key] = j.id
            j.duplicate_of = None


def run_sync(db: Session, trigger: str = "scheduled", client: SafeClient | None = None) -> SyncRun:
    now = datetime.now(timezone.utc)
    run = SyncRun(trigger=trigger, started_at=now)
    db.add(run)
    db.commit()
    own_client = client is None
    client = client or SafeClient()
    res = SyncResult()
    try:
        for company in db.scalars(select(Company).where(Company.enabled.is_(True))).all():
            sync_company(db, company, client, now, res)
            db.flush()
        mark_duplicates(db)
    finally:
        if own_client:
            client.close()
    run.finished_at = datetime.now(timezone.utc)
    run.new_jobs, run.updated_jobs, run.expired_jobs = res.new, res.updated, res.expired
    run.errors = "\n".join(res.errors)[:20000]
    db.commit()
    log.info("sync %s: new=%d updated=%d expired=%d errors=%d", trigger, res.new, res.updated, res.expired, len(res.errors))
    return run

import json

import httpx
import respx
from sqlalchemy import select

from app.ingest import dedup_key, run_sync
from app.models import Company, Job
from app.netsafe import SafeClient
from tests.conftest import FIXTURES

GH = "https://boards-api.greenhouse.io/v1/boards/acme/jobs"


def _client():
    return SafeClient(httpx.Client(follow_redirects=False), resolve_dns=False)


def _setup(db):
    db.add(Company(name="Acme", ats="greenhouse", slug="acme"))
    db.commit()


@respx.mock
def test_new_update_expire_cycle(db_session):
    _setup(db_session)
    data = json.loads((FIXTURES / "greenhouse_jobs.json").read_text())
    route = respx.get(GH).mock(return_value=httpx.Response(200, json=data))

    run = run_sync(db_session, "manual", _client())
    assert run.new_jobs == 1  # only the AppSec role is in scope
    job = db_session.scalar(select(Job))
    assert job.role_category == "appsec" and job.role_group == "cyber"
    assert job.seniority == "senior"
    assert job.country == "IN"
    assert job.work_mode == "hybrid"
    assert job.min_years == 5
    assert "Threat Modeling" in job.skills_csv and "SAST" in job.skills_csv

    # unchanged -> no update
    run2 = run_sync(db_session, "manual", _client())
    assert (run2.new_jobs, run2.updated_jobs) == (0, 0)

    # JD edited -> update
    data["jobs"][0]["content"] += "&lt;p&gt;Terraform&lt;/p&gt;"
    route.mock(return_value=httpx.Response(200, json=data))
    run3 = run_sync(db_session, "manual", _client())
    assert run3.updated_jobs == 1
    db_session.refresh(job)
    assert "Terraform" in job.skills_csv

    # removed for 2 syncs -> expired
    route.mock(return_value=httpx.Response(200, json={"jobs": []}))
    assert run_sync(db_session, "manual", _client()).expired_jobs == 0
    assert run_sync(db_session, "manual", _client()).expired_jobs == 1
    db_session.refresh(job)
    assert job.is_active is False


@respx.mock
def test_failed_board_does_not_expire_jobs(db_session):
    _setup(db_session)
    data = json.loads((FIXTURES / "greenhouse_jobs.json").read_text())
    route = respx.get(GH).mock(return_value=httpx.Response(200, json=data))
    run_sync(db_session, "manual", _client())
    route.mock(return_value=httpx.Response(500))
    for _ in range(3):
        r = run_sync(db_session, "manual", _client())
        assert "acme" in r.errors
    assert db_session.scalar(select(Job)).is_active is True


def test_dedup_key_normalises():
    a = dedup_key("Acme Inc", "Sr. Security Engineer", "Bengaluru, India")
    b = dedup_key("acme inc", "Senior Security Engineer (Remote)", "Bengaluru, India / Pune")
    assert a == b
    assert a != dedup_key("Acme Inc", "Security Engineer", "Bengaluru, India")


@respx.mock
def test_cross_source_duplicates_marked(db_session):
    db_session.add_all([Company(name="Acme", ats="greenhouse", slug="acme"),
                        Company(name="Acme", ats="ashby", slug="acme")])
    db_session.commit()
    gh = {"jobs": [{"id": 1, "title": "Security Engineer", "absolute_url": "https://x.example/1",
                    "location": {"name": "Pune"}, "content": "Python"}]}
    ab = {"jobs": [{"id": "z", "title": "Security Engineer", "location": "Pune", "isListed": True,
                    "jobUrl": "https://y.example/z", "descriptionPlain": "Python and AWS"}]}
    respx.get(GH).mock(return_value=httpx.Response(200, json=gh))
    respx.get("https://api.ashbyhq.com/posting-api/job-board/acme").mock(return_value=httpx.Response(200, json=ab))
    run_sync(db_session, "manual", _client())
    jobs = db_session.scalars(select(Job).order_by(Job.id)).all()
    assert len(jobs) == 2
    assert sum(1 for j in jobs if j.duplicate_of is None) == 1

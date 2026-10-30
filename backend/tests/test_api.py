"""Integration + security tests for the HTTP API (OWASP API Top 10 oriented)."""
import io
from datetime import datetime, timezone

from app.models import Job
from tests.test_resume_match_ats import JD, RESUME, _docx_bytes


def _job(db, **kw):
    now = datetime.now(timezone.utc)
    j = Job(source="greenhouse", source_job_id=kw.get("sid", "1"), company_name=kw.get("company", "Acme"),
            title=kw.get("title", "Senior Application Security Engineer"), location="Pune, India", country="IN",
            work_mode=kw.get("mode", "remote"), seniority="senior", role_category="appsec", role_group="cyber",
            description_text=JD, skills_csv="Python,SAST,AWS", apply_url="https://x.example/apply",
            first_seen_at=now, last_seen_at=now, updated_at=now, content_hash="h", dedup_key=kw.get("dk", "d1"))
    db.add(j)
    db.commit()
    return j


def test_health_is_public(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.headers["x-content-type-options"] == "nosniff"
    assert "strict-transport-security" in r.headers


def test_auth_required_everywhere(client):
    for path in ["/v1/jobs", "/v1/companies", "/v1/sources", "/v1/sync/runs", "/v1/taxonomy"]:
        assert client.get(path).status_code == 401
        assert client.get(path, headers={"Authorization": "Bearer wrong"}).status_code == 401
        assert client.get(path, headers={"Authorization": "Basic abc"}).status_code == 401


def test_no_docs_exposed(client):
    for p in ["/docs", "/redoc", "/openapi.json"]:
        assert client.get(p).status_code == 404


def test_fail_closed_without_keys(client, monkeypatch):
    from app.config import get_settings
    monkeypatch.setattr(get_settings(), "api_key_hashes", "")
    assert client.get("/v1/jobs", headers={"Authorization": "Bearer x"}).status_code == 503


def test_list_filter_paginate(client, auth, db_session):
    _job(db_session, sid="1", dk="a")
    _job(db_session, sid="2", dk="b", title="Cloud Security Engineer", mode="hybrid")
    r = client.get("/v1/jobs", headers=auth, params={"work_mode": "remote", "page_size": 1})
    assert r.status_code == 200
    body = r.json()
    assert body["total"] == 1 and body["items"][0]["source_job_id"] == "1"
    assert client.get("/v1/jobs", headers=auth, params={"page_size": 500}).status_code == 422
    assert client.get("/v1/jobs", headers=auth, params={"country": "india"}).status_code == 422


def test_sql_injection_strings_are_inert(client, auth, db_session):
    _job(db_session)
    for q in ["' OR 1=1 --", "\" ; DROP TABLE jobs; --", "%' UNION SELECT * FROM companies --"]:
        r = client.get("/v1/jobs", headers=auth, params={"q": q})
        assert r.status_code == 200 and r.json()["total"] == 0
    assert client.get("/v1/jobs", headers=auth).json()["total"] == 1


def test_company_input_validation(client, auth):
    bad = [
        {"name": "x", "ats": "workday", "slug": "x"},
        {"name": "x", "ats": "lever", "slug": "../../etc"},
        {"name": "x", "ats": "lever", "slug": "evil.com/x"},
        {"name": "x", "ats": "lever", "slug": "ok", "careers_url": "javascript:alert(1)"},
        {"name": "a\x00b", "ats": "lever", "slug": "ok"},
    ]
    for b in bad:
        assert client.post("/v1/companies", headers=auth, json=b).status_code == 422, b
    ok = client.post("/v1/companies", headers=auth, json={"name": "Acme", "ats": "lever", "slug": "acme"})
    assert ok.status_code == 201
    assert client.post("/v1/companies", headers=auth, json={"name": "Acme", "ats": "lever", "slug": "acme"}).status_code == 409


def test_resume_upload_validation(client, auth):
    files = {"file": ("cv.pdf", io.BytesIO(b"MZ not a pdf"), "application/pdf")}
    assert client.post("/v1/resume/parse", headers=auth, files=files).status_code == 422
    files = {"file": ("cv.docx", io.BytesIO(_docx_bytes(RESUME)), "application/octet-stream")}
    r = client.post("/v1/resume/parse", headers=auth, files=files)
    assert r.status_code == 200 and "Python" in r.json()["skills"]


def test_oversized_request_rejected(client, auth):
    big = b"%PDF-" + b"0" * (6 * 1024 * 1024)
    r = client.post("/v1/resume/parse", headers=auth, files={"file": ("a.pdf", io.BytesIO(big), "application/pdf")})
    assert r.status_code == 413


def test_match_and_ats_endpoints(client, auth, db_session):
    j = _job(db_session)
    r = client.post("/v1/match", headers=auth, json={"resume_text": RESUME, "job_id": j.id})
    assert r.status_code == 200 and 0 <= r.json()["score"] <= 100
    r = client.post("/v1/ats", headers=auth, json={"resume_text": RESUME, "title": "AppSec", "description": JD})
    assert r.status_code == 200 and r.json()["disclaimer"]
    assert client.post("/v1/match", headers=auth, json={"resume_text": RESUME, "job_id": 999}).status_code == 404
    assert client.post("/v1/match", headers=auth, json={"resume_text": "short"}).status_code == 422


def test_deeplinks_are_https_and_encode_input(client, auth):
    r = client.get("/v1/deeplinks", headers=auth, params={"q": "appsec <script>", "location": "Pune"})
    assert r.status_code == 200
    links = r.json()
    assert len(links) == 9
    for link in links:
        assert link["url"].startswith("https://")
        assert "<script>" not in link["url"]


def test_rate_limit_on_sync(client, auth, monkeypatch):
    import app.main as m
    monkeypatch.setattr(m, "sync_job", lambda *_: None)
    codes = [client.post("/v1/sync", headers=auth).status_code for _ in range(6)]
    assert codes[:4] == [202] * 4 and 429 in codes

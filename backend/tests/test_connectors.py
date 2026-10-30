import json

import httpx
import respx

from app.connectors import make_connector
from app.netsafe import SafeClient
from tests.conftest import FIXTURES


def _fx(name):
    return json.loads((FIXTURES / name).read_text())


def _client():
    return SafeClient(httpx.Client(follow_redirects=False), resolve_dns=False)


@respx.mock
def test_greenhouse_parses_and_rejects_non_https_apply_urls():
    respx.get("https://boards-api.greenhouse.io/v1/boards/acme/jobs").mock(
        return_value=httpx.Response(200, json=_fx("greenhouse_jobs.json")))
    jobs = make_connector("greenhouse", _client()).list_titles("acme", "Acme")
    ids = [j.source_job_id for j in jobs]
    assert ids == ["4001", "4002"]  # 4003 had a javascript: apply URL and is dropped
    j = jobs[0]
    assert j.apply_url == "https://boards.greenhouse.io/acme/jobs/4001"
    assert "threat modeling" in j.description_text.lower()
    assert "<" not in j.description_text and "alert(1)" not in j.description_text
    assert j.location == "Bengaluru, India"
    assert j.posted_at is not None


@respx.mock
def test_lever_parses_lists_salary_and_workplace():
    respx.get("https://api.lever.co/v0/postings/acme").mock(
        return_value=httpx.Response(200, json=_fx("lever_postings.json")))
    jobs = make_connector("lever", _client()).list_titles("acme", "Acme")
    j = jobs[0]
    assert j.title == "Forward Deployed Engineer, AI"
    assert j.workplace_hint == "remote"
    assert j.salary_min == 3000000 and j.salary_currency == "INR"
    assert "3+ years of experience with Python" in j.description_text


@respx.mock
def test_ashby_skips_unlisted_and_reads_compensation():
    respx.get("https://api.ashbyhq.com/posting-api/job-board/acme").mock(
        return_value=httpx.Response(200, json=_fx("ashby_board.json")))
    jobs = make_connector("ashby", _client()).list_titles("acme", "Acme")
    assert [j.source_job_id for j in jobs] == ["9f8e"]
    assert jobs[0].location == "Hyderabad / Pune"
    assert jobs[0].salary_max == 2600000


@respx.mock
def test_smartrecruiters_list_then_details():
    respx.get("https://api.smartrecruiters.com/v1/companies/Acme/postings").mock(
        return_value=httpx.Response(200, json=_fx("smartrecruiters_list.json")))
    respx.get("https://api.smartrecruiters.com/v1/companies/Acme/postings/744000001").mock(
        return_value=httpx.Response(200, json=_fx("smartrecruiters_detail.json")))
    conn = make_connector("smartrecruiters", _client())
    jobs = conn.list_titles("Acme", "Acme")
    assert len(jobs) == 2 and jobs[0].description_text == ""
    j = conn.fill_details("Acme", jobs[0])
    assert "Terraform" in j.description_text
    assert j.apply_url.startswith("https://jobs.smartrecruiters.com/Acme/744000001")

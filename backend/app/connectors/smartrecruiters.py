"""SmartRecruiters public Posting API (no auth): https://developers.smartrecruiters.com/docs/posting-api"""
from __future__ import annotations

from ..netsafe import safe_slug
from ..textutil import clean_text, html_to_text, sanitize_short
from .base import Connector, RawJob, https_or_none, parse_iso

PAGE = 100


class SmartRecruitersConnector(Connector):
    ats = "smartrecruiters"

    def list_titles(self, slug: str, company_name: str) -> list[RawJob]:
        base = f"https://api.smartrecruiters.com/v1/companies/{safe_slug(slug)}/postings"
        out: list[RawJob] = []
        offset = 0
        while True:
            data = self.client.get_json(base, params={"limit": PAGE, "offset": offset})
            content = data.get("content") or []
            for p in content:
                if not p.get("id"):
                    continue
                loc = p.get("location") or {}
                hint = "remote" if loc.get("remote") else ("hybrid" if loc.get("hybrid") else None)
                out.append(RawJob(
                    source=self.ats,
                    source_job_id=str(p["id"]),
                    company_name=company_name,
                    title=sanitize_short(p.get("name")),
                    apply_url=f"https://jobs.smartrecruiters.com/{safe_slug(slug)}/{safe_slug(str(p['id']))}",
                    location=sanitize_short(loc.get("fullLocation") or ", ".join(
                        x for x in (loc.get("city"), loc.get("country")) if x)),
                    department=sanitize_short((p.get("department") or {}).get("label")) or None,
                    employment_type=sanitize_short((p.get("typeOfEmployment") or {}).get("label")) or None,
                    workplace_hint=hint,
                    posted_at=parse_iso(p.get("releasedDate")),
                ))
            offset += PAGE
            if not content or offset >= int(data.get("totalFound") or 0) or offset > 3000:
                break
        return out

    def fill_details(self, slug: str, job: RawJob) -> RawJob:
        url = (f"https://api.smartrecruiters.com/v1/companies/{safe_slug(slug)}"
               f"/postings/{safe_slug(job.source_job_id)}")
        d = self.client.get_json(url)
        secs = ((d.get("jobAd") or {}).get("sections") or {})
        parts = []
        for key in ("jobDescription", "qualifications", "additionalInformation"):
            sec = secs.get(key) or {}
            if sec.get("text"):
                parts.append(f"{sanitize_short(sec.get('title'))}\n{html_to_text(sec['text'])}")
        job.description_text = clean_text("\n\n".join(parts))
        job.apply_url = https_or_none(d.get("postingUrl")) or https_or_none(d.get("applyUrl")) or job.apply_url
        return job

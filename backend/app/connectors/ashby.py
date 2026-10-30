"""Ashby public Job Posting API (no auth): https://developers.ashbyhq.com/docs/public-job-posting-api"""
from __future__ import annotations

from ..netsafe import safe_slug
from ..textutil import clean_text, html_to_text, sanitize_short
from .base import Connector, RawJob, https_or_none, parse_iso


class AshbyConnector(Connector):
    ats = "ashby"

    def list_titles(self, slug: str, company_name: str) -> list[RawJob]:
        url = f"https://api.ashbyhq.com/posting-api/job-board/{safe_slug(slug)}"
        data = self.client.get_json(url, params={"includeCompensation": "true"})
        out = []
        for j in data.get("jobs", []):
            if j.get("isListed") is False:
                continue
            apply = https_or_none(j.get("jobUrl")) or https_or_none(j.get("applyUrl"))
            if not apply or not j.get("id"):
                continue
            locs = [j.get("location") or ""] + [
                (s or {}).get("location", "") for s in (j.get("secondaryLocations") or [])
            ]
            hint = j.get("workplaceType") or ("Remote" if j.get("isRemote") else None)
            smin = smax = cur = None
            for comp in ((j.get("compensation") or {}).get("summaryComponents") or []):
                if comp.get("compensationType") == "Salary":
                    smin, smax = comp.get("minValue"), comp.get("maxValue")
                    cur = sanitize_short(comp.get("currencyCode"), 8) or None
                    break
            desc = j.get("descriptionPlain") or html_to_text(j.get("descriptionHtml"))
            out.append(RawJob(
                source=self.ats,
                source_job_id=str(j["id"]),
                company_name=company_name,
                title=sanitize_short(j.get("title")),
                apply_url=apply,
                location=sanitize_short(" / ".join(x for x in locs if x)),
                description_text=clean_text(desc),
                department=sanitize_short(j.get("department") or j.get("team")) or None,
                employment_type=sanitize_short(j.get("employmentType")) or None,
                workplace_hint=hint,
                posted_at=parse_iso(j.get("publishedAt")),
                salary_min=smin if isinstance(smin, (int, float)) else None,
                salary_max=smax if isinstance(smax, (int, float)) else None,
                salary_currency=cur,
            ))
        return out

"""Greenhouse public Job Board API (no auth): https://developers.greenhouse.io/job-board.html"""
from __future__ import annotations

from ..netsafe import safe_slug
from ..textutil import html_to_text, sanitize_short
from .base import Connector, RawJob, https_or_none, parse_iso


class GreenhouseConnector(Connector):
    ats = "greenhouse"

    def list_titles(self, slug: str, company_name: str) -> list[RawJob]:
        url = f"https://boards-api.greenhouse.io/v1/boards/{safe_slug(slug)}/jobs"
        data = self.client.get_json(url, params={"content": "true"})
        out = []
        for j in data.get("jobs", []):
            apply = https_or_none(j.get("absolute_url"))
            if not apply or not j.get("id"):
                continue
            depts = j.get("departments") or []
            out.append(RawJob(
                source=self.ats,
                source_job_id=str(j["id"]),
                company_name=company_name,
                title=sanitize_short(j.get("title")),
                apply_url=apply,
                location=sanitize_short((j.get("location") or {}).get("name")),
                description_text=html_to_text(j.get("content")),
                department=sanitize_short(depts[0].get("name")) if depts else None,
                posted_at=parse_iso(j.get("first_published") or j.get("updated_at")),
            ))
        return out

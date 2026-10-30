"""Lever public Postings API (no auth): https://github.com/lever/postings-api"""
from __future__ import annotations

from ..netsafe import safe_slug
from ..textutil import clean_text, html_to_text, sanitize_short
from .base import Connector, RawJob, https_or_none, parse_epoch_ms

PAGE = 100


class LeverConnector(Connector):
    ats = "lever"
    host = "api.lever.co"

    def list_titles(self, slug: str, company_name: str) -> list[RawJob]:
        base = f"https://{self.host}/v0/postings/{safe_slug(slug)}"
        out: list[RawJob] = []
        skip = 0
        while True:
            page = self.client.get_json(base, params={"mode": "json", "limit": PAGE, "skip": skip})
            if not isinstance(page, list) or not page:
                break
            for p in page:
                job = self._to_raw(p, company_name)
                if job:
                    out.append(job)
            if len(page) < PAGE or skip > 5000:
                break
            skip += PAGE
        return out

    def _to_raw(self, p: dict, company_name: str) -> RawJob | None:
        apply = https_or_none(p.get("hostedUrl")) or https_or_none(p.get("applyUrl"))
        if not apply or not p.get("id"):
            return None
        cats = p.get("categories") or {}
        parts = [p.get("descriptionPlain") or html_to_text(p.get("description"))]
        for lst in p.get("lists") or []:
            parts.append(f"\n{sanitize_short(lst.get('text'))}\n{html_to_text(lst.get('content'))}")
        parts.append(p.get("additionalPlain") or "")
        sal = p.get("salaryRange") or {}
        smin = sal.get("min") if isinstance(sal.get("min"), (int, float)) else None
        smax = sal.get("max") if isinstance(sal.get("max"), (int, float)) else None
        return RawJob(
            source=self.ats,
            source_job_id=str(p["id"]),
            company_name=company_name,
            title=sanitize_short(p.get("text")),
            apply_url=apply,
            location=sanitize_short(cats.get("location")),
            description_text=clean_text("\n".join(x for x in parts if x)),
            department=sanitize_short(cats.get("team") or cats.get("department")) or None,
            employment_type=sanitize_short(cats.get("commitment")) or None,
            workplace_hint=p.get("workplaceType"),
            posted_at=parse_epoch_ms(p.get("createdAt")),
            salary_min=smin, salary_max=smax,
            salary_currency=sanitize_short(sal.get("currency"), 8) or None if smin else None,
        )

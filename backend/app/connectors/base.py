from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timezone

from ..netsafe import SafeClient


@dataclass
class RawJob:
    source: str
    source_job_id: str
    company_name: str
    title: str
    apply_url: str
    location: str = ""
    description_text: str = ""
    department: str | None = None
    employment_type: str | None = None
    workplace_hint: str | None = None
    posted_at: datetime | None = None
    salary_min: float | None = None
    salary_max: float | None = None
    salary_currency: str | None = None
    extra: dict = field(default_factory=dict)


class Connector(ABC):
    """A source-specific job connector. Implementations must only build URLs on
    hosts in netsafe.ALLOWED_HOSTS and must pass slugs through netsafe.safe_slug."""

    ats: str = ""

    def __init__(self, client: SafeClient):
        self.client = client

    @abstractmethod
    def list_titles(self, slug: str, company_name: str) -> list[RawJob]:
        """Return postings (description may be empty if the source needs a detail call)."""

    def fill_details(self, slug: str, job: RawJob) -> RawJob:
        """Fetch description for a posting if list_titles didn't include it."""
        return job


def parse_iso(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
    except ValueError:
        return None


def parse_epoch_ms(value) -> datetime | None:
    try:
        return datetime.fromtimestamp(int(value) / 1000, tz=timezone.utc)
    except (TypeError, ValueError, OSError):
        return None


def https_or_none(url: str | None) -> str | None:
    """Only accept https apply links (they are opened by the user's browser)."""
    if isinstance(url, str) and url.startswith("https://") and len(url) <= 1000:
        return url
    return None

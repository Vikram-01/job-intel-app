from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from .netsafe import SLUG_RE


class JobOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    source: str
    source_job_id: str
    company_name: str
    title: str
    location: str
    country: str | None
    work_mode: str
    employment_type: str | None
    seniority: str
    role_category: str | None
    role_group: str | None
    department: str | None
    description_text: str
    skills_csv: str
    min_years: int | None
    salary_min: float | None
    salary_max: float | None
    salary_currency: str | None
    apply_url: str
    posted_at: datetime | None
    first_seen_at: datetime
    updated_at: datetime
    is_active: bool
    duplicate_of: int | None


class JobPage(BaseModel):
    items: list[JobOut]
    page: int
    page_size: int
    total: int
    server_time: datetime


class CompanyIn(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    ats: Literal["greenhouse", "lever", "ashby", "smartrecruiters"]
    slug: str = Field(min_length=1, max_length=100)
    careers_url: str | None = Field(default=None, max_length=500)

    @field_validator("slug")
    @classmethod
    def _slug(cls, v: str) -> str:
        if not SLUG_RE.fullmatch(v) or ".." in v:
            raise ValueError("slug may contain only letters, digits, '.', '_' and '-'")
        return v

    @field_validator("careers_url")
    @classmethod
    def _url(cls, v: str | None) -> str | None:
        if v and not v.startswith("https://"):
            raise ValueError("careers_url must be https")
        return v

    @field_validator("name")
    @classmethod
    def _name(cls, v: str) -> str:
        if any(ord(c) < 32 for c in v):
            raise ValueError("control characters not allowed")
        return v.strip()


class CompanyOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    name: str
    ats: str
    slug: str
    careers_url: str | None
    enabled: bool
    last_status: str | None
    last_synced_at: datetime | None


class AnalyseIn(BaseModel):
    resume_text: str = Field(min_length=50, max_length=60_000)
    job_id: int | None = None
    title: str | None = Field(default=None, max_length=300)
    description: str | None = Field(default=None, max_length=60_000)


class SyncRunOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    trigger: str
    started_at: datetime
    finished_at: datetime | None
    new_jobs: int
    updated_jobs: int
    expired_jobs: int
    errors: str

"""Runtime configuration. Every secret comes from the environment; nothing is hardcoded."""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

REPO_ROOT = Path(__file__).resolve().parents[2]
SHARED_DIR = REPO_ROOT / "shared"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="JOBINTEL_", env_file=".env", extra="ignore")

    database_url: str = "sqlite:///./jobintel.db"
    # Comma-separated SHA-256 hex digests of valid API keys. Generate with scripts/gen_api_key.py.
    api_key_hashes: str = Field(default="", repr=False)
    rate_limit: str = "60/minute"
    sync_rate_limit: str = "4/hour"
    upload_rate_limit: str = "20/hour"
    max_upload_bytes: int = 5 * 1024 * 1024
    sync_hour_utc: int = 1
    sync_minute_utc: int = 30
    enable_scheduler: bool = True
    http_timeout_s: float = 20.0
    http_max_bytes: int = 15 * 1024 * 1024
    expire_after_missed_syncs: int = 2
    companies_file: Path = SHARED_DIR / "companies.json"
    taxonomy_file: Path = SHARED_DIR / "taxonomy.json"

    @field_validator("sync_hour_utc")
    @classmethod
    def _hour(cls, v: int) -> int:
        if not 0 <= v <= 23:
            raise ValueError("sync_hour_utc must be 0-23")
        return v

    @property
    def api_key_hash_set(self) -> set[str]:
        return {h.strip().lower() for h in self.api_key_hashes.split(",") if h.strip()}


@lru_cache
def get_settings() -> Settings:
    return Settings()

from __future__ import annotations

import logging
import threading

from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger

from .config import get_settings
from .db import SessionLocal
from .ingest import run_sync

log = logging.getLogger("jobintel.scheduler")
_lock = threading.Lock()


def sync_job(trigger: str = "scheduled") -> int | None:
    """Run one sync; returns run id, or None if a sync is already in progress."""
    if not _lock.acquire(blocking=False):
        log.info("sync already running; skipped")
        return None
    try:
        with SessionLocal() as db:
            return run_sync(db, trigger=trigger).id
    finally:
        _lock.release()


def is_running() -> bool:
    return _lock.locked()


def start_scheduler() -> BackgroundScheduler:
    s = get_settings()
    sched = BackgroundScheduler(timezone="UTC")
    sched.add_job(sync_job, CronTrigger(hour=s.sync_hour_utc, minute=s.sync_minute_utc),
                  id="daily_sync", max_instances=1, coalesce=True, misfire_grace_time=3600)
    sched.start()
    log.info("daily sync scheduled at %02d:%02d UTC", s.sync_hour_utc, s.sync_minute_utc)
    return sched

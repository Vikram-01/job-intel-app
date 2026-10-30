from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

TEST_KEY = "test-key-0123456789abcdef0123456789"
os.environ.setdefault("JOBINTEL_DATABASE_URL", "sqlite:///:memory:")
os.environ["JOBINTEL_ENABLE_SCHEDULER"] = "false"
import hashlib  # noqa: E402

os.environ["JOBINTEL_API_KEY_HASHES"] = hashlib.sha256(TEST_KEY.encode()).hexdigest()

from sqlalchemy import create_engine  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402
from sqlalchemy.pool import StaticPool  # noqa: E402

from app import db as dbmod  # noqa: E402
from app.db import Base  # noqa: E402

FIXTURES = Path(__file__).parent / "fixtures"


@pytest.fixture()
def db_session():
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, expire_on_commit=False)
    old_engine, old_sl = dbmod.engine, dbmod.SessionLocal
    dbmod.engine, dbmod.SessionLocal = engine, Session
    s = Session()
    yield s
    s.close()
    dbmod.engine, dbmod.SessionLocal = old_engine, old_sl


@pytest.fixture()
def client(db_session):
    from fastapi.testclient import TestClient

    from app.db import get_db
    from app.main import app
    from app.security import limiter

    limiter.reset()
    app.dependency_overrides[get_db] = lambda: db_session
    with TestClient(app, base_url="https://testserver") as c:
        yield c
    app.dependency_overrides.clear()


@pytest.fixture()
def auth():
    return {"Authorization": f"Bearer {TEST_KEY}"}

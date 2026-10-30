# Deploying the CipherPath backend (optional)

The Android app works without a server. Run the backend if you want ingestion to happen on a server you control (one daily sync shared by several devices) or want to use the API from other tools.

## 1. Create an API key

```bash
cd backend && python scripts/gen_api_key.py
# API key (enter in app, keep secret): ji_...
# Digest (server env JOBINTEL_API_KEY_HASHES): 3f5a...
```

The server stores **only the digest**. Put the key into the app under **Settings → Data source → My backend**, where it's stored Keystore-encrypted.

## 2. Run with Docker

```bash
docker build -f backend/Dockerfile -t cipherpath-api .
docker run -d --name cipherpath-api --restart unless-stopped \
  -e JOBINTEL_API_KEY_HASHES=<digest> \
  -e JOBINTEL_SYNC_HOUR_UTC=1 -e JOBINTEL_SYNC_MINUTE_UTC=30 \
  -v cipherpath-data:/data -p 127.0.0.1:8000:8000 cipherpath-api
```

The container applies Alembic migrations on start, runs as an unprivileged user, and schedules the daily sync with APScheduler.

## 3. Terminate TLS in front of it (required)

The app refuses non-https backends. Put a reverse proxy in front of it, for example Caddy, which obtains certificates automatically:

```
api.example.com {
    reverse_proxy 127.0.0.1:8000
}
```

You can also use a managed platform (Fly.io, Render, Cloud Run) that provides HTTPS, as long as it keeps `/data` on a persistent volume.

## 4. Configuration

| Variable | Default | Purpose |
|---|---|---|
| `JOBINTEL_API_KEY_HASHES` | *(empty: server refuses all requests)* | Comma-separated SHA-256 digests of valid keys. Add a second digest to rotate keys without downtime. |
| `JOBINTEL_DATABASE_URL` | `sqlite:////data/jobintel.db` | Any SQLAlchemy URL. Use PostgreSQL for multi-instance deployments. |
| `JOBINTEL_SYNC_HOUR_UTC` / `_MINUTE_UTC` | `1` / `30` | Daily sync time. 01:30 UTC is 07:00 IST. |
| `JOBINTEL_RATE_LIMIT` | `60/minute` | Global per-key limit. |
| `JOBINTEL_SYNC_RATE_LIMIT` | `4/hour` | Limit on manual `POST /v1/sync`. |
| `JOBINTEL_MAX_UPLOAD_BYTES` | `5242880` | Resume upload cap. |
| `JOBINTEL_ENABLE_SCHEDULER` | `true` | Disable this on all but one instance if you scale out. |

## API summary

All `/v1` routes require `Authorization: Bearer <key>`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Liveness (public). |
| GET | `/v1/jobs` | Filter and paginate jobs. Use `updated_since` to pull incremental changes, which is how the app syncs. |
| GET | `/v1/jobs/{id}` | A single job. |
| POST | `/v1/sync` | Trigger a sync (rate-limited, single-flight). |
| GET | `/v1/sync/runs` | Sync history and errors. |
| GET/POST | `/v1/companies` | List or add company boards (the ATS and slug are validated). |
| GET | `/v1/sources`, `/v1/deeplinks` | Source inventory and search links. |
| POST | `/v1/resume/parse` | Stateless PDF/DOCX parse (nothing is stored). |
| POST | `/v1/match`, `/v1/ats` | Score resume text against a job id or a pasted JD. |

## Operations

* **Backups.** Snapshot the `/data` volume. It holds only public job data, never resumes.
* **Upgrades.** Pull, rebuild and restart. Migrations run automatically.
* **New schema changes.** Run `alembic revision --autogenerate -m "..."`, review the result, and commit it. CI's `alembic check` fails if the models and migrations drift.

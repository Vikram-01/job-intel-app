FROM python:3.13-slim AS base
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
WORKDIR /srv/backend
RUN useradd --create-home --uid 10001 jobintel
COPY backend/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY shared /srv/shared
COPY backend/app ./app
COPY backend/migrations ./migrations
COPY backend/alembic.ini .
RUN mkdir -p /data && chown jobintel /data
USER jobintel
ENV JOBINTEL_DATABASE_URL=sqlite:////data/jobintel.db
EXPOSE 8000
# Run migrations, then serve. TLS is terminated by the reverse proxy / platform (see DEPLOY.md).
CMD ["sh", "-c", "alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port 8000 --proxy-headers --forwarded-allow-ips='*' --no-server-header"]

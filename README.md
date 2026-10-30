<p align="center"><img src="docs/logo.png" width="96" alt="CipherPath logo"></p>

# CipherPath

**Job intelligence and career assistant for Cybersecurity and Forward-Deployed / Solutions Engineering roles.**
An Android app (Kotlin + Jetpack Compose) with an optional self-hosted Python FastAPI backend.

CipherPath pulls roles from public company job boards and de-duplicates them. It scores every job against your resumes with a transparent, explainable model, runs ATS-style checks, and tracks your applications on a Kanban board. It works fully offline from its encrypted local database, and you don't need to run a server.

---

## What it does

| Area | Features |
|---|---|
| **Job aggregation** | Greenhouse, Lever, Ashby and SmartRecruiters public job-board APIs (26 seed companies, add your own in-app). Roles are filtered to 14 cybersecurity families and the FDE / Solutions / Customer / Platform engineering families. Every job keeps its original application URL. LinkedIn, Naukri, Indeed, Wellfound, Instahyre, Foundit, Cutshort, Glassdoor and Google Jobs get **search deep-links** (see [Sources](docs/SOURCES.md)). |
| **Automated intelligence** | WorkManager background sync on a configurable 6, 12, 24 or 48 h schedule, with optional Wi-Fi-only. Manual sync. JD change detection, expiry detection after 2 missed syncs, and cross-source de-duplication. New jobs appear live because the UI observes the Room database directly. |
| **Filters** | Role family, role type, location (India, US, UK), remote / hybrid / on-site, company, level (intern through manager), skill, minimum posted salary, posting date, minimum match and saved jobs. Five sort orders, with pagination. |
| **Resume intelligence** | Multiple PDF/DOCX resumes. On-device parsing: sections, skills, titles and merged experience years. Each JD is split into required vs. preferred skills. Scoring is weighted and explainable, with a per-component breakdown. Missing skills, a recommended resume per job, resume health checks and improvement suggestions. |
| **ATS checker** | Keyword coverage, missing keywords, section completeness, parsing risks (tables, text boxes, icon fonts, image-only PDFs), quantified achievements, title relevance, experience alignment and readability. It's clearly labelled as an **estimate**, not an employer ATS result. |
| **Tracker** | Kanban board: Saved → Applied → Assessment → Interview → Technical Round → HR → Offer → Rejected. Tracks dates and deadlines, interviews (with reminders), recruiter contact, salary and offer, notes, history, the resume used, and a frozen JD snapshot. Exports to CSV and PDF. |
| **Career intelligence** | Skill gaps with learning paths, recommended certifications, salary insights (only from ranges published in postings, never estimated), a market snapshot, and company profiles with careers links. Also: daily digest, new-job and high-match alerts, and side-by-side job comparison. |

## Install the APK

1. Download `CipherPath-release.apk` (signed) or `CipherPath-debug.apk` from the latest **Releases** page or the latest **Actions → Android build** run (artifact `cipherpath-apk`). Check it against `SHA256SUMS.txt`.
2. On your phone, allow your browser or Files app to install unknown apps (**Settings → Apps → Special access → Install unknown apps**).
3. Open the APK and tap **Install**. Android 8.0 (API 26) or newer is required.
4. Open CipherPath, choose your role families, allow notifications, and tap **Find jobs**.

> If Play Protect warns about an unknown developer, that's because the app isn't distributed through Google Play. The release APK is signed with your own key (see below), and the debug APK is signed with the CI debug key.

## Architecture

```
android/   Kotlin, Jetpack Compose, Material 3, Room on SQLCipher, WorkManager, OkHttp
  analysis/   Taxonomy · ResumeExtractor (PdfBox / streaming DOCX) · Matcher · AtsChecker   (pure Kotlin)
  data/net/   SafeHttp (SSRF-hardened) · Greenhouse/Lever/Ashby/SmartRecruiters connectors · BackendClient · DeepLinks
  data/repo/  JobRepository (sync, upsert, expire, dedup) · MatchEngine · ResumeRepository · ApplicationRepository
  security/   KeystoreCrypto (AES-256-GCM, Android Keystore) · DatabaseKey · SecureFileStore · SecretStore
  work/       SyncWorker · DigestWorker · ReminderWorker · Notifier
  ui/         9 main screens and sub-screens, light/dark themes
backend/   Python 3.13, FastAPI, SQLAlchemy 2, Alembic, APScheduler, pypdf, python-docx
shared/    taxonomy.json (skills, roles, certifications, learning paths), companies.json, parity fixtures
```

There are two sync modes, chosen in **Settings → Data source**:

* **On-device** (the default). The app calls the public ATS APIs directly, so no server is needed.
* **My backend**. The app pulls incremental changes from your FastAPI server (`GET /v1/jobs?updated_since=`), and the server runs the daily scheduler. Resumes are **never** sent to the backend; matching and ATS checks always run on the phone.

Both sides read the same `shared/taxonomy.json`. `ParityTest.kt` asserts the Kotlin port reproduces the Python implementation's skills, classifications, years, and match/ATS scores (within ±1 for rounding) on shared fixtures.

### How the match score works

```
score = Σ wᵢ·sᵢ / Σ wᵢ   over components the JD actually specifies
  required skills 45% · preferred skills 15% · experience 15% · role relevance 15% · seniority 10%
```

Experience and seniority use **relevant** years: total years × max(title relevance, required-skill coverage). That stops ten years in an unrelated field from inflating the score. If a component isn't stated in the JD, its weight is redistributed instead of counting for or against you. The app shows every component, its weight, its evidence, and the matched and missing skills.

## Build from source

```bash
# Android: JDK 17 + Android SDK 35
cd android && ./gradlew testDebugUnitTest assembleDebug        # app/build/outputs/apk/debug/
# Backend
cd backend && pip install -r requirements-dev.txt && python -m pytest -q
```

### Signed release APKs in CI

Create a keystore **once** on your own machine (never commit it):

```bash
keytool -genkeypair -v -keystore cipherpath-release.jks -alias cipherpath \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 cipherpath-release.jks > keystore.b64     # macOS: base64 -i cipherpath-release.jks -o keystore.b64
```

Then add four repository secrets under **Settings → Secrets and variables → Actions**:
`CIPHERPATH_KEYSTORE_BASE64` (the contents of keystore.b64), `CIPHERPATH_KEYSTORE_PASSWORD`, `CIPHERPATH_KEY_ALIAS` (`cipherpath`) and `CIPHERPATH_KEY_PASSWORD`.
The next push to `main` produces `CipherPath-release.apk`. Pushing a tag such as `v1.0.0` also attaches it to a GitHub Release. Keep the `.jks` backed up: future updates must be signed with the same key.

## Tests and checks (all run in CI)

* **Backend** (`backend/tests`): connector parsing, SSRF defences, ingest new/update/expire/dedup, resume parsing (PDF and DOCX, plus macro, zip-bomb, encrypted and wrong-type rejection), matching and ATS, API auth, rate limiting, SQL-injection strings, input validation and upload limits. Then bandit, pip-audit, an Alembic drift check, and a Docker build.
* **Android** (`android/app/src/test`): parity with Python, ingestion rules, and security checks (URL allowlist, private-IP rejection, slugs, CSV formula injection, HTML stripping, DOCX bomb, macro and traversal). Then `LiveConnectorTest`, which runs the app's own Kotlin connectors against the real APIs, and the R8 release build with lint-vital.
* **Live connector check**: a daily workflow that queries every seed board and publishes a report.
* **CodeQL** (Python, security-extended) and **Dependabot** for pip, Gradle and Actions.

## Documentation

* [docs/SOURCES.md](docs/SOURCES.md): which sources are integrated, how, and why the others are link-outs only.
* [docs/SECURITY.md](docs/SECURITY.md): threat model, OWASP MASVS and API Security Top 10 mapping.
* [docs/DEPLOY.md](docs/DEPLOY.md): self-hosting the backend securely.

## Known limitations

* **LinkedIn, Naukri, Indeed, Glassdoor and the other aggregators are not imported.** They don't offer a permitted public job-search API, and the project deliberately avoids scraping or bypassing their access controls.
* Coverage depends on which company boards you follow. Most large enterprises use Workday or iCIMS, which have no common public API.
* Match and ATS scores are heuristic and deterministic. There's no LLM in the loop. That makes them explainable, but they can miss skills phrased in unusual ways. You can extend `shared/taxonomy.json`.
* Salary insights are only shown when postings publish a range, which is common in the US and rare in India.

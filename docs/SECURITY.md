# Security design

## Assets and threats

| Asset | Threats considered |
|---|---|
| Resumes and parsed content (PII) | Device theft, other apps, cloud backups, screenshots, leakage to job sites or the backend |
| Application tracker (recruiter contacts, offers, notes) | Same as above, plus spreadsheet injection on export |
| Backend API key | Hard-coding, logging, interception |
| Backend server | SSRF via ingestion, injection, abuse/DoS, malicious uploads, credential stuffing |

## Android: OWASP MASVS v2 mapping

| MASVS control | Implementation |
|---|---|
| **STORAGE-1/2**: sensitive data stored securely; no leakage | Room on **SQLCipher** (256-bit). The random DB passphrase is wrapped with an **AES-256-GCM Android Keystore** key (non-exportable, hardware-backed where available) and kept in `noBackupFilesDir`. Each resume file is encrypted with the same Keystore key, with its id bound as AAD. The backend API key is stored encrypted (`SecretStore`), never in prefs. `allowBackup=false`, and `data_extraction_rules` exclude everything from cloud backup and device transfer. `FLAG_SECURE` blocks screenshots and the recents thumbnail. Notifications use `VISIBILITY_PRIVATE` with a redacted public version. |
| **CRYPTO-1/2**: strong, standard crypto; proper key management | AES-GCM with a random 96-bit IV and 128-bit tag. Keys are generated in and never leave the Keystore. "Erase all data" deletes the DB, files, secrets *and* the Keystore key. |
| **AUTH**: n/a on device | There are no accounts. Backend auth uses a bearer key (below). |
| **NETWORK-1/2**: secure communication | `usesCleartextTraffic=false` plus a network security config trusting **system CAs only** (user-installed CAs are rejected). `SafeHttp` allows https only, to a fixed ATS host allowlist plus the single user-configured backend host. Redirects are re-validated per hop, DNS answers for ATS hosts must be public IPs, and responses are capped at 15 MB with timeouts. |
| **PLATFORM-1/2/3**: IPC and WebView | One exported activity (the launcher). Notification deep links are matched against a strict internal-route regex, and arbitrary intents and URIs are ignored. There's no WebView: links open in Custom Tabs, https only. |
| **CODE-1..4**: up-to-date platform; input validation; dependencies | targetSdk 35 and minSdk 26. Every DB query uses bound parameters (sort is chosen with `CASE`, never string-built). Board slugs are regex-validated. Resume uploads are checked by magic bytes, size-capped at 5 MB, page-capped for PDFs, and rejected if encrypted. DOCX is read by a streaming parser with inflation, entry-count, macro and path-traversal limits, and the XML parser has DTDs disabled (no XXE). Dependabot covers Gradle dependencies. |
| **RESILIENCE** | R8 minification and resource shrinking in release builds, and `debuggable=false`. Verbose logging is stripped by ProGuard rules. |
| **PRIVACY-1..4** | No analytics, ads, crash reporters or trackers, and the `AD_ID` permission is explicitly removed. Permissions are limited to `INTERNET`, `ACCESS_NETWORK_STATE` and `POST_NOTIFICATIONS`. Files are opened through the Storage Access Framework, so no storage permission is needed. Nothing is sent to job platforms: Apply only opens a URL, and deep links carry only search keywords. Export is user-initiated through a system file picker. |

## Backend: OWASP API Security Top 10 (2023) mapping

| Risk | Mitigation |
|---|---|
| API1 Broken object-level authorization | The API exposes no per-user objects. Jobs and companies are shared, read-mostly data. Resumes are never stored. |
| API2 Broken authentication | Bearer keys are stored server-side only as **SHA-256 digests** and compared in constant time. The server **fails closed** (503) if no keys are configured. Credentials are length-capped. |
| API3 Broken object property level authorization | Responses use explicit Pydantic schemas. Inputs are typed and validated (`Literal` ATS values, slug regex, https-only URLs, control-character rejection). |
| API4 Unrestricted resource consumption | `slowapi` rate limits per key: 60/min globally, 4/h for sync, 20/h for uploads, 120/h for analysis. Page size is capped at 200. A request-size guard rejects anything over 5 MB with a 413. PDF page caps and DOCX inflation limits apply. |
| API5 Broken function level authorization | Every `/v1/*` route requires the key. `/health` is the only public route. |
| API6 Unrestricted access to sensitive business flows | Manual sync is rate-limited and single-flight (a lock prevents concurrent runs). |
| API7 SSRF | Ingestion never fetches user-supplied URLs. Requests can only go to a fixed host allowlist, with slugs validated, DNS resolution checked against private, loopback, link-local, CGNAT and metadata ranges, redirects re-validated, and size and time caps. All of this is covered by `tests/test_netsafe.py`. |
| API8 Security misconfiguration | OpenAPI and docs are disabled in production. Security headers are set (HSTS, nosniff, `frame-ancestors 'none'`, no-store, no-referrer). The container runs as a non-root user, and the server header is suppressed. |
| API9 Improper inventory management | A single versioned prefix (`/v1`), with the routes listed in `main.py`. |
| API10 Unsafe consumption of APIs | Upstream data is sanitised: HTML is stripped to text (scripts dropped), fields are length-capped, and non-https apply URLs are discarded. |

**SQL injection.** All queries go through SQLAlchemy's ORM and bound parameters, and `test_sql_injection_strings_are_inert` covers it. **Uploads** are parsed in memory and discarded. They're never written to disk or logged.

## Supply chain

* Backend: `pip-audit` (fails CI on known vulnerabilities), `bandit` and CodeQL `security-extended`. The pinned Starlette was upgraded to 1.10 after pip-audit flagged older releases.
* Android: Gradle wrapper validation and Dependabot.
* Secrets: none are in the repo. Signing material and API keys are provided via CI secrets and environment variables only.

## Reporting

Please report vulnerabilities privately via GitHub Security Advisories on this repository.

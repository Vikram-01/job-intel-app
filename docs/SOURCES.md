# Job sources: what's integrated, how, and why

CipherPath only collects jobs through **official, permitted, unauthenticated public endpoints**. It never scrapes logged-in pages, bypasses CAPTCHAs, rotates IPs, or uses private APIs. Every imported job keeps the employer's original `https://` posting URL, and **Apply** opens that page in the user's browser.

## Imported (public ATS job-board APIs)

| Source | Endpoint | Auth | Notes |
|---|---|---|---|
| Greenhouse | `GET boards-api.greenhouse.io/v1/boards/{board}/jobs?content=true` | none | Full JD HTML in the list call. |
| Lever | `GET api.lever.co/v0/postings/{site}?mode=json&skip=&limit=` | none | Paginated. Includes `workplaceType` and, when published, `salaryRange`. |
| Ashby | `GET api.ashbyhq.com/posting-api/job-board/{board}?includeCompensation=true` | none | Unlisted postings are skipped. Compensation is read when published. |
| SmartRecruiters | `GET api.smartrecruiters.com/v1/companies/{id}/postings` and `/postings/{id}` | none | Descriptions need a per-posting call, which is made only for in-scope titles. |
| Company career pages | Via the ATS above (most tech companies' career pages are backed by one) | — | Add any company in **Sources → Add board** (or `POST /v1/companies`). |

**Verification.** Each connector is tested in three ways:
1. Against recorded response fixtures (`backend/tests/test_connectors.py`).
2. Live, by the backend's Python connectors (`.github/workflows/connector-check.yml`, daily).
3. Live, by the app's own Kotlin connectors (`LiveConnectorTest`, on every Android build).

The latest per-board results are in each run's summary and artifacts. Treat an integration as working only when those reports show `OK`.

**Seed boards.** `shared/companies.json` lists 24 companies chosen for security and FDE hiring. Companies change ATS vendors, so a slug can go stale. The daily check reports `FAIL` for any board that stops responding, and the app shows a per-board status under **Sources**.

## Search-link only (not imported)

For each of these, the app builds a link to the site's **own search page** with your keywords and location filled in. Only the search keywords and location are included in the URL, never resume or personal data.

| Source | Why it isn't imported |
|---|---|
| LinkedIn Jobs | The Job Posting API is restricted to approved partners and covers *posting* jobs, not searching them. Automated access to linkedin.com is prohibited by its User Agreement. |
| Naukri | No public job-search API. Naukri's RMS APIs are for recruiters. |
| Indeed | The Publisher job-search API has been retired. Current Indeed APIs are employer-side (posting, sponsored jobs). |
| Glassdoor | Its public API was discontinued. |
| Google Jobs | No consumer search API. Cloud Talent Solution only indexes jobs *you* own. |
| Wellfound (AngelList Talent) | No public job-search API. |
| Instahyre | No public job-search API. |
| Foundit (formerly Monster India) | No public job-search API. |
| Cutshort | No public job-search API. |

**Alternative compliant collection.** If you have a commercial agreement with any of these platforms, or with a licensed aggregator, implement a new `Connector` in `backend/app/connectors/` and `android/.../data/net/Connectors.kt`. Add its host to both SSRF allowlists (`netsafe.ALLOWED_HOSTS`, `SafeHttp.ATS_HOSTS`) and store credentials server-side only. Never put them in the app.

## Not yet supported ATS vendors

Workday, iCIMS, Taleo, SuccessFactors and Oracle Recruiting have per-tenant or undocumented endpoints with no stable public job-board API. Companies on these platforms are reachable only through their careers page link.

"""Search deep-links for job sites that offer no public, permitted listing API.

These sources are NOT ingested. The app opens the site's own search page in the user's
browser with the query pre-filled, so the user browses under the site's own terms.
No resume or personal data is ever sent; only the search keywords and location.
"""
from __future__ import annotations

from urllib.parse import quote, quote_plus

DEEPLINK_SOURCES: dict[str, dict] = {
    "linkedin": {"label": "LinkedIn Jobs", "reason": "Job Posting API is partner-only and for posting, not search."},
    "naukri": {"label": "Naukri", "reason": "No public job-search API; RMS API is for recruiters."},
    "indeed": {"label": "Indeed", "reason": "Publisher job-search API has been retired; current APIs are employer-side."},
    "wellfound": {"label": "Wellfound", "reason": "No public job-search API."},
    "instahyre": {"label": "Instahyre", "reason": "No public job-search API."},
    "foundit": {"label": "Foundit", "reason": "No public job-search API."},
    "cutshort": {"label": "Cutshort", "reason": "No public job-search API."},
    "glassdoor": {"label": "Glassdoor", "reason": "Public API discontinued."},
    "google": {"label": "Google Jobs", "reason": "No consumer search API (Cloud Talent Solution indexes only your own jobs)."},
}


def _slugify(s: str) -> str:
    return "-".join("".join(ch.lower() if ch.isalnum() else " " for ch in s).split())


def build_links(query: str, location: str = "", remote: bool = False) -> list[dict]:
    q = query.strip()[:120]
    loc = location.strip()[:80]
    qp, lp = quote_plus(q), quote_plus(loc)
    links = {
        "linkedin": f"https://www.linkedin.com/jobs/search/?keywords={qp}&location={lp}" + ("&f_WT=2" if remote else ""),
        "naukri": f"https://www.naukri.com/{_slugify(q) or 'security'}-jobs" + (f"-in-{_slugify(loc)}" if loc else ""),
        "indeed": f"https://in.indeed.com/jobs?q={qp}&l={quote_plus('remote' if remote and not loc else loc)}",
        "wellfound": f"https://wellfound.com/role/{_slugify(q) or 'security-engineer'}" + ("/remote" if remote else ""),
        "instahyre": f"https://www.instahyre.com/search-jobs/?designation={qp}&location={lp}",
        "foundit": f"https://www.foundit.in/srp/results?query={qp}&locations={lp}",
        "cutshort": f"https://cutshort.io/jobs/{_slugify(q) or 'security'}-jobs",
        "glassdoor": f"https://www.glassdoor.co.in/Job/jobs.htm?sc.keyword={qp}&locKeyword={lp}",
        "google": "https://www.google.com/search?ibp=htl;jobs&q=" + quote(f"{q} jobs {loc}".strip()),
    }
    return [
        {"source": k, "label": DEEPLINK_SOURCES[k]["label"], "url": v, "reason": DEEPLINK_SOURCES[k]["reason"]}
        for k, v in links.items()
    ]

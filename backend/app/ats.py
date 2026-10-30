"""ATS compatibility estimate. This is a heuristic model of common ATS behaviour; it is
NOT the score any employer's ATS will produce. Every result carries that disclaimer."""
from __future__ import annotations

import re
from dataclasses import asdict, dataclass

from . import taxonomy as tx
from .matching import _title_tokens
from .resume_parser import ResumeDoc

DISCLAIMER = (
    "Estimated compatibility from JobIntel's own transparent heuristics. Employer ATS products "
    "(Workday, Greenhouse, Lever, iCIMS, Taleo, etc.) use their own parsing and ranking, and many "
    "rank by recruiter search rather than a score. Treat this as guidance, not a prediction."
)

WEIGHTS = {"keywords": 0.35, "sections": 0.15, "parsing": 0.15, "achievements": 0.10,
           "title": 0.10, "experience": 0.10, "readability": 0.05}


@dataclass
class Finding:
    check: str
    severity: str  # pass|info|warn|fail
    message: str
    fix: str = ""


@dataclass
class AtsResult:
    score: int
    breakdown: dict[str, int]
    findings: list[Finding]
    matched_keywords: list[str]
    missing_keywords: list[str]
    disclaimer: str = DISCLAIMER

    def to_dict(self) -> dict:
        return asdict(self)


def _jd_keywords(title: str, description: str) -> list[str]:
    req, pref = tx.split_required_preferred(description)
    kws = req + [p for p in pref if p not in req]
    return kws


def check(resume: ResumeDoc, title: str, description: str, min_years: int | None = None) -> AtsResult:
    f: list[Finding] = []
    b: dict[str, float] = {}
    low = resume.text.lower()

    # 1. Keywords
    kws = _jd_keywords(title, description)
    have = set(resume.skills)
    matched = [k for k in kws if k in have]
    missing = [k for k in kws if k not in have]
    b["keywords"] = len(matched) / len(kws) if kws else 1.0
    if not kws:
        f.append(Finding("keywords", "info", "No recognisable technical keywords in this JD to compare."))
    elif missing:
        sev = "fail" if b["keywords"] < 0.5 else "warn"
        f.append(Finding("keywords", sev, f"{len(matched)}/{len(kws)} JD keywords found. Missing: {', '.join(missing[:12])}",
                         "Use the JD's exact wording for skills you genuinely have, ideally in both Skills and Experience."))
    else:
        f.append(Finding("keywords", "pass", f"All {len(kws)} JD keywords present."))

    # 2. Sections
    needed = {"experience": "Experience", "education": "Education", "skills": "Skills"}
    present = [k for k in needed if resume.sections.get(k)]
    contact = resume.has_email and resume.has_phone
    b["sections"] = (len(present) + (1 if contact else 0) + (1 if resume.sections.get("summary") else 0.5)) / 5
    for k, label in needed.items():
        if k not in present:
            f.append(Finding("sections", "warn", f"No '{label}' heading detected.",
                             f"Add a plain heading named '{label}' so parsers can locate it."))
    if not resume.has_email:
        f.append(Finding("sections", "fail", "No email address detected.", "Put your email in the body text, not a header image."))
    if not resume.has_phone:
        f.append(Finding("sections", "warn", "No phone number detected."))
    if not resume.sections.get("summary"):
        f.append(Finding("sections", "info", "No summary section.", "A 2-3 line summary targeted to the role helps recruiters and keyword search."))

    # 3. Parsing risk
    p = 1.0
    words = len(resume.text.split())
    if words < 150:
        p -= 0.6
        f.append(Finding("parsing", "fail", f"Only {words} words of extractable text.",
                         "The file may be image-based or use embedded graphics for text. Export from a text editor instead."))
    if resume.tables:
        p -= 0.2
        f.append(Finding("parsing", "warn", f"{resume.tables} table(s) found.", "Some ATS read tables out of order; prefer simple lists."))
    if resume.text_boxes:
        p -= 0.25
        f.append(Finding("parsing", "warn", f"{resume.text_boxes} text box(es) found.", "Text boxes are often skipped by parsers; move content into the body."))
    if resume.images and words < 300:
        p -= 0.1
        f.append(Finding("parsing", "info", "Images present alongside little text.", "Avoid putting skills or contact info in images/icons."))
    if re.search(r"[-]", resume.text):
        p -= 0.1
        f.append(Finding("parsing", "warn", "Private-use glyphs detected (icon fonts).", "Replace icon-font symbols with plain text labels."))
    if resume.pages and resume.pages > 3:
        p -= 0.1
        f.append(Finding("parsing", "info", f"{resume.pages} pages.", "Two pages is usually enough unless the role is very senior or academic."))
    b["parsing"] = max(0.0, p)
    if p >= 1.0:
        f.append(Finding("parsing", "pass", "No common parsing risks detected."))

    # 4. Achievements
    if resume.bullets:
        ratio = resume.quantified_bullets / resume.bullets
        b["achievements"] = min(1.0, ratio / 0.4)
        sev = "pass" if ratio >= 0.4 else ("warn" if ratio >= 0.15 else "fail")
        f.append(Finding("achievements", sev, f"{resume.quantified_bullets}/{resume.bullets} bullets include numbers.",
                         "" if sev == "pass" else "Quantify impact: vulns found/fixed, MTTR reduced, % coverage, customers onboarded."))
    else:
        b["achievements"] = 0.3
        f.append(Finding("achievements", "warn", "No bullet points detected.", "Use bullet points for responsibilities and results."))

    # 5. Title relevance
    jt = _title_tokens(title)
    hits = sum(1 for t in jt if t in low)
    b["title"] = hits / len(jt) if jt else 1.0
    if jt and b["title"] < 0.5:
        f.append(Finding("title", "warn", f"Few words from the job title '{title}' appear in your resume.",
                         "If accurate, mirror the target title in your headline or summary."))
    else:
        f.append(Finding("title", "pass", "Job title terms appear in your resume."))

    # 6. Experience alignment
    if min_years is None:
        min_years = tx.min_years_required(description)
    if min_years:
        b["experience"] = min(1.0, resume.years_experience / min_years) if resume.years_experience else 0.0
        if resume.years_experience == 0:
            f.append(Finding("experience", "warn", "Could not read any date ranges from your experience.",
                             "Use a consistent format like 'Jan 2021 – Present' for each role."))
        elif resume.years_experience < min_years:
            f.append(Finding("experience", "warn", f"~{resume.years_experience:g} yrs detected vs {min_years}+ required."))
        else:
            f.append(Finding("experience", "pass", f"~{resume.years_experience:g} yrs meets the {min_years}+ asked."))
    else:
        b["experience"] = 1.0

    # 7. Readability
    sentences = [s for s in re.split(r"[.!?\n]", resume.text) if len(s.split()) > 3]
    avg = sum(len(s.split()) for s in sentences) / len(sentences) if sentences else 0
    b["readability"] = 1.0 if avg <= 25 else max(0.3, 1 - (avg - 25) / 30)
    if avg > 25:
        f.append(Finding("readability", "info", f"Average line length is {avg:.0f} words.", "Shorter bullets (under ~25 words) scan better."))

    score = round(100 * sum(WEIGHTS[k] * v for k, v in b.items()))
    return AtsResult(score, {k: round(v * 100) for k, v in b.items()}, f, matched, missing)

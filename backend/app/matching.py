"""Explainable resume-to-JD match score.

score = sum(weight_i * component_i) over components with data; weights are
re-normalised when a component has no data (e.g. JD lists no preferred skills),
so the score never silently rewards or penalises missing JD information.
"""
from __future__ import annotations

import re
from dataclasses import asdict, dataclass

from . import taxonomy as tx
from .resume_parser import ResumeDoc

WEIGHTS = {
    "required_skills": 0.45,
    "preferred_skills": 0.15,
    "experience": 0.15,
    "role_relevance": 0.15,
    "seniority": 0.10,
}

SENIORITY_YEARS = {
    "intern": 0, "entry": 0, "junior": 1, "mid": 3, "senior": 5,
    "lead": 7, "principal": 10, "architect": 8, "manager": 7,
}


@dataclass
class Component:
    key: str
    label: str
    weight: float
    score: float | None  # 0..1, None = not applicable
    detail: str


@dataclass
class MatchResult:
    score: int
    components: list[Component]
    matched_required: list[str]
    missing_required: list[str]
    matched_preferred: list[str]
    missing_preferred: list[str]
    resume_years: float
    relevant_years: float
    relevance: float
    required_years: int | None
    suggestions: list[str]

    def to_dict(self) -> dict:
        return asdict(self)


_STOP = {"and", "or", "the", "of", "senior", "junior", "staff", "lead", "principal", "sr", "i", "ii", "iii",
         "engineer", "engineering", "manager", "-", "&", "remote", "hybrid"}


def _title_tokens(title: str) -> set[str]:
    return {t for t in re.findall(r"[a-z]+", title.lower()) if t not in _STOP and len(t) > 2}


def match(resume: ResumeDoc, title: str, description: str, min_years: int | None = None) -> MatchResult:
    req, pref = tx.split_required_preferred(description)
    if min_years is None:
        min_years = tx.min_years_required(description)
    have = set(resume.skills)
    m_req = [s for s in req if s in have]
    x_req = [s for s in req if s not in have]
    m_pref = [s for s in pref if s in have]
    x_pref = [s for s in pref if s not in have]

    comps: list[Component] = []
    comps.append(Component(
        "required_skills", "Required skills", WEIGHTS["required_skills"],
        len(m_req) / len(req) if req else None,
        f"{len(m_req)} of {len(req)} required skills found" if req else "JD lists no recognisable required skills"))
    comps.append(Component(
        "preferred_skills", "Preferred skills", WEIGHTS["preferred_skills"],
        len(m_pref) / len(pref) if pref else None,
        f"{len(m_pref)} of {len(pref)} preferred skills found" if pref else "JD lists no preferred skills"))

    role, _ = tx.classify_role(title)
    jt = _title_tokens(title)
    best = 0.0
    best_title = ""
    for rt in resume.titles + [resume.sections.get("summary", "")[:200]]:
        r_role, _ = tx.classify_role(rt)
        overlap = len(jt & _title_tokens(rt)) / len(jt) if jt else 0.0
        s = max(overlap, 0.7 if (role and r_role == role) else 0.0)
        if s > best:
            best, best_title = s, rt
    comps.append(Component("role_relevance", "Role relevance", WEIGHTS["role_relevance"], round(best, 3),
                           f"Closest resume title: '{best_title[:60]}'" if best_title else "No matching role titles found in resume"))

    # Relevance factor: how much of the resume's experience is in this field.
    # Years in an unrelated field shouldn't count fully toward a security/FDE role.
    req_cov = len(m_req) / len(req) if req else 0.0
    rf = round(max(best, req_cov), 3)
    yrs = resume.years_experience
    rel_yrs = round(yrs * rf, 1)
    rel_note = f" (~{rel_yrs:g} relevant after scaling by relevance {rf:.0%})" if rf < 1 else ""
    if min_years:
        exp_score = min(1.0, rel_yrs / min_years) if rel_yrs else 0.0
        comps.append(Component("experience", "Experience", WEIGHTS["experience"], exp_score,
                               f"Resume shows ~{yrs:g} yrs{rel_note}; JD asks for {min_years}+"))
    else:
        comps.append(Component("experience", "Experience", WEIGHTS["experience"], None,
                               "JD states no minimum years"))

    level = tx.classify_seniority(title)
    if level in SENIORITY_YEARS:
        need = SENIORITY_YEARS[level]
        if need == 0:
            s = 1.0
        else:
            s = min(1.0, rel_yrs / need) if rel_yrs else 0.0
            if rel_yrs > need * 2.5 and level in ("junior", "entry"):
                s = 0.6
        comps.append(Component("seniority", "Seniority fit", WEIGHTS["seniority"], s,
                               f"Role level '{level}' typically ~{need}+ yrs; resume ~{rel_yrs:g} relevant"))
    else:
        comps.append(Component("seniority", "Seniority fit", WEIGHTS["seniority"], None, "Seniority not stated"))

    applicable = [c for c in comps if c.score is not None]
    wsum = sum(c.weight for c in applicable)
    score = round(100 * sum(c.weight * c.score for c in applicable) / wsum) if wsum else 0

    sugg = []
    if x_req:
        sugg.append("If you have them, add evidence for: " + ", ".join(x_req[:8]) +
                    " (in a bullet showing where you used each).")
    if x_pref:
        sugg.append("Preferred skills you could highlight: " + ", ".join(x_pref[:6]) + ".")
    if min_years and rel_yrs < min_years:
        sugg.append(f"Experience appears below the {min_years}+ yrs asked; make sure all relevant roles, "
                    "internships and projects have clear date ranges.")
    if best < 0.5:
        sugg.append(f"Tailor your summary/headline toward '{title}'.")
    return MatchResult(score, comps, m_req, x_req, m_pref, x_pref, yrs, rel_yrs, rf, min_years, sugg)

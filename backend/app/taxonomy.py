"""Loads the shared taxonomy and provides deterministic, explainable text classification.

The same rules are implemented in the Android app (Taxonomy.kt) against the same JSON file;
tests/test_parity_fixtures.py and the Android unit tests share fixture expectations.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from functools import lru_cache

from .config import get_settings


@dataclass(frozen=True)
class Skill:
    name: str
    cat: str
    patterns: tuple[re.Pattern, ...]


def _term_pattern(term: str) -> re.Pattern:
    # Word-boundary match that tolerates symbols like C++, CI/CD, .NET
    esc = re.escape(term.lower())
    return re.compile(rf"(?<![a-z0-9]){esc}(?![a-z0-9+#])")


@lru_cache
def load() -> dict:
    with open(get_settings().taxonomy_file, encoding="utf-8") as f:
        return json.load(f)


@lru_cache
def skills() -> tuple[Skill, ...]:
    out = []
    for s in load()["skills"]:
        terms = [s["name"], *s.get("aliases", [])]
        # Single-letter/very short names ("C", "Go") are too ambiguous as bare words in prose;
        # only match them via aliases or explicit language-list context.
        pats = []
        for t in terms:
            if len(t) <= 2 and t == s["name"]:
                if t == "C":
                    pats.append(re.compile(r"(?<![a-z0-9])c(?=\s*(?:,|/|and|\bor\b)\s*(?:c\+\+|python|rust|go|java))"))
                elif t == "Go":
                    pats.append(re.compile(r"(?<![a-z0-9])go(?=\s*(?:,|/)\s*(?:python|rust|java|c\+\+|kotlin))|(?<=python, )go\b|(?<=python/)go\b"))
                continue
            pats.append(_term_pattern(t))
        out.append(Skill(s["name"], s["cat"], tuple(pats)))
    return tuple(out)


def extract_skills(text: str) -> list[str]:
    low = text.lower()
    found = []
    for sk in skills():
        if any(p.search(low) for p in sk.patterns):
            found.append(sk.name)
    return found


_REQ_HDR = re.compile(r"(requirements|what you('|’)ll need|you have|you bring|qualifications|must have|minimum|required|what we('|’)re looking for|about you)", re.I)
_PREF_HDR = re.compile(r"(nice to have|preferred|bonus|plus|good to have|ideally|desired)", re.I)


def split_required_preferred(text: str) -> tuple[list[str], list[str]]:
    """Split JD skills into required vs preferred using section headings.

    Lines after a 'preferred/nice to have' heading count as preferred; everything else
    (including text with no headings) counts as required. Explicitly marked lines
    ('... is a plus') are preferred.
    """
    required: list[str] = []
    preferred: list[str] = []
    mode = "req"
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        is_heading = len(stripped) < 80 and not stripped.startswith("•")
        if is_heading and _PREF_HDR.search(stripped):
            mode = "pref"
        elif is_heading and _REQ_HDR.search(stripped):
            mode = "req"
        line_pref = mode == "pref" or re.search(r"\b(a plus|is a bonus|nice to have|preferred)\b", stripped, re.I)
        for s in extract_skills(stripped):
            (preferred if line_pref else required).append(s)
    req = list(dict.fromkeys(required))
    pref = [s for s in dict.fromkeys(preferred) if s not in req]
    return req, pref


def classify_role(title: str, description: str = "") -> tuple[str | None, str | None]:
    t = title.lower()
    roles = load()["roles"]
    for r in roles:  # title match first, ordered most-specific-first in the JSON
        if any(k in t for k in r["keywords"]):
            return r["id"], r["group"]
    return None, None


def classify_seniority(title: str) -> str:
    t = f" {title.lower()} "
    for lvl in ("intern", "manager", "principal", "architect", "lead", "senior", "mid", "junior", "entry"):
        entry = next(s for s in load()["seniority"] if s["level"] == lvl)
        if any(k in t for k in entry["keywords"]):
            return lvl
    return "unknown"


_REMOTE = re.compile(r"\b(remote|work from home|wfh|anywhere)\b", re.I)
_HYBRID = re.compile(r"\bhybrid\b", re.I)
_ONSITE = re.compile(r"\b(on-?site|in[- ]office)\b", re.I)


def classify_work_mode(location: str, description: str = "", hint: str | None = None) -> str:
    if hint:
        h = hint.lower()
        if "remote" in h:
            return "remote"
        if "hybrid" in h:
            return "hybrid"
        if "site" in h or "office" in h:
            return "onsite"
    if _HYBRID.search(location):
        return "hybrid"
    if _REMOTE.search(location):
        return "remote"
    if _ONSITE.search(location):
        return "onsite"
    head = description[:1500]
    if _HYBRID.search(head):
        return "hybrid"
    if re.search(r"\b(fully remote|100% remote|remote-first|remote position)\b", head, re.I):
        return "remote"
    return "onsite" if location.strip() else "unknown"


_INDIA = re.compile(r"\b(india|bengaluru|bangalore|hyderabad|pune|mumbai|delhi|new delhi|gurgaon|gurugram|noida|chennai|kolkata|ahmedabad|kochi|jaipur)\b", re.I)
_US = re.compile(r"\b(united states|usa|u\.s\.|new york|san francisco|seattle|austin|boston|chicago|remote - us|remote, us|, ca\b|, ny\b|, wa\b|, tx\b)\b", re.I)
_UK = re.compile(r"\b(united kingdom|london|uk)\b", re.I)


def classify_country(location: str) -> str | None:
    if _INDIA.search(location):
        return "IN"
    if _US.search(location):
        return "US"
    if _UK.search(location):
        return "GB"
    return None


_YEARS = re.compile(r"(\d{1,2})\s*\+?\s*(?:-|to|–)?\s*(?:\d{1,2})?\s*\+?\s*years?", re.I)


def min_years_required(text: str) -> int | None:
    vals = []
    for m in _YEARS.finditer(text[:20000]):
        ctx = text[m.end(): m.end() + 60].lower()
        if "experience" in ctx or "exp" in ctx or "of " in ctx[:4]:
            v = int(m.group(1))
            if 0 < v <= 25:
                vals.append(v)
    return min(vals) if vals else None


_SAL = re.compile(
    r"(?P<cur>\$|USD|₹|INR|£|GBP|€|EUR)\s?(?P<a>\d[\d,]*(?:\.\d+)?)\s?(?P<ak>[kK]|L|lakh|LPA)?\s*(?:-|–|to)\s*"
    r"(?:\$|USD|₹|INR|£|GBP|€|EUR)?\s?(?P<b>\d[\d,]*(?:\.\d+)?)\s?(?P<bk>[kK]|L|lakh|LPA)?"
)
_CUR = {"$": "USD", "USD": "USD", "₹": "INR", "INR": "INR", "£": "GBP", "GBP": "GBP", "€": "EUR", "EUR": "EUR"}


def _num(v: str, unit: str | None) -> float:
    n = float(v.replace(",", ""))
    if unit in ("k", "K"):
        n *= 1_000
    elif unit in ("L", "lakh", "LPA"):
        n *= 100_000
    return n


def parse_salary(text: str) -> tuple[float | None, float | None, str | None]:
    """Only returns a salary when the posting states an explicit range. Never estimates."""
    m = _SAL.search(text)
    if not m:
        return None, None, None
    lo = _num(m.group("a"), m.group("ak") or m.group("bk"))
    hi = _num(m.group("b"), m.group("bk"))
    if lo <= 0 or hi < lo or hi > 50_000_000:
        return None, None, None
    return lo, hi, _CUR[m.group("cur")]

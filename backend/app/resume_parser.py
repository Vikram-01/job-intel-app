"""Secure resume parsing (PDF/DOCX). Files are processed in memory and never persisted.

Validation: size cap, magic-byte type detection (extension/MIME are not trusted),
DOCX zip-bomb limits and macro rejection, encrypted-PDF rejection and page cap.
"""
from __future__ import annotations

import io
import re
import zipfile
from dataclasses import dataclass, field
from datetime import date

from . import taxonomy as tx
from .textutil import clean_text

MAX_PAGES = 15
MAX_ZIP_ENTRIES = 500
MAX_ZIP_UNCOMPRESSED = 50 * 1024 * 1024


class ResumeRejected(ValueError):
    pass


@dataclass
class ResumeDoc:
    text: str
    file_type: str
    pages: int | None = None
    tables: int = 0
    images: int = 0
    text_boxes: int = 0
    sections: dict[str, str] = field(default_factory=dict)
    skills: list[str] = field(default_factory=list)
    years_experience: float = 0.0
    titles: list[str] = field(default_factory=list)
    has_email: bool = False
    has_phone: bool = False
    has_linkedin: bool = False
    bullets: int = 0
    quantified_bullets: int = 0


def sniff_type(data: bytes) -> str:
    if data[:5] == b"%PDF-":
        return "pdf"
    if data[:4] == b"PK\x03\x04":
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                names = set(z.namelist())
        except zipfile.BadZipFile as exc:
            raise ResumeRejected("corrupt DOCX") from exc
        if "word/document.xml" in names and "[Content_Types].xml" in names:
            return "docx"
    raise ResumeRejected("unsupported file type: only PDF and DOCX are accepted")


def _check_docx_zip(data: bytes) -> None:
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        infos = z.infolist()
        if len(infos) > MAX_ZIP_ENTRIES:
            raise ResumeRejected("DOCX has too many parts")
        if sum(i.file_size for i in infos) > MAX_ZIP_UNCOMPRESSED:
            raise ResumeRejected("DOCX expands too large")
        for i in infos:
            n = i.filename.lower()
            if n.endswith("vbaproject.bin") or n.startswith("..") or n.startswith("/"):
                raise ResumeRejected("DOCX contains macros or unsafe paths")


def _extract_pdf(data: bytes) -> ResumeDoc:
    from pypdf import PdfReader
    from pypdf.errors import PdfReadError

    try:
        reader = PdfReader(io.BytesIO(data), strict=False)
    except PdfReadError as exc:
        raise ResumeRejected("unreadable PDF") from exc
    if reader.is_encrypted:
        raise ResumeRejected("encrypted PDFs are not supported")
    n = len(reader.pages)
    if n > MAX_PAGES:
        raise ResumeRejected(f"PDF has more than {MAX_PAGES} pages")
    parts, images = [], 0
    for page in reader.pages:
        parts.append(page.extract_text() or "")
        try:
            images += len(page.images)
        except Exception:  # noqa: BLE001  # nosec B110 B112 - image count is advisory; malformed image streams are ignored
            continue
    return ResumeDoc(text=clean_text("\n".join(parts)), file_type="pdf", pages=n, images=images)


def _extract_docx(data: bytes) -> ResumeDoc:
    _check_docx_zip(data)
    import docx

    d = docx.Document(io.BytesIO(data))
    parts = [p.text for p in d.paragraphs]
    for t in d.tables:
        for row in t.rows:
            parts.append(" | ".join(c.text for c in row.cells))
    for s in d.sections:
        for p in s.header.paragraphs:
            parts.insert(0, p.text)
    xml = d.element.xml
    return ResumeDoc(
        text=clean_text("\n".join(parts)), file_type="docx", tables=len(d.tables),
        images=len(d.inline_shapes), text_boxes=xml.count("<w:txbxContent"),
    )


_SECTION_ALIASES = {
    "summary": ["summary", "profile", "professional summary", "about me", "objective"],
    "experience": ["experience", "work experience", "professional experience", "employment", "work history"],
    "education": ["education", "academic", "qualifications"],
    "skills": ["skills", "technical skills", "core competencies", "technologies", "tools"],
    "certifications": ["certifications", "certificates", "licenses"],
    "projects": ["projects", "personal projects", "key projects"],
    "achievements": ["achievements", "awards", "publications", "cves", "talks"],
}


def split_sections(text: str) -> dict[str, str]:
    lines = text.splitlines()
    sections: dict[str, list[str]] = {}
    current = "header"
    for line in lines:
        s = line.strip().rstrip(":").lower()
        hit = None
        if 0 < len(s) <= 40:
            for key, aliases in _SECTION_ALIASES.items():
                if s in aliases or any(s == a or s.startswith(a + " ") for a in aliases):
                    hit = key
                    break
        if hit:
            current = hit
            sections.setdefault(current, [])
        else:
            sections.setdefault(current, []).append(line)
    return {k: "\n".join(v).strip() for k, v in sections.items()}


_MONTHS = {m: i for i, m in enumerate(
    ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"], start=1)}
_RANGE = re.compile(
    r"(?P<m1>[A-Za-z]{3,9}\.?\s+)?(?P<y1>(?:19|20)\d{2})\s*(?:-|–|—|to)\s*"
    r"(?:(?P<m2>[A-Za-z]{3,9}\.?\s+)?(?P<y2>(?:19|20)\d{2})|(?P<now>present|current|now|till date|ongoing))",
    re.I)


def _month(tok: str | None, default: int) -> int:
    if not tok:
        return default
    return _MONTHS.get(tok.strip().lower()[:3], default)


def estimate_years(experience_text: str, today: date | None = None) -> float:
    """Sum of merged (non-overlapping) date ranges found in the experience section."""
    today = today or date.today()
    spans = []
    for m in _RANGE.finditer(experience_text):
        start = (int(m.group("y1")), _month(m.group("m1"), 1))
        end = (today.year, today.month) if m.group("now") else (int(m.group("y2")), _month(m.group("m2"), 12))
        s, e = start[0] * 12 + start[1], end[0] * 12 + end[1]
        if e >= s and start[0] >= 1970:
            spans.append((s, e))
    spans.sort()
    total, cur = 0, None
    for s, e in spans:
        if cur and s <= cur[1]:
            cur = (cur[0], max(cur[1], e))
        else:
            if cur:
                total += cur[1] - cur[0] + 1
            cur = (s, e)
    if cur:
        total += cur[1] - cur[0] + 1
    return round(total / 12, 1)


_BULLET = re.compile(r"^\s*(?:[•\-\*▪●◦]|\d+\.)\s+")
_QUANT = re.compile(r"(\d+(\.\d+)?\s*%|\$\s?\d|₹\s?\d|\b\d{2,}\b|\b\d+x\b|\b\d+\+)")


def analyse_text(doc: ResumeDoc) -> ResumeDoc:
    doc.sections = split_sections(doc.text)
    doc.skills = tx.extract_skills(doc.text)
    exp = doc.sections.get("experience") or doc.text
    doc.years_experience = estimate_years(exp)
    titles = []
    for line in exp.splitlines()[:200]:
        ln = line.strip()
        if 3 < len(ln) < 90 and tx.classify_role(ln)[0]:
            titles.append(ln)
    doc.titles = list(dict.fromkeys(titles))[:10]
    doc.has_email = bool(re.search(r"[\w.+-]+@[\w-]+\.[\w.]+", doc.text))
    doc.has_phone = bool(re.search(r"(\+?\d[\d\s().-]{8,}\d)", doc.text))
    doc.has_linkedin = "linkedin.com/" in doc.text.lower()
    lines = doc.text.splitlines()
    bullets = [ln for ln in lines if _BULLET.match(ln)]
    if not bullets and doc.sections.get("experience"):
        bullets = [ln for ln in doc.sections["experience"].splitlines() if len(ln.split()) >= 6]
    doc.bullets = len(bullets)
    doc.quantified_bullets = sum(1 for b in bullets if _QUANT.search(b))
    return doc


def parse_resume(data: bytes, max_bytes: int) -> ResumeDoc:
    if not data:
        raise ResumeRejected("empty file")
    if len(data) > max_bytes:
        raise ResumeRejected("file too large")
    kind = sniff_type(data)
    doc = _extract_pdf(data) if kind == "pdf" else _extract_docx(data)
    return analyse_text(doc)


def from_text(text: str) -> ResumeDoc:
    return analyse_text(ResumeDoc(text=clean_text(text, 60_000), file_type="text"))

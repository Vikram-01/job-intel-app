import io
import zipfile
from datetime import date

import pytest

from app import ats, matching
from app import taxonomy as tx
from app.resume_parser import ResumeRejected, estimate_years, from_text, parse_resume, split_sections

RESUME = """Priya Sharma
priya@example.com | +91 98765 43210 | linkedin.com/in/priya

Summary
Application security engineer focused on secure code review and threat modeling.

Experience
Senior Application Security Engineer, Fintech Co — Jan 2021 – Present
• Led threat modeling for 40+ services, reducing critical findings by 35%
• Rolled out Semgrep SAST across 120 repos in GitHub Actions
• Built Python tooling for AWS IAM reviews

Security Engineer, Startup — Jul 2018 – Dec 2020
• Ran Burp Suite assessments of REST APIs
• Wrote detection rules

Education
B.Tech Computer Science, 2018

Skills
Python, Go, AWS, Semgrep, Burp Suite, Threat Modeling, OWASP, Docker
"""

JD = """About the role
We need a senior application security engineer.

Requirements
• 5+ years of experience in application security
• Python
• SAST tools such as Semgrep or CodeQL
• AWS

Nice to have
• Kubernetes
• Terraform
"""


def test_sections_and_years():
    secs = split_sections(RESUME)
    assert {"summary", "experience", "education", "skills"} <= set(secs)
    yrs = estimate_years(secs["experience"], today=date(2026, 10, 1))
    assert 8.0 <= yrs <= 8.4  # Jul 2018 -> Oct 2026, non-overlapping


def test_overlapping_ranges_are_merged():
    assert estimate_years("A 2020 - 2022\nB 2021 - 2022", today=date(2026, 1, 1)) == 3.0


def test_required_vs_preferred_split():
    req, pref = tx.split_required_preferred(JD)
    assert {"Python", "SAST", "AWS", "Application Security"} <= set(req)
    assert set(pref) == {"Kubernetes", "Terraform"}


def test_match_is_explainable_and_bounded():
    r = matching.match(from_text(RESUME), "Senior Application Security Engineer", JD)
    assert 0 <= r.score <= 100
    assert r.missing_required == []
    assert set(r.missing_preferred) == {"Kubernetes", "Terraform"}
    keys = {c.key for c in r.components}
    assert keys == set(matching.WEIGHTS)
    # recompute from the published breakdown — the score must be reproducible
    appl = [c for c in r.components if c.score is not None]
    assert r.score == round(100 * sum(c.weight * c.score for c in appl) / sum(c.weight for c in appl))
    assert r.score >= 80


def test_match_low_for_unrelated_resume():
    other = "Jane\njane@x.com\nExperience\nChef, Bistro — 2015 – 2020\n• Cooked 200 meals a day\nSkills\nCooking"
    r = matching.match(from_text(other), "Senior Application Security Engineer", JD)
    assert r.score < 25
    assert "Python" in r.missing_required


def test_ats_flags_and_disclaimer():
    res = ats.check(from_text(RESUME), "Senior Application Security Engineer", JD)
    assert res.disclaimer and "not" in res.disclaimer.lower()
    assert 0 <= res.score <= 100
    assert set(res.breakdown) == set(ats.WEIGHTS)
    assert "Kubernetes" in res.missing_keywords
    bad = ats.check(from_text("Nobody\n" + "word " * 60), "Cloud Security Engineer", JD)
    sev = {f.check: f.severity for f in bad.findings if f.severity in ("fail", "warn")}
    assert "sections" in sev and "parsing" in sev
    assert bad.score < res.score


def _docx_bytes(text: str) -> bytes:
    import docx
    d = docx.Document()
    for line in text.splitlines():
        d.add_paragraph(line)
    d.add_table(rows=1, cols=2)
    buf = io.BytesIO()
    d.save(buf)
    return buf.getvalue()


def test_parse_docx():
    doc = parse_resume(_docx_bytes(RESUME), 5_000_000)
    assert doc.file_type == "docx" and doc.tables == 1
    assert "Threat Modeling" in doc.skills


def test_parse_pdf():
    from reportlab.lib.pagesizes import A4
    from reportlab.pdfgen import canvas
    buf = io.BytesIO()
    c = canvas.Canvas(buf, pagesize=A4)
    y = 800
    for line in RESUME.splitlines():
        c.drawString(40, y, line)
        y -= 14
    c.save()
    doc = parse_resume(buf.getvalue(), 5_000_000)
    assert doc.file_type == "pdf" and doc.pages == 1
    assert "Python" in doc.skills and doc.has_email


def test_rejects_wrong_type_and_size():
    with pytest.raises(ResumeRejected):
        parse_resume(b"MZ\x90\x00 this is an exe", 5_000_000)
    with pytest.raises(ResumeRejected):
        parse_resume(b"%PDF-" + b"0" * 100, 50)
    with pytest.raises(ResumeRejected):
        parse_resume(b"", 100)


def test_rejects_macro_docx():
    raw = _docx_bytes(RESUME)
    buf = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(raw)) as src, zipfile.ZipFile(buf, "w") as dst:
        for i in src.infolist():
            dst.writestr(i, src.read(i))
        dst.writestr("word/vbaProject.bin", b"x")
    with pytest.raises(ResumeRejected):
        parse_resume(buf.getvalue(), 5_000_000)


def test_rejects_zip_bomb_docx():
    raw = _docx_bytes(RESUME)
    buf = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(raw)) as src, zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as dst:
        for i in src.infolist():
            dst.writestr(i, src.read(i))
        dst.writestr("word/bomb.xml", b"\0" * (60 * 1024 * 1024))
    with pytest.raises(ResumeRejected):
        parse_resume(buf.getvalue(), 50_000_000)


def test_rejects_encrypted_pdf():
    from pypdf import PdfWriter
    w = PdfWriter()
    w.add_blank_page(200, 200)
    w.encrypt("pw")
    buf = io.BytesIO()
    w.write(buf)
    with pytest.raises(ResumeRejected):
        parse_resume(buf.getvalue(), 5_000_000)


@pytest.mark.parametrize("title,role,level", [
    ("Senior Application Security Engineer", "appsec", "senior"),
    ("Forward Deployed Engineer, AI", "fde", "unknown"),
    ("SOC Analyst I", "soc", "junior"),
    ("Principal Security Architect", "secarch", "principal"),
    ("Security Engineering Intern", "seceng", "intern"),
    ("Staff Detection Engineer", "soc", "lead"),
    ("Head of GRC", "grc", "manager"),
    ("Solutions Engineer", "solutions", "unknown"),
    ("Marketing Manager", None, "manager"),
])
def test_classification(title, role, level):
    assert tx.classify_role(title)[0] == role
    assert tx.classify_seniority(title) == level


@pytest.mark.parametrize("text,expected", [
    ("Salary: $150,000 - $190,000", (150000, 190000, "USD")),
    ("CTC ₹25L - ₹40L", (2500000, 4000000, "INR")),
    ("Competitive pay", (None, None, None)),
])
def test_salary_only_when_explicit(text, expected):
    assert tx.parse_salary(text) == expected

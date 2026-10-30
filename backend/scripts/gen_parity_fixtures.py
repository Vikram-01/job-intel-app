"""Generates shared/fixtures/parity.json from the Python implementation.

The Android unit test ParityTest.kt asserts the Kotlin port produces the same
skills, required/preferred split, classifications, years and match/ATS scores.
Re-run after changing taxonomy.json or scoring logic:  python scripts/gen_parity_fixtures.py
"""
import json
import sys
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import ats, matching  # noqa: E402
from app import taxonomy as tx  # noqa: E402
from app.resume_parser import ResumeDoc, analyse_text  # noqa: E402
import app.resume_parser as rp  # noqa: E402
from tests.test_resume_match_ats import JD, RESUME  # noqa: E402

TODAY = date(2026, 10, 1)
_orig = rp.estimate_years
rp.estimate_years = lambda text, today=None: _orig(text, TODAY)

JD2 = """Forward Deployed Engineer
You'll work directly with customers to deploy LLMs and AI agents into production.

What you'll need
• 3+ years of experience building REST APIs in Python or TypeScript
• Customer-facing experience
• Comfortable with Docker and Kubernetes

Bonus
• Experience with Terraform
• Prior presales or POC work
"""
RESUME2 = """Arjun Rao
arjun@example.com +91 99887 76655
Experience
Solutions Engineer — Acme AI, Mar 2022 – Present
- Delivered 15 customer POCs integrating LLMs via REST APIs
- Built Python and TypeScript services on Docker
Software Engineer — Beta, Jun 2020 - Feb 2022
- Built microservices
Education
B.E. 2020
Skills
Python, TypeScript, Docker, LLMs, RAG
"""
cases = [
    ("Senior Application Security Engineer", JD, RESUME),
    ("Forward Deployed Engineer", JD2, RESUME2),
    ("Senior Application Security Engineer", JD, RESUME2),
]
out = {"today": TODAY.isoformat(), "cases": [], "titles": [], "salaries": [], "locations": []}
for title, jd, resume in cases:
    doc = analyse_text(ResumeDoc(text=resume, file_type="text"))
    req, pref = tx.split_required_preferred(jd)
    m = matching.match(doc, title, jd)
    a = ats.check(doc, title, jd)
    out["cases"].append({
        "title": title, "jd": jd, "resume": resume,
        "resume_skills": doc.skills, "years": doc.years_experience, "titles": doc.titles,
        "req": req, "pref": pref, "min_years": tx.min_years_required(jd),
        "match_score": m.score, "ats_score": a.score, "ats_breakdown": a.to_dict()["breakdown"],
        "bullets": doc.bullets, "quantified": doc.quantified_bullets,
    })
for t in ["Senior Application Security Engineer", "Forward Deployed Engineer, AI", "SOC Analyst I", "Principal Security Architect",
          "Security Engineering Intern", "Staff Detection Engineer", "Head of GRC", "Solutions Engineer", "Marketing Manager",
          "Sr. Cloud Security Engineer", "Red Team Operator", "Malware Reverse Engineer II", "Platform Engineer", "IAM Engineer"]:
    role, group = tx.classify_role(t)
    out["titles"].append({"title": t, "role": role, "group": group, "seniority": tx.classify_seniority(t)})
for s in ["Salary: $150,000 - $190,000", "CTC ₹25L - ₹40L", "£60k–£80k", "Competitive pay"]:
    out["salaries"].append({"text": s, "result": list(tx.parse_salary(s))})
for loc, desc in [("Bengaluru, India", ""), ("Remote - US", ""), ("London", "Hybrid working"), ("", ""), ("Pune", "This is a fully remote position")]:
    out["locations"].append({"location": loc, "desc": desc, "country": tx.classify_country(loc), "mode": tx.classify_work_mode(loc, desc)})
Path(__file__).resolve().parents[2].joinpath("shared/fixtures/parity.json").write_text(json.dumps(out, indent=1, ensure_ascii=False))
print("wrote", len(out["cases"]), "cases")

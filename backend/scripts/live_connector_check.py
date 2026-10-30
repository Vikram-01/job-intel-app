"""Live check of every seed company board against the real public ATS APIs.

Run in CI (connector-check workflow) or locally with internet access. Writes a Markdown
report and exits non-zero only if an entire connector type returns zero working boards
(meaning the integration itself is broken, not just one stale slug).
"""
import json
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import taxonomy as tx  # noqa: E402
from app.connectors import make_connector  # noqa: E402
from app.netsafe import SafeClient  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
companies = json.loads((ROOT / "shared/companies.json").read_text())["companies"]
client = SafeClient()
rows, ok_by_ats = [], defaultdict(int)
for c in companies:
    conn = make_connector(c["ats"], client)
    try:
        jobs = conn.list_titles(c["slug"], c["name"])
        rel = [j for j in jobs if tx.classify_role(j.title)[0]]
        detail_ok = ""
        if rel and not rel[0].description_text:
            d = conn.fill_details(c["slug"], rel[0])
            detail_ok = " (details ok)" if d.description_text else " (details EMPTY)"
        bad_urls = sum(1 for j in jobs if not j.apply_url.startswith("https://"))
        status = f"OK: {len(jobs)} postings, {len(rel)} in scope{detail_ok}" + (f", {bad_urls} bad URLs" if bad_urls else "")
        ok_by_ats[c["ats"]] += 1
    except Exception as e:  # noqa: BLE001
        status = f"FAIL: {type(e).__name__}: {str(e)[:120]}"
    rows.append((c["ats"], c["name"], c["slug"], status))
    print(f"{c['ats']:16} {c['slug']:20} {status}", flush=True)

out = ["| ATS | Company | Slug | Result |", "|---|---|---|---|"]
out += [f"| {a} | {n} | `{s}` | {r} |" for a, n, s, r in rows]
Path("connector-report.md").write_text("\n".join(out) + "\n")
broken = [a for a in {c["ats"] for c in companies} if ok_by_ats[a] == 0]
if broken:
    print("BROKEN CONNECTORS:", broken)
    sys.exit(1)

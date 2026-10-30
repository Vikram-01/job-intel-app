"""HTML-to-text conversion and sanitisation. Output is plain text only; no markup survives."""
from __future__ import annotations

import html
import re
from html.parser import HTMLParser

_BLOCK_TAGS = {"p", "div", "br", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "section"}
_CONTROL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")


class _Extractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self._skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style"):
            self._skip += 1
        elif tag == "li":
            self.parts.append("\n• ")
        elif tag in _BLOCK_TAGS:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag in ("script", "style"):
            self._skip = max(0, self._skip - 1)
        elif tag in _BLOCK_TAGS:
            self.parts.append("\n")

    def handle_data(self, data):
        if not self._skip:
            self.parts.append(data)


def html_to_text(raw: str | None, max_len: int = 60_000) -> str:
    if not raw:
        return ""
    # Greenhouse returns HTML-escaped HTML; unescape once before parsing.
    if "&lt;" in raw[:2000]:
        raw = html.unescape(raw)
    p = _Extractor()
    p.feed(raw)
    p.close()
    return clean_text("".join(p.parts), max_len)


def clean_text(text: str, max_len: int = 60_000) -> str:
    text = _CONTROL.sub("", text).replace("\xa0", " ")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n\s*\n\s*\n+", "\n\n", text)
    return text.strip()[:max_len]


def sanitize_short(value: object, max_len: int = 300) -> str:
    if value is None:
        return ""
    return clean_text(str(value), max_len).replace("\n", " ")

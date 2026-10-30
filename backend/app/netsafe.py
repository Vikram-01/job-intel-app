"""SSRF-hardened outbound HTTP for job ingestion.

Defences:
  * Only https, only hosts on a fixed allowlist (never user-supplied hosts).
  * Path components (board slugs) validated against a strict regex before URL building.
  * DNS is resolved and every address must be public (no private, loopback, link-local,
    multicast, reserved or cloud-metadata ranges).
  * Redirects are followed manually (max 3) and each hop is re-validated.
  * Response bodies are size-capped and requests time out.
"""
from __future__ import annotations

import ipaddress
import re
import socket
from typing import Any
from urllib.parse import quote, urlsplit

import httpx

from .config import get_settings

ALLOWED_HOSTS: frozenset[str] = frozenset({
    "boards-api.greenhouse.io",
    "api.lever.co",
    "api.eu.lever.co",
    "api.ashbyhq.com",
    "api.smartrecruiters.com",
})

SLUG_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")
USER_AGENT = "JobIntel/1.0 (+https://github.com/Vikram-01/job-intel-app)"


class UnsafeRequest(ValueError):
    """Raised when a request would violate the outbound-request policy."""


def safe_slug(slug: str) -> str:
    if not isinstance(slug, str) or not SLUG_RE.fullmatch(slug) or ".." in slug:
        raise UnsafeRequest(f"invalid board slug: {slug!r}")
    return quote(slug, safe="._-")


def _ip_is_public(ip: str) -> bool:
    addr = ipaddress.ip_address(ip)
    if isinstance(addr, ipaddress.IPv6Address) and addr.ipv4_mapped:
        addr = addr.ipv4_mapped
    return not (
        addr.is_private or addr.is_loopback or addr.is_link_local or addr.is_multicast
        or addr.is_reserved or addr.is_unspecified
        or (isinstance(addr, ipaddress.IPv4Address) and addr in ipaddress.ip_network("100.64.0.0/10"))
    )


def validate_url(url: str, *, resolve: bool = True) -> None:
    parts = urlsplit(url)
    if parts.scheme != "https":
        raise UnsafeRequest("only https is allowed")
    host = (parts.hostname or "").lower()
    if host not in ALLOWED_HOSTS:
        raise UnsafeRequest(f"host not allowed: {host}")
    if parts.username or parts.password:
        raise UnsafeRequest("credentials in URL not allowed")
    if parts.port not in (None, 443):
        raise UnsafeRequest("non-standard port not allowed")
    if resolve:
        try:
            infos = socket.getaddrinfo(host, 443, proto=socket.IPPROTO_TCP)
        except socket.gaierror as exc:
            raise UnsafeRequest(f"DNS resolution failed for {host}") from exc
        for info in infos:
            if not _ip_is_public(info[4][0]):
                raise UnsafeRequest(f"{host} resolves to non-public address")


class SafeClient:
    def __init__(self, client: httpx.Client | None = None, *, resolve_dns: bool = True):
        s = get_settings()
        self._max_bytes = s.http_max_bytes
        self._resolve = resolve_dns
        self._client = client or httpx.Client(
            timeout=s.http_timeout_s, follow_redirects=False,
            headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
        )

    def get_json(self, url: str, params: dict[str, Any] | None = None) -> Any:
        resp = self._request("GET", url, params=params)
        return resp.json()

    def post_json(self, url: str, body: dict[str, Any]) -> Any:
        resp = self._request("POST", url, json=body)
        return resp.json()

    def _request(self, method: str, url: str, **kw) -> httpx.Response:
        for _ in range(4):
            validate_url(url, resolve=self._resolve)
            with self._client.stream(method, url, **kw) as resp:
                if resp.is_redirect:
                    loc = resp.headers.get("location", "")
                    url = str(httpx.URL(url).join(loc))
                    kw.pop("params", None)
                    continue
                resp.raise_for_status()
                chunks, total = [], 0
                for chunk in resp.iter_bytes():
                    total += len(chunk)
                    if total > self._max_bytes:
                        raise UnsafeRequest("response too large")
                    chunks.append(chunk)
                return httpx.Response(resp.status_code, headers=resp.headers, content=b"".join(chunks))
        raise UnsafeRequest("too many redirects")

    def close(self) -> None:
        self._client.close()

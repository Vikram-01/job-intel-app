import socket

import httpx
import pytest
import respx

from app.netsafe import SafeClient, UnsafeRequest, safe_slug, validate_url


@pytest.mark.parametrize("url", [
    "http://boards-api.greenhouse.io/v1/boards/x/jobs",          # not https
    "https://evil.example.com/v1/boards/x/jobs",                  # not allowlisted
    "https://169.254.169.254/latest/meta-data/",                   # metadata IP
    "https://user:pw@api.lever.co/v0/postings/x",                 # credentials
    "https://api.lever.co:8443/v0/postings/x",                    # odd port
    "https://boards-api.greenhouse.io.evil.com/v1/boards/x/jobs",  # suffix trick
])
def test_rejects_unsafe_urls(url):
    with pytest.raises(UnsafeRequest):
        validate_url(url, resolve=False)


@pytest.mark.parametrize("slug", ["../admin", "a/b", "a?b=1", "a#x", "", "a b", "%2e%2e", "x" * 101, "a..b"])
def test_rejects_bad_slugs(slug):
    with pytest.raises(UnsafeRequest):
        safe_slug(slug)


def test_accepts_good_slugs():
    assert safe_slug("cloudflare") == "cloudflare"
    assert safe_slug("Acme_Corp-1.io") == "Acme_Corp-1.io"


@pytest.mark.parametrize("ip", ["127.0.0.1", "10.0.0.5", "192.168.1.1", "169.254.169.254", "::1",
                                "fd00::1", "100.64.0.1", "0.0.0.0", "::ffff:127.0.0.1"])
def test_rejects_hosts_resolving_to_private_ranges(monkeypatch, ip):
    fam = socket.AF_INET6 if ":" in ip else socket.AF_INET
    monkeypatch.setattr(socket, "getaddrinfo", lambda *a, **k: [(fam, 1, 6, "", (ip, 443))])
    with pytest.raises(UnsafeRequest):
        validate_url("https://api.lever.co/v0/postings/x")


def test_accepts_public_resolution(monkeypatch):
    monkeypatch.setattr(socket, "getaddrinfo", lambda *a, **k: [(socket.AF_INET, 1, 6, "", ("104.18.1.1", 443))])
    validate_url("https://api.lever.co/v0/postings/x")


@respx.mock
def test_redirect_to_disallowed_host_is_blocked():
    respx.get("https://api.lever.co/v0/postings/x").mock(
        return_value=httpx.Response(302, headers={"location": "https://internal.corp/secret"}))
    c = SafeClient(httpx.Client(follow_redirects=False), resolve_dns=False)
    with pytest.raises(UnsafeRequest):
        c.get_json("https://api.lever.co/v0/postings/x")


@respx.mock
def test_response_size_cap(monkeypatch):
    respx.get("https://api.lever.co/v0/postings/x").mock(return_value=httpx.Response(200, content=b"[" + b" " * 5000 + b"]"))
    c = SafeClient(httpx.Client(follow_redirects=False), resolve_dns=False)
    c._max_bytes = 1000
    with pytest.raises(UnsafeRequest):
        c.get_json("https://api.lever.co/v0/postings/x")

"""Generate a random API key and its SHA-256 digest.

Put the DIGEST in the server's JOBINTEL_API_KEY_HASHES; paste the KEY into the app
(Settings > Backend). The key is shown once and never stored by the server.
"""
import hashlib
import secrets

key = "ji_" + secrets.token_urlsafe(32)
print("API key (enter in app, keep secret):", key)
print("Digest (server env JOBINTEL_API_KEY_HASHES):", hashlib.sha256(key.encode()).hexdigest())

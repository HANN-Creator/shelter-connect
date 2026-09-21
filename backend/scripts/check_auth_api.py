"""Checks packaged authentication boundaries without a real Supabase user or private key."""
import json
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import UUID

BASE = "http://127.0.0.1:8080"
checks = [
    ("/v1/shelter-admin/dogs/02200000-0000-4000-8000-000000000001/behavior", "GET", {}),
    ("/v1/shelter-admin/dogs/02200000-0000-4000-8000-000000000001/behavior", "PUT", {}),
    ("/v1/shelter-admin/dogs/02200000-0000-4000-8000-000000000001/behavior/confirmation", "POST", {}),
    ("/v1/dogs/02200000-0000-4000-8000-000000000001/photos", "GET", {}),
    ("/v1/chat-sessions/00000000-0000-4000-8000-000000000001/messages/00000000-0000-4000-8000-000000000002/reply", "POST", {}),
    ("/v1/dogs/02200000-0000-4000-8000-000000000001/chat-sessions", "POST", {}),
    ("/v1/me/chat-sessions", "GET", {}),
    ("/v1/chat-sessions/00000000-0000-4000-8000-000000000001", "GET", {}),
    ("/v1/chat-sessions/00000000-0000-4000-8000-000000000001/messages", "GET", {}),
    ("/v1/chat-sessions/00000000-0000-4000-8000-000000000001/messages", "POST", {}),
    ("/v1/me", "GET", {}),
    ("/v1/me", "POST", {}),
    ("/v1/me/shelters", "GET", {}),
    ("/v1/shelter-admin/shelters/02100000-0000-4000-8000-000000000001/access", "GET", {}),
    ("/v1/shelter-admin/dogs/02200000-0000-4000-8000-000000000001/access", "GET", {}),
    ("/v1/me", "GET", {"Authorization": "Bearer invalid"}),
    ("/v1/me?access_token=invalid", "GET", {"Cookie": "access_token=invalid"}),
    ("/v1/dogs", "POST", {}),
    ("/actuator/env", "GET", {}),
    ("/v1/shelters", "GET", {"Authorization": "Bearer invalid"}),
    ("/v1/shelter-admin/dogs", "POST", {}),
    ("/v1/shelter-admin/dogs/02200000-0000-4000-8000-000000000001", "GET", {}),
    ("/v1/shelter-admin/dogs/02200000-0000-4000-8000-000000000001", "PATCH", {}),
    ("/v1/shelter-admin/shelters/02100000-0000-4000-8000-000000000001/dogs", "GET", {}),
    ("/v1/shelter-admin/dogs/02200000-0000-4000-8000-000000000001/observations", "GET", {}),
    ("/v1/shelter-admin/dogs/02200000-0000-4000-8000-000000000001/observations", "POST", {}),
    ("/v1/shelter-admin/dogs/02200000-0000-4000-8000-000000000001/observations/00000000-0000-4000-8000-000000000001", "PATCH", {}),
]
for path, method, headers in checks:
    try:
        response = urlopen(Request(BASE + path, method=method, headers=headers), timeout=10)
    except HTTPError as error:
        response = error
    with response:
        assert response.status == 401, (path, method, response.status)
        assert response.headers["WWW-Authenticate"] == "Bearer"
        assert response.headers["Cache-Control"] == "no-store"
        assert not response.headers.get("Set-Cookie")
        assert not response.headers.get("Location")
        body = json.load(response)
        assert body["code"] == "UNAUTHENTICATED"
        assert body["requestId"] == response.headers["X-Request-ID"]
        UUID(body["requestId"])
print(f"Packaged authentication: {len(checks)} unauthorized boundaries passed.")

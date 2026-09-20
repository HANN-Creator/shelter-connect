"""Checks the packaged server against the fictional dataset in a disposable local DB."""
import json
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import urlopen

BASE = "http://127.0.0.1:8080"
ONGI = "02100000-0000-4000-8000-000000000001"
DAON = "02100000-0000-4000-8000-000000000002"
BOMI = "02200000-0000-4000-8000-000000000001"
HAERI = "02200000-0000-4000-8000-000000000005"


def get(path, expected=200, **params):
    url = BASE + path + ("?" + urlencode(params) if params else "")
    try:
        response = urlopen(url, timeout=10)
    except HTTPError as error:
        response = error
    with response:
        assert response.status == expected, (path, response.status)
        assert response.headers["Cache-Control"] == "no-store"
        assert response.headers["X-Request-ID"]
        body = json.load(response)
        if expected != 200:
            assert body["requestId"] == response.headers["X-Request-ID"]
        return body


shelters = get("/v1/shelters", limit=1)
assert shelters["data"][0]["id"] == ONGI
assert shelters["data"][0]["dogCount"] == 3
last = get("/v1/shelters", limit=1, cursor=shelters["nextCursor"])
assert last["data"][0]["id"] == DAON and last["nextCursor"] is None
assert len(get("/v1/shelters", region="서울")["data"]) == 1
assert get("/v1/shelters", region="제주") == {"data": [], "nextCursor": None}
assert get("/v1/shelters/" + DAON)["data"]["dogCount"] == 1
dogs = get("/v1/shelters/" + ONGI + "/dogs", limit=2)
assert [dog["name"] for dog in dogs["data"]] == ["봄이", "두부"]
last = get("/v1/shelters/" + ONGI + "/dogs", cursor=dogs["nextCursor"])
assert last["data"][0]["name"] == "콩이" and last["nextCursor"] is None
profile = get("/v1/dogs/" + BOMI)["data"]
assert profile["birthDatePrecision"] == "YEAR" and profile["birthDateEstimated"] is True
assert profile["name"] == "봄이"
assert not any(key in profile for key in ("photos", "photoUrl", "storageKey", "observations"))
assert get("/v1/dogs/" + HAERI, 404)["code"] == "RESOURCE_NOT_FOUND"
assert get("/v1/shelters", 400, limit=0)["code"] == "INVALID_REQUEST"
print("Packaged read API: shelter/dog pages, profiles, filters and errors passed.")

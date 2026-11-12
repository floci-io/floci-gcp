#!/usr/bin/env python3
"""Regenerates src/main/resources/oauth/oauth-scopes.json, the Google OAuth 2.0 scope catalog.

The catalog is the union of the scopes every API version listed by the Google API Discovery
directory (https://discovery.googleapis.com/discovery/v1/apis, all versions, not only the
preferred ones) declares under auth.oauth2.scopes in its discovery document, plus the OpenID
Connect scopes that https://developers.google.com/identity/protocols/oauth2/scopes lists under
"Google Sign-In" and that no discovery document declares.

Python standard library only. Run from the repository root:

    python3 tools/regen_oauth_scopes.py
"""

import concurrent.futures
import datetime
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request

DIRECTORY = "https://discovery.googleapis.com/discovery/v1/apis"
SCOPES_PAGE = "https://developers.google.com/identity/protocols/oauth2/scopes"
# Listed under "Google Sign-In" on SCOPES_PAGE.
SIGN_IN_SCOPES = ("email", "openid", "profile")
OUTPUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/oauth/oauth-scopes.json"


ATTEMPTS = 5


def fetch_json(url):
    for attempt in range(1, ATTEMPTS + 1):
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code == 404 or attempt == ATTEMPTS:
                raise
        except (urllib.error.URLError, TimeoutError):
            if attempt == ATTEMPTS:
                raise
        time.sleep(2 * attempt)


def scopes_of(item):
    """The scopes of one API version, or the HTTP status of a listed document that cannot be served.

    The directory still lists a few retired APIs whose documents answer 404, or 502 on every attempt.
    """
    try:
        document = fetch_json(item["discoveryRestUrl"])
    except urllib.error.HTTPError as error:
        return error.code
    return set(document.get("auth", {}).get("oauth2", {}).get("scopes", {}))


def main():
    items = fetch_json(DIRECTORY)["items"]
    scopes = set(SIGN_IN_SCOPES)
    failures = []
    missing = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
        futures = {pool.submit(scopes_of, item): item["id"] for item in items}
        for future in concurrent.futures.as_completed(futures):
            try:
                result = future.result()
            except Exception as error:  # noqa: BLE001 - reported, and the catalog is not written
                failures.append(f"{futures[future]}: {error}")
                continue
            if isinstance(result, int):
                missing.append(f"{futures[future]} (HTTP {result})")
            else:
                scopes |= result
    if failures:
        sys.exit("Discovery documents that could not be read:\n" + "\n".join(sorted(failures)))
    catalog = {
        "source": f"{DIRECTORY} (auth.oauth2.scopes of the {len(items)} API versions it lists, "
                  f"except missingDocuments, whose discovery documents answer an HTTP error) "
                  f"and the Google Sign-In scopes of {SCOPES_PAGE}",
        "generated": datetime.date.today().isoformat(),
        "generator": "tools/regen_oauth_scopes.py",
        "missingDocuments": sorted(missing),
        "scopes": sorted(scopes),
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(catalog, indent=1) + "\n", encoding="utf-8")
    print(f"{len(catalog['scopes'])} scopes from {len(items)} API versions written to {OUTPUT}")


if __name__ == "__main__":
    main()

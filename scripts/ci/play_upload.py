#!/usr/bin/env python3
"""Put a released Spends AAB on Google Play's CLOSED TESTING track ("alpha"). Never production.

  play_upload.py --aab Spends-v1.74.0.aab --version 1.74.0 --version-code 90 [--dry-run]

Run by .github/workflows/android-release.yml (job "play-upload") after every v* tag's GitHub Release.
Adapted from MettleUp's scripts/ci/play_upload.py (ForgeAI repo), which has run this way since Oct 2026.

Keyless: the workflow's google-github-actions/auth step (Workload Identity Federation, provider
"aucksy-spends" in GCP project forgeai-d7eea, condition assertion.repository=='aucksy/spends') hands
this script a short-lived token for the service account in the PLAY_SERVICE_ACCOUNT repo variable.
No key file exists; never create one.

Release notes: play/whatsnew.txt. Its first line must be "version: X.Y.Z" for THIS version, or the
notes fall back to a plain line (old notes must never ship with a new build).
Running twice for the same version changes nothing (a track that already has the versionCode is skipped).
"""
import argparse
import os
import sys

PACKAGE = "com.simpleapps.spends"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
TRACK = "alpha"  # Play's id for "Closed testing - Alpha"
NOTES_LIMIT = 500
FALLBACK_NOTES = "Small fixes and improvements."


def log(msg):
    print(msg, flush=True)


def warn(msg):
    print(f"::warning::{msg}", flush=True)


def fail(msg):
    print(f"::error::{msg}", flush=True)
    sys.exit(1)


def notes_for(text, version):
    """The notes in play/whatsnew.txt when it is for `version`, else None. Whole lines, <= 500 chars. PURE."""
    lines = text.replace("\r\n", "\n").split("\n")
    if not lines or lines[0].strip() != f"version: {version}":
        return None
    out = []
    for line in (l.rstrip() for l in lines[1:]):
        if not line.strip():
            continue
        if len("\n".join(out + [line])) > NOTES_LIMIT:
            break
        out.append(line)
    return "\n".join(out) or None


def track_decision(track, code):
    """('skip' | 'add', reason). Skips when the track already has `code` or serves a newer build. PURE."""
    releases = track.get("releases") or []
    for r in releases:
        if code in [int(c) for c in r.get("versionCodes") or []]:
            return "skip", f"track '{TRACK}' already has versionCode {code} ({r.get('status')})"
    newer = [int(c) for r in releases if r.get("status") in ("completed", "inProgress", "halted")
             for c in r.get("versionCodes") or [] if int(c) > code]
    if newer:
        return "skip", f"track '{TRACK}' already serves newer versionCode {max(newer)}"
    return "add", f"track '{TRACK}': adding versionCode {code}"


def http_error_text(e):
    status = getattr(getattr(e, "resp", None), "status", "")
    content = getattr(e, "content", b"")
    if isinstance(content, bytes):
        content = content.decode("utf-8", "replace")
    return " ".join(f"HTTP {status} {content or e}".split())[:1500]


def service():
    import google.auth
    import google_auth_httplib2
    import httplib2
    from googleapiclient.discovery import build

    creds, _ = google.auth.default(scopes=[SCOPE])
    raw = httplib2.Http(timeout=900)
    # A resumable upload answers each chunk with 308; httplib2 would follow it as a redirect and crash.
    raw.redirect_codes = raw.redirect_codes - {308}
    return build("androidpublisher", "v3", http=google_auth_httplib2.AuthorizedHttp(creds, http=raw),
                 cache_discovery=False)


def commit(edits, edit_id, dry_run):
    from googleapiclient.errors import HttpError

    if dry_run:
        edits.validate(packageName=PACKAGE, editId=edit_id).execute()
        log("DRY RUN: Play VALIDATED the edit. Not committed.")
        edits.delete(packageName=PACKAGE, editId=edit_id).execute()
        return "validated"
    try:
        # Never cancel a review already running (Play's default would restart it from zero).
        edits.commit(packageName=PACKAGE, editId=edit_id, changesInReviewBehavior="ERROR_IF_IN_REVIEW").execute()
        return "committed"
    except HttpError as e:
        t = http_error_text(e).lower()
        if not ("changesnotsentforreview" in t or "in review" in t or "being reviewed" in t):
            raise
        edits.commit(packageName=PACKAGE, editId=edit_id, changesNotSentForReview=True,
                     changesInReviewBehavior="ERROR_IF_IN_REVIEW").execute()
        warn("COMMITTED but NOT sent for review: in Play Console → Publishing overview, press 'Send changes for review'.")
        return "committed-not-sent-for-review"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--aab", required=True)
    ap.add_argument("--version", required=True, help="versionName, e.g. 1.74.0")
    ap.add_argument("--version-code", required=True, type=int)
    ap.add_argument("--notes-file", default="play/whatsnew.txt")
    ap.add_argument("--language", default="en-US")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    notes = None
    if os.path.exists(a.notes_file):
        with open(a.notes_file, encoding="utf-8") as f:
            notes = notes_for(f.read(), a.version)
    if not notes:
        warn(f"{a.notes_file} is not for {a.version}; using '{FALLBACK_NOTES}'.")
        notes = FALLBACK_NOTES
    log(f"Spends {a.version} = versionCode {a.version_code} → {TRACK}; {'DRY RUN' if a.dry_run else 'LIVE'}")
    log(f"Release notes:\n{notes}")

    from googleapiclient.http import MediaFileUpload

    edits = service().edits()
    edit_id = edits.insert(packageName=PACKAGE, body={}).execute()["id"]
    have = {int(b["versionCode"]) for b in
            (edits.bundles().list(packageName=PACKAGE, editId=edit_id).execute().get("bundles") or [])}
    if a.version_code in have:
        log(f"Play already has versionCode {a.version_code}: not uploading again.")
    else:
        log(f"Uploading {os.path.basename(a.aab)} ({os.path.getsize(a.aab) / 1e6:.1f} MB)...")
        media = MediaFileUpload(a.aab, mimetype="application/octet-stream", resumable=True, chunksize=8 * 1024 * 1024)
        got = edits.bundles().upload(packageName=PACKAGE, editId=edit_id, media_body=media).execute()
        if int(got["versionCode"]) != a.version_code:
            edits.delete(packageName=PACKAGE, editId=edit_id).execute()
            fail(f"The AAB says versionCode {got['versionCode']}, the build file says {a.version_code}. Nothing changed.")
        log(f"Uploaded versionCode {got['versionCode']}.")

    tracks = {t["track"]: t for t in
              (edits.tracks().list(packageName=PACKAGE, editId=edit_id).execute().get("tracks") or [])}
    if TRACK not in tracks:
        edits.delete(packageName=PACKAGE, editId=edit_id).execute()
        fail(f"No '{TRACK}' track on Play (tracks: {sorted(tracks)}). Nothing changed.")
    action, why = track_decision(tracks[TRACK], a.version_code)
    log(why)
    if action == "skip":
        edits.delete(packageName=PACKAGE, editId=edit_id).execute()
        log("RESULT: nothing to do")
        return
    edits.tracks().update(packageName=PACKAGE, editId=edit_id, track=TRACK, body={
        "track": TRACK,
        "releases": [{
            "name": a.version,
            "versionCodes": [str(a.version_code)],
            "status": "completed",
            "releaseNotes": [{"language": a.language, "text": notes}],
        }],
    }).execute()
    log(f"RESULT: {commit(edits, edit_id, a.dry_run)}; {a.version} ({a.version_code}) on {TRACK}")


if __name__ == "__main__":
    main()

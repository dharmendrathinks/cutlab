#!/usr/bin/env python3
"""Check the publishable file set, documentation links, and pinned CI actions.

Uses only the Python standard library. This is a hygiene check, not a complete
secret scanner or security audit. Ignored local artifacts are never inspected.
"""

import hashlib
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parent.parent
paths = subprocess.check_output(
    ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
    cwd=ROOT,
).decode().split("\0")
files = {p for p in paths if p and (ROOT / p).is_file()}
errors = []

for name in sorted(files):
    path = ROOT / name
    if path.is_symlink():
        errors.append(f"{name}: symlinks need an explicit publication review")
        continue
    parts = set(path.relative_to(ROOT).parts)
    if parts & {".gradle", ".kotlin", "build", "artifacts", ".idea", "__pycache__"}:
        errors.append(f"{name}: local/generated output must not be published")
    if name.startswith("evidence/raw/") or path.name == "local.properties":
        errors.append(f"{name}: local evidence/configuration must not be published")
    if path.suffix.lower() in {".apk", ".aab", ".apks", ".keystore", ".jks", ".pem", ".p12", ".pfx"} or path.name.startswith(".env"):
        errors.append(f"{name}: binary release or sensitive local file")
    if path.stat().st_size > 5 * 1024 * 1024:
        errors.append(f"{name}: larger than 5 MiB; use a release artifact instead")
    try:
        content = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        continue
    if re.search(r"/(?:Users|home)/[A-Za-z0-9_.-]+/", content):
        errors.append(f"{name}: absolute home-directory path")
    if re.search(r"(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|AKIA[A-Z0-9]{16}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)", content):
        errors.append(f"{name}: possible credential; inspect locally")
    if path.suffix == ".json":
        try:
            json.loads(content)
        except ValueError as error:
            errors.append(f"{name}: invalid JSON: {error}")
    if path.suffix == ".md":
        for target in re.findall(r"\]\(([^\s)]+)\)", content) + re.findall(r'(?:src|href)="([^"]+)"', content):
            parsed = urlsplit(target)
            if parsed.scheme or target.startswith(("#", "//")):
                continue
            destination = (path.parent / unquote(parsed.path)).resolve()
            try:
                relative = destination.relative_to(ROOT).as_posix()
            except ValueError:
                errors.append(f"{name}: link leaves repository: {target}")
                continue
            if relative not in files and not destination.is_dir():
                errors.append(f"{name}: link does not point to a published file: {target}")
    if name.startswith(".github/workflows/"):
        for action in re.findall(r"uses:\s*(\S+)", content):
            if not action.startswith("./") and not re.fullmatch(r"[^@]+@[0-9a-f]{40}", action):
                errors.append(f"{name}: pin action to a full commit SHA: {action}")
        if "pull_request_target:" in content:
            errors.append(f"{name}: privileged PR workflows require separate review")

expected_hashes = {
    "gradle/wrapper/gradle-wrapper.jar": "2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046",
    "app/src/androidTest/assets/reference.mp4": "d3a2f9b4a074472428dc05fb1313ae93855bf757197f074e046f818894be2c84",
}
for name, expected in expected_hashes.items():
    if name not in files or hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != expected:
        errors.append(f"{name}: provenance checksum mismatch")

if errors:
    raise SystemExit("Repository checks failed:\n" + "\n".join(f"- {e}" for e in errors))
print(f"Repository checks passed ({len(files)} publishable files).")

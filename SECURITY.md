# Security policy

## Supported versions

CutLab is pre-1.0. Security fixes target the current `main` branch and the latest published version when one exists; older snapshots do not receive separate maintenance. This is not a security-audited application.

## Report a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/dharmendrathinks/cutlab/security/advisories/new). Include the affected commit/version, Android version, reproduction steps, and a minimal synthetic file when needed. Do not include private media or credentials. If private reporting is unavailable, use the maintainer's GitHub profile contact options; do not publish exploit details in an issue.

Relevant areas include archive traversal, malformed project/media input, unintended file access, export recovery, and data disclosure. The project relies on Android's media decoders, which may have device-specific behavior. Avoid running untrusted media on an unpatched OS.

The maintainer will triage reports and coordinate disclosure with the reporter. There is no guaranteed response time or bug bounty.

## Project safeguards

The app declares no Internet permission. Imports are copied to app-private storage, archives are validated before being published as projects, and successful exports are written to MediaStore. Debug builds are for development; release signing keys must remain outside the repository. See [privacy](docs/PRIVACY.md) and [release checks](docs/RELEASING.md).

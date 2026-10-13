#!/usr/bin/env python3
"""Build and verify Android release APKs without installing or publishing them."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.aistudio.macbridge.kxmpzq"


def run(arguments, environment):
    result = subprocess.run(arguments, env=environment, cwd=ROOT, capture_output=True, text=True)
    if result.returncode:
        # Build output can contain signing/account details; never echo it in errors.
        raise ValueError(f"{Path(arguments[0]).name} failed; check local configuration")
    return result.stdout


def preflight(environment, root=ROOT):
    for name in ("KEYSTORE_PATH", "STORE_PASSWORD", "KEY_PASSWORD", "MACBRIDGE_RELEASE_CERT_SHA256"):
        if not environment.get(name):
            raise ValueError(f"Set {name} in the protected build environment")
    key = Path(environment["KEYSTORE_PATH"])
    if not key.is_absolute() or key.is_symlink() or not key.is_file():
        raise ValueError("KEYSTORE_PATH must identify a regular file by absolute path")
    if key.resolve().is_relative_to(root.resolve()):
        raise ValueError("Keep the signing keystore outside the repository")
    fingerprint = environment["MACBRIDGE_RELEASE_CERT_SHA256"].replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
        raise ValueError("MACBRIDGE_RELEASE_CERT_SHA256 must be a SHA-256 certificate fingerprint")
    return fingerprint


def inspect_apk(badging, signatures, fingerprint, test_signing=False):
    match = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.MULTILINE)
    if not match or match[1] != PACKAGE:
        raise ValueError("Unexpected APK package")
    code, version = match[2], match[3]
    if not re.fullmatch(r"[1-9][0-9]*", code) or not re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,2}", version):
        raise ValueError("APK requires a positive version code and numeric version name")
    if "application-debuggable" in badging:
        raise ValueError("Refusing a debuggable APK")
    if not re.search(r"^sdkVersion:'24'$", badging, re.MULTILINE) or not re.search(r"^targetSdkVersion:'36'$", badging, re.MULTILINE):
        raise ValueError("Unexpected Android SDK levels")
    signer = r"(?:V[0-9]+(?:\.[0-9]+)? )?Signer(?: #[0-9]+)?"
    certificates = re.findall(rf"^{signer}:? certificate SHA-256 digest: ([0-9a-fA-F]+)$", signatures, re.MULTILINE)
    if (not re.search(r"^Number of signers: 1$", signatures, re.MULTILINE)
            or not certificates or {certificate.lower() for certificate in certificates} != {fingerprint}):
        raise ValueError("APK signer does not match the expected owner certificate")
    if not re.search(r"^Verified using v2 scheme .*: true$", signatures, re.MULTILINE):
        raise ValueError("APK v2 signature verification is required")
    if not test_signing and re.search(rf"^{signer}:? certificate DN: .*CN=Android Debug(?:,|$)", signatures, re.MULTILINE):
        raise ValueError("Android Debug certificate cannot produce a production release")
    return {"package": PACKAGE, "version": version, "versionCode": int(code),
            "minSdk": 24, "targetSdk": 36, "certificateSHA256": fingerprint,
            "signing": "test-only" if test_signing else "owner-certificate"}


def build(output, build_tools, environment, test_signing=False, runner=run, root=ROOT):
    fingerprint = preflight(environment, root)
    output = output.absolute()
    build_tools = build_tools.resolve()
    if output.exists() or output.is_symlink():
        raise ValueError("Output already exists; choose a fresh directory")
    if not all((build_tools / name).is_file() for name in ("apksigner", "aapt")):
        raise ValueError("Build Tools directory must contain apksigner and aapt")
    apk = root / "app/build/outputs/apk/release/app-release.apk"
    # Rerun packaging/signing instead of accepting an artifact from a prior credential set.
    runner([str(root / "gradlew"), ":app:assembleRelease", "--rerun-tasks", "--no-daemon", "--console=plain"], environment)
    if apk.is_symlink() or not apk.is_file():
        raise ValueError("Release build did not produce a regular APK")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="macbridge-android-release-", dir=output.parent) as temporary:
        stage = Path(temporary)
        staged_apk = stage / "verified.apk"
        shutil.copyfile(apk, staged_apk)
        signatures = runner([str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(staged_apk)], environment)
        badging = runner([str(build_tools / "aapt"), "dump", "badging", str(staged_apk)], environment)
        manifest = inspect_apk(badging, signatures, fingerprint, test_signing)
        suffix = "-test-signing" if test_signing else ""
        name = f'MacBridge-{manifest["version"]}-{manifest["versionCode"]}-android{suffix}.apk'
        artifacts = stage / "artifacts"
        artifacts.mkdir()
        staged_apk.rename(artifacts / name)
        digest = hashlib.sha256((artifacts / name).read_bytes()).hexdigest()
        manifest.update({"apk": name, "sha256": digest})
        (artifacts / "SHA256SUMS").write_text(f"{digest}  {name}\n")
        (artifacts / "release.json").write_text(json.dumps(manifest, indent=2) + "\n")
        artifacts.rename(output)
        return output / name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True, help="Android SDK Build Tools directory")
    parser.add_argument("--test-signing", action="store_true", help="Label disposable test-key artifacts; never distribute")
    args = parser.parse_args()
    try:
        print(build(args.output, args.build_tools, dict(os.environ), args.test_signing))
    except (ValueError, OSError) as error:
        parser.exit(1, f"Release preparation failed: {error if isinstance(error, ValueError) else type(error).__name__}\n")


if __name__ == "__main__":
    main()

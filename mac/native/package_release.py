#!/usr/bin/env python3
"""Package an isolated copy of a built app; never re-sign the installed app."""
import argparse
import hashlib
import json
import plistlib
import re
import shutil
import subprocess
import tempfile
from pathlib import Path


EXECUTABLES = ("MacBridgeIdentity", "MacBridgeEngine", "MacBridge")
FILES = {"Contents/Info.plist", "Contents/Resources/MacBridge.icns",
         *(f"Contents/MacOS/{name}" for name in EXECUTABLES),
         "Contents/_CodeSignature/CodeResources"}
DIRECTORIES = {"Contents", "Contents/MacOS", "Contents/Resources", "Contents/_CodeSignature"}


def run(arguments):
    result = subprocess.run(arguments, check=True, capture_output=True, text=True)
    return result.stdout or result.stderr


def validate_bundle(app):
    if app.is_symlink() or not app.is_dir() or app.name != "MacBridge.app":
        raise ValueError("Expected a regular MacBridge.app directory")
    for path in app.rglob("*"):
        relative = path.relative_to(app).as_posix()
        if path.is_symlink():
            raise ValueError("Bundle must not contain symlinks")
        if path.is_dir() and relative in DIRECTORIES:
            continue
        if not path.is_file() or relative not in FILES:
            raise ValueError(f"Unexpected bundle entry: {relative}")
    required = FILES - {"Contents/_CodeSignature/CodeResources"}
    if not all((app / item).is_file() for item in required):
        raise ValueError("Bundle is incomplete")
    info = plistlib.loads((app / "Contents/Info.plist").read_bytes())
    if info.get("CFBundleIdentifier") != "com.macbridge.companion" or info.get("CFBundleExecutable") != "MacBridge":
        raise ValueError("Unexpected app identity")
    version = info.get("CFBundleShortVersionString", "")
    build = info.get("CFBundleVersion", "")
    if not isinstance(version, str) or not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("Version must be numeric major.minor.patch")
    if not isinstance(build, str) or not re.fullmatch(r"[1-9]\d*", build):
        raise ValueError("Build must be a positive integer")
    if info.get("LSMinimumSystemVersion") != "15.0":
        raise ValueError("Release requires macOS 15.0")
    return version, build


def validate_options(identity, profile, development):
    if development:
        if identity or profile:
            raise ValueError("Development packaging cannot use signing or notarization credentials")
    elif not identity or not identity.startswith("Developer ID Application: "):
        raise ValueError("Provide a Developer ID Application identity, or explicitly use --development")
    if profile and (not profile.strip() or profile.startswith("-")):
        raise ValueError("Invalid notary Keychain profile")


def prepare_signature(app, identity, profile, development, runner=run):
    if development:
        runner(["codesign", "--verify", "--strict", "--deep", str(app)])
        return "local-development"
    for name in EXECUTABLES:
        target = app if name == "MacBridge" else app / "Contents/MacOS" / name
        arguments = ["codesign", "--force", "--sign", identity, "--options", "runtime", "--timestamp"]
        if name != "MacBridge":
            suffix = "identity-helper" if name == "MacBridgeIdentity" else "engine"
            arguments += ["--identifier", f"com.macbridge.companion.{suffix}"]
        runner(arguments + [str(target)])
        metadata = runner(["codesign", "--display", "--verbose=4", str(target)])
        if ("Authority=Developer ID Application:" not in metadata
                or not re.search(r"^TeamIdentifier=[A-Z0-9]{10}$", metadata, re.MULTILINE)
                or not re.search(r"\bflags=[^\n]*\bruntime\b", metadata)
                or not re.search(r"^Timestamp=.+", metadata, re.MULTILINE)):
            raise ValueError("Signed executable lacks Developer ID, hardened runtime or secure timestamp")
    runner(["codesign", "--verify", "--strict", "--deep", str(app)])
    return "developer-id-notarized" if profile else "developer-id-unnotarized"


def package(app, output, identity=None, profile=None, development=False, runner=run):
    validate_options(identity, profile, development)
    app = app.absolute()
    version, build = validate_bundle(app)
    if output.exists() or output.is_symlink():
        raise ValueError("Output directory already exists; choose a new directory")
    if output.resolve().is_relative_to(app.resolve()):
        raise ValueError("Output must be outside the source app")
    output.parent.mkdir(parents=True, exist_ok=True)
    # Only publish the output directory after every requested check succeeds.
    with tempfile.TemporaryDirectory(prefix="macbridge-release-", dir=output.parent) as temporary:
        staging = Path(temporary)
        copy = staging / "MacBridge.app"
        shutil.copytree(app, copy, symlinks=True)
        validate_bundle(copy)
        status = prepare_signature(copy, identity, profile, development, runner)
        if profile:
            submission = staging / "submission.zip"
            runner(["ditto", "-c", "-k", "--keepParent", str(copy), str(submission)])
            response = json.loads(runner(["xcrun", "notarytool", "submit", str(submission),
                                          "--keychain-profile", profile, "--wait", "--output-format", "json"]))
            if response.get("status") != "Accepted":
                raise ValueError("Apple did not accept notarization; inspect the submission log with notarytool")
            runner(["xcrun", "stapler", "staple", str(copy)])
            runner(["xcrun", "stapler", "validate", str(copy)])
            runner(["codesign", "--verify", "--strict", "--deep", str(copy)])
            runner(["spctl", "--assess", "--type", "execute", str(copy)])
        architecture = runner(["lipo", "-archs", str(copy / "Contents/MacOS/MacBridge")]).strip()
        if not architecture or any(item not in {"arm64", "x86_64"} for item in architecture.split()):
            raise ValueError("Unexpected executable architecture")
        for helper in EXECUTABLES[:-1]:
            helper_architecture = runner(["lipo", "-archs", str(copy / "Contents/MacOS" / helper)]).split()
            if set(helper_architecture) != set(architecture.split()):
                raise ValueError("App and helper architectures differ")
        suffix = "-development" if development else "-unnotarized" if not profile else ""
        name = f"MacBridge-{version}-{architecture.replace(' ', '-')}{suffix}.zip"
        artifacts = staging / "artifacts"
        artifacts.mkdir()
        archive = artifacts / name
        runner(["ditto", "-c", "-k", "--keepParent", str(copy), str(archive)])
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        (artifacts / "SHA256SUMS").write_text(f"{digest}  {name}\n")
        (artifacts / "release.json").write_text(json.dumps({
            "version": version, "build": build, "minimumMacOS": "15.0", "architectures": architecture.split(),
            "signing": status, "archive": name, "sha256": digest,
        }, indent=2) + "\n")
        artifacts.rename(output)
        return output / name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path, default=Path(__file__).parent / "dist/MacBridge.app")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--identity", help="Developer ID Application certificate name; private key stays in Keychain")
    parser.add_argument("--notary-profile", help="Previously configured notarytool Keychain profile; submits to Apple")
    parser.add_argument("--development", action="store_true", help="Explicitly package a local test build")
    args = parser.parse_args()
    try:
        print(package(args.app, args.output, args.identity, args.notary_profile, args.development))
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        # Do not print commands/output that could contain account or credential details.
        parser.exit(1, f"Packaging failed: {error if isinstance(error, ValueError) else type(error).__name__}\n")


if __name__ == "__main__":
    main()

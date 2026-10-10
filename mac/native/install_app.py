#!/usr/bin/env python3
"""Install the local development bundle without touching runtime state or preferences."""
import argparse
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import tempfile

BUNDLE_ID = "com.macbridge.companion"


def validate_bundle(bundle):
    if bundle.is_symlink() or not bundle.is_dir():
        raise ValueError("Expected a regular MacBridge.app bundle, not a symlink or file.")
    try:
        with (bundle / "Contents/Info.plist").open("rb") as source:
            info = plistlib.load(source)
    except (OSError, ValueError, plistlib.InvalidFileException) as error:
        raise ValueError("The app bundle has no valid Info.plist.") from error
    if info.get("CFBundleIdentifier") != BUNDLE_ID or info.get("CFBundleExecutable") != "MacBridge":
        raise ValueError("Refusing to replace an unrelated application.")
    if not os.access(bundle / "Contents/MacOS/MacBridge", os.X_OK):
        raise ValueError("The app executable is missing or not executable.")
    subprocess.run(["/usr/bin/codesign", "--verify", "--strict", "--deep", str(bundle)],
                   check=True, capture_output=True)


def runtime_check():
    candidates = ["/opt/homebrew/bin/python3", "/usr/local/bin/python3", "/usr/bin/python3"]
    python = next((path for path in candidates if os.access(path, os.X_OK)), None)
    if not python:
        raise ValueError("Python 3 is required by this testing build; install Python before installing MacBridge.")
    subprocess.run([python, "-c", "import ssl, sys; assert sys.version_info >= (3, 9)"],
                   check=True, capture_output=True, timeout=15)
    environment = dict(os.environ, PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin")
    subprocess.run(["/usr/bin/env", "openssl", "version"], env=environment,
                   check=True, capture_output=True, timeout=15)


def install(source, directory):
    validate_bundle(source)
    if directory.is_symlink():
        raise ValueError("The installation folder must not be a symlink.")
    target = directory / "MacBridge.app"
    if target.exists() or target.is_symlink():
        validate_bundle(target)
    directory.mkdir(parents=True, exist_ok=True)
    # Keep staging and backup on the destination volume for atomic renames.
    staging = Path(tempfile.mkdtemp(prefix=".macbridge-install-", dir=directory))
    staged = staging / "MacBridge.app"
    backup = staging / "previous.app"
    try:
        shutil.copytree(source, staged, symlinks=True)
        validate_bundle(staged)
        if target.exists():
            os.replace(target, backup)
        try:
            os.replace(staged, target)
        except OSError:
            if backup.exists():
                os.replace(backup, target)
            raise
    finally:
        # If rollback itself fails, preserve the old bundle for recovery.
        if backup.exists() and not target.exists():
            print("Previous app retained for recovery at " + str(backup))
        else:
            shutil.rmtree(staging)
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path, default=Path.home() / "Applications")
    args = parser.parse_args()
    try:
        running = subprocess.run(["/usr/bin/pgrep", "-u", str(os.getuid()), "-x", "MacBridge"], capture_output=True)
        if running.returncode == 0:
            raise ValueError("Quit MacBridge from its menu before installing or updating it.")
        if running.returncode != 1:
            raise ValueError("Could not check running apps; installation stopped safely.")
        runtime_check()
        source = Path(__file__).parent / "dist/MacBridge.app"
        target = install(source, args.destination.expanduser().absolute())
        print("Installed " + str(target))
        print("Open it from Finder or Launchpad. Open at Login is optional in Settings → General.")
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        parser.exit(1, "Installation stopped: " + str(error) + "\n")


if __name__ == "__main__":
    main()

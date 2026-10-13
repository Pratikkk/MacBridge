import json
import plistlib
import hashlib
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from package_release import FILES, package, validate_bundle, validate_options


class PackagingTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.app = self.root / "MacBridge.app"
        for name in FILES:
            path = self.app / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"fixture")
        self.info = {"CFBundleIdentifier": "com.macbridge.companion", "CFBundleExecutable": "MacBridge",
                     "CFBundleShortVersionString": "0.3.0", "CFBundleVersion": "3", "LSMinimumSystemVersion": "15.0"}
        self.write_info()
        self.calls = []
        self.status = "Accepted"

    def write_info(self):
        (self.app / "Contents/Info.plist").write_bytes(plistlib.dumps(self.info))

    def runner(self, command):
        self.calls.append(command)
        if command[0] == "ditto":
            Path(command[-1]).write_bytes(b"isolated archive fixture")
        if command[:2] == ["lipo", "-archs"]:
            return "arm64\n"
        if command[:2] == ["codesign", "--display"]:
            return "Authority=Developer ID Application: fixture\nTeamIdentifier=ABCDEFGHIJ\nCodeDirectory v=20500 size=200 flags=0x10000(runtime)\nTimestamp=fixture\n"
        if command[:3] == ["xcrun", "notarytool", "submit"]:
            return json.dumps({"status": self.status})
        return ""

    def test_development_archive_and_manifest(self):
        archive = package(self.app, self.root / "out", development=True, runner=self.runner)
        manifest = json.loads((archive.parent / "release.json").read_text())
        self.assertEqual(manifest["signing"], "local-development")
        self.assertIn("-development.zip", archive.name)
        self.assertEqual((archive.parent / "SHA256SUMS").read_text(), f'{manifest["sha256"]}  {archive.name}\n')
        self.assertFalse(any("--force" in call for call in self.calls))

    def test_production_requires_explicit_certificate(self):
        for identity in (None, "-", "Apple Development: fixture", "Developer ID Installer: fixture"):
            with self.subTest(identity=identity), self.assertRaises(ValueError):
                validate_options(identity, None, False)

    def test_development_rejects_credentials(self):
        for identity, profile in (("Developer ID Application: fixture", None), (None, "fixture")):
            with self.subTest(identity=identity), self.assertRaises(ValueError):
                validate_options(identity, profile, True)

    def test_signing_inside_out_and_no_installed_app_mutation(self):
        before = {p.relative_to(self.app): p.read_bytes() for p in self.app.rglob("*") if p.is_file()}
        archive = package(self.app, self.root / "out", identity="Developer ID Application: fixture", runner=self.runner)
        signs = [c for c in self.calls if "--force" in c]
        self.assertEqual([Path(c[-1]).name for c in signs], ["MacBridgeIdentity", "MacBridgeEngine", "MacBridge.app"])
        self.assertTrue(all("runtime" in c and "--timestamp" in c for c in signs))
        self.assertTrue(all(str(self.app) != c[-1] for c in signs))
        self.assertIn("-unnotarized.zip", archive.name)
        self.assertEqual(before, {p.relative_to(self.app): p.read_bytes() for p in self.app.rglob("*") if p.is_file()})

    def test_notary_staples_before_final_zip(self):
        archive = package(self.app, self.root / "out", identity="Developer ID Application: fixture",
                          profile="fixture-profile", runner=self.runner)
        commands = [c[:3] for c in self.calls]
        self.assertLess(commands.index(["xcrun", "stapler", "staple"]), len(commands) - 1)
        self.assertIn(["xcrun", "stapler", "validate"], commands)
        self.assertTrue(any(c[0] == "spctl" for c in self.calls))
        self.assertEqual(json.loads((archive.parent / "release.json").read_text())["signing"], "developer-id-notarized")
        self.assertNotIn("fixture-profile", (archive.parent / "release.json").read_text())

    def test_rejected_notarization_never_publishes(self):
        self.status = "Invalid"
        with self.assertRaises(ValueError):
            package(self.app, self.root / "out", identity="Developer ID Application: fixture",
                    profile="fixture", runner=self.runner)
        self.assertFalse((self.root / "out").exists())
        self.assertFalse(any(c[:3] == ["xcrun", "stapler", "staple"] for c in self.calls))

    def test_failed_signing_never_publishes(self):
        def failing(command):
            raise OSError("fixture signer unavailable")
        with self.assertRaises(OSError):
            package(self.app, self.root / "out", identity="Developer ID Application: fixture", runner=failing)
        self.assertFalse((self.root / "out").exists())

    def test_incorrect_signature_metadata_rejected(self):
        def incorrect(command):
            if command[:2] == ["codesign", "--display"]:
                return "Signature=adhoc\n"
            return self.runner(command)
        with self.assertRaises(ValueError):
            package(self.app, self.root / "out", identity="Developer ID Application: fixture", runner=incorrect)
        self.assertFalse((self.root / "out").exists())

    def test_mismatched_architecture_rejected(self):
        def mismatched(command):
            if command[0] == "lipo" and Path(command[-1]).name == "MacBridgeEngine":
                return "x86_64"
            return self.runner(command)
        with self.assertRaises(ValueError):
            package(self.app, self.root / "out", development=True, runner=mismatched)
        self.assertFalse((self.root / "out").exists())

    def test_existing_output_preserved(self):
        output = self.root / "out"
        output.mkdir()
        (output / "keep").write_text("keep")
        with self.assertRaises(ValueError):
            package(self.app, output, development=True, runner=self.runner)
        self.assertEqual((output / "keep").read_text(), "keep")

    def test_embedded_output_rejected(self):
        with self.assertRaises(ValueError):
            package(self.app, self.app / "out", development=True, runner=self.runner)

    def test_symlink_rejected(self):
        target = self.app / "Contents/Resources/MacBridge.icns"
        target.unlink()
        target.symlink_to(self.root / "secret")
        with self.assertRaises(ValueError):
            validate_bundle(self.app)

    def test_unexpected_runtime_or_secret_rejected(self):
        for name in ("identity.pem", "engine.py", "extra.app"):
            path = self.app / "Contents/Resources" / name
            path.write_text("fixture")
            with self.subTest(name=name), self.assertRaises(ValueError):
                validate_bundle(self.app)
            path.unlink()

    def test_missing_helper_rejected(self):
        (self.app / "Contents/MacOS/MacBridgeIdentity").unlink()
        with self.assertRaises(ValueError):
            validate_bundle(self.app)

    def test_invalid_metadata_rejected(self):
        for key, value in (("CFBundleShortVersionString", "../escape"), ("CFBundleVersion", "0"),
                           ("LSMinimumSystemVersion", "13.0"), ("CFBundleIdentifier", "another.app")):
            old = self.info[key]
            self.info[key] = value
            self.write_info()
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_bundle(self.app)
            self.info[key] = old


@unittest.skipUnless(sys.platform == "darwin" and (Path(__file__).parent / "dist/MacBridge.app").exists(),
                     "Build mac/native/dist/MacBridge.app on macOS for actual archive checks")
class ActualArchiveTests(unittest.TestCase):
    def test_built_bundle_round_trip(self):
        app = Path(__file__).parent / "dist/MacBridge.app"
        with tempfile.TemporaryDirectory(prefix="macbridge-package-check-") as temporary:
            root = Path(temporary)
            archive = package(app, root / "artifacts", development=True)
            manifest = json.loads((archive.parent / "release.json").read_text())
            self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), manifest["sha256"])
            subprocess.run(["ditto", "-x", "-k", str(archive), str(root / "extracted")], check=True)
            extracted = root / "extracted/MacBridge.app"
            subprocess.run(["codesign", "--verify", "--strict", "--deep", str(extracted)], check=True)
            for name in ("MacBridge", "MacBridgeEngine", "MacBridgeIdentity"):
                relative = Path("Contents/MacOS") / name
                self.assertEqual((app / relative).read_bytes(), (extracted / relative).read_bytes())
            self.assertFalse(list(extracted.rglob("*.py")))


if __name__ == "__main__":
    unittest.main()

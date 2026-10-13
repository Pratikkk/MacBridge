import hashlib
import json
import os
import secrets
import subprocess
import tempfile
import unittest
from pathlib import Path

from android_release import PACKAGE, build, inspect_apk, preflight, run

FINGERPRINT = "ab" * 32
BADGING = f"package: name='{PACKAGE}' versionCode='1' versionName='1.0'\nsdkVersion:'24'\ntargetSdkVersion:'36'\n"
SIGNATURE = f"Verified using v2 scheme (APK Signature Scheme v2): true\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: {FINGERPRINT}\nSigner #1 certificate DN: CN=Owner\n"


class AndroidReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.root = self.directory / "repo"
        self.root.mkdir()
        key = self.directory / "owner.jks"
        key.write_bytes(b"fixture")
        self.environment = {"KEYSTORE_PATH": str(key), "STORE_PASSWORD": "fixture-store-secret",
                            "KEY_PASSWORD": "fixture-key-secret", "MACBRIDGE_RELEASE_CERT_SHA256": FINGERPRINT}
        self.tools = self.directory / "sdk/build-tools/fixture"
        self.tools.mkdir(parents=True)
        for name in ("apksigner", "aapt"):
            (self.tools / name).touch()
        self.apk = self.root / "app/build/outputs/apk/release/app-release.apk"
        self.calls = []

    def runner(self, arguments, environment):
        self.calls.append(arguments)
        if Path(arguments[0]).name == "gradlew":
            self.apk.parent.mkdir(parents=True, exist_ok=True)
            self.apk.write_bytes(b"release fixture")
        if Path(arguments[0]).name == "apksigner":
            return SIGNATURE
        if Path(arguments[0]).name == "aapt":
            return BADGING
        return ""

    def test_normal_packaging_and_secrets_excluded(self):
        apk = build(self.directory / "out", self.tools, self.environment, runner=self.runner, root=self.root)
        manifest = json.loads((apk.parent / "release.json").read_text())
        self.assertEqual(manifest["sha256"], hashlib.sha256(apk.read_bytes()).hexdigest())
        self.assertEqual(manifest["signing"], "owner-certificate")
        self.assertEqual((apk.parent / "SHA256SUMS").read_text(), f'{manifest["sha256"]}  {apk.name}\n')
        self.assertEqual(set(p.name for p in apk.parent.iterdir()), {apk.name, "release.json", "SHA256SUMS"})
        for secret in ("fixture-store-secret", "fixture-key-secret", str(self.directory / "owner.jks")):
            self.assertNotIn(secret, json.dumps(self.calls))
            self.assertNotIn(secret, (apk.parent / "release.json").read_text())
        self.assertIn("--rerun-tasks", self.calls[0])

    def test_missing_credentials_rejected_before_build(self):
        for name in self.environment:
            missing = dict(self.environment)
            del missing[name]
            with self.subTest(name=name), self.assertRaises(ValueError):
                build(self.directory / "out", self.tools, missing, runner=self.runner, root=self.root)
        self.assertEqual(self.calls, [])

    def test_keystore_in_repository_rejected(self):
        key = self.root / "upload.jks"
        key.touch()
        self.environment["KEYSTORE_PATH"] = str(key)
        with self.assertRaises(ValueError):
            preflight(self.environment, self.root)

    def test_symlink_keystore_rejected(self):
        link = self.directory / "link.jks"
        link.symlink_to(self.environment["KEYSTORE_PATH"])
        self.environment["KEYSTORE_PATH"] = str(link)
        with self.assertRaises(ValueError):
            preflight(self.environment, self.root)

    def test_invalid_fingerprint_rejected(self):
        self.environment["MACBRIDGE_RELEASE_CERT_SHA256"] = "invalid"
        with self.assertRaises(ValueError):
            preflight(self.environment, self.root)

    def test_colon_separated_fingerprint_normalized(self):
        self.environment["MACBRIDGE_RELEASE_CERT_SHA256"] = ":".join(["AB"] * 32)
        self.assertEqual(preflight(self.environment, self.root), FINGERPRINT)

    def test_wrong_identity_or_signature_rejected(self):
        for signature in (SIGNATURE.replace(FINGERPRINT, "cd" * 32), SIGNATURE.replace(": true", ": false"),
                          SIGNATURE.replace("Number of signers: 1", "Number of signers: 2") + f"Signer #2 certificate SHA-256 digest: {FINGERPRINT}\n"):
            with self.subTest(signature=signature), self.assertRaises(ValueError):
                inspect_apk(BADGING, signature, FINGERPRINT)

    def test_sdk37_scheme_specific_certificate_output(self):
        modern = SIGNATURE.replace("Signer #1", "V2 Signer:")
        self.assertEqual(inspect_apk(BADGING, modern, FINGERPRINT)["certificateSHA256"], FINGERPRINT)
        # Repeated identical certificates from different schemes are valid;
        # rotated/different keys are deliberately outside this single-key workflow.
        modern += f"V3.1 Signer: certificate SHA-256 digest: {FINGERPRINT}\n"
        self.assertEqual(inspect_apk(BADGING, modern, FINGERPRINT)["certificateSHA256"], FINGERPRINT)
        with self.assertRaises(ValueError):
            inspect_apk(BADGING, modern + f"V3 Signer: certificate SHA-256 digest: {'cd' * 32}\n", FINGERPRINT)
        with self.assertRaises(ValueError):
            inspect_apk(BADGING, modern.replace("CN=Owner", "CN=Android Debug,O=Android"), FINGERPRINT)

    def test_debug_certificate_rejected_for_production(self):
        debug = SIGNATURE.replace("CN=Owner", "CN=Android Debug,O=Android,C=US")
        with self.assertRaises(ValueError):
            inspect_apk(BADGING, debug, FINGERPRINT)
        self.assertEqual(inspect_apk(BADGING, debug, FINGERPRINT, True)["signing"], "test-only")

    def test_debuggable_or_wrong_package_sdk_version_rejected(self):
        for badging in (BADGING + "application-debuggable\n", BADGING.replace(PACKAGE, "other.app"),
                        BADGING.replace("sdkVersion:'24'", "sdkVersion:'23'"),
                        BADGING.replace("targetSdkVersion:'36'", "targetSdkVersion:'35'"),
                        BADGING.replace("versionCode='1'", "versionCode='0'"),
                        BADGING.replace("versionName='1.0'", "versionName='../escape'")):
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                inspect_apk(badging, SIGNATURE, FINGERPRINT)

    def test_existing_output_untouched(self):
        output = self.directory / "out"
        output.mkdir()
        (output / "keep").write_text("keep")
        with self.assertRaises(ValueError):
            build(output, self.tools, self.environment, runner=self.runner, root=self.root)
        self.assertEqual((output / "keep").read_text(), "keep")
        self.assertFalse(self.calls)

    def test_failed_verification_publishes_nothing(self):
        def failing(arguments, environment):
            if Path(arguments[0]).name == "apksigner":
                raise ValueError("fixture verification failure")
            return self.runner(arguments, environment)
        with self.assertRaises(ValueError):
            build(self.directory / "out", self.tools, self.environment, runner=failing, root=self.root)
        self.assertFalse((self.directory / "out").exists())

    def test_fixture_artifact_explicitly_labelled(self):
        apk = build(self.directory / "out", self.tools, self.environment, True, self.runner, self.root)
        self.assertIn("-test-signing.apk", apk.name)
        self.assertEqual(json.loads((apk.parent / "release.json").read_text())["signing"], "test-only")


@unittest.skipUnless(os.environ.get("MACBRIDGE_TEST_BUILD_TOOLS"),
                     "Set MACBRIDGE_TEST_BUILD_TOOLS for disposable-key actual release build")
class ActualAndroidReleaseTests(unittest.TestCase):
    def test_disposable_key_release_build(self):
        environment = dict(os.environ)
        java_home = Path(environment["JAVA_HOME"])
        tools = Path(environment["MACBRIDGE_TEST_BUILD_TOOLS"])
        with tempfile.TemporaryDirectory(prefix="macbridge-disposable-signing-") as temporary:
            directory = Path(temporary)
            environment.update(KEYSTORE_PATH=str(directory / "fixture.jks"),
                               STORE_PASSWORD=secrets.token_hex(24))
            environment["KEY_PASSWORD"] = environment["STORE_PASSWORD"]
            subprocess.run([str(java_home / "bin/keytool"), "-genkeypair", "-keystore", environment["KEYSTORE_PATH"],
                            "-storepass:env", "STORE_PASSWORD", "-keypass:env", "KEY_PASSWORD", "-alias", "upload",
                            "-dname", "CN=MacBridge Packaging Fixture", "-keyalg", "RSA", "-keysize", "2048",
                            "-validity", "3650"], env=environment, check=True, capture_output=True)
            certificate = subprocess.run([str(java_home / "bin/keytool"), "-exportcert", "-keystore",
                                          environment["KEYSTORE_PATH"], "-storepass:env", "STORE_PASSWORD",
                                          "-alias", "upload"], env=environment, check=True, capture_output=True).stdout
            fingerprint = hashlib.sha256(certificate).hexdigest()
            environment["MACBRIDGE_RELEASE_CERT_SHA256"] = fingerprint
            apk = build(directory / "artifacts", tools, environment, test_signing=True)
            manifest_text = (apk.parent / "release.json").read_text()
            manifest = json.loads(manifest_text)
            self.assertEqual(manifest["signing"], "test-only")
            self.assertEqual(manifest["certificateSHA256"], fingerprint)
            self.assertEqual(manifest["sha256"], hashlib.sha256(apk.read_bytes()).hexdigest())
            for secret in (environment["STORE_PASSWORD"], environment["KEYSTORE_PATH"]):
                self.assertNotIn(secret, manifest_text)
            signatures = run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], environment)
            badging = run([str(tools / "aapt"), "dump", "badging", str(apk)], environment)
            with self.assertRaises(ValueError):
                inspect_apk(badging, signatures, "00" * 32, True)


if __name__ == "__main__":
    unittest.main()

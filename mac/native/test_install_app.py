import os
from pathlib import Path
import plistlib
import tempfile
import unittest
from unittest.mock import patch

from install_app import BUNDLE_ID, install, runtime_check


class InstallationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.bundle(self.root / "source.app", "new")
        self.destination = self.root / "Applications 世界 with spaces"
        self.signature = patch("install_app.subprocess.run")
        self.signature.start()
        self.addCleanup(self.signature.stop)

    def bundle(self, path, content, identifier=BUNDLE_ID):
        (path / "Contents/MacOS").mkdir(parents=True)
        with (path / "Contents/Info.plist").open("wb") as target:
            plistlib.dump({"CFBundleIdentifier": identifier, "CFBundleExecutable": "MacBridge"}, target)
        executable = path / "Contents/MacOS/MacBridge"
        executable.write_text(content)
        executable.chmod(0o755)
        return path

    def test_install_and_update_unicode_paths(self):
        target = install(self.source, self.destination)
        self.assertEqual((target / "Contents/MacOS/MacBridge").read_text(), "new")
        (self.source / "Contents/MacOS/MacBridge").write_text("updated")
        install(self.source, self.destination)
        self.assertEqual((target / "Contents/MacOS/MacBridge").read_text(), "updated")
        self.assertEqual(list(self.destination.iterdir()), [target])

    def test_unrelated_target_is_preserved(self):
        target = self.bundle(self.destination / "MacBridge.app", "unrelated", "other.app")
        with self.assertRaises(ValueError):
            install(self.source, self.destination)
        self.assertEqual((target / "Contents/MacOS/MacBridge").read_text(), "unrelated")

    def test_symlink_target_and_directory_are_rejected(self):
        self.destination.mkdir()
        (self.destination / "MacBridge.app").symlink_to(self.source, target_is_directory=True)
        with self.assertRaises(ValueError):
            install(self.source, self.destination)
        directory_link = self.root / "linked"
        directory_link.symlink_to(self.destination, target_is_directory=True)
        with self.assertRaises(ValueError):
            install(self.source, directory_link)

    def test_missing_or_unsigned_source_does_not_change_target(self):
        target = self.bundle(self.destination / "MacBridge.app", "old")
        with self.assertRaises(ValueError):
            install(self.root / "absent.app", self.destination)
        import subprocess
        with patch("install_app.subprocess.run", side_effect=subprocess.CalledProcessError(1, "codesign")):
            with self.assertRaises(subprocess.CalledProcessError):
                install(self.source, self.destination)
        self.assertEqual((target / "Contents/MacOS/MacBridge").read_text(), "old")

    def test_failed_replacement_restores_previous_app(self):
        target = self.bundle(self.destination / "MacBridge.app", "old")
        original = os.replace

        def fail_new(source, destination):
            if Path(source).name == "MacBridge.app" and ".macbridge-install-" in str(source):
                raise OSError("Simulated replacement failure")
            original(source, destination)

        with patch("install_app.os.replace", side_effect=fail_new):
            with self.assertRaises(OSError):
                install(self.source, self.destination)
        self.assertEqual((target / "Contents/MacOS/MacBridge").read_text(), "old")

    def test_older_or_unknown_macos_is_rejected(self):
        for version in ["13.7", "14.8", "", "unknown"]:
            with self.subTest(version=version), patch("install_app.platform.mac_ver", return_value=(version, (), "")):
                with self.assertRaisesRegex(ValueError, "macOS 15"):
                    runtime_check()

    def test_native_runtime_does_not_require_python_or_openssl(self):
        with patch("install_app.platform.mac_ver", return_value=("15.8.1", (), "")), patch("install_app.os.access", return_value=False), patch("install_app.subprocess.run") as commands:
            runtime_check()
            commands.assert_not_called()


if __name__ == "__main__":
    unittest.main()

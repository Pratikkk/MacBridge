import hashlib
import json
from pathlib import Path
import ssl
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from identity_store import IdentityStorageError, KeychainStore, load_keychain_identity, public_key
from macbridge import Companion, openssl


class MemoryStore:
    def __init__(self):
        self.items = {}
        self.adds = 0
        self.locked = False
        self.fail_add = False

    def read(self, account):
        if self.locked:
            raise IdentityStorageError('Keychain unavailable')
        return self.items.get(account)

    def add(self, account, key):
        self.adds += 1
        if self.fail_add:
            raise IdentityStorageError('Keychain write failed')
        self.items.setdefault(account, key)


class IdentityMigrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.key_path = self.root / 'identity.key'
        self.cert = self.root / 'identity.crt'
        openssl('req', '-new', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1',
                '-nodes', '-days', '1', '-subj', '/CN=Isolated Test', '-keyout', str(self.key_path), '-out', str(self.cert))
        self.key_path.chmod(0o600)
        self.key = self.key_path.read_bytes()
        self.public = public_key(self.key)
        self.account = hashlib.sha256(self.public).hexdigest()
        self.store = MemoryStore()

    def load(self):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        load_keychain_identity(self.root, self.cert, self.public, context, self.store)
        return context

    def test_migrate_actual_tls_load_then_restart_without_file(self):
        self.load()
        self.assertFalse(self.key_path.exists())
        self.assertEqual(self.store.items[self.account], self.key)
        self.assertEqual((self.root / 'identity-keychain.json').stat().st_mode & 0o777, 0o600)
        self.load()
        self.assertEqual(self.store.adds, 1)

    def test_companion_identity_and_peers_survive_migration_restart(self):
        original = Companion(self.root, host='127.0.0.1', port=0)
        identity = original.device_id, original.pin
        original.peers = {'test-phone': {'publicKey': 'test-public', 'name': 'Test 世界'}}
        original.save_peers()
        peers = original.peers.copy()
        original.close()
        for _ in range(2):
            restored = Companion(self.root, host='127.0.0.1', port=0, identity_store=self.store)
            try:
                self.assertEqual((restored.device_id, restored.pin), identity)
                self.assertEqual(restored.peers, peers)
            finally:
                restored.close()
        self.assertFalse(self.key_path.exists())

    def test_locked_or_failed_write_preserves_legacy_and_no_marker(self):
        self.store.locked = True
        with self.assertRaises(IdentityStorageError):
            self.load()
        self.store.locked = False
        self.store.fail_add = True
        with self.assertRaises(IdentityStorageError):
            self.load()
        self.assertEqual(self.key_path.read_bytes(), self.key)
        self.assertFalse((self.root / 'identity-keychain.json').exists())

    def test_readback_failure_preserves_legacy(self):
        self.store.add = lambda account, key: None
        with self.assertRaises(IdentityStorageError):
            self.load()
        self.assertEqual(self.key_path.read_bytes(), self.key)

    def test_wrong_stored_key_cannot_replace_identity(self):
        self.store.items[self.account] = openssl('genpkey', '-algorithm', 'EC', '-pkeyopt', 'ec_paramgen_curve:prime256v1')
        with self.assertRaises(IdentityStorageError):
            self.load()
        self.assertEqual(self.key_path.read_bytes(), self.key)

    def test_revoked_item_never_reimports_leftover_file(self):
        self.load()
        self.key_path.write_bytes(self.key)  # Simulates a restored old file after migration.
        self.store.items.clear()
        with self.assertRaises(IdentityStorageError):
            self.load()
        self.assertEqual(self.store.adds, 1)
        self.assertEqual(self.key_path.read_bytes(), self.key)

    def test_failed_tls_load_does_not_delete_file_or_commit_marker(self):
        context = unittest.mock.Mock()
        context.load_cert_chain.side_effect = ssl.SSLError('Injected load failure')
        with self.assertRaises(IdentityStorageError):
            load_keychain_identity(self.root, self.cert, self.public, context, self.store)
        self.assertTrue(self.key_path.exists())
        self.assertFalse((self.root / 'identity-keychain.json').exists())

    def test_failed_marker_commit_is_retryable(self):
        with patch('identity_store.os.replace', side_effect=OSError('Simulated disk failure')):
            with self.assertRaises(IdentityStorageError):
                self.load()
        self.assertTrue(self.key_path.exists())
        self.load()
        self.assertEqual(self.store.adds, 1)
        self.assertFalse(self.key_path.exists())

    def test_symlink_oversized_and_malformed_legacy_are_rejected(self):
        self.key_path.unlink()
        self.key_path.symlink_to(self.cert)
        with self.assertRaises(IdentityStorageError):
            self.load()
        self.key_path.unlink()
        for value in [b'x' * 8193, b'not a private key']:
            self.key_path.write_bytes(value)
            with self.assertRaises(IdentityStorageError):
                self.load()
        self.assertEqual(self.store.adds, 0)

    def test_missing_certificate_does_not_rotate_migrated_identity(self):
        self.load()
        self.cert.unlink()
        with self.assertRaises(IdentityStorageError):
            Companion(self.root, host='127.0.0.1', port=0, identity_store=self.store)
        self.assertFalse(self.cert.exists())

    def test_changed_migration_account_is_rejected(self):
        (self.root / 'identity-keychain.json').write_text(json.dumps({'version': 1, 'account': 'changed'}))
        with self.assertRaises(IdentityStorageError):
            self.load()
        self.assertEqual(self.store.adds, 0)

    def test_cli_cannot_fall_back_after_migration(self):
        self.load()
        self.key_path.write_bytes(self.key)
        with self.assertRaises(IdentityStorageError):
            Companion(self.root, host='127.0.0.1', port=0)

    def test_cleanup_failure_retains_keychain_identity_and_can_retry(self):
        original = Path.unlink
        def fail_legacy(path, *args, **kwargs):
            if path == self.key_path:
                raise PermissionError('Simulated cleanup denial')
            return original(path, *args, **kwargs)
        with patch('identity_store.Path.unlink', new=fail_legacy):
            with self.assertRaises(IdentityStorageError):
                self.load()
        self.assertTrue(self.key_path.exists())
        self.assertTrue((self.root / 'identity-keychain.json').exists())
        self.load()
        self.assertFalse(self.key_path.exists())
        self.assertEqual(self.store.adds, 1)

    def test_gui_reports_denial_without_exposing_key_or_pairing_code(self):
        helper = self.root / 'denied-helper'
        helper.write_text('#!/bin/sh\nexit 4\n')
        helper.chmod(0o700)
        import sys
        result = subprocess.run([sys.executable, str(Path(__file__).with_name('macbridge.py')),
            '--gui', '--identity-helper', str(helper), '--state-dir', str(self.root),
            '--address', '127.0.0.1', '--host', '127.0.0.1', '--port', '0'],
            capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 0)
        event = json.loads(result.stdout)
        self.assertEqual(event['event'], 'error')
        self.assertIn('Keychain access unavailable', event['message'])
        self.assertNotIn('PRIVATE KEY', result.stdout + result.stderr)
        self.assertNotIn('pairingURI', result.stdout)
        self.assertEqual(self.key_path.read_bytes(), self.key)


    def test_authorization_runs_without_starting_engine_or_exporting_data(self):
        import sys
        helper = self.root / 'authorization-helper'
        for status in (0, 3, 4, 6, 7):
            helper.write_text('#!/bin/sh\nprintf "must-not-export-fixture"\nexit ' + str(status) + '\n')
            helper.chmod(0o700)
            result = subprocess.run([sys.executable, str(Path(__file__).with_name('macbridge.py')),
                '--identity-helper', str(helper), '--authorize-identity', self.account,
                '--address', 'invalid-address', '--state-dir', str(self.root / 'unused')],
                capture_output=True, timeout=10)
            self.assertEqual(result.returncode, status)
            self.assertEqual(result.stdout, b'')
            self.assertFalse((self.root / 'unused').exists())

    def test_authorization_rejects_invalid_request_before_helper(self):
        import sys
        helper = self.root / 'should-not-run'
        marker = self.root / 'helper-ran'
        helper.write_text('#!/bin/sh\ntouch "' + str(marker) + '"\n')
        helper.chmod(0o700)
        for account in ('', 'invalid', 'A' * 64, 'f' * 65, '世' * 64):
            result = subprocess.run([sys.executable, str(Path(__file__).with_name('macbridge.py')),
                '--identity-helper', str(helper), '--authorize-identity', account],
                capture_output=True, timeout=10)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(marker.exists())

    def test_authorization_shutdown_terminates_pending_helper(self):
        import sys
        import time
        ready = self.root / 'ready'
        ended = self.root / 'ended'
        helper = self.root / 'pending-helper'
        helper.write_text('#!' + sys.executable + '\nimport signal,time\nfrom pathlib import Path\n'
            + 'def stop(*args):\n    Path(' + repr(str(ended)) + ').write_text("stopped")\n    raise SystemExit(0)\n'
            + 'signal.signal(signal.SIGTERM,stop)\nPath(' + repr(str(ready)) + ').write_text("ready")\ntime.sleep(30)\n')
        helper.chmod(0o700)
        request = subprocess.Popen([sys.executable, str(Path(__file__).with_name('macbridge.py')),
            '--identity-helper', str(helper), '--authorize-identity', self.account],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            deadline = time.monotonic() + 5
            while not ready.exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(ready.exists())
            request.terminate()
            self.assertEqual(request.wait(timeout=7), 4)
            self.assertTrue(ended.exists())
        finally:
            if request.poll() is None:
                request.kill()
                request.wait()


class HelperBoundaryTests(unittest.TestCase):
    def test_secret_uses_stdin_and_not_arguments(self):
        store = KeychainStore('/test/helper')
        with patch('identity_store.subprocess.run', return_value=subprocess.CompletedProcess([], 0, b'', b'')) as run:
            store.add('a' * 64, b'private-test-payload')
        self.assertNotIn(b'private-test-payload', run.call_args.args[0])
        self.assertEqual(run.call_args.kwargs['input'], b'private-test-payload')

    def test_missing_duplicate_denied_timeout_and_oversized_output(self):
        store = KeychainStore('/test/helper')
        with patch('identity_store.subprocess.run', return_value=subprocess.CompletedProcess([], 3, b'', b'')):
            self.assertIsNone(store.read('a' * 64))
        with patch('identity_store.subprocess.run', return_value=subprocess.CompletedProcess([], 5, b'', b'')):
            store.add('a' * 64, b'test')
        for outcome in [subprocess.CompletedProcess([], 4, b'', b'private-test-error'),
                        subprocess.CompletedProcess([], 0, b'x' * 8193, b'')]:
            with patch('identity_store.subprocess.run', return_value=outcome):
                with self.assertRaises(IdentityStorageError) as failed:
                    store.read('a' * 64)
                self.assertNotIn('private-test-error', str(failed.exception))
        with patch('identity_store.subprocess.run', side_effect=subprocess.TimeoutExpired('helper', 15)):
            with self.assertRaises(IdentityStorageError):
                store.read('a' * 64)

    def test_only_public_status_number_is_exposed_in_error(self):
        store = KeychainStore('/test/helper')
        for diagnostic, expected in [(b'KeychainStatus=-25293\n', '(macOS status -25293)'),
                                     (b'KeychainStatus=private-fixture\n', None),
                                     (b'KeychainStatus=-25293\nprivate-fixture', None)]:
            outcome = subprocess.CompletedProcess([], 4, b'', diagnostic)
            with patch('identity_store.subprocess.run', return_value=outcome):
                with self.assertRaises(IdentityStorageError) as failed:
                    store.read('a' * 64)
                message = str(failed.exception)
                self.assertNotIn('private-fixture', message)
                self.assertEqual('(macOS status' in message, expected is not None)
                if expected:
                    self.assertIn(expected, message)

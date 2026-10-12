import hashlib
from pathlib import Path
import socket
import ssl
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from certificate_store import CertificateInfo, certificate_info, certificate_public, read_certificate, renew_certificate
from identity_store import IdentityStorageError, public_key
from macbridge import Companion, openssl
from test_identity_store import MemoryStore


class CertificateRenewalTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="certificate 世界 '")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.peer = Companion(self.root, host='127.0.0.1', port=0)
        self.key = self.peer.key.read_bytes()
        self.public = self.peer.public_key
        self.original = self.peer.cert.read_bytes()
        self.addCleanup(self.peer.close)

    def test_expiry_warning_boundaries_and_clock_skew(self):
        info = CertificateInfo(100, 40 * 86400)
        self.assertEqual(info.status(99), 'notYetValid')
        self.assertEqual(info.status(100), 'valid')
        self.assertEqual(info.status(10 * 86400), 'expiring')
        self.assertEqual(info.status(info.expires), 'expired')
        self.assertEqual(info.status(info.expires + 1), 'expired')

    def test_same_key_renewal_and_restart_preserve_peers_and_identity(self):
        self.peer.peers = {'fixture': {'name': 'Test 世界', 'publicKey': 'fixture'}}
        self.peer.save_peers()
        identity = self.peer.device_id, self.peer.pin, self.peer.secret
        files = {name: (self.root / name).read_bytes() for name in ['device-id', 'peers.json', 'identity.key']}
        self.peer.handle_command({'action': 'renewCertificate'})
        self.assertNotEqual(self.peer.cert.read_bytes(), self.original)
        self.assertEqual((self.peer.device_id, self.peer.pin, self.peer.secret), identity)
        for name, before in files.items(): self.assertEqual((self.root / name).read_bytes(), before)
        self.assertEqual(certificate_public(self.peer.cert.read_bytes()), self.public)
        self.assertEqual(self.peer.certificate_info.status(), 'valid')
        self.peer.close()
        restored = Companion(self.root, host='127.0.0.1', port=0)
        try:
            self.assertEqual((restored.device_id, restored.pin), identity[:2])
            self.assertEqual(restored.peers, self.peer.peers)
        finally: restored.close()

    def test_keychain_renewal_does_not_write_a_key_file_or_change_migration(self):
        self.peer.close()
        store = MemoryStore()
        migrated = Companion(self.root, host='127.0.0.1', port=0, identity_store=store)
        try:
            record = (self.root / 'identity-keychain.json').read_bytes()
            migrated.renew_certificate()
            self.assertEqual((self.root / 'identity-keychain.json').read_bytes(), record)
            self.assertFalse((self.root / 'identity.key').exists())
            self.assertEqual(store.adds, 1)
            self.assertEqual(store.items[hashlib.sha256(self.public).hexdigest()], self.key)
            store.locked = True
            before = migrated.cert.read_bytes()
            context = migrated.context
            with self.assertRaises(IdentityStorageError): migrated.renew_certificate()
            self.assertEqual(migrated.cert.read_bytes(), before)
            self.assertIs(migrated.context, context)
            self.assertFalse(migrated.certificate_renewing)
        finally: migrated.close()

    def test_changed_private_key_or_certificate_are_rejected_before_publication(self):
        wrong = openssl('genpkey', '-algorithm', 'EC', '-pkeyopt', 'ec_paramgen_curve:prime256v1')
        with self.assertRaises(IdentityStorageError): renew_certificate(self.peer.cert, self.public, wrong)
        with self.assertRaises(IdentityStorageError): renew_certificate(self.peer.cert, public_key(wrong), self.key)
        self.assertEqual(self.peer.cert.read_bytes(), self.original)

    def test_failed_replace_tls_load_or_shutdown_preserves_original_and_cleans_candidate(self):
        for operation in ['replace', 'tls', 'shutdown']:
            context = patch('certificate_store.os.replace', side_effect=OSError()) if operation == 'replace' else \
                      patch('certificate_store.ssl.SSLContext.load_cert_chain', side_effect=ssl.SSLError()) if operation == 'tls' else \
                      patch('certificate_store.time.time', return_value=time.time())
            with context:
                with self.assertRaises(IdentityStorageError):
                    renew_certificate(self.peer.cert, self.public, self.key, permitted=lambda: operation != 'shutdown')
            self.assertEqual(self.peer.cert.read_bytes(), self.original)
            self.assertEqual(list(self.root.glob('.certificate-renew-*')), [])

    def test_mutated_certificate_during_generation_is_not_overwritten(self):
        def changed():
            self.peer.cert.write_bytes(b'external change')
            return True
        with self.assertRaises(IdentityStorageError): renew_certificate(self.peer.cert, self.public, self.key, permitted=changed)
        self.assertEqual(self.peer.cert.read_bytes(), b'external change')

    def test_malformed_oversized_and_symlink_certificate_fail_closed(self):
        for data in [b'', b'malformed', b'x' * 65537]:
            self.peer.cert.write_bytes(data)
            with self.assertRaises(IdentityStorageError): renew_certificate(self.peer.cert, self.public, self.key)
        self.peer.cert.unlink()
        target = self.root / 'other.crt'; target.write_bytes(self.original)
        self.peer.cert.symlink_to(target)
        with self.assertRaises(IdentityStorageError): read_certificate(self.peer.cert)
        self.assertEqual(target.read_bytes(), self.original)

    def test_existing_tls_session_survives_and_new_session_uses_renewed_certificate(self):
        clients = []
        accepted = []
        errors = []
        def accept():
            try:
                for _ in range(2):
                    raw, _ = self.peer.listener.accept()
                    stream = self.peer.context.wrap_socket(raw, server_side=True)
                    accepted.append(stream)
                    stream.recv(1)
            except Exception as error: errors.append(error)
        worker = threading.Thread(target=accept)
        worker.start()
        try:
            client_context = ssl._create_unverified_context() # Pin/date asserted below; isolated fixture only.
            first = client_context.wrap_socket(socket.create_connection(('127.0.0.1', self.peer.port)), server_hostname='fixture')
            clients.append(first)
            first_cert = ssl.DER_cert_to_PEM_cert(first.getpeercert(binary_form=True)).encode()
            self.peer.renew_certificate()
            first.sendall(b'x') # Existing TLS remains usable after context replacement.
            second = client_context.wrap_socket(socket.create_connection(('127.0.0.1', self.peer.port)), server_hostname='fixture')
            clients.append(second)
            renewed = ssl.DER_cert_to_PEM_cert(second.getpeercert(binary_form=True)).encode()
            self.assertNotEqual(first_cert, renewed)
            self.assertEqual(certificate_public(renewed), self.public)
            self.assertEqual(certificate_info(renewed).status(), 'valid')
            second.sendall(b'x')
            worker.join(5)
            self.assertFalse(worker.is_alive()); self.assertFalse(errors)
        finally:
            for stream in clients + accepted: stream.close()
            self.peer.listener.close(); worker.join(5)

    def test_actual_expired_and_future_certificates_can_renew_using_same_key(self):
        for starts, ends, status in [('20000101000000Z', '20010101000000Z', 'expired'),
                                     ('20990101000000Z', '21000101000000Z', 'notYetValid')]:
            # CA signing dates work on older OpenSSL too; no machine clock changes.
            (self.root / 'index').write_text(''); (self.root / 'serial').write_text('01')
            config = self.root / 'ca.conf'
            config.write_text('[ca]\ndefault_ca=local\n[local]\ndatabase=index\nserial=serial\nnew_certs_dir=.\ncertificate=seed.crt\nprivate_key=identity.key\ndefault_md=sha256\ndefault_days=365\npolicy=policy\n[policy]\ncommonName=supplied\n')
            (self.root / 'seed.crt').write_bytes(self.original)
            csr = openssl('req', '-new', '-key', str(self.root / 'identity.key'), '-subj', '/CN=Date Fixture')
            (self.root / 'fixture.csr').write_bytes(csr)
            import subprocess
            result = subprocess.run(['openssl', 'ca', '-batch', '-selfsign', '-config', 'ca.conf', '-startdate', starts, '-enddate', ends, '-notext', '-out', 'dated.crt', '-in', 'fixture.csr'], input=csr, cwd=self.root, capture_output=True, check=True)
            dated = (self.root / "dated.crt").read_bytes()
            self.peer.cert.write_bytes(dated)
            self.assertEqual(certificate_info(dated).status(), status)
            context, info = renew_certificate(self.peer.cert, self.public, self.key)
            self.assertEqual(info.status(), 'valid')
            self.assertEqual(certificate_public(self.peer.cert.read_bytes()), self.public)

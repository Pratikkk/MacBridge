import base64
import io
import json
from pathlib import Path
import tempfile
import time
import unittest
import subprocess
import sys
import socket
from unittest.mock import patch, Mock

from macbridge import Companion, MAX_FRAME, detect_address, fingerprint, openssl, read_frame, create_listener


class ChallengeStream:
    def __init__(self, server, key, peer_id='phone', secret=None, bad_signature=False, replay=None):
        self.server, self.key, self.peer_id = server, key, peer_id
        self.secret, self.bad_signature, self.replay = secret, bad_signature, replay
        self.output = bytearray()
        self.last_proof = None

    def write(self, data):
        self.output.extend(data)

    def flush(self):
        pass

    def readline(self, limit):
        challenge = json.loads(bytes(self.output).split(b'\n')[0])
        public = openssl('pkey', '-in', str(self.key), '-pubout', '-outform', 'DER')
        transcript = f"macbridge-auth-v2\n{challenge['nonce']}\n{self.server.device_id}\n{self.server.pin}\n{self.peer_id}\n{fingerprint(public)}"
        signature = openssl('dgst', '-sha256', '-sign', str(self.key), input=transcript.encode())
        proof = dict(type='AUTH_PROOF', deviceId=self.peer_id, deviceName='Test Phone',
            publicKey=base64.b64encode(public).decode(), signature=base64.b64encode(signature).decode())
        if self.secret is not None:
            proof['secret'] = self.secret
        if self.bad_signature:
            proof['signature'] = base64.b64encode(b'bad').decode()
        if self.replay is not None:
            proof = self.replay
        self.last_proof = proof
        return json.dumps(proof).encode() + b'\n'


class AddressTests(unittest.TestCase):
    def test_auto_address_uses_the_active_route_without_sending_packets(self):
        with patch('macbridge.socket.socket') as factory:
            probe = factory.return_value.__enter__.return_value
            probe.getsockname.return_value = ('192.168.0.100', 12345)
            self.assertEqual('192.168.0.100', detect_address())
            probe.send.assert_not_called()
            probe.sendto.assert_not_called()

    def test_loopback_route_requires_an_explicit_address(self):
        with patch('macbridge.socket.socket') as factory:
            factory.return_value.__enter__.return_value.getsockname.return_value = ('127.0.0.1', 12345)
            with self.assertRaises(ValueError):
                detect_address()


class AuthenticationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.server = Companion(Path(self.temp.name) / 'mac', host='127.0.0.1', port=0)
        self.phone_key = Path(self.temp.name) / 'phone.key'
        openssl('ecparam', '-genkey', '-name', 'prime256v1', '-out', str(self.phone_key))

    def tearDown(self):
        self.server.close()
        self.temp.cleanup()

    def pair(self):
        stream = ChallengeStream(self.server, self.phone_key, secret=self.server.secret)
        self.assertEqual('phone', self.server.authenticate(stream))
        return stream

    def test_reply_command_requires_original_live_session_and_never_replays(self):
        token = '11111111-1111-1111-1111-111111111111'
        self.server.notifications = True
        self.server.peers['phone'] = {'publicKey': 'pinned-phone-key', 'name': 'Phone'}
        self.server.active_id = 'phone'; self.server.active_stream = io.BytesIO(); self.server.connection_id = 'original'
        self.server.notification_mirror.process(dict(type='NOTIFICATION', notificationId='key', packageName='com.chat', appName='Chat', title='Hello', text='World', replyToken=token), True)
        command = dict(action='replyNotification', notificationId='key', actionToken=token, connectionId='original', replyText='世界 🌉')
        with self.assertRaises(ValueError): self.server.handle_command(dict(command, connectionId='new'))
        with self.assertRaises(ValueError): self.server.handle_command(dict(command, replyText='🌉'*1025))
        self.server.notifications = False
        with self.assertRaises(ValueError): self.server.handle_command(command)
        self.server.notifications = True; self.server.handle_command(command)
        self.assertEqual('世界 🌉', json.loads(self.server.active_stream.getvalue())['replyText'])
        with self.assertRaises(ValueError): self.server.handle_command(command)
        self.server.notification_mirror.reset(); self.server.active_stream = None
        with self.assertRaises(ValueError): self.server.handle_command(command)

    def test_notification_dismiss_commands_require_enabled_original_live_session(self):
        token = '11111111-1111-1111-1111-111111111111'
        events = []
        self.server.event_sink = events.append
        self.server.notifications = True
        self.server.peers['phone'] = {'publicKey': 'pinned-phone-key', 'name': 'Phone'}
        self.server.active_id = 'phone'
        self.server.active_stream = io.BytesIO()
        self.server.connection_id = 'original'
        message = dict(type='NOTIFICATION', notificationId='key', packageName='com.chat', appName='Chat', title='Hello', text='World', dismissToken=token)
        self.server.notification_mirror.process(message, True)
        command = dict(action='dismissNotification', notificationId='key', actionToken=token, connectionId='original')
        with self.assertRaises(ValueError): self.server.handle_command(dict(command, connectionId='new'))
        self.server.notifications = False
        with self.assertRaises(ValueError): self.server.handle_command(command)
        self.server.notifications = True
        self.server.handle_command(command)
        frame = json.loads(self.server.active_stream.getvalue())
        self.assertEqual('DISMISS', frame['actionType'])
        self.assertEqual(token, frame['actionToken'])
        with self.assertRaises(ValueError): self.server.handle_command(command)
        self.server.notification_mirror.reset()
        with self.assertRaises(ValueError): self.server.handle_command(command)
        self.server.active_stream = None
        with self.assertRaises(ValueError): self.server.handle_command(command)

    def test_valid_pair_persists_pin_and_reconnects_without_secret(self):
        self.pair()
        self.assertIn('phone', json.loads(self.server.peers_file.read_text()))
        self.assertEqual('phone', self.server.authenticate(ChallengeStream(self.server, self.phone_key)))
        self.assertEqual(0o600, self.server.key.stat().st_mode & 0o777)
        self.assertEqual(0o600, self.server.peers_file.stat().st_mode & 0o777)

    def test_unknown_peer_without_code_is_refused(self):
        with self.assertRaises(ValueError):
            self.server.authenticate(ChallengeStream(self.server, self.phone_key))
        self.assertFalse(self.server.peers)

    def test_wrong_code_does_not_consume_valid_code(self):
        original = self.server.secret
        with self.assertRaises(ValueError):
            self.server.authenticate(ChallengeStream(self.server, self.phone_key, secret='0' * 64))
        self.assertEqual(original, self.server.secret)
        self.pair()

    def test_expired_and_consumed_codes_are_refused(self):
        original = self.server.secret
        self.server.secret_expires = time.monotonic() - 1
        with self.assertRaises(ValueError):
            self.server.authenticate(ChallengeStream(self.server, self.phone_key, secret=original))
        self.server.rotate_code()
        consumed = self.server.secret
        self.pair()
        with self.assertRaises(ValueError):
            self.server.authenticate(ChallengeStream(self.server, self.phone_key, secret=consumed))

    def test_bad_signature_is_refused_without_persisting(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.server.authenticate(ChallengeStream(self.server, self.phone_key, secret=self.server.secret, bad_signature=True))
        self.assertFalse(self.server.peers)

    def test_signature_replay_against_new_nonce_is_refused(self):
        first = self.pair()
        with self.assertRaises(subprocess.CalledProcessError):
            self.server.authenticate(ChallengeStream(self.server, self.phone_key, replay=first.last_proof))

    def test_known_phone_key_change_is_refused_even_with_new_code(self):
        self.pair()
        self.server.rotate_code()
        other_key = Path(self.temp.name) / 'other.key'
        openssl('ecparam', '-genkey', '-name', 'prime256v1', '-out', str(other_key))
        with self.assertRaises(ValueError):
            self.server.authenticate(ChallengeStream(self.server, other_key, secret=self.server.secret))

    def test_forgetting_phone_removes_reconnect_authorization(self):
        self.pair()
        self.server.forget('phone')
        with self.assertRaises(ValueError):
            self.server.authenticate(ChallengeStream(self.server, self.phone_key))
        self.assertEqual({}, json.loads(self.server.peers_file.read_text()))

    def test_legacy_phone_name_upgrade_preserves_the_pinned_key(self):
        self.pair()
        key = self.server.peers['phone']['publicKey']
        del self.server.peers['phone']['name']
        self.server.save_peers()
        self.server.authenticate(ChallengeStream(self.server, self.phone_key))
        self.assertEqual(key, self.server.peers['phone']['publicKey'])
        self.assertEqual('Test Phone', self.server.peers['phone']['name'])

    def test_paused_or_disconnected_clipboard_does_not_read_pasteboard(self):
        with patch('macbridge.subprocess.run') as process:
            with self.assertRaises(ValueError):
                self.server.push_clipboard()
            self.server.clipboard = True
            with self.assertRaises(ValueError):
                self.server.push_clipboard()
            process.assert_not_called()

    def test_invalid_code_address_preserves_previous_pairing_code(self):
        self.server.address = '127.0.0.1'
        original = self.server.pairing_uri(self.server.address)
        with self.assertRaises(ValueError):
            self.server.handle_command(dict(action='reissueCode', address='not-an-ip'))
        self.assertEqual(original, self.server.pairing_uri(self.server.address))

    def test_expired_code_is_hidden_and_status_does_not_expose_phone_keys(self):
        self.pair()
        self.server.address = '127.0.0.1'
        self.server.rotate_code()
        self.server.secret_expires = time.monotonic() - 1
        snapshots = []
        self.server.event_sink = snapshots.append
        self.server.emit_state()
        self.assertEqual('', snapshots[-1]['pairingURI'])
        self.assertNotIn('publicKey', snapshots[-1]['peers'][0])
        discovery = snapshots[-1]['discovery']
        self.assertEqual({'id', 'name', 'fingerprint', 'port', 'ipv6'}, set(discovery))
        self.assertEqual(self.server.pin, discovery['fingerprint'])
        self.assertNotIn(self.server.secret, json.dumps(discovery))

    def test_explicit_ipv6_pairing_address_preserves_identity(self):
        from urllib.parse import parse_qs, urlparse
        original_id, original_pin = self.server.device_id, self.server.pin
        self.server.handle_command(dict(action='reissueCode', address='fd00::20'))
        fields = parse_qs(urlparse(self.server.pairing_uri(self.server.address)).query)
        self.assertEqual(['fd00::20'], fields['ip'])
        self.assertEqual(original_id, self.server.device_id)
        self.assertEqual(original_pin, self.server.pin)

    def test_forget_already_closed_phone_still_revokes_it(self):
        self.pair()
        self.server.active_id = 'phone'
        self.server.active_socket = Mock()
        self.server.active_socket.shutdown.side_effect = OSError('Already closed')
        self.server.forget('phone')
        self.assertNotIn('phone', self.server.peers)
        self.assertIsNone(self.server.active_id)

    def test_oversized_and_malformed_frames_are_refused(self):
        for data in (b'x' * (MAX_FRAME + 1) + b'\n', b'\xc3\n', b'[]\n', b'{'):
            with self.assertRaises(ValueError):
                read_frame(io.BytesIO(data))


class DesktopControlTests(unittest.TestCase):
    def test_gui_status_toggle_code_rotation_and_parent_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(__file__).with_name('macbridge.py')
            process = subprocess.Popen([sys.executable, str(script), '--gui', '--state-dir', directory,
                '--host', '127.0.0.1', '--address', '127.0.0.1', '--port', '0'],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                initial = json.loads(process.stdout.readline())
                port = int(initial['endpoint'].rsplit(':', 1)[1])
                self.assertEqual('state', initial['event'])
                self.assertTrue(initial['running'])
                self.assertFalse(initial['connected'])
                self.assertFalse(initial['clipboardEnabled'])
                self.assertIn('ip=127.0.0.1', initial['pairingURI'])
                process.stdin.write(json.dumps(dict(action='setClipboardEnabled', enabled=True)) + '\n')
                process.stdin.flush()
                toggled = json.loads(process.stdout.readline())
                self.assertTrue(toggled['clipboardEnabled'])
                process.stdin.write(json.dumps(dict(action='reissueCode', address='127.0.0.1')) + '\n')
                process.stdin.flush()
                reissued = json.loads(process.stdout.readline())
                self.assertNotEqual(initial['pairingURI'], reissued['pairingURI'])
                process.stdin.write(json.dumps(dict(action='pushClipboard')) + '\n')
                process.stdin.flush()
                failure = json.loads(process.stdout.readline())
                self.assertEqual('error', failure['event'])
                self.assertIn('No authenticated phone', failure['message'])
                process.stdin.write('{invalid JSON}\n')
                process.stdin.flush()
                self.assertEqual('error', json.loads(process.stdout.readline())['event'])
                process.stdin.write(json.dumps(dict(action='status')) + '\n')
                process.stdin.flush()
                self.assertTrue(json.loads(process.stdout.readline())['running'])
                process.stdin.close()
                self.assertEqual(0, process.wait(timeout=5))
                with socket.socket() as probe:
                    probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                    probe.bind(('127.0.0.1', port))  # Parent exit releases the listener.
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=5)
                process.stdout.close()
                process.stderr.close()


class DiscoveryListenerTests(unittest.TestCase):
    def test_ipv4_listener_is_reachable_and_reusable(self):
        with create_listener('127.0.0.1', 0) as listener:
            port = listener.getsockname()[1]
            with socket.create_connection(('127.0.0.1', port), timeout=2):
                stream, _ = listener.accept()
                stream.close()
        with create_listener('127.0.0.1', port):
            pass

    @unittest.skipUnless(socket.has_dualstack_ipv6(), 'OS has no dual-stack IPv6')
    def test_bonjour_listener_accepts_ipv4_and_ipv6(self):
        with create_listener('0.0.0.0', 0) as listener:
            self.assertEqual(socket.AF_INET6, listener.family)
            port = listener.getsockname()[1]
            listener.settimeout(2)
            for address in ['127.0.0.1', '::1']:
                with socket.create_connection((address, port), timeout=2):
                    stream, _ = listener.accept()
                    stream.close()


if __name__ == '__main__':
    unittest.main()

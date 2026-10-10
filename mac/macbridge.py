#!/usr/bin/env python3
"""Development Mac peer: pinned TLS and signed Android identity, no pip dependencies."""
import argparse
import base64
import hashlib
import hmac
import ipaddress
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import tempfile
import threading
import time
from urllib.parse import urlencode
import uuid

from file_receiver import FileReceiver, RESUME_TTL_SECONDS
from file_sender import FileSender
from transfer_state import TransferState

MAX_FRAME = 1024 * 1024


def detect_address():
    # UDP connect selects a local route; no packet is sent.
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        probe.connect(('192.0.2.1', 9))
        address = probe.getsockname()[0]
    parsed = ipaddress.IPv4Address(address)
    if parsed.is_loopback or parsed.is_unspecified or parsed.is_link_local:
        raise ValueError('No usable LAN address. Connect the Mac to your network or provide --address.')
    return address


from notification_mirror import NotificationMirror


def create_listener(host, port):
    # Bonjour can resolve IPv6; listen on both families when binding all interfaces.
    dual = host == '0.0.0.0' and socket.has_dualstack_ipv6()
    family = socket.AF_INET6 if dual or ':' in host else socket.AF_INET
    return socket.create_server(('::' if dual else host, port), family=family,
                                backlog=4, dualstack_ipv6=dual)


def fingerprint(key):
    return ':'.join(f'{byte:02X}' for byte in hashlib.sha256(key).digest())


def read_frame(stream):
    line = stream.readline(MAX_FRAME + 2)
    if not line or not line.endswith(b'\n') or len(line) > MAX_FRAME + 1:
        raise ValueError('Missing or oversized frame')
    value = json.loads(line.decode('utf-8'))
    if not isinstance(value, dict):
        raise ValueError('Expected a JSON object')
    return value


def write_frame(stream, message):
    payload = json.dumps(message, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    if len(payload) > MAX_FRAME:
        raise ValueError('Oversized frame')
    stream.write(payload + b'\n')
    stream.flush()


def openssl(*args, input=None):
    return subprocess.run(['openssl', *args], input=input, capture_output=True, check=True, timeout=10).stdout


def verify_signature(public_key, signature, transcript):
    with tempfile.TemporaryDirectory(prefix='macbridge-verify-') as temp:
        key = Path(temp) / 'public.der'
        sig = Path(temp) / 'signature.der'
        key.write_bytes(public_key)
        sig.write_bytes(signature)
        # Accept only the identity key type used by Android Keystore.
        description = openssl('pkey', '-pubin', '-inform', 'DER', '-in', str(key), '-text', '-noout')
        if b'ASN1 OID: prime256v1' not in description:
            raise ValueError('Expected an EC P-256 identity')
        pem = openssl('pkey', '-pubin', '-inform', 'DER', '-in', str(key), '-outform', 'PEM')
        key.write_bytes(pem)
        openssl('dgst', '-sha256', '-verify', str(key), '-signature', str(sig), input=transcript.encode('utf-8'))


class Companion:
    def __init__(self, directory, host='0.0.0.0', port=8990, echo=False, clipboard=False, event_sink=None, files=False):
        self.event_sink = event_sink
        self.notifications = False
        self.notification_mirror = NotificationMirror()
        self.address = None
        self.last_action = "Ready to connect"
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.directory.chmod(0o700)
        self.cert = self.directory / 'identity.crt'
        self.key = self.directory / 'identity.key'
        if not self.cert.exists() and not self.key.exists():
            openssl('req', '-new', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1',
                    '-nodes', '-days', '365', '-subj', '/CN=MacBridge Development Peer',
                    '-keyout', str(self.key), '-out', str(self.cert))
        if not self.cert.exists() or not self.key.exists():
            raise ValueError('Incomplete Mac identity; restore both identity files')
        self.key.chmod(0o600)
        self.cert.chmod(0o600)
        pem = openssl('x509', '-in', str(self.cert), '-pubkey', '-noout')
        self.public_key = openssl('pkey', '-pubin', '-outform', 'DER', input=pem)
        self.pin = fingerprint(self.public_key)
        self.identity_file = self.directory / 'device-id'
        if not self.identity_file.exists():
            self.identity_file.write_text(str(uuid.uuid4()))
            self.identity_file.chmod(0o600)
        self.device_id = self.identity_file.read_text().strip()
        self.peers_file = self.directory / 'peers.json'
        self.peers = json.loads(self.peers_file.read_text()) if self.peers_file.exists() else {}
        self.name = socket.gethostname().split('.')[0]
        self.echo = echo
        self.clipboard = clipboard
        self.files = files
        self.receivers = set()
        self.sender = None
        self.sender_peer = None
        self.peer_receivers = {}
        self.last_receiver = None
        self.next_receive_report = 0
        self.receive_report_key = None
        self.transfer_state = self.directory / 'TransferState'
        sender_path = self.transfer_state / 'sender.json'
        try:
            if not sender_path.is_symlink() and sender_path.stat().st_size <= 128 * 1024:
                identity = json.loads(sender_path.read_text()).get('identity')
                if isinstance(identity, list) and len(identity) == 4 and identity[:2] == [self.device_id, self.pin] and self.peers.get(identity[2], {}).get('publicKey') == identity[3]:
                    self.sender_peer = tuple(identity[2:])
                    self.sender = FileSender(self.directory / 'OutgoingFiles', lambda _: (_ for _ in ()).throw(OSError('Reconnect first')), self.report,
                        state=TransferState(sender_path, identity))
        except (OSError, ValueError, TypeError, AttributeError):
            pass
        for peer_id, peer in self.peers.items():
            identity = (peer_id, peer.get('publicKey'))
            state = self.receiver_state(identity)
            if self.files and state.path.exists() and len(self.peer_receivers) < 4:
                self.peer_receivers[identity] = FileReceiver(self.directory / 'ReceivedFiles', state=state)
        allowed_states = {r.state.path for r in self.peer_receivers.values()}
        if self.transfer_state.exists() and not self.transfer_state.is_symlink():
            for stale in self.transfer_state.glob('receiver-*.json'):
                if stale not in allowed_states:
                    stale.unlink(missing_ok=True)
        retained = {r.active['temporary'] for r in self.peer_receivers.values() if r.active}
        if self.sender and self.sender.snapshot:
            retained.add(self.sender.snapshot)
        outgoing = self.directory / 'OutgoingFiles'
        if outgoing.exists() and not outgoing.is_symlink():
            for partial in outgoing.glob('.outgoing-*'):
                if partial not in retained and (partial.is_file() or partial.is_symlink()):
                    partial.unlink()
        received = self.directory / 'ReceivedFiles'
        if received.exists() and not received.is_symlink():
            for partial in received.glob('.incoming-*'):
                if partial not in retained and (partial.is_file() or partial.is_symlink()):
                    partial.unlink()
        self.lock = threading.RLock()
        self.stop_event = threading.Event()
        self.active_socket = None
        self.active_stream = None
        self.active_id = None
        self.connection_id = ""
        self.rotate_code()
        self.context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.context.minimum_version = ssl.TLSVersion.TLSv1_2
        self.context.load_cert_chain(self.cert, self.key)
        self.listener = create_listener(host, port)
        self.listener.settimeout(1)
        self.port = self.listener.getsockname()[1]
        self.slots = threading.BoundedSemaphore(4)
        self.connections = set()

    def receiver_state(self, identity):
        binding = [self.device_id, self.pin, *identity]
        name = hashlib.sha256(json.dumps(binding).encode()).hexdigest()
        return TransferState(self.transfer_state / ('receiver-' + name + '.json'), binding)

    def rotate_code(self):
        with self.lock:
            self.secret = secrets.token_hex(32)
            self.secret_expires = time.monotonic() + 300

    def pairing_uri(self, address):
        with self.lock:
            return 'macbridge://pair?' + urlencode(dict(v=2, id=self.device_id, name=self.name,
                fingerprint=self.pin, ip=address, port=self.port, secret=self.secret))

    def report(self, message):
        self.last_action = message
        if self.event_sink:
            self.emit_state()
        else:
            print(message, flush=True)

    def receiving_selection(self):
        current = self.peer_receivers.get((self.active_id, self.peers.get(self.active_id, {}).get('publicKey')))
        return current if current and current.result != 'idle' else next((r for r in self.peer_receivers.values() if r.active), self.last_receiver)

    def receive_token(self, receiver):
        if not receiver or not receiver.active:
            return ''
        identity = next((key for key, value in self.peer_receivers.items() if value is receiver), None)
        if identity is None:
            return ''
        return hashlib.sha256(json.dumps([*identity, receiver.active['id'], receiver.active['temporary'].name]).encode()).hexdigest()

    def emit_state(self):
        if not self.event_sink:
            return
        with self.lock:
            remaining = max(0, self.secret_expires - time.monotonic())
            receiver = self.receiving_selection()
            value = dict(event='state', running=not self.stop_event.is_set(),
                connected=self.active_id is not None,
                phoneName=self.peers.get(self.active_id, {}).get('name', 'Android Phone'),
                peers=[dict(id=key, name=peer.get('name', 'Android Phone')) for key, peer in sorted(self.peers.items())],
                clipboardEnabled=self.clipboard, filesEnabled=self.files, notificationsEnabled=self.notifications,
                fileSendStatus=self.sender.result if self.sender else 'idle',
                fileCanResume=bool(self.sender and self.sender.result == 'paused' and self.active_stream is not None and self.sender_peer == (self.active_id, self.peers.get(self.active_id, {}).get('publicKey'))),
                connectionId=self.connection_id, fileSending=self.sender.busy if self.sender else False,
                fileReceiveStatus=receiver.result if receiver else 'idle', fileReceiveToken=self.receive_token(receiver),
                receivedBytes=receiver.received_bytes if receiver else 0, receivedFileSize=receiver.file_size if receiver else 0,
                sentBytes=self.sender.sent_bytes if self.sender else 0, fileSize=self.sender.file_size if self.sender else 0, endpoint=f'{self.address}:{self.port}',
                pairingURI=self.pairing_uri(self.address) if self.address and self.secret and remaining > 0 else '',
                expiresAt=time.time() + remaining, lastAction=self.last_action,
                discovery=dict(id=self.device_id, name=self.name, fingerprint=self.pin, port=self.port,
                    ipv6=self.listener.family == socket.AF_INET6) if self.address and not self.stop_event.is_set() else None)
        self.event_sink(value)

    def disconnect_phone(self):
        with self.lock:
            current = self.active_socket
            self.active_socket = self.active_stream = self.active_id = None
            self.connection_id = ""
            if self.sender:
                self.sender.interrupt()
            if current:
                try:
                    current.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
        self.report('Phone disconnected')

    def handle_command(self, command):
        action = command.get('action')
        if action == 'pushClipboard':
            self.push_clipboard()
        elif action == 'sendFile':
            path = command.get('path')
            if not isinstance(path, str) or not path or len(path) > 4096:
                raise ValueError('Choose a file to send')
            self.start_file(path, command.get('connectionId'))
        elif action in ('dismissNotification', 'replyNotification'):
            with self.lock:
                if not self.notifications or not self.active_id or self.active_id not in self.peers or not self.active_stream or command.get('connectionId') != self.connection_id:
                    raise ValueError('Reconnect the original phone and enable notifications before dismissing')
                key = command.get('notificationId')
                if not isinstance(key, str) or not key.strip() or len(key.encode('utf-8')) > 512:
                    raise ValueError('Invalid notification action')
                frame = (self.notification_mirror.request_reply(key, command.get('actionToken'), command.get('replyText'))
                    if action == 'replyNotification' else self.notification_mirror.request_dismiss(key, command.get('actionToken')))
                try:
                    write_frame(self.active_stream, frame)
                except OSError:
                    self.notification_mirror.pending.pop(key, None)
                    self.notification_mirror.reply_pending.pop(key, None)
                    raise
        elif action == 'resumeFileSend':
            self.resume_file(command.get('connectionId'))
        elif action == 'cancelFileSend':
            with self.lock:
                if self.sender:
                    identity = (self.active_id, self.peers.get(self.active_id, {}).get('publicKey'))
                    if self.active_stream is not None and identity == self.sender_peer:
                        try:
                            write_frame(self.active_stream, dict(type='FILE_CANCEL', transferId=self.sender.transfer_id))
                        except OSError:
                            pass
                    self.sender.cancel()
        elif action == 'cancelFileReceive':
            with self.lock:
                receiver = self.receiving_selection()
                token = self.receive_token(receiver)
                if not token or command.get('transferToken') != token:
                    return
                identity = next(key for key, value in self.peer_receivers.items() if value is receiver)
                ack = receiver.cancel(receiver.active['id'])
                current = (self.active_id, self.peers.get(self.active_id, {}).get('publicKey'))
                if ack and self.active_stream is not None and identity == current:
                    try:
                        write_frame(self.active_stream, ack)
                    except OSError:
                        pass
                self.receive_progress(receiver)
        elif action == 'reissueCode':
            address = command.get('address') or detect_address()
            ipaddress.ip_address(address)
            self.address = address
            self.rotate_code()
            self.report('New pairing code ready')
        elif action == 'setClipboardEnabled':
            enabled = command.get('enabled')
            if not isinstance(enabled, bool):
                raise ValueError('Clipboard setting must be true or false')
            with self.lock:
                self.clipboard = enabled
            self.report('Clipboard sharing enabled' if enabled else 'Clipboard sharing paused')
        elif action == 'setNotificationsEnabled':
            enabled = command.get('enabled')
            if not isinstance(enabled, bool):
                raise ValueError('Notification setting must be true or false')
            with self.lock:
                self.notifications = enabled
                if not enabled:
                    self.notification_mirror.reset()
                    if self.event_sink:
                        self.event_sink(dict(event='notification', connectionId=self.connection_id, operation='clear'))
            self.report('Notification receiving enabled' if enabled else 'Notification receiving paused')
        elif action == 'setFilesEnabled':
            enabled = command.get('enabled')
            if not isinstance(enabled, bool):
                raise ValueError('File setting must be true or false')
            with self.lock:
                self.files = enabled
                if not enabled:
                    for receiver in set(self.peer_receivers.values()) | self.receivers:
                        receiver.discard()
            self.report('File receiving enabled' if enabled else 'File receiving paused')
        elif action == 'disconnect':
            self.disconnect_phone()
        elif action == 'forget':
            peer_id = command.get('id')
            if not isinstance(peer_id, str):
                raise ValueError('Missing phone identity')
            self.forget(peer_id)
            self.report('Phone forgotten')
        elif action == 'status':
            self.emit_state()
        elif action == 'quit':
            return False
        else:
            raise ValueError('Unknown companion command')
        return True

    def save_peers(self):
        temporary = self.peers_file.with_suffix('.tmp')
        with temporary.open('w') as stream:
            os.chmod(temporary, 0o600)
            json.dump(self.peers, stream)
        temporary.replace(self.peers_file)

    def authenticate(self, stream):
        nonce = secrets.token_hex(32)
        write_frame(stream, dict(type='AUTH_CHALLENGE', protocolVersion=2, nonce=nonce,
            deviceId=self.device_id, deviceName=self.name, fingerprint=self.pin,
            publicKey=base64.b64encode(self.public_key).decode()))
        proof = read_frame(stream)
        if proof.get('type') != 'AUTH_PROOF':
            raise ValueError('Authentication required')
        peer_id = proof.get('deviceId')
        if not isinstance(peer_id, str) or not peer_id or len(peer_id) > 128:
            raise ValueError('Invalid identity')
        encoded_key = proof.get('publicKey', '')
        encoded_signature = proof.get('signature', '')
        if not isinstance(encoded_key, str) or len(encoded_key) > 4096 or not isinstance(encoded_signature, str) or len(encoded_signature) > 256:
            raise ValueError('Invalid identity proof')
        public_key = base64.b64decode(encoded_key, validate=True)
        signature = base64.b64decode(encoded_signature, validate=True)
        transcript = f'macbridge-auth-v2\n{nonce}\n{self.device_id}\n{self.pin}\n{peer_id}\n{fingerprint(public_key)}'
        verify_signature(public_key, signature, transcript)
        with self.lock:
            supplied = proof.get('secret')
            known = self.peers.get(peer_id)
            if supplied is not None:
                if not isinstance(supplied, str) or len(supplied) != 64 or time.monotonic() >= self.secret_expires or not hmac.compare_digest(supplied, self.secret):
                    raise ValueError('Pairing code expired or already used')
            elif known is None:
                raise ValueError('Unknown phone; pairing code required')
            if known is not None and known['publicKey'] != encoded_key:
                raise ValueError('Phone public key changed; forget the old identity first')
            name = str(proof.get('deviceName', 'Android Phone')).strip()[:128]
            if supplied is not None:
                self.peers[peer_id] = {'publicKey': encoded_key, 'name': name}
                self.save_peers()
                self.secret = ''
                self.secret_expires = 0
            elif known.get('name') != name:
                known['name'] = name
                self.save_peers()
            write_frame(stream, dict(type='AUTH_OK', deviceId=self.device_id))
        return peer_id

    def handle(self, raw):
        secure = None
        receiver = None
        try:
            raw.settimeout(10)
            secure = self.context.wrap_socket(raw, server_side=True)
            with self.lock:
                self.connections.discard(raw)
                self.connections.add(secure)
            stream = secure.makefile('rwb')
            try:
                peer_id = self.authenticate(stream)
                with self.lock:
                    if self.stop_event.is_set():
                        return
                    previous = self.active_socket
                    if previous:
                        previous.shutdown(socket.SHUT_RDWR)
                    if self.sender:
                        self.sender.interrupt()
                    self.active_socket, self.active_stream, self.active_id = secure, stream, peer_id
                    self.connection_id = str(uuid.uuid4())
                    self.notification_mirror.reset()
                with self.lock:
                    identity = (peer_id, self.peers[peer_id].get('publicKey'))
                    receiver = self.peer_receivers.get(identity)
                    if receiver is None:
                        if len(self.peer_receivers) >= 4:
                            _, old = self.peer_receivers.popitem()
                            old.discard()
                        receiver = FileReceiver(self.directory / "ReceivedFiles", state=self.receiver_state(identity))
                        self.peer_receivers[identity] = receiver
                    self.receivers.add(receiver)
                secure.settimeout(45)
                self.report('Phone connected with verified identity')
                while not self.stop_event.is_set():
                    message = read_frame(stream)
                    with self.lock:
                        if self.active_socket is not secure:
                            break
                        if message.get('type') == 'HEARTBEAT' and message.get('isAck') is False:
                            write_frame(stream, dict(message, isAck=True))
                        elif message.get('type') == 'FILE_ACK':
                            if self.sender and identity == self.sender_peer:
                                self.sender.handle_ack(message)
                        elif message.get('type') in ('FILE_INIT', 'FILE_CHUNK', 'FILE_CANCEL'):
                            ack = receiver.process(message, self.files)
                            write_frame(stream, ack)
                            self.receive_progress(receiver)
                        elif message.get('type') in ('NOTIFICATION', 'NOTIFICATION_ACTION'):
                            for event in self.notification_mirror.process(message, self.notifications):
                                if self.event_sink:
                                    self.event_sink(dict(event='notification', connectionId=self.connection_id, **event))
                        elif message.get('type') == 'CLIPBOARD':
                            text = message.get('content')
                            if not isinstance(text, str) or message.get('mimeType') != 'text/plain':
                                raise ValueError('Invalid clipboard payload')
                            if self.clipboard:
                                subprocess.run(['pbcopy'], input=text.encode('utf-8'), check=True, timeout=5)
                                self.report('Received text copied to Mac clipboard')
                            if self.echo:
                                write_frame(stream, dict(type='CLIPBOARD', content=text, sourceDevice=self.name,
                                    mimeType='text/plain', timestamp=int(time.time() * 1000)))
                            if not self.clipboard and not self.echo:
                                self.report('Received text; Mac clipboard sharing is paused')
                        else:
                            raise ValueError('Unsupported message type')
            finally:
                stream.close()
        except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
            # Never print pairing secrets, message contents or proofs.
            self.report(f'Connection closed ({type(error).__name__})')
        finally:
            with self.lock:
                if receiver is not None:
                    current_receiver = self.peer_receivers.get((self.active_id, self.peers.get(self.active_id, {}).get('publicKey')))
                    if self.active_socket is secure or current_receiver is not receiver:
                        receiver.pause()
                    self.receivers.discard(receiver)
                if self.active_socket is secure:
                    self.active_socket = self.active_stream = self.active_id = None
                    self.connection_id = ""
                    self.notification_mirror.reset()
                    if self.sender:
                        self.sender.interrupt()
                self.connections.discard(raw)
                self.connections.discard(secure)
            if secure:
                secure.close()
            raw.close()
            self.slots.release()
            self.emit_state()

    def receive_progress(self, receiver, clock=time.monotonic):
        # Presentation is coalesced; every transfer frame still receives its exact ACK.
        self.last_receiver = receiver
        key = (id(receiver), receiver.result)
        now = clock()
        if key != self.receive_report_key or now >= self.next_receive_report:
            self.receive_report_key = key
            self.next_receive_report = now + .25
            self.report('File received and verified. Open Received Files to view it.' if receiver.result == 'completed' else
                'Receiving file from phone' if receiver.result == 'receiving' else 'File receiving ' + receiver.result)

    def expire_transfers(self):
        with self.lock:
            if self.sender and self.sender.result == 'paused' and time.monotonic() - self.sender.paused_at > RESUME_TTL_SECONDS:
                self.sender.cancel()
            for receiver in self.peer_receivers.values():
                if receiver.paused_at is not None and time.monotonic() - receiver.paused_at > RESUME_TTL_SECONDS:
                    receiver.abort()

    def serve(self):
        while not self.stop_event.is_set():
            self.expire_transfers()
            if not self.slots.acquire(timeout=1):
                continue
            try:
                raw, _ = self.listener.accept()
            except (socket.timeout, OSError):
                self.slots.release()
                continue
            with self.lock:
                self.connections.add(raw)
            threading.Thread(target=self.handle, args=(raw,), daemon=True).start()

    def start_file(self, path, connection_id):
        with self.lock:
            destination = self.active_stream
            if destination is None or not connection_id or connection_id != self.connection_id:
                raise ValueError('Phone connection changed. Choose the file again.')
            if self.sender and self.sender.busy:
                raise ValueError('A file is already being sent')
            def send(message):
                with self.lock:
                    if self.active_stream is not destination or self.connection_id != connection_id or self.stop_event.is_set():
                        raise OSError('Phone connection changed')
                    write_frame(destination, message)
            if self.sender and self.sender.result == 'paused':
                raise ValueError('Resume or cancel the paused transfer first')
            self.sender_peer = (self.active_id, self.peers.get(self.active_id, {}).get('publicKey'))
            self.sender = FileSender(self.directory / 'OutgoingFiles', send, self.report,
                state=TransferState(self.transfer_state / 'sender.json', [self.device_id, self.pin, *self.sender_peer]))
            self.sender.start(path)
        self.emit_state()

    def resume_file(self, connection_id):
        with self.lock:
            destination = self.active_stream
            identity = (self.active_id, self.peers.get(self.active_id, {}).get('publicKey'))
            if destination is None or not connection_id or connection_id != self.connection_id or identity != self.sender_peer:
                raise ValueError('Reconnect the original paired phone to resume')
            if not self.sender:
                raise ValueError('No paused transfer')
            def send(message):
                with self.lock:
                    if self.active_stream is not destination or self.connection_id != connection_id or self.stop_event.is_set():
                        raise OSError('Phone connection changed')
                    write_frame(destination, message)
            self.sender.resume(send)
        self.emit_state()

    def push_clipboard(self):
        if not self.clipboard:
            raise ValueError('Start with --clipboard to enable Mac clipboard access')
        with self.lock:
            destination = self.active_stream
            if destination is None:
                raise ValueError('No authenticated phone connection')
        text = subprocess.run(['pbpaste'], capture_output=True, check=True, timeout=5).stdout.decode('utf-8')
        with self.lock:
            if self.active_stream is not destination or not self.clipboard:
                raise ValueError('Phone connection or clipboard permission changed; try again')
            write_frame(destination, dict(type='CLIPBOARD', content=text, sourceDevice=self.name,
                mimeType='text/plain', timestamp=int(time.time() * 1000)))
        self.report('Sent Mac clipboard to phone')

    def forget(self, peer_id):
        with self.lock:
            for identity in list(self.peer_receivers):
                if identity[0] == peer_id:
                    self.peer_receivers.pop(identity).discard()
            if self.sender_peer and self.sender_peer[0] == peer_id and self.sender:
                self.sender.cancel()
            if peer_id in self.peers:
                del self.peers[peer_id]
                self.save_peers()
            if self.active_id == peer_id and self.active_socket:
                try:
                    self.active_socket.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                self.active_socket = self.active_stream = self.active_id = None
                self.connection_id = ""
                self.notification_mirror.reset()
                if self.sender:
                    self.sender.cancel()

    def close(self):
        self.stop_event.set()
        self.listener.close()
        with self.lock:
            self.notification_mirror.reset()
            if self.sender:
                self.sender.interrupt()
            for receiver in set(self.peer_receivers.values()) | self.receivers:
                receiver.suspend() if receiver.state and self.files else receiver.abort()
            for connection in self.connections:
                try:
                    connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                connection.close()
        if self.sender and self.sender.busy:
            self.sender.finished.wait(5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-dir', default=str(Path.home() / 'Library/Application Support/MacBridgeDev'))
    parser.add_argument('--host', default='0.0.0.0')
    parser.add_argument('--port', type=int, default=8990)
    parser.add_argument('--address', help='Override the automatically selected Mac LAN IP')
    parser.add_argument('--clipboard', action='store_true', help='Enable actual Mac clipboard reads and writes')
    parser.add_argument('--files', action='store_true', help='Allow verified phone files in the private ReceivedFiles folder')
    parser.add_argument('--echo', action='store_true', help='Echo text for protocol testing without clipboard access')
    parser.add_argument('--headless', action='store_true')
    parser.add_argument('--gui', action='store_true', help='JSON control channel for the native Mac app')
    args = parser.parse_args()
    try:
        address = args.address or detect_address()
        ipaddress.ip_address(address)
    except (OSError, ValueError) as error:
        parser.error(f'Cannot choose a Mac address: {error}. Use --address with the Mac LAN IP.')
    os.umask(0o077)
    output_lock = threading.Lock()
    def emit(value):
        with output_lock:
            print(json.dumps(value, separators=(',', ':')), flush=True)
    companion = Companion(args.state_dir, args.host, args.port, args.echo, args.clipboard, emit if args.gui else None, files=args.files)
    companion.address = address
    if args.gui:
        companion.emit_state()
    else:
        print('PAIRING_URI=' + companion.pairing_uri(address), flush=True)
        print(f'Mac endpoint: {address}:{companion.port}. Keep this companion running while pairing.', flush=True)
        print('Code expires in 5 minutes. Commands: /push, /send PATH, /code, /peers, /forget ID, /quit', flush=True)
    worker = threading.Thread(target=companion.serve, daemon=True)
    worker.start()
    try:
        if args.gui:
            import sys
            while True:
                line = sys.stdin.buffer.readline(4097)
                if not line:
                    break  # Native app closed: stop the child server too.
                try:
                    if len(line) > 4096 or not line.endswith(b'\n'):
                        raise ValueError('Invalid control command')
                    command = json.loads(line)
                    if not isinstance(command, dict):
                        raise ValueError('Invalid control command')
                    if not companion.handle_command(command):
                        break
                except (ValueError, OSError, subprocess.SubprocessError) as error:
                    emit(dict(event='error', message=str(error)))
        elif args.headless:
            worker.join()
        else:
            while True:
                command = input().strip()
                try:
                    if command == '/quit':
                        break
                    if command == '/push':
                        companion.push_clipboard()
                    elif command.startswith('/send '):
                        companion.start_file(command[6:], companion.connection_id)
                    elif command == '/resume':
                        companion.resume_file(companion.connection_id)
                    elif command == '/cancel':
                        if companion.sender:
                            companion.sender.cancel()
                    elif command == '/code':
                        address = args.address or detect_address()
                        companion.rotate_code()
                        print('PAIRING_URI=' + companion.pairing_uri(address), flush=True)
                    elif command == '/peers':
                        with companion.lock:
                            print('\n'.join(companion.peers) or 'No paired phones')
                    elif command.startswith('/forget '):
                        companion.forget(command.split(' ', 1)[1])
                except (ValueError, OSError, subprocess.SubprocessError) as error:
                    print(str(error), flush=True)
    except (EOFError, KeyboardInterrupt):
        pass
    finally:
        companion.close()
        worker.join(timeout=2)


if __name__ == '__main__':
    main()

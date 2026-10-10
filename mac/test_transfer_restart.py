import base64
import hashlib
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

from file_receiver import FileReceiver, CHUNK_SIZE
from file_sender import FileSender
from transfer_state import TransferState
from macbridge import Companion


class TransferRestartTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.folder = self.root / 'received'
        self.state = TransferState(self.root / 'state' / 'receiver.json', ['mac-id', 'mac-pin', 'phone-id', 'phone-key'])
        self.data = b'x' * CHUNK_SIZE + b'tail'
        self.init = dict(type='FILE_INIT', transferId='restart', fileName='🌉 file.txt', fileSize=len(self.data),
            sha256Checksum=hashlib.sha256(self.data).hexdigest(), chunkSize=CHUNK_SIZE)
    def tearDown(self):
        self.temp.cleanup()
    def chunk(self, index):
        offset = index * CHUNK_SIZE
        data = self.data[offset:offset+CHUNK_SIZE]
        return dict(type='FILE_CHUNK', transferId='restart', offset=offset, chunkIndex=index,
            totalChunks=2, chunkLength=len(data), dataBase64=base64.b64encode(data).decode())
    def partial(self):
        receiver = FileReceiver(self.folder, state=self.state)
        receiver.process(self.init, True)
        receiver.process(self.chunk(0), True)
        receiver.suspend()
        return receiver
    def test_receiver_restart_rehashes_prefix_and_discards_uncheckpointed_tail(self):
        self.partial()
        partial = next(self.folder.glob('.incoming-*'))
        with partial.open('ab') as stream:
            stream.write(b'uncheckpointed bytes')
        receiver = FileReceiver(self.folder, state=self.state)
        try:
            ack = receiver.process(dict(self.init, resume=True), True)
            self.assertEqual(('READY', CHUNK_SIZE), (ack['status'], ack['receivedBytes']))
            self.assertEqual('COMPLETED', receiver.process(self.chunk(1), True)['status'])
            self.assertEqual(self.data, receiver.last_saved.read_bytes())
        finally: receiver.abort()
    def test_cancelling_restored_partial_cannot_restore_after_another_restart(self):
        self.partial()
        receiver = FileReceiver(self.folder, state=self.state)
        self.assertEqual('CANCELLED', receiver.cancel('restart')['status'])
        self.assertFalse(list(self.folder.glob('.incoming-*')))
        restored = FileReceiver(self.folder, state=self.state)
        self.assertIsNone(restored.active)
        self.assertEqual('REJECTED', restored.process(dict(self.init, resume=True), True)['status'])
        restored.abort()

    def test_reopening_without_resuming_does_not_extend_the_saved_deadline(self):
        self.partial()
        expires = json.loads(self.state.path.read_text())['incoming']['expires']
        restored = FileReceiver(self.folder, state=self.state)
        restored.suspend()
        self.assertEqual(expires, json.loads(self.state.path.read_text())['incoming']['expires'])

    def test_abrupt_receiver_stop_can_rewind_to_an_earlier_durable_checkpoint(self):
        receiver = FileReceiver(self.folder, state=self.state)
        receiver.process(self.init, True)
        initial = self.state.path.read_bytes()
        receiver.process(self.chunk(0), True)
        receiver.active['stream'].flush()
        receiver.active['stream'].close()  # Simulate exit without suspend or abort.
        self.state.path.write_bytes(initial)
        restored = FileReceiver(self.folder, state=self.state)
        try:
            self.assertEqual(0, restored.process(dict(self.init, resume=True), True)['receivedBytes'])
            restored.process(self.chunk(0), True)
            self.assertEqual('COMPLETED', restored.process(self.chunk(1), True)['status'])
            self.assertEqual(self.data, restored.last_saved.read_bytes())
        finally: restored.abort()
    def test_receiver_corruption_expiry_wrong_identity_and_traversal_are_rejected(self):
        for mode in ['corrupt', 'expired', 'identity', 'path', 'oversized', 'malformed']:
            with self.subTest(mode=mode):
                self.partial()
                state = self.state
                value = json.loads(state.path.read_text())
                if mode == 'corrupt': next(self.folder.glob('.incoming-*')).write_bytes(b'y' * CHUNK_SIZE)
                if mode == 'expired': value['incoming']['expires'] = time.time() - 1
                if mode == 'identity': state = TransferState(state.path, ['changed-key'])
                if mode == 'path': value['incoming']['temporary'] = '../outside'
                if mode == 'oversized': value['incoming']['size'] = 101 * 1024 * 1024
                state.path.write_text('{' if mode == 'malformed' else json.dumps(value))
                restored = FileReceiver(self.folder, state=state)
                self.assertIsNone(restored.active)
                self.assertEqual('REJECTED', restored.process(dict(self.init, resume=True), True)['status'])
                restored.abort()
                for path in self.folder.glob('.incoming-*'): path.unlink()
    def test_verified_receipt_survives_restart_without_duplicate_publication(self):
        self.partial()
        receiver = FileReceiver(self.folder, state=self.state)
        receiver.process(dict(self.init, resume=True), True)
        receiver.process(self.chunk(1), True)
        receiver.suspend()
        restored = FileReceiver(self.folder, state=self.state)
        ack = restored.process(dict(self.init, resume=True), True)
        self.assertEqual(('COMPLETED', len(self.data)), (ack['status'], ack['receivedBytes']))
        self.assertEqual(1, len(list(self.folder.glob('verified-*'))))
        restored.abort()
    def test_completed_receipt_rechecks_file_and_expiry_before_acknowledging(self):
        for mode in ['corrupt', 'expired']:
            self.partial()
            receiver = FileReceiver(self.folder, state=self.state)
            receiver.process(dict(self.init, resume=True), True)
            receiver.process(self.chunk(1), True)
            restored = FileReceiver(self.folder, state=self.state)
            if mode == 'corrupt': receiver.last_saved.write_bytes(b'y' * len(self.data))
            else: restored.receipt_files['restart']['expires'] = time.time() - 1
            self.assertNotEqual('COMPLETED', restored.process(dict(self.init, resume=True), True)['status'])
            restored.abort()
            self.state.clear()
            for path in self.folder.iterdir(): path.unlink()

    def test_publication_journal_recovers_a_crash_before_rename(self):
        self.partial()
        receiver = FileReceiver(self.folder, state=self.state)
        receiver.process(dict(self.init, resume=True), True)
        original_replace = Path.replace
        def fail_publish(path, destination):
            if path.name.startswith('.incoming-'):
                raise OSError('simulated exit before rename')
            return original_replace(path, destination)
        with patch.object(Path, 'replace', fail_publish):
            receiver.process(self.chunk(1), True)
        restored = FileReceiver(self.folder, state=self.state)
        self.assertEqual('COMPLETED', restored.process(dict(self.init, resume=True), True)['status'])
        self.assertEqual(self.data, next(self.folder.glob('verified-*')).read_bytes())
        restored.abort()
    def test_sender_snapshot_and_identity_binding_survive_restart(self):
        source = self.root / 'source'; source.write_bytes(self.data)
        state = TransferState(self.root / 'state' / 'sender.json', self.state.identity)
        receiver = FileReceiver(self.folder, state=self.state)
        def first_send(frame):
            if frame['type'] == 'FILE_CHUNK' and frame['chunkIndex'] == 1:
                raise OSError('disconnected')
            sender.handle_ack(receiver.process(frame, True))
        sender = FileSender(self.root / 'outgoing', first_send, lambda _: None, state=state)
        sender.start(source); self.assertTrue(sender.finished.wait(3))
        self.assertEqual('paused', sender.result)
        source.write_bytes(b'changed source')
        receiver.suspend()
        receiver = FileReceiver(self.folder, state=self.state)
        offsets = []
        def send(frame):
            if frame['type'] == 'FILE_CHUNK': offsets.append(frame['offset'])
            restored.handle_ack(receiver.process(frame, True))
        restored = FileSender(sender.directory, send, lambda _: None, state=state)
        self.assertEqual('paused', restored.result)
        restored.resume(send); self.assertTrue(restored.finished.wait(3))
        self.assertEqual('completed', restored.result)
        self.assertEqual([CHUNK_SIZE], offsets)
        self.assertEqual(self.data, receiver.last_saved.read_bytes())
        self.assertFalse(state.path.exists())
        receiver.abort()
    def test_sender_corrupt_expired_and_changed_identity_never_restore(self):
        for mode in ['corrupt', 'expired', 'identity']:
            source = self.root / 'source'; source.write_bytes(self.data)
            state = TransferState(self.root / 'state' / 'sender.json', self.state.identity)
            sender = FileSender(self.root / 'outgoing', lambda _: (_ for _ in ()).throw(OSError()), lambda _: None, state=state)
            sender.start(source); self.assertTrue(sender.finished.wait(3))
            if mode == 'corrupt': sender.snapshot.write_bytes(b'y' * len(self.data))
            if mode == 'expired':
                value = json.loads(state.path.read_text()); value['outgoing']['expires'] = time.time() - 1; state.path.write_text(json.dumps(value))
            if mode == 'identity': state = TransferState(state.path, ['different'])
            restored = FileSender(sender.directory, lambda _: self.fail('Must not send'), lambda _: None, state=state)
            self.assertEqual('idle', restored.result)
            sender.cancel()
    def test_companion_shutdown_restart_preserves_known_peer_partial_and_forget_discards_it(self):
        companion = Companion(self.root, host='127.0.0.1', port=0, files=True, event_sink=lambda _: None)
        companion.peers['phone'] = {'publicKey': 'key', 'name': 'Phone'}; companion.save_peers()
        identity = ('phone', 'key')
        receiver = FileReceiver(self.root / 'ReceivedFiles', state=companion.receiver_state(identity))
        companion.peer_receivers[identity] = receiver
        receiver.process(self.init, True); receiver.process(self.chunk(0), True)
        companion.close()
        restored = Companion(self.root, host='127.0.0.1', port=0, files=True, event_sink=lambda _: None)
        try:
            receiver = restored.peer_receivers[identity]
            self.assertEqual(CHUNK_SIZE, receiver.process(dict(self.init, resume=True), True)['receivedBytes'])
            restored.forget('phone')
            self.assertFalse(receiver.state.path.exists())
            self.assertFalse(list((self.root / 'ReceivedFiles').glob('.incoming-*')))
        finally: restored.close()

class RealProcessRestartTests(unittest.TestCase):
    def test_authenticated_tls_resume_after_forced_server_exit_and_final_receipt_restart(self):
        import socket
        import ssl
        import subprocess
        import sys
        from macbridge import openssl, fingerprint, read_frame, write_frame
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            phone_key = root / 'phone.key'
            openssl('ecparam', '-genkey', '-name', 'prime256v1', '-out', str(phone_key))
            public = openssl('pkey', '-in', str(phone_key), '-pubout', '-outform', 'DER')
            directory = root / 'mac'
            initial = Companion(directory, host='127.0.0.1', port=0, files=True, event_sink=lambda _: None)
            initial.peers['phone'] = {'publicKey': base64.b64encode(public).decode(), 'name': 'Phone'}
            initial.save_peers(); pin = initial.pin; initial.close()
            script = """import sys,json
sys.path.insert(0,sys.argv[1])
from macbridge import Companion
peer=Companion(sys.argv[2],host='127.0.0.1',port=0,files=True,event_sink=lambda _:None)
print(json.dumps({'port':peer.port}),flush=True)
peer.serve()
"""
            process = connection = stream = None
            def connect():
                nonlocal process, connection, stream
                process = subprocess.Popen([sys.executable, '-u', '-c', script, str(Path(__file__).parent), str(directory)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                port = json.loads(process.stdout.readline())['port']
                context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
                context.check_hostname = False; context.verify_mode = ssl.CERT_NONE
                connection = context.wrap_socket(socket.create_connection(('127.0.0.1', port), timeout=5))
                stream = connection.makefile('rwb')
                challenge = read_frame(stream)
                self.assertEqual(pin, challenge['fingerprint'])
                transcript = f"macbridge-auth-v2\n{challenge['nonce']}\n{challenge['deviceId']}\n{pin}\nphone\n{fingerprint(public)}"
                signature = openssl('dgst', '-sha256', '-sign', str(phone_key), input=transcript.encode())
                write_frame(stream, dict(type='AUTH_PROOF', deviceId='phone', deviceName='Phone', publicKey=base64.b64encode(public).decode(), signature=base64.b64encode(signature).decode()))
                self.assertEqual('AUTH_OK', read_frame(stream)['type'])
            def stop():
                nonlocal process, connection, stream
                if process:
                    process.kill(); process.wait(timeout=5)
                    process.stdout.close(); process.stderr.close(); process = None
                if stream: stream.close(); stream = None
                if connection: connection.close(); connection = None
            data = b'z' * (CHUNK_SIZE + 37)
            init = dict(type='FILE_INIT', transferId='real-restart', fileName='🌉 actual.txt', fileSize=len(data), sha256Checksum=hashlib.sha256(data).hexdigest(), chunkSize=CHUNK_SIZE)
            def chunk(index):
                offset = index * CHUNK_SIZE; block = data[offset:offset+CHUNK_SIZE]
                return dict(type='FILE_CHUNK', transferId='real-restart', offset=offset, chunkIndex=index, totalChunks=2, chunkLength=len(block), dataBase64=base64.b64encode(block).decode())
            try:
                connect(); write_frame(stream, init); self.assertEqual('READY', read_frame(stream)['status'])
                write_frame(stream, chunk(0)); self.assertEqual('IN_PROGRESS', read_frame(stream)['status'])
                stop(); connect()
                write_frame(stream, dict(init, resume=True)); ack = read_frame(stream)
                self.assertEqual('READY', ack['status']); self.assertIn(ack['receivedBytes'], [0, CHUNK_SIZE])
                for index in range(ack['receivedBytes'] // CHUNK_SIZE, 2):
                    write_frame(stream, chunk(index)); final = read_frame(stream)
                self.assertEqual('COMPLETED', final['status'])
                stop(); connect(); write_frame(stream, dict(init, resume=True))
                self.assertEqual('COMPLETED', read_frame(stream)['status'])
                files = list((directory / 'ReceivedFiles').glob('verified-*'))
                self.assertEqual(1, len(files)); self.assertEqual(data, files[0].read_bytes())
            finally: stop()

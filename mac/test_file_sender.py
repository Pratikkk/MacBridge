import base64
import hashlib
import io
from pathlib import Path
import tempfile
import time
import unittest

from file_receiver import MAX_FILE_SIZE
from file_sender import FileSender
from macbridge import Companion


class FileSenderTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def scenario(self, data=b'hello', behavior='valid', name='🌉 notes.bin'):
        source = self.root / name
        source.write_bytes(data)
        statuses, frames, received = [], [], bytearray()
        def send(frame):
            frames.append(frame)
            if frame['type'] == 'FILE_INIT':
                if behavior == 'timeout':
                    return
                if behavior == 'cancel':
                    sender.cancel()
                    return
                if behavior == 'disconnect':
                    raise OSError('private path detail')
                ack = dict(type='FILE_ACK', transferId=frame['transferId'], receivedBytes=0,
                    status='REJECTED' if behavior == 'reject' else ('READY' if data else 'COMPLETED'))
                if not data:
                    ack['sha256Checksum'] = frame['sha256Checksum']
                if behavior == 'wrong-id':
                    ack['transferId'] = 'other'
                sender.handle_ack(ack)
            elif frame['type'] == 'FILE_CHUNK':
                received.extend(base64.b64decode(frame['dataBase64'], validate=True))
                final = len(received) == len(data)
                sender.handle_ack(dict(type='FILE_ACK', transferId=frame['transferId'],
                    receivedBytes=len(received), status='COMPLETED' if final else 'IN_PROGRESS',
                    sha256Checksum='wrong' if behavior == 'bad-hash' else hashlib.sha256(received).hexdigest()))
        sender = FileSender(self.root / 'spool', send, statuses.append, timeout=.05)
        sender.start(source)
        self.assertTrue(sender.finished.wait(3))
        self.assertFalse(sender.busy)
        paused = behavior in ('timeout', 'wrong-id', 'disconnect')
        self.assertEqual(bool(list((self.root / 'spool').glob('.outgoing-*'))), paused)
        if paused:
            self.assertEqual(sender.result, 'paused')
            self.assertFalse(any(frame['type'] == 'FILE_CANCEL' for frame in frames))
        success = behavior == 'valid'
        self.assertEqual('received and verified' in statuses[-1], success)
        if success:
            self.assertEqual(bytes(received), data)
            self.assertEqual(sender.sent_bytes, len(data))
        elif not paused:
            self.assertEqual(frames[-1]['type'], 'FILE_CANCEL')
        self.assertTrue(all(name not in status and 'private' not in status for status in statuses))
        if paused:
            sender.cancel()
            self.assertEqual(list((self.root / "spool").glob(".outgoing-*")), [])
        return frames

    def test_multichunk_unicode_exact_delivery(self):
        frames = self.scenario(bytes(range(256)) * 400)
        self.assertEqual(sum(frame['type'] == 'FILE_CHUNK' for frame in frames), 2)

    def test_empty_file_requires_checksum_confirmation(self):
        self.scenario(b'')

    def test_phone_rejection_wrong_checksum_timeout_and_unrelated_ack(self):
        for behavior in ('reject', 'bad-hash', 'timeout', 'wrong-id'):
            with self.subTest(behavior=behavior):
                self.scenario(behavior=behavior)

    def test_cancellation_and_disconnect_cleanup(self):
        self.scenario(behavior='cancel')
        self.scenario(behavior='disconnect')

    def test_oversize_missing_and_nonregular_files_send_nothing(self):
        oversized = self.root / 'large'
        with oversized.open('wb') as file:
            file.truncate(MAX_FILE_SIZE + 1)
        for path in (oversized, self.root / 'missing', self.root):
            frames, statuses = [], []
            sender = FileSender(self.root / 'spool', frames.append, statuses.append)
            sender.start(path)
            self.assertTrue(sender.finished.wait(3))
            self.assertEqual(frames, [])
            self.assertFalse(sender.busy)
            self.assertEqual(list((self.root / 'spool').glob('.outgoing-*')), [])

    def test_resume_receiver_offset_and_lost_final_ack_use_original_snapshot(self):
        from file_receiver import FileReceiver, CHUNK_SIZE
        for lose_final in (False, True):
            with self.subTest(lose_final=lose_final):
                source = self.root / 'source'
                data = bytes(range(256)) * 700
                source.write_bytes(data)
                receiver = FileReceiver(self.root / ('received-' + str(lose_final)))
                frames = []
                interrupted = False
                def send(frame):
                    nonlocal interrupted
                    frames.append(frame)
                    ack = receiver.process(frame, True)
                    if not interrupted and frame['type'] == 'FILE_CHUNK' and (ack['status'] == 'COMPLETED' if lose_final else ack['receivedBytes'] == CHUNK_SIZE):
                        interrupted = True
                        receiver.pause()
                        raise OSError('disconnect after receiver wrote chunk')
                    sender.handle_ack(ack)
                sender = FileSender(self.root / 'spool', send, lambda _: None, timeout=.05)
                sender.start(source)
                self.assertTrue(sender.finished.wait(3))
                self.assertEqual('paused', sender.result)
                source.write_bytes(b'changed original document')
                before = len(frames)
                sender.resume(send)
                self.assertTrue(sender.finished.wait(3))
                self.assertEqual('completed', sender.result)
                self.assertEqual(frames[before]['resume'], True)
                chunks = [f for f in frames[before:] if f['type'] == 'FILE_CHUNK']
                if lose_final:
                    self.assertEqual([], chunks)
                else:
                    self.assertEqual(CHUNK_SIZE, chunks[0]['offset'])
                self.assertEqual(data, receiver.last_saved.read_bytes())
                self.assertEqual(1, len(list(receiver.directory.iterdir())))

    def test_resume_rejects_corrupt_snapshot_and_expiry(self):
        for expired in (False, True, "missing"):
            source = self.root / 'source'
            source.write_bytes(b'hello')
            sender = FileSender(self.root / 'spool', lambda _: None, lambda _: None, timeout=.02)
            sender.start(source)
            self.assertTrue(sender.finished.wait(3))
            if expired is True:
                sender.paused_at -= 601
                with self.assertRaises(ValueError): sender.resume(lambda _: self.fail('expired snapshot sent'))
            else:
                if expired == 'missing':
                    sender.snapshot.unlink()
                else:
                    sender.snapshot.write_bytes(b'other')
                sender.resume(lambda _: self.fail('corrupt snapshot sent'))
                self.assertTrue(sender.finished.wait(3))
                self.assertEqual('failed', sender.result)
            self.assertEqual([], list((self.root / 'spool').glob('.outgoing-*')))

    def test_backend_resume_refuses_different_identity(self):
        companion = Companion(self.root / 'peer', host='127.0.0.1', port=0, event_sink=lambda _: None)
        try:
            source = self.root / 'source'
            source.write_bytes(b'x')
            companion.active_id = 'original'
            companion.peers['original'] = {'publicKey': 'pinned'}
            companion.active_stream = io.BytesIO()
            companion.connection_id = 'first'
            companion.start_file(source, 'first')
            companion.sender.interrupt()
            self.assertTrue(companion.sender.finished.wait(3))
            companion.active_id = 'different'
            companion.connection_id = 'second'
            with self.assertRaises(ValueError): companion.resume_file('second')
            companion.active_id = 'original'
            companion.peers['original']['publicKey'] = 'changed'
            with self.assertRaises(ValueError): companion.resume_file('second')
        finally:
            companion.close()

    def test_duplicate_start_is_rejected(self):
        source = self.root / 'source'
        source.write_bytes(b'x')
        sender = FileSender(self.root / 'spool', lambda frame: None, lambda status: None)
        sender.start(source)
        with self.assertRaises(ValueError):
            sender.start(source)
        sender.cancel()
        self.assertTrue(sender.finished.wait(3))

    def test_backend_checks_original_connection_and_shutdown_cancels_sender(self):
        source = self.root / 'source'
        source.write_bytes(b'x')
        companion = Companion(self.root / 'peer', host='127.0.0.1', port=0, event_sink=lambda event: None)
        try:
            with self.assertRaises(ValueError):
                companion.start_file(source, 'stale')
            companion.active_stream = io.BytesIO()
            companion.connection_id = 'new-session'
            with self.assertRaises(ValueError):
                companion.start_file(source, 'old-session')
            companion.start_file(source, 'new-session')
            with self.assertRaises(ValueError):
                companion.start_file(source, 'new-session')
            companion.close()
            self.assertTrue(companion.sender.finished.wait(3))
            self.assertFalse(companion.sender.busy)
        finally:
            companion.close()

    def test_restart_cleans_only_stale_snapshots(self):
        directory = self.root / 'peer'
        outgoing = directory / 'OutgoingFiles'
        outgoing.mkdir(parents=True)
        (outgoing / '.outgoing-interrupted').write_bytes(b'partial')
        (outgoing / 'unrelated').write_bytes(b'keep')
        companion = Companion(directory, host='127.0.0.1', port=0)
        try:
            self.assertFalse((outgoing / '.outgoing-interrupted').exists())
            self.assertTrue((outgoing / 'unrelated').exists())
        finally:
            companion.close()


if __name__ == '__main__':
    unittest.main()

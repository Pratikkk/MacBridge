import base64
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from macbridge import Companion
from file_receiver import FileReceiver, CHUNK_SIZE, MAX_FILE_SIZE

class FileReceiverTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name) / 'received'
        self.receiver = FileReceiver(self.directory)
    def tearDown(self):
        self.receiver.abort()
        self.temp.cleanup()
    def init(self, data, name='notes.txt', transfer='test'):
        return dict(type='FILE_INIT', transferId=transfer, fileName=name, fileSize=len(data),
            sha256Checksum=hashlib.sha256(data).hexdigest(), chunkSize=CHUNK_SIZE)
    def chunk(self, data, index=0, offset=0, total=1, transfer='test'):
        return dict(type='FILE_CHUNK', transferId=transfer, chunkIndex=index, offset=offset,
            totalChunks=total, chunkLength=len(data), dataBase64=base64.b64encode(data).decode())
    def files(self):
        return list(self.directory.iterdir()) if self.directory.exists() else []
    def test_verified_multichunk_and_unicode_name(self):
        data = bytes(range(256)) * 300
        self.assertEqual('READY', self.receiver.process(self.init(data, '🌉 notes.txt'), True)['status'])
        self.assertEqual('IN_PROGRESS', self.receiver.process(dict(type='FILE_CHUNK', transferId='test', chunkIndex=0, totalChunks=2, offset=0, chunkLength=CHUNK_SIZE, dataBase64=base64.b64encode(data[:CHUNK_SIZE]).decode()), True)['status'])
        ack = self.receiver.process(self.chunk(data[CHUNK_SIZE:], 1, CHUNK_SIZE, 2), True)
        self.assertEqual('COMPLETED', ack['status'])
        self.assertEqual(hashlib.sha256(data).hexdigest(), ack['sha256Checksum'])
        self.assertEqual(data, self.files()[0].read_bytes())
        self.assertIn('🌉 notes.txt', self.files()[0].name)
        self.assertEqual(0o600, self.files()[0].stat().st_mode & 0o777)
    def test_old_receipt_replay_does_not_replace_current_receiving_progress(self):
        self.receiver.process(self.init(b'x'), True)
        self.receiver.process(self.chunk(b'x'), True)
        self.receiver.process(self.init(b'y' * (CHUNK_SIZE + 1), transfer='new'), True)
        self.assertEqual('COMPLETED', self.receiver.process(dict(self.init(b'x'), resume=True), True)['status'])
        self.assertEqual(('receiving', 0, CHUNK_SIZE + 1), (self.receiver.result, self.receiver.received_bytes, self.receiver.file_size))

    def test_local_cancel_active_paused_and_stale_actions_preserve_verified_files(self):
        self.receiver.process(self.init(b'v', transfer='verified'), True)
        self.receiver.process(self.chunk(b'v', transfer='verified'), True)
        saved = self.receiver.last_saved
        for paused in (False, True):
            self.receiver.process(self.init(b'x' * (CHUNK_SIZE + 1)), True)
            self.receiver.process(self.chunk(b'x' * CHUNK_SIZE, total=2), True)
            if paused:
                self.receiver.pause()
            self.assertIsNone(self.receiver.cancel('other'))
            ack = self.receiver.cancel('test')
            self.assertEqual(('CANCELLED', CHUNK_SIZE), (ack['status'], ack['receivedBytes']))
            self.assertEqual('cancelled', self.receiver.result)
            self.assertIsNone(self.receiver.cancel('test'))
            self.assertEqual(b'v', saved.read_bytes())
            self.assertEqual([saved], self.files())
            self.assertEqual('REJECTED', self.receiver.process(dict(self.init(b'x' * (CHUNK_SIZE + 1)), resume=True), True)['status'])
            self.receiver.cancelled.clear()

    def test_zero_byte_file_is_verified(self):
        self.assertEqual('COMPLETED', self.receiver.process(self.init(b''), True)['status'])
        self.assertEqual(b'', self.files()[0].read_bytes())
    def test_paused_permission_and_revocation_remove_partial(self):
        init = self.init(b'x')
        self.assertEqual('REJECTED', self.receiver.process(init, False)['status'])
        self.assertFalse(self.files())
        self.receiver.process(init, True)
        self.assertEqual('REJECTED', self.receiver.process(self.chunk(b'x'), False)['status'])
        self.assertFalse(self.files())
    def test_bad_size_digest_order_and_base64_never_publish(self):
        bads = [dict(chunkIndex=1), dict(offset=10), dict(chunkLength=99), dict(totalChunks=7), dict(dataBase64='!')]
        for change in bads:
            self.receiver.process(self.init(b'x'), True)
            msg = self.chunk(b'x'); msg.update(change)
            self.assertEqual('FAILED', self.receiver.process(msg, True)['status'])
            self.assertFalse(self.files())
        self.receiver.process(self.init(b'x'), True)
        self.assertEqual('VERIFICATION_FAILED', self.receiver.process(self.chunk(b'y'), True)['status'])
        self.assertFalse(self.files())
    def test_cancel_disconnect_and_duplicate_init(self):
        self.receiver.process(self.init(b'x'), True)
        self.assertEqual('BUSY', self.receiver.process(self.init(b'x'), True)['status'])
        self.assertEqual('CANCELLED', self.receiver.process(dict(type='FILE_CANCEL', transferId='test'), True)['status'])
        self.assertFalse(self.files())
        self.receiver.process(self.init(b'x'), True)
        self.receiver.abort()
        self.assertFalse(self.files())
    def test_path_traversal_and_same_names_cannot_overwrite(self):
        for name in ['../../outside.txt', 'C:\\temp\\outside.txt', '/outside.txt']:
            self.receiver.process(self.init(b'x', name), True)
            self.receiver.process(self.chunk(b'x'), True)
        self.assertEqual(3, len(self.files()))
        self.assertTrue(all(p.parent == self.directory for p in self.files()))
        self.assertFalse((Path(self.temp.name) / 'outside.txt').exists())
    def test_invalid_metadata_and_oversized_inputs(self):
        for change in [dict(fileSize=-1), dict(fileSize=MAX_FILE_SIZE+1), dict(fileSize=True), dict(chunkSize=1),
                dict(fileName=''), dict(fileName='x'*257), dict(sha256Checksum='bad')]:
            msg=self.init(b'x'); msg.update(change)
            self.assertEqual('FAILED', self.receiver.process(msg, True)['status'])
            self.assertFalse(self.files())
    def test_disk_failure_is_safe_and_retry_works(self):
        with patch('file_receiver.shutil.disk_usage') as usage:
            usage.return_value.free=0
            self.assertEqual('FAILED', self.receiver.process(self.init(b'x'), True)['status'])
        self.assertFalse(self.files())
        self.assertEqual('READY', self.receiver.process(self.init(b'x'), True)['status'])
    def test_wrong_transfer_cannot_append_to_active_file(self):
        self.receiver.process(self.init(b'x'), True)
        self.assertEqual('REJECTED', self.receiver.process(self.chunk(b'x', transfer='other'), True)['status'])
        self.assertEqual('COMPLETED', self.receiver.process(self.chunk(b'x'), True)['status'])

    def test_resume_mismatch_cancel_and_expiry(self):
        import time
        data = b'x' * (CHUNK_SIZE + 10)
        init = self.init(data)
        self.receiver.process(init, True)
        self.receiver.process(dict(type='FILE_CHUNK', transferId='test', chunkIndex=0, totalChunks=2, offset=0, chunkLength=CHUNK_SIZE, dataBase64=base64.b64encode(data[:CHUNK_SIZE]).decode()), True)
        self.receiver.pause()
        resumed = dict(init, resume=True)
        self.assertEqual('REJECTED', self.receiver.process(dict(resumed, sha256Checksum='0'*64), True)['status'])
        self.assertEqual(CHUNK_SIZE, self.receiver.process(resumed, True)['receivedBytes'])
        self.receiver.process(dict(type='FILE_CANCEL', transferId='test'), True)
        self.assertEqual('REJECTED', self.receiver.process(resumed, True)['status'])
        self.assertFalse(self.files())
        self.receiver.process(dict(init, transferId='expired'), True)
        self.receiver.pause()
        self.receiver.paused_at = time.monotonic() - 601
        self.assertEqual('REJECTED', self.receiver.process(dict(resumed, transferId='expired'), True)['status'])
        self.receiver.abort()

class CompanionFileTests(unittest.TestCase):
    def test_receiving_cancel_token_notifies_only_original_peer_and_rejects_stale_clicks(self):
        import io, json
        with tempfile.TemporaryDirectory() as temp:
            events = []
            companion = Companion(temp, host='127.0.0.1', port=0, files=True, event_sink=events.append)
            receiver = FileReceiver(Path(temp) / 'ReceivedFiles')
            identity = ('phone', 'public')
            companion.peers['phone'] = dict(publicKey='public')
            companion.peer_receivers[identity] = receiver
            companion.active_id = 'phone'
            stream = companion.active_stream = io.BytesIO()
            init = dict(type='FILE_INIT', transferId='first', fileName='file', fileSize=1, sha256Checksum=hashlib.sha256(b'x').hexdigest(), chunkSize=CHUNK_SIZE)
            try:
                receiver.process(init, True); companion.emit_state()
                old = events[-1]['fileReceiveToken']
                companion.handle_command(dict(action='cancelFileReceive', transferToken='bad'))
                self.assertIsNotNone(receiver.active)
                companion.handle_command(dict(action='cancelFileReceive', transferToken=old))
                self.assertEqual('CANCELLED', json.loads(stream.getvalue())['status'])
                self.assertEqual('cancelled', events[-1]['fileReceiveStatus'])
                self.assertEqual('', events[-1]['fileReceiveToken'])
                receiver.process(init, True)  # A peer may reuse a wire ID for a new transfer.
                companion.handle_command(dict(action='cancelFileReceive', transferToken=old))
                self.assertEqual('first', receiver.active['id'])
                self.assertNotEqual(old, companion.receive_token(receiver))
                receiver.pause(); companion.active_id = None; companion.emit_state()
                token = events[-1]['fileReceiveToken']
                before = stream.getvalue()
                companion.handle_command(dict(action='cancelFileReceive', transferToken=token))
                self.assertIsNone(receiver.active)
                self.assertEqual(before, stream.getvalue())
            finally:
                companion.active_stream = None
                companion.close()


    def test_incoming_progress_is_coalesced_and_final_state_is_immediate(self):
        with tempfile.TemporaryDirectory() as temp:
            events = []
            companion = Companion(temp, host='127.0.0.1', port=0, files=True, event_sink=events.append)
            receiver = FileReceiver(Path(temp) / 'ReceivedFiles')
            companion.receivers.add(receiver)
            data = b'x' * (CHUNK_SIZE * 128)
            init = dict(type='FILE_INIT', transferId='progress', fileName='🌉 progress.bin', fileSize=len(data), sha256Checksum=hashlib.sha256(data).hexdigest(), chunkSize=CHUNK_SIZE)
            try:
                acks = [receiver.process(init, True)]
                companion.receive_progress(receiver, clock=lambda: 0)
                for index in range(128):
                    acks.append(receiver.process(dict(type='FILE_CHUNK', transferId='progress', chunkIndex=index, offset=index*CHUNK_SIZE, totalChunks=128, chunkLength=CHUNK_SIZE, dataBase64=base64.b64encode(data[index*CHUNK_SIZE:(index+1)*CHUNK_SIZE]).decode()), True))
                    companion.receive_progress(receiver, clock=lambda: 0)
                self.assertEqual(129, len(acks))
                self.assertEqual(2, len(events))
                self.assertEqual('receiving', events[0]['fileReceiveStatus'])
                self.assertEqual(('completed',len(data),len(data)),(events[-1]['fileReceiveStatus'],events[-1]['receivedBytes'],events[-1]['receivedFileSize']))
                receiver.process(dict(init, transferId='paused'), True)
                receiver.pause(); companion.receive_progress(receiver, clock=lambda: 0)
                self.assertEqual('paused',events[-1]['fileReceiveStatus'])
                receiver.abort(); companion.receive_progress(receiver, clock=lambda: 0)
                self.assertEqual('failed',events[-1]['fileReceiveStatus'])
            finally: companion.close()

    def test_default_permission_off_strict_toggle_and_revocation(self):
        with tempfile.TemporaryDirectory() as temp:
            companion = Companion(temp, host='127.0.0.1', port=0, event_sink=lambda _: None)
            try:
                self.assertFalse(companion.files)
                with self.assertRaises(ValueError): companion.handle_command(dict(action='setFilesEnabled', enabled='yes'))
                companion.handle_command(dict(action='setFilesEnabled', enabled=True))
                receiver = FileReceiver(Path(temp) / 'ReceivedFiles')
                companion.receivers.add(receiver)
                receiver.process(dict(type='FILE_INIT', transferId='test', fileName='test.txt', fileSize=1,
                    chunkSize=CHUNK_SIZE, sha256Checksum=hashlib.sha256(b'x').hexdigest()), True)
                companion.handle_command(dict(action='setFilesEnabled', enabled=False))
                self.assertIsNone(receiver.active)
                self.assertFalse(list(receiver.directory.iterdir()))
            finally: companion.close()
    def test_stale_partials_are_removed_and_verified_files_survive_restart(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / 'ReceivedFiles'; folder.mkdir()
            (folder / '.incoming-stale').write_bytes(b'partial')
            (folder / 'verified.txt').write_bytes(b'verified')
            companion = Companion(temp, host='127.0.0.1', port=0, event_sink=lambda _: None)
            try:
                self.assertEqual(['verified.txt'], [p.name for p in folder.iterdir()])
                receiver = FileReceiver(folder); companion.receivers.add(receiver)
                receiver.process(dict(type='FILE_INIT', transferId='test', fileName='test.txt', fileSize=1,
                    chunkSize=CHUNK_SIZE, sha256Checksum=hashlib.sha256(b'x').hexdigest()), True)
                companion.close()
                self.assertIsNone(receiver.active)
                self.assertEqual(['verified.txt'], [p.name for p in folder.iterdir()])
            finally: companion.close()

if __name__ == '__main__': unittest.main()

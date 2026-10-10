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

import base64
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from resource_limits import ReceiveQuota, StorageLimit, WorkBudget, RateLimited, QUOTAS
from file_receiver import FileReceiver, CHUNK_SIZE
from transfer_state import TransferState

class ResourceLimitsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='quota 世界 ')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.directory = self.root / 'received'
        self.quota = ReceiveQuota(self.directory, self.root / 'storage.json')
        self.quota.limit = 3
        self.receiver = FileReceiver(self.directory, quota=self.quota, state=TransferState(self.root / 'checkpoint.json', ['fixture']))
        self.addCleanup(self.receiver.abort)

    def offer(self, identity, data):
        return dict(type='FILE_INIT', transferId=identity, fileName='世界.txt', fileSize=len(data), chunkSize=CHUNK_SIZE, sha256Checksum=hashlib.sha256(data).hexdigest())

    def receive(self, identity, data):
        ack = self.receiver.process(self.offer(identity, data), True)
        if data and ack['status'] == 'READY':
            ack = self.receiver.process(dict(type='FILE_CHUNK', transferId=identity, offset=0, chunkIndex=0,totalChunks=1, chunkLength=len(data), dataBase64=base64.b64encode(data).decode()), True)
        return ack

    def test_sequential_quota_rejection_retains_verified_files_and_recovers_after_manual_move(self):
        self.assertEqual(self.receive('one', b'abc')['status'], 'COMPLETED')
        before = self.receiver.last_saved.read_bytes()
        self.assertEqual(self.receive('two', b'd')['status'], 'STORAGE_FULL')
        self.assertIsNone(self.receiver.active)
        self.assertEqual(self.receiver.last_saved.read_bytes(), before)
        self.receiver.last_saved.rename(self.root / 'export.txt')
        self.assertEqual(self.receive('two', b'd')['status'], 'COMPLETED')
        self.assertEqual((self.root / 'export.txt').read_bytes(), before)

    def test_empty_file_cap_and_restart_quota_configuration(self):
        self.directory.mkdir()
        for i in range(100): (self.directory / ('verified-'+str(i))).touch()
        self.assertEqual(self.receive('empty', b'')['status'], 'STORAGE_FULL')
        self.quota.set_limit(QUOTAS[0])
        self.assertEqual(ReceiveQuota(self.directory, self.root / 'storage.json').limit, QUOTAS[0])
        with self.assertRaises(ValueError): self.quota.set_limit(True)
        with self.assertRaises(ValueError): self.quota.set_limit(1)
        self.assertEqual(len(list(self.directory.iterdir())), 100)

    def test_reservations_near_full_disk_revocation_and_no_partial_leak(self):
        self.quota.limit = 10
        self.receiver.reservations = lambda: 9
        self.assertEqual(self.receive('quota', b'ab')['status'], 'STORAGE_FULL')
        self.receiver.reservations = lambda: 0
        with patch('file_receiver.shutil.disk_usage') as disk:
            disk.return_value.free = 0
            self.assertEqual(self.receive('disk', b'ab')['status'], 'STORAGE_FULL')
        self.assertEqual(self.receiver.process(self.offer('permission', b'ab'), False)['status'], 'REJECTED')
        self.assertEqual(self.receiver.process(self.offer('active', b'ab'), True)['status'], 'READY')
        self.receiver.abort()
        self.assertEqual(list(self.directory.glob('.incoming-*')), [])

    def test_symlink_and_unknown_folder_entries_fail_closed_without_touching_target(self):
        self.directory.mkdir()
        target = self.root / 'saved.txt'; target.write_text('saved')
        (self.directory / 'verified-link').symlink_to(target)
        with self.assertRaises(StorageLimit): self.quota.require_space(0)
        self.assertEqual(target.read_text(), 'saved')

    def test_budget_flood_bounds_refill_and_reconnect_does_not_reset(self):
        now = [0.0]; budget = WorkBudget(lambda: now[0])
        for _ in range(10): budget.message('CLIPBOARD')
        with self.assertRaises(RateLimited): budget.message('CLIPBOARD')
        # A new stream still uses this same companion-owned budget.
        with self.assertRaises(RateLimited): budget.message('CLIPBOARD')
        now[0] += .5; budget.message('CLIPBOARD')
        for _ in range(60): budget.message('NOTIFICATION')
        with self.assertRaises(RateLimited): budget.message('NOTIFICATION')
        now[0] += 1; budget.message('NOTIFICATION')
        for _ in range(1024): budget.frame(1)
        with self.assertRaises(RateLimited): budget.frame(1)
        now[0] += 1; budget.frame(1)
        now[0] += 3
        for _ in range(32): budget.frame(1024 * 1024)
        with self.assertRaises(RateLimited): budget.frame(1024 * 1024)

    def test_byte_budget_rejects_before_json_parsing(self):
        import io
        from macbridge import read_frame
        budget = WorkBudget(lambda: 0)
        for _ in range(32): budget.frame(1024 * 1024)
        with patch('macbridge.load_object') as parse:
            with self.assertRaises(RateLimited): read_frame(io.BytesIO(b'{"type":"CLIPBOARD"}\n'), budget)
            parse.assert_not_called()

    def test_ordinary_chunks_heartbeats_cancellation_and_unicode_are_not_semantically_throttled(self):
        now = [0.0]; budget = WorkBudget(lambda: now[0])
        for _ in range(2000):
            now[0] += .01
            budget.frame(90000); budget.message('FILE_CHUNK'); budget.message('FILE_CANCEL')
        budget.message('HEARTBEAT')
        self.assertEqual(self.receive('unicode', '世'.encode())['status'], 'COMPLETED')

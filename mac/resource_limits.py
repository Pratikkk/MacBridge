"""Persistent receive quotas and reconnect-resistant, monotonic work budgets."""
import os
from pathlib import Path
import stat
import threading
import time
from bounded_json import load_object
from transfer_state import TransferState

MIB = 1024 * 1024
QUOTAS = (250 * MIB, 500 * MIB, 1000 * MIB)
MAX_RECEIVED_FILES = 100

class StorageLimit(ValueError):
    pass

class ReceiveQuota:
    def __init__(self, directory, settings):
        self.directory = Path(directory)
        self.settings = Path(settings)
        self.limit = QUOTAS[1]
        try:
            if not self.settings.is_symlink() and self.settings.stat().st_size <= 4096:
                value = load_object(self.settings.read_text()).get('limit')
                if type(value) is int and value in QUOTAS: self.limit = value
        except (OSError, ValueError): pass

    def set_limit(self, value):
        if type(value) is not int or value not in QUOTAS: raise ValueError('Unsupported storage limit')
        TransferState(self.settings, []).save(limit=value)
        self.limit = value

    def usage(self):
        if not self.directory.exists(): return 0, 0
        if self.directory.is_symlink(): raise StorageLimit('Unsafe receive folder')
        total = count = 0
        with os.scandir(self.directory) as entries:
            for index, entry in enumerate(entries):
                if index >= 10000: raise StorageLimit('Receive folder needs manual cleanup')
                info = entry.stat(follow_symlinks=False)
                if not stat.S_ISREG(info.st_mode): raise StorageLimit('Receive folder contains an unsupported entry')
                total += info.st_size
                count += 1
        return total, count

    def require_space(self, size, reservations=0):
        used, count = self.usage()
        if used + reservations + size > self.limit or count >= MAX_RECEIVED_FILES:
            raise StorageLimit('Received storage limit reached. Move files out of Received Files or increase the limit in Settings → Storage.')

class RateLimited(ValueError):
    pass

class WorkBudget:
    """Shared by all authenticated sessions; reconnect does not reset tokens."""
    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.lock = threading.Lock()
        self.buckets = {}

    def take(self, name, amount, rate, capacity):
        with self.lock:
            now = self.clock()
            tokens, previous = self.buckets.get(name, (capacity, now))
            tokens = min(capacity, tokens + max(0, now - previous) * rate)
            self.buckets[name] = (max(0, tokens - amount), now)
            if amount > tokens: raise RateLimited('Peer request limit exceeded. Wait before reconnecting.')

    def frame(self, size):
        self.take('frames', 1, 512, 1024)
        self.take('bytes', size, 16 * MIB, 32 * MIB)

    def message(self, kind):
        limits = {'CLIPBOARD': (2, 10), 'NOTIFICATION': (30, 60), 'NOTIFICATION_ACTION': (10, 20),
                  'FILE_INIT': (1, 4), 'HEARTBEAT': (10, 20)}
        if kind in limits: self.take(kind, 1, *limits[kind])

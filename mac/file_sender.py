"""Send one selected regular file using bounded snapshots and verified acknowledgements."""
import base64
import hashlib
import os
from pathlib import Path
import queue
import stat
import tempfile
import threading
import uuid

from file_receiver import CHUNK_SIZE, MAX_FILE_SIZE


class FileSender:
    def __init__(self, directory, send, report, timeout=15):
        self.directory = Path(directory)
        self.send = send
        self.report = report
        self.timeout = timeout
        self.transfer_id = 'mac-file-v1-' + str(uuid.uuid4())
        self.busy = False
        self.result = "idle"
        self.sent_bytes = 0
        self.file_size = 0
        self.cancelled = threading.Event()
        self.finished = threading.Event()
        self.acks = queue.Queue(maxsize=4)

    def start(self, path):
        if self.busy or self.finished.is_set():
            raise ValueError('A file is already being sent')
        self.busy = True
        self.result = "preparing"
        threading.Thread(target=self.run, args=(path,), daemon=True).start()

    def cancel(self):
        self.cancelled.set()
        try:
            self.acks.put_nowait(None)
        except queue.Full:
            pass

    def handle_ack(self, message):
        if self.busy and message.get('transferId') == self.transfer_id:
            try:
                self.acks.put_nowait(message)
            except queue.Full:
                pass

    def check(self):
        if self.cancelled.is_set():
            raise ValueError('Cancelled')

    def confirm(self, size, status, checksum=None):
        self.check()
        ack = self.acks.get(timeout=self.timeout)
        self.check()
        if not isinstance(ack, dict) or ack.get('status') != status or type(ack.get('receivedBytes')) is not int or ack['receivedBytes'] != size:
            raise ValueError('Phone rejected transfer')
        if checksum is not None and ack.get('sha256Checksum') != checksum:
            raise ValueError('Phone checksum did not match')

    def run(self, path):
        temporary = None
        offered = complete = False
        try:
            self.check()
            self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
            if self.directory.is_symlink():
                raise ValueError('Invalid snapshot directory')
            self.directory.chmod(0o700)
            # Nonblocking open + fstat rejects devices, directories and named pipes.
            descriptor = os.open(path, os.O_RDONLY | os.O_NONBLOCK)
            with os.fdopen(descriptor, 'rb') as source:
                metadata = os.fstat(source.fileno())
                if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > MAX_FILE_SIZE:
                    raise ValueError('Choose a regular file up to 100 MB')
                digest = hashlib.sha256()
                with tempfile.NamedTemporaryFile(prefix='.outgoing-', dir=self.directory, delete=False) as snapshot:
                    temporary = Path(snapshot.name)
                    while True:
                        self.check()
                        data = source.read(CHUNK_SIZE)
                        if not data:
                            break
                        self.file_size += len(data)
                        if self.file_size > MAX_FILE_SIZE:
                            raise ValueError('File grew beyond the limit')
                        snapshot.write(data)
                        digest.update(data)
            self.check()
            checksum = digest.hexdigest()
            name = ''.join(c for c in Path(path).name if c.isprintable())[:100] or 'document'
            self.result = 'sending'
            self.report('Sending file to phone')
            self.send(dict(type='FILE_INIT', transferId=self.transfer_id, fileName=name,
                fileSize=self.file_size, sha256Checksum=checksum, chunkSize=CHUNK_SIZE,
                mimeType='application/octet-stream'))
            offered = True
            self.confirm(0, 'COMPLETED' if self.file_size == 0 else 'READY', checksum if self.file_size == 0 else None)
            with temporary.open('rb') as snapshot:
                index = 0
                total = (self.file_size + CHUNK_SIZE - 1) // CHUNK_SIZE
                while self.sent_bytes < self.file_size:
                    self.check()
                    data = snapshot.read(min(CHUNK_SIZE, self.file_size - self.sent_bytes))
                    if not data:
                        raise ValueError('Snapshot unavailable')
                    self.send(dict(type='FILE_CHUNK', transferId=self.transfer_id, chunkIndex=index,
                        totalChunks=total, offset=self.sent_bytes, chunkLength=len(data), dataBase64=base64.b64encode(data).decode('ascii')))
                    count = self.sent_bytes + len(data)
                    final = count == self.file_size
                    self.confirm(count, 'COMPLETED' if final else 'IN_PROGRESS', checksum if final else None)
                    self.sent_bytes = count
                    index += 1
                    self.report('Sending file to phone')
            complete = True
        except (OSError, ValueError, queue.Empty, TypeError):
            pass  # Status must never disclose file paths or contents.
        finally:
            if offered and not complete:
                try:
                    self.send(dict(type='FILE_CANCEL', transferId=self.transfer_id))
                except (OSError, ValueError):
                    pass
            if temporary:
                try:
                    temporary.unlink(missing_ok=True)
                except OSError:
                    pass
            self.result = 'completed' if complete else ('cancelled' if self.cancelled.is_set() else 'failed')
            self.busy = False
            self.report('File received and verified by phone. Use Share → Files → Save As on Android.' if complete else
                ('File sending cancelled' if self.cancelled.is_set() else 'File not confirmed. Check connection, file access, free space and Android File sharing, then try again.'))
            self.finished.set()

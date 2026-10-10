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
import time

from file_receiver import CHUNK_SIZE, MAX_FILE_SIZE, RESUME_TTL_SECONDS
from transfer_state import private_file, file_digest, metadata


class TransferDisconnected(Exception):
    pass


class FileSender:
    def __init__(self, directory, send, report, timeout=15, progress_clock=time.monotonic, state=None):
        self.directory = Path(directory)
        self.send = send
        self.report = report
        self.timeout = timeout
        self.progress_clock = progress_clock
        self.next_progress_report = 0
        self.transfer_id = 'mac-file-v1-' + str(uuid.uuid4())
        self.busy = False
        self.result = "idle"
        self.sent_bytes = 0
        self.file_size = 0
        self.cancelled = threading.Event()
        self.finished = threading.Event()
        self.acks = queue.Queue(maxsize=4)
        self.snapshot = None
        self.checksum = self.name = None
        self.paused_at = 0
        self.interrupted = False
        self.state = state
        self.deadline = 0
        if state:
            self.restore()

    def restore(self):
        value = self.state.load().get('outgoing')
        try:
            metadata(value, MAX_FILE_SIZE)
            if not time.time() < value['expires'] <= time.time() + RESUME_TTL_SECONDS + 1:
                raise ValueError('Expired checkpoint')
            snapshot = private_file(self.directory, value['snapshot'], '.outgoing-')
            if snapshot.stat().st_size != value['size'] or file_digest(snapshot).hexdigest() != value['checksum']:
                raise ValueError('Corrupt snapshot')
            self.transfer_id, self.name, self.file_size, self.checksum = value['id'], value['name'], value['size'], value['checksum']
            self.snapshot, self.deadline = snapshot, value['expires']
            self.paused_at = time.monotonic() - (RESUME_TTL_SECONDS - (self.deadline - time.time()))
            self.result = 'paused'
            self.finished.set()
        except (OSError, ValueError, TypeError, KeyError):
            self.state.clear()

    def checkpoint(self):
        if self.state and self.snapshot:
            self.state.save(outgoing=dict(id=self.transfer_id, name=self.name, size=self.file_size,
                checksum=self.checksum, snapshot=self.snapshot.name, expires=self.deadline))

    def start(self, path):
        if self.busy or self.finished.is_set():
            raise ValueError('A file is already being sent')
        self.busy = True
        self.result = "preparing"
        threading.Thread(target=self.run, args=(path,), daemon=True).start()

    def resume(self, send):
        if self.busy or not self.finished.is_set() or self.result != "paused":
            raise ValueError("No paused transfer")
        if time.monotonic() - self.paused_at > RESUME_TTL_SECONDS:
            self.cancel()
            raise ValueError("Paused transfer expired")
        self.send = send
        self.cancelled.clear()
        self.finished.clear()
        self.interrupted = False
        self.acks = queue.Queue(maxsize=4)
        self.busy = True
        threading.Thread(target=self.run, args=(None,), daemon=True).start()

    def interrupt(self):
        self.interrupted = True
        self.cancelled.set()
        try:
            self.acks.put_nowait(None)
        except queue.Full:
            pass

    def cancel(self):
        self.interrupted = False
        if not self.busy and self.snapshot:
            self.snapshot.unlink(missing_ok=True)
            self.snapshot = None
            if self.state:
                self.state.clear()
            self.result = "cancelled"
            self.report("File sending cancelled")
        self.cancelled.set()
        try:
            self.acks.put_nowait(None)
        except queue.Full:
            pass

    def handle_ack(self, message):
        if message.get('transferId') == self.transfer_id and message.get('status') == 'CANCELLED' and (self.busy or self.result == 'paused'):
            self.cancel()
            return
        if self.busy and message.get('transferId') == self.transfer_id:
            try:
                self.acks.put_nowait(message)
            except queue.Full:
                pass

    def check(self):
        if self.cancelled.is_set():
            raise ValueError('Cancelled')

    def report_progress(self, force=False):
        now = self.progress_clock()
        if force or now >= self.next_progress_report:
            self.next_progress_report = now + .25
            if self.state and self.snapshot:
                self.deadline = time.time() + RESUME_TTL_SECONDS
                self.checkpoint()
            self.report('Sending file to phone')

    def transmit(self, message):
        try:
            self.send(message)
        except OSError as error:
            raise TransferDisconnected() from error

    def confirm(self, size, status, checksum=None):
        self.check()
        ack = self.acks.get(timeout=self.timeout)
        self.check()
        if not isinstance(ack, dict) or ack.get('status') != status or type(ack.get('receivedBytes')) is not int or ack['receivedBytes'] != size:
            raise ValueError('Phone rejected transfer')
        if checksum is not None and ack.get('sha256Checksum') != checksum:
            raise ValueError('Phone checksum did not match')

    def run(self, path):
        temporary = self.snapshot if path is None else None
        offered = complete = paused = False
        try:
            self.check()
            self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
            if self.directory.is_symlink():
                raise ValueError('Invalid snapshot directory')
            self.directory.chmod(0o700)
            # Nonblocking open + fstat rejects devices, directories and named pipes.
            if path is not None:
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
                self.snapshot, self.checksum, self.name = temporary, checksum, name
                with temporary.open('rb') as durable:
                    os.fsync(durable.fileno())
                self.deadline = time.time() + RESUME_TTL_SECONDS
                self.checkpoint()
            else:
                checksum, name = self.checksum, self.name
                if temporary is None or temporary.is_symlink() or temporary.stat().st_size != self.file_size:
                    raise ValueError("Snapshot unavailable")
                digest = hashlib.sha256()
                with temporary.open("rb") as source:
                    while data := source.read(CHUNK_SIZE):
                        self.check()
                        digest.update(data)
                if digest.hexdigest() != checksum:
                    raise ValueError("Snapshot changed")
            self.result = 'sending'
            self.report_progress(force=True)
            self.transmit(dict(type='FILE_INIT', transferId=self.transfer_id, fileName=name,
                fileSize=self.file_size, sha256Checksum=checksum, chunkSize=CHUNK_SIZE,
                mimeType='application/octet-stream', resume=path is None))
            offered = True
            ack = self.acks.get(timeout=self.timeout)
            self.check()
            if not isinstance(ack, dict) or type(ack.get('receivedBytes')) is not int:
                raise ValueError('Invalid acknowledgement')
            offset = ack['receivedBytes']
            if ack.get('status') == 'COMPLETED' and offset == self.file_size and ack.get('sha256Checksum') == checksum:
                self.sent_bytes = offset
            elif ack.get('status') == 'READY' and 0 <= offset < self.file_size and offset % CHUNK_SIZE == 0 and (path is None or offset == 0):
                self.sent_bytes = offset
            else:
                raise ValueError('Phone rejected transfer')
            with temporary.open('rb') as snapshot:
                snapshot.seek(self.sent_bytes)
                index = self.sent_bytes // CHUNK_SIZE
                total = (self.file_size + CHUNK_SIZE - 1) // CHUNK_SIZE
                while self.sent_bytes < self.file_size:
                    self.check()
                    data = snapshot.read(min(CHUNK_SIZE, self.file_size - self.sent_bytes))
                    if not data:
                        raise ValueError('Snapshot unavailable')
                    self.transmit(dict(type='FILE_CHUNK', transferId=self.transfer_id, chunkIndex=index,
                        totalChunks=total, offset=self.sent_bytes, chunkLength=len(data), dataBase64=base64.b64encode(data).decode('ascii')))
                    count = self.sent_bytes + len(data)
                    final = count == self.file_size
                    self.confirm(count, 'COMPLETED' if final else 'IN_PROGRESS', checksum if final else None)
                    self.sent_bytes = count
                    index += 1
                    if not final:
                        self.report_progress()
            complete = True
        except (TransferDisconnected, queue.Empty):
            paused = self.snapshot is not None and (not self.cancelled.is_set() or self.interrupted)
        except (OSError, ValueError, TypeError):
            paused = self.interrupted and self.snapshot is not None
            pass  # Status must never disclose file paths or contents.
        finally:
            if offered and not complete and not paused:
                try:
                    self.transmit(dict(type='FILE_CANCEL', transferId=self.transfer_id))
                except (TransferDisconnected, ValueError):
                    pass
            if temporary and not paused:
                try:
                    temporary.unlink(missing_ok=True)
                except OSError:
                    pass
            if not paused:
                self.snapshot = None
                if self.state:
                    self.state.clear()
            elif self.state:
                try:
                    self.deadline = time.time() + RESUME_TTL_SECONDS
                    self.checkpoint()
                except OSError:
                    paused = False
                    temporary.unlink(missing_ok=True)
                    self.snapshot = None
            self.paused_at = time.monotonic() if paused else 0
            self.result = 'paused' if paused else ('completed' if complete else ('cancelled' if self.cancelled.is_set() else 'failed'))
            self.busy = False
            self.report('File received and verified by phone. Use Share → Files → Save As on Android.' if complete else
                ('Transfer paused. Reconnect the same phone and choose Resume File Sending within 10 minutes.' if paused else 'File sending cancelled' if self.cancelled.is_set() else 'File not confirmed. Check connection, file access, free space and Android File sharing, then try again.'))
            self.finished.set()

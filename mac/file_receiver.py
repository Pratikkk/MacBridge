"""Bounded, sequential phone-to-Mac transfers; publish only verified files."""
import base64
import hashlib
import os
from pathlib import Path
import re
import shutil
import tempfile
import uuid
import time
from transfer_state import private_file, file_digest, metadata

CHUNK_SIZE = 65536
RESUME_TTL_SECONDS = 600
MAX_FILE_SIZE = 100 * 1024 * 1024


class FileReceiver:
    def __init__(self, directory, state=None):
        self.directory = Path(directory)
        self.active = None
        self.last_saved = None
        self.completed = {}
        self.cancelled = set()
        self.paused_at = None
        self.state = state
        self.deadline = 0
        self.next_checkpoint = 0
        self.result = "idle"
        self.received_bytes = self.file_size = 0
        self.receipt_files = {}
        if state:
            self.restore()

    def restore(self):
        saved = self.state.load()
        stored_receipts = saved.get('receipts', [])
        for receipt in (stored_receipts if isinstance(stored_receipts, list) else [])[:64]:
            try:
                metadata(receipt, MAX_FILE_SIZE)
                if not time.time() < receipt['expires'] <= time.time() + RESUME_TTL_SECONDS + 1:
                    continue
                destination = self.directory / receipt['destination']
                if destination.name != receipt['destination'] or destination.is_symlink() or not destination.name.startswith('verified-'):
                    continue
                path = destination if destination.is_file() else private_file(self.directory, receipt['temporary'], '.incoming-')
                if path.stat().st_size != receipt['size'] or (path != destination and file_digest(path).hexdigest() != receipt['checksum']):
                    continue
                if path != destination:
                    path.replace(destination)
                self.completed[receipt['id']] = (receipt['size'], receipt['name'], receipt['checksum'])
                self.receipt_files[receipt['id']] = dict(receipt, temporary=None)
            except (OSError, ValueError, TypeError, KeyError):
                continue
        value = saved.get('incoming')
        try:
            metadata(value, MAX_FILE_SIZE)
            if value['id'] in self.completed or not time.time() < value['expires'] <= time.time() + RESUME_TTL_SECONDS + 1:
                raise ValueError('Expired checkpoint')
            offset = value['offset']
            if type(offset) is not int or not 0 <= offset < value['size'] or offset % CHUNK_SIZE:
                raise ValueError('Invalid checkpoint offset')
            temporary = private_file(self.directory, value['temporary'], '.incoming-')
            digest = file_digest(temporary, offset)
            if digest.hexdigest() != value['prefix']:
                raise ValueError('Corrupt partial')
            destination = self.directory / value['destination']
            if destination.name != value['destination'] or not destination.name.startswith('verified-') or destination.exists():
                raise ValueError('Invalid destination')
            stream = temporary.open('r+b')
            stream.truncate(offset)
            stream.seek(offset)
            self.active = dict(value, index=offset // CHUNK_SIZE, temporary=temporary, destination=destination, stream=stream, digest=digest)
            self.deadline = value['expires']
            self.paused_at = time.monotonic() - (RESUME_TTL_SECONDS - (self.deadline - time.time()))
            self.result = "paused"
            self.received_bytes, self.file_size = offset, value["size"]
        except (OSError, ValueError, TypeError, KeyError):
            pass
        self.checkpoint()

    def checkpoint(self, force=True):
        if not self.state or (not force and time.monotonic() < self.next_checkpoint):
            return
        self.next_checkpoint = time.monotonic() + .25
        incoming = None
        if self.active:
            value = self.active
            value['stream'].flush()
            os.fsync(value['stream'].fileno())
            incoming = {key: value[key] for key in ('id', 'name', 'size', 'checksum', 'offset')}
            incoming.update(temporary=value['temporary'].name, destination=value['destination'].name,
                prefix=value['digest'].copy().hexdigest(), expires=self.deadline)
        self.receipt_files = dict(list((key, value) for key, value in self.receipt_files.items() if value['expires'] > time.time())[-64:])
        self.completed = {key: value for key, value in self.completed.items() if not self.state or key in self.receipt_files}
        self.state.save(incoming=incoming, receipts=list(self.receipt_files.values())[-64:])

    def suspend(self):
        if self.active:
            self.pause()
            self.active['stream'].close()
            self.active = None

    def pause(self):
        if self.active:
            self.active["stream"].flush()
            if self.paused_at is None:
                self.paused_at = time.monotonic()
                self.deadline = time.time() + RESUME_TTL_SECONDS
            self.result = "paused"
            self.checkpoint()

    def abort(self):
        self.paused_at = None
        if self.active:
            value, self.active = self.active, None
            self.result = "failed"
            try:
                value['stream'].close()
            except OSError:
                pass
            try:
                value['temporary'].unlink(missing_ok=True)
            except OSError:
                pass
        self.checkpoint()

    def cancel(self, transfer_id):
        # A stale UI action must never discard a different or completed transfer.
        if not self.active or self.active['id'] != transfer_id:
            return None
        count = self.active['offset']
        self.cancelled.add(transfer_id)
        if len(self.cancelled) > 64:
            self.cancelled.pop()
        self.abort()
        self.result = 'cancelled'
        return self.ack(transfer_id, 'CANCELLED', count)

    def discard(self):
        self.abort()
        self.completed.clear()
        self.receipt_files.clear()
        if self.state:
            self.state.clear()

    def ack(self, transfer_id, status, count=0, checksum=None):
        result = dict(type='FILE_ACK', transferId=transfer_id, status=status, receivedBytes=count)
        if checksum is not None:
            result['sha256Checksum'] = checksum
        return result

    def process(self, message, enabled):
        transfer_id = message.get('transferId')
        if not isinstance(transfer_id, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', transfer_id):
            raise ValueError('Invalid transfer identity')
        if not enabled:
            self.abort()
            return self.ack(transfer_id, 'REJECTED')
        try:
            kind = message.get('type')
            if kind == 'FILE_CANCEL':
                if len(self.cancelled) >= 64:
                    self.cancelled.pop()
                self.cancelled.add(transfer_id)
                self.completed.pop(transfer_id, None)
                self.receipt_files.pop(transfer_id, None)
                if self.active and self.active['id'] == transfer_id:
                    self.abort()
                    self.result = 'cancelled'
                self.checkpoint()
                return self.ack(transfer_id, 'CANCELLED')
            if kind == 'FILE_INIT':
                if self.paused_at is not None and time.monotonic() - self.paused_at > RESUME_TTL_SECONDS:
                    self.abort()
                if message.get('resume') is True:
                    if transfer_id in self.cancelled:
                        return self.ack(transfer_id, 'REJECTED')
                    receipt = self.completed.get(transfer_id)
                    if self.state and self.receipt_files.get(transfer_id, {}).get('expires', 0) <= time.time():
                        receipt = None
                    if message.get('chunkSize') == CHUNK_SIZE and receipt and receipt[:3] == (message.get('fileSize'), message.get('fileName'), message.get('sha256Checksum')):
                        if self.state:
                            record = self.receipt_files[transfer_id]
                            path = private_file(self.directory, record['destination'], 'verified-')
                            if path.stat().st_size != receipt[0] or file_digest(path).hexdigest() != receipt[2]:
                                self.completed.pop(transfer_id, None)
                                self.receipt_files.pop(transfer_id, None)
                                self.checkpoint()
                                return self.ack(transfer_id, 'REJECTED')
                        if not self.active:
                            self.result = 'completed'
                            self.received_bytes = self.file_size = receipt[0]
                        return self.ack(transfer_id, 'COMPLETED', receipt[0], receipt[2])
                    if self.active and self.active['id'] == transfer_id:
                        value = self.active
                        if (message.get('fileSize'), message.get('fileName'), message.get('sha256Checksum'), message.get('chunkSize')) != (value['size'], value['name'], value['checksum'], CHUNK_SIZE):
                            return self.ack(transfer_id, 'REJECTED')
                        value['stream'].flush()
                        if value['temporary'].stat().st_size != value['offset']:
                            self.abort()
                            return self.ack(transfer_id, 'FAILED')
                        value['digest'] = hashlib.sha256()
                        with value['temporary'].open('rb') as prefix:
                            while data := prefix.read(CHUNK_SIZE):
                                value['digest'].update(data)
                        self.paused_at = None
                        self.result = "receiving"
                        return self.ack(transfer_id, 'READY', value['offset'])
                    return self.ack(transfer_id, 'REJECTED')
                if self.active:
                    return self.ack(transfer_id, 'BUSY')
                size, name, digest = message.get('fileSize'), message.get('fileName'), message.get('sha256Checksum')
                if type(size) is not int or not 0 <= size <= MAX_FILE_SIZE:
                    raise ValueError('Invalid file size')
                if not isinstance(name, str) or not 1 <= len(name) <= 256:
                    raise ValueError('Invalid file name')
                if not isinstance(digest, str) or not re.fullmatch('[0-9a-f]{64}', digest):
                    raise ValueError('Invalid checksum')
                if message.get('chunkSize') != CHUNK_SIZE:
                    raise ValueError('Unsupported chunk size')
                safe = name.replace('\\', '/').split('/')[-1]
                safe = ''.join(c if c.isprintable() and c not in '/\\:' else '_' for c in safe).strip(' .')[:100] or 'download'
                self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
                if self.directory.is_symlink():
                    raise ValueError('Invalid receive directory')
                self.directory.chmod(0o700)
                if shutil.disk_usage(self.directory).free < size + 8 * 1024 * 1024:
                    raise OSError('Insufficient free space')
                stream = tempfile.NamedTemporaryFile(prefix='.incoming-', dir=self.directory, delete=False)
                os.chmod(stream.name, 0o600)
                self.active = dict(id=transfer_id, name=name, size=size, checksum=digest, offset=0, index=0,
                    stream=stream, temporary=Path(stream.name), digest=hashlib.sha256(),
                    destination=self.directory / ('verified-' + str(uuid.uuid4()) + '-' + safe))
                self.result = "receiving"
                self.received_bytes, self.file_size = 0, size
                self.deadline = time.time() + RESUME_TTL_SECONDS
                self.checkpoint()
                if size == 0:
                    return self.finish()
                return self.ack(transfer_id, 'READY')
            if kind != 'FILE_CHUNK' or not self.active or self.active['id'] != transfer_id:
                return self.ack(transfer_id, 'REJECTED')
            value = self.active
            expected = min(CHUNK_SIZE, value['size'] - value['offset'])
            for key, expected_value in [('offset', value['offset']), ('chunkIndex', value['index']),
                    ('totalChunks', (value['size'] + CHUNK_SIZE - 1) // CHUNK_SIZE), ('chunkLength', expected)]:
                if type(message.get(key)) is not int or message[key] != expected_value:
                    raise ValueError('Out of order or invalid chunk')
            encoded = message.get('dataBase64')
            if not isinstance(encoded, str) or len(encoded) > 87384:
                raise ValueError('Invalid chunk encoding')
            data = base64.b64decode(encoded, validate=True)
            if len(data) != expected:
                raise ValueError('Invalid chunk length')
            value['stream'].write(data)
            value['digest'].update(data)
            value['offset'] += len(data)
            self.received_bytes = value['offset']
            value['index'] += 1
            if value['offset'] == value['size']:
                return self.finish()
            self.deadline = time.time() + RESUME_TTL_SECONDS
            self.checkpoint(force=False)
            return self.ack(transfer_id, 'IN_PROGRESS', value['offset'])
        except (ValueError, OSError, TypeError):
            self.abort()
            self.result = "failed"
            return self.ack(transfer_id, 'FAILED')

    def finish(self):
        value = self.active
        digest = value['digest'].hexdigest()
        if value['offset'] != value['size'] or digest != value['checksum']:
            self.abort()
            return self.ack(value['id'], 'VERIFICATION_FAILED')
        value['stream'].flush()
        os.fsync(value['stream'].fileno())
        value['stream'].close()
        self.receipt_files[value['id']] = dict(id=value['id'], size=value['size'], name=value['name'], checksum=digest,
            destination=value['destination'].name, temporary=value['temporary'].name, expires=time.time() + RESUME_TTL_SECONDS)
        # Journal the verified publication before rename, so restart recovers a lost final ACK.
        self.active = None
        self.checkpoint()
        value['temporary'].replace(value['destination'])
        self.completed[value['id']] = (value['size'], value['name'], digest)
        self.receipt_files[value['id']]['temporary'] = None
        self.checkpoint()
        if len(self.completed) > 64:
            del self.completed[next(iter(self.completed))]
        self.last_saved = value['destination']
        self.result = 'completed'
        self.active = None
        return self.ack(value['id'], 'COMPLETED', value['size'], digest)

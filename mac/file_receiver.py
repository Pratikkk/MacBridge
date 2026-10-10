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

CHUNK_SIZE = 65536
RESUME_TTL_SECONDS = 600
MAX_FILE_SIZE = 100 * 1024 * 1024


class FileReceiver:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.active = None
        self.last_saved = None
        self.completed = {}
        self.cancelled = set()
        self.paused_at = None

    def pause(self):
        if self.active:
            self.active["stream"].flush()
            self.paused_at = time.monotonic()

    def abort(self):
        self.paused_at = None
        if self.active:
            value, self.active = self.active, None
            try:
                value['stream'].close()
            except OSError:
                pass
            try:
                value['temporary'].unlink(missing_ok=True)
            except OSError:
                pass

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
                if self.active and self.active['id'] == transfer_id:
                    self.abort()
                return self.ack(transfer_id, 'CANCELLED')
            if kind == 'FILE_INIT':
                if self.paused_at is not None and time.monotonic() - self.paused_at > RESUME_TTL_SECONDS:
                    self.abort()
                if message.get('resume') is True:
                    if transfer_id in self.cancelled:
                        return self.ack(transfer_id, 'REJECTED')
                    receipt = self.completed.get(transfer_id)
                    if message.get('chunkSize') == CHUNK_SIZE and receipt and receipt[:3] == (message.get('fileSize'), message.get('fileName'), message.get('sha256Checksum')):
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
                    destination=self.directory / (str(uuid.uuid4()) + '-' + safe))
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
            value['index'] += 1
            if value['offset'] == value['size']:
                return self.finish()
            return self.ack(transfer_id, 'IN_PROGRESS', value['offset'])
        except (ValueError, OSError, TypeError):
            self.abort()
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
        value['temporary'].replace(value['destination'])
        self.completed[value['id']] = (value['size'], value['name'], digest)
        if len(self.completed) > 64:
            del self.completed[next(iter(self.completed))]
        self.last_saved = value['destination']
        self.active = None
        return self.ack(value['id'], 'COMPLETED', value['size'], digest)

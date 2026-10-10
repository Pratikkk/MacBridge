"""Private, bounded atomic transfer checkpoints; no identity secrets are stored here."""
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile

LIMIT = 128 * 1024


class TransferState:
    def __init__(self, path, identity):
        self.path = Path(path)
        self.identity = list(identity)

    def load(self):
        try:
            if self.path.is_symlink() or self.path.stat().st_size > LIMIT:
                return {}
            value = json.loads(self.path.read_text())
            if value.get('version') != 1 or value.get('identity') != self.identity:
                return {}
            return value
        except (OSError, ValueError, TypeError, AttributeError):
            return {}

    def save(self, **values):
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if self.path.parent.is_symlink():
            raise ValueError('Invalid checkpoint directory')
        self.path.parent.chmod(0o700)
        data = json.dumps(dict(values, version=1, identity=self.identity), ensure_ascii=True).encode()
        if len(data) > LIMIT:
            raise ValueError('Oversized checkpoint')
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(dir=self.path.parent, prefix='.checkpoint-', delete=False) as stream:
                temporary = Path(stream.name)
                os.chmod(temporary, 0o600)
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            temporary.replace(self.path)
            descriptor = os.open(self.path.parent, os.O_RDONLY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        finally:
            if temporary:
                temporary.unlink(missing_ok=True)

    def clear(self):
        self.path.unlink(missing_ok=True)


def private_file(directory, name, prefix):
    if Path(directory).is_symlink():
        raise ValueError('Invalid private file directory')
    if not isinstance(name, str) or not name.startswith(prefix) or Path(name).name != name:
        raise ValueError('Invalid checkpoint path')
    path = Path(directory) / name
    if path.is_symlink() or not path.is_file():
        raise ValueError('Missing checkpoint file')
    return path


def file_digest(path, size=None):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        remaining = size
        while remaining is None or remaining:
            block = source.read(65536 if remaining is None else min(65536, remaining))
            if not block:
                if remaining:
                    raise ValueError('Truncated checkpoint file')
                break
            digest.update(block)
            if remaining is not None:
                remaining -= len(block)
    return digest


def metadata(value, maximum):
    if not isinstance(value, dict) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', value.get('id', '')):
        raise ValueError('Invalid transfer identity')
    if type(value.get('size')) is not int or not 0 <= value['size'] <= maximum:
        raise ValueError('Invalid checkpoint size')
    if not isinstance(value.get('name'), str) or not 1 <= len(value['name']) <= 256:
        raise ValueError('Invalid checkpoint name')
    if not isinstance(value.get('checksum'), str) or not re.fullmatch('[0-9a-f]{64}', value['checksum']):
        raise ValueError('Invalid checkpoint checksum')

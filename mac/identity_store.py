"""Fail-closed Keychain migration for the native app's existing TLS identity."""
import hashlib
import json
import os
import re
from pathlib import Path
import stat
import ssl
import subprocess
import tempfile

MAX_KEY_BYTES = 8192


class IdentityStorageError(ValueError):
    def __init__(self, message, account=None):
        super().__init__(message)
        self.account = account


class KeychainStore:
    def __init__(self, helper):
        self.helper = str(helper)

    def _run(self, operation, account, key=None):
        try:
            result = subprocess.run([self.helper, operation, account], input=key,
                                    capture_output=True, timeout=15)
        except (OSError, subprocess.SubprocessError) as error:
            raise IdentityStorageError('Keychain helper unavailable. Reinstall MacBridge and try again.') from error
        if result.returncode == 4:
            status = re.fullmatch(rb'KeychainStatus=(-?[0-9]{1,10})\n?', result.stderr[:64])
            detail = ' (macOS status ' + status.group(1).decode('ascii') + ')' if status else ''
            raise IdentityStorageError('Keychain access unavailable' + detail + '. Unlock your login keychain or use Allow Keychain Access to review access, then restart the companion.', account)
        if result.returncode not in (0, 3, 5):
            raise IdentityStorageError('Keychain operation failed. Your existing identity was not replaced.')
        if len(result.stdout) > MAX_KEY_BYTES:
            raise IdentityStorageError('Invalid Keychain identity size.')
        return result

    def read(self, account):
        result = self._run('read', account)
        if result.returncode == 3:
            return None
        if result.returncode != 0 or not result.stdout:
            raise IdentityStorageError('Could not read the existing Keychain identity.')
        return result.stdout

    def add(self, account, key):
        result = self._run('add', account, key)
        if result.returncode not in (0, 5):
            raise IdentityStorageError('Could not save the identity to Keychain.')


def public_key(key):
    if not key or len(key) > MAX_KEY_BYTES:
        raise IdentityStorageError('Invalid private identity size.')
    try:
        return subprocess.run(['openssl', 'pkey', '-pubout', '-outform', 'DER'], input=key,
                              check=True, capture_output=True, timeout=10).stdout
    except (OSError, subprocess.SubprocessError) as error:
        raise IdentityStorageError('Stored private key is invalid. Restore the existing identity; do not re-pair automatically.') from error


def read_legacy(path):
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except FileNotFoundError:
        return None
    except OSError as error:
        raise IdentityStorageError('Cannot safely read the existing identity file.') from error
    with os.fdopen(fd, 'rb') as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_KEY_BYTES or info.st_uid != os.getuid():
            raise IdentityStorageError('Unsafe existing identity file; migration stopped.')
        return source.read(MAX_KEY_BYTES + 1)


def load_keychain_identity(directory, cert, certificate_public_key, context, store):
    directory = Path(directory)
    legacy_path = directory / 'identity.key'
    marker = directory / 'identity-keychain.json'
    account = hashlib.sha256(certificate_public_key).hexdigest()
    if marker.is_symlink():
        raise IdentityStorageError('Unsafe Keychain migration record.')
    if marker.exists():
        try:
            if marker.stat().st_size > 1024 or json.loads(marker.read_text()) != {'version': 1, 'account': account}:
                raise ValueError()
        except (OSError, ValueError) as error:
            raise IdentityStorageError('Certificate identity changed. Restore the matching identity and Keychain record.') from error
    legacy = read_legacy(legacy_path)
    if legacy is not None and public_key(legacy) != certificate_public_key:
        raise IdentityStorageError('Existing key and certificate do not match. Migration stopped.')
    key = store.read(account)
    if key is None:
        if marker.exists() or legacy is None:
            raise IdentityStorageError('Existing Keychain identity is missing. Restore it; MacBridge will not rotate or fall back to a file key.')
        store.add(account, legacy)
        key = store.read(account)  # Read back before declaring migration durable.
    if key is None or public_key(key) != certificate_public_key:
        raise IdentityStorageError('Keychain key does not match the existing certificate. Migration stopped.')
    # OpenSSL needs a filename. TemporaryFile is unlinked immediately; /dev/fd
    # refers to this process's owner-only open descriptor, not a durable key file.
    with tempfile.TemporaryFile() as material:
        material.write(key)
        material.flush()
        material.seek(0)
        try:
            context.load_cert_chain(str(cert), '/dev/fd/' + str(material.fileno()))
        except (OSError, ssl.SSLError) as error:
            raise IdentityStorageError('Cannot load the existing TLS identity. Migration cleanup was not performed.') from error
    if not marker.exists():
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(mode='w', dir=directory, prefix='.keychain-', delete=False) as target:
                temporary = Path(target.name)
                json.dump({'version': 1, 'account': account}, target)
                target.flush()
                os.fsync(target.fileno())
            os.replace(temporary, marker)
        except OSError as error:
            raise IdentityStorageError('Could not save the Keychain migration record. Existing file key retained; free space or fix state-folder access and restart.') from error
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    if legacy is not None:
        # Detect a changed file before removing the legacy copy.
        current = read_legacy(legacy_path)
        if current is not None and current != legacy:
            raise IdentityStorageError('Identity file changed during migration. Cleanup stopped.')
        try:
            legacy_path.unlink(missing_ok=True)
        except OSError as error:
            raise IdentityStorageError('Identity is saved in Keychain, but the old key file could not be removed. Fix state-folder access and restart to finish cleanup.') from error

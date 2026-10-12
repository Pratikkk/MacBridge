"""Bounded, same-key certificate renewal; never creates/replaces private identity."""
from dataclasses import dataclass
import os
from pathlib import Path
import ssl
import stat
import subprocess
import tempfile
import time
from identity_store import IdentityStorageError, public_key

MAX_CERT_BYTES = 65536
WARNING_SECONDS = 30 * 86400


def run(*args, material):
    try:
        result = subprocess.run(['openssl', *args], input=material, capture_output=True, check=True, timeout=10)
        if len(result.stdout) > MAX_CERT_BYTES:
            raise ValueError()
        return result.stdout
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        raise IdentityStorageError('Could not read or renew the Mac certificate. Existing pairing has not been replaced.') from error


def read_certificate(path):
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(fd, 'rb') as source:
            info = os.fstat(source.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_size > MAX_CERT_BYTES:
                raise ValueError()
            data = source.read(MAX_CERT_BYTES + 1)
            if not data or len(data) > MAX_CERT_BYTES:
                raise ValueError()
            return data
    except (OSError, ValueError) as error:
        raise IdentityStorageError('Cannot safely read the existing Mac certificate. Restore it rather than re-pairing.') from error


def certificate_public(data):
    return run('pkey', '-pubin', '-outform', 'DER', material=run('x509', '-pubkey', '-noout', material=data))


@dataclass(frozen=True)
class CertificateInfo:
    starts: float
    expires: float

    def status(self, now=None):
        now = time.time() if now is None else now
        if now < self.starts:
            return 'notYetValid'
        if now >= self.expires:
            return 'expired'
        return 'expiring' if self.expires - now <= WARNING_SECONDS else 'valid'


def certificate_info(data):
    try:
        dates = run('x509', '-dates', '-noout', material=data).decode('ascii').splitlines()
        if len(dates) != 2 or not dates[0].startswith('notBefore=') or not dates[1].startswith('notAfter='):
            raise ValueError()
        starts, expires = (ssl.cert_time_to_seconds(line.split('=', 1)[1]) for line in dates)
        if starts >= expires:
            raise ValueError()
        return CertificateInfo(starts, expires)
    except (ValueError, UnicodeError) as error:
        raise IdentityStorageError('Invalid Mac certificate dates. Restore the existing identity certificate.') from error


def renew_certificate(path, expected_public, key, permitted=lambda: True):
    path = Path(path)
    original = read_certificate(path)
    if certificate_public(original) != expected_public or public_key(key) != expected_public:
        raise IdentityStorageError('Certificate or private identity changed. Renewal stopped; pairing was not replaced.')
    # The existing key enters OpenSSL over stdin, never argv or a durable key file.
    renewed = run('req', '-new', '-x509', '-sha256', '-key', '/dev/stdin', '-days', '365',
                  '-subj', '/CN=MacBridge Development Peer', material=key)
    info = certificate_info(renewed)
    if certificate_public(renewed) != expected_public or info.status() != 'valid':
        raise IdentityStorageError('Renewed certificate failed identity or validity checks. Existing certificate retained.')
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.certificate-renew-', suffix='.crt', delete=False) as target:
            temporary = Path(target.name)
            target.write(renewed); target.flush(); os.fsync(target.fileno())
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        with tempfile.TemporaryFile() as material:
            material.write(key); material.flush(); material.seek(0)
            context.load_cert_chain(str(temporary), '/dev/fd/' + str(material.fileno()))
        if not permitted() or read_certificate(path) != original:
            raise IdentityStorageError('Certificate changed or companion stopped during renewal. Retry after restart.')
        os.replace(temporary, path)  # Valid old or valid new certificate, never truncated.
        return context, info
    except IdentityStorageError:
        raise
    except (OSError, ssl.SSLError) as error:
        raise IdentityStorageError('Could not publish the renewed certificate. Check storage access and restart; no identity was regenerated.') from error
    finally:
        if temporary is not None:
            try: temporary.unlink(missing_ok=True)
            except OSError: pass  # Public candidate only; never undo an atomic commit.

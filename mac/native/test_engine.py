"""Real TLS interoperability against isolated native-engine state; no live keys or alerts."""
import base64
import hashlib
import json
import os
from pathlib import Path
import queue
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from urllib.parse import urlparse, parse_qs

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from macbridge import Companion, openssl, fingerprint, read_frame, write_frame
from file_receiver import FileReceiver, CHUNK_SIZE
from transfer_state import TransferState

ENGINE = Path(__file__).parent / '.build/debug/MacBridgeEngine'

class NativePeer:
    def __init__(self, root, key, restart=False, files=True):
        self.root, self.key = Path(root), key
        self.events = queue.Queue()
        self.process = subprocess.Popen([str(ENGINE), '--test-mode', '--gui', '--state-dir', str(root),
            '--host', '127.0.0.1', '--address', '127.0.0.1', '--port', '0', '--echo', *(['--files'] if files else [])],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.reader = threading.Thread(target=self.read_events, daemon=True); self.reader.start()
        self.initial = self.event(lambda event: event.get('event') in ('state', 'error'))
        if self.initial.get('event') != 'state':
            self.close(); raise AssertionError('Native fixture failed startup: ' + self.initial.get('message', ''))
        self.port = int(self.initial['endpoint'].rsplit(':', 1)[1]); self.sockets = []; self.streams = []
    def read_events(self):
        for line in self.process.stdout:
            try: self.events.put(json.loads(line))
            except ValueError: self.events.put({'event': 'invalid'})
        self.events.put({'event': 'exit'})
    def event(self, predicate, timeout=8):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            value = self.events.get(timeout=max(.01, end-time.monotonic()))
            if predicate(value): return value
            if value.get('event') == 'exit': raise AssertionError('Native engine exited unexpectedly')
        raise AssertionError('Missing native event')
    def command(self, **values):
        self.process.stdin.write(json.dumps(values, ensure_ascii=False).encode() + b'\n'); self.process.stdin.flush()
    def proof(self, challenge, secret=None, key=None, peer_id='phone'):
        key = key or self.key
        public = openssl('pkey', '-in', str(key), '-pubout', '-outform', 'DER')
        transcript = f"macbridge-auth-v2\n{challenge['nonce']}\n{challenge['deviceId']}\n{challenge['fingerprint']}\n{peer_id}\n{fingerprint(public)}"
        signature = openssl('dgst', '-sha256', '-sign', str(key), input=transcript.encode())
        proof = dict(type='AUTH_PROOF', deviceId=peer_id, deviceName='Fixture 世界 🌉',
            publicKey=base64.b64encode(public).decode(), signature=base64.b64encode(signature).decode())
        if secret is not None: proof['secret'] = secret
        return proof
    def socket(self):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT); context.check_hostname = False; context.verify_mode = ssl.CERT_NONE
        sock = context.wrap_socket(socket.create_connection(('127.0.0.1', self.port), timeout=5), server_hostname='localhost')
        sock.settimeout(5); self.sockets.append(sock)
        stream = sock.makefile('rwb', buffering=0); self.streams.append(stream)
        return sock, stream, read_frame(stream)
    def connect(self, secret=None):
        sock, stream, challenge = self.socket()
        write_frame(stream, self.proof(challenge, secret))
        result = read_frame(stream)
        assert result['type'] == 'AUTH_OK'
        state = self.event(lambda e: e.get('event') == 'state' and e.get('connected'))
        return sock, stream, state, challenge
    def close(self):
        for stream in getattr(self, 'streams', []):
            try: stream.close()
            except OSError: pass
        for sock in getattr(self, 'sockets', []):
            try: sock.shutdown(socket.SHUT_RDWR)
            except OSError: pass
            finally: sock.close()
        if self.process.poll() is None:
            self.process.terminate()
            try: self.process.wait(timeout=8)
            except subprocess.TimeoutExpired: self.process.kill(); self.process.wait()
        self.process.stdin.close(); self.process.stdout.close(); self.reader.join(timeout=2)
        diagnostics=self.process.stderr.read();self.process.stderr.close()
        if diagnostics: sys.stderr.buffer.write(diagnostics)

class NativeEngineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.root = Path(self.temp.name)
        self.key = self.root / 'phone.key'
        openssl('ecparam', '-genkey', '-name', 'prime256v1', '-out', str(self.key))
        self.peer = NativePeer(self.root / 'mac', self.key)
        self.secret = parse_qs(urlparse(self.peer.initial['pairingURI']).query)['secret'][0]
    def tearDown(self):
        self.peer.close(); self.temp.cleanup()
    def pair(self): return self.peer.connect(self.secret)
    def init(self, data, transfer='fixture', name='世界 🌉.txt', resume=False):
        return dict(type='FILE_INIT', transferId=transfer, fileName=name, fileSize=len(data),
            sha256Checksum=hashlib.sha256(data).hexdigest(), chunkSize=CHUNK_SIZE, resume=resume)
    def chunk(self, data, index=0, transfer='fixture'):
        offset = index * CHUNK_SIZE; block = data[offset:offset+CHUNK_SIZE]
        return dict(type='FILE_CHUNK', transferId=transfer, offset=offset, chunkIndex=index,
            totalChunks=(len(data)+CHUNK_SIZE-1)//CHUNK_SIZE, chunkLength=len(block), dataBase64=base64.b64encode(block).decode())
    def exchange(self, stream, value): write_frame(stream, value); return read_frame(stream)
    def test_pair_reconnect_unicode_heartbeat_and_native_certificate(self):
        sock, stream, state, challenge = self.pair()
        self.assertEqual('Fixture 世界 🌉', state['phoneName'])
        self.assertEqual('valid', state['certificateStatus'])
        cert = self.peer.root / 'identity.crt'
        public = openssl('pkey', '-pubin', '-outform', 'DER', input=openssl('x509', '-in', str(cert), '-pubkey', '-noout'))
        self.assertEqual(challenge['fingerprint'], fingerprint(public))
        dates = openssl('x509', '-in', str(cert), '-dates', '-noout').decode().splitlines()
        self.assertAlmostEqual(ssl.cert_time_to_seconds(dates[0].split('=',1)[1]), state['certificateStarts'], delta=1)
        self.assertAlmostEqual(ssl.cert_time_to_seconds(dates[1].split('=',1)[1]), state['certificateExpires'], delta=1)
        text = dict(type='CLIPBOARD', mimeType='text/plain', content='你好 🌉')
        self.assertEqual(text['content'], self.exchange(stream, text)['content'])
        self.assertEqual('HEARTBEAT', self.exchange(stream, {'type':'HEARTBEAT','seq':1,'isAck':False})['type'])
        sock.shutdown(socket.SHUT_RDWR); stream.close(); sock.close()
        self.peer.event(lambda e: e.get('event') == 'state' and not e.get('connected'))
        self.peer.connect()
    def test_reused_code_changed_key_and_replayed_signature_rejected(self):
        _, _, _, challenge = self.pair()
        other = self.root / 'other.key'; openssl('ecparam', '-genkey', '-name', 'prime256v1', '-out', str(other))
        for key, secret, replay in [(self.key,self.secret,None),(other,None,None),(self.key,None,challenge)]:
            sock, stream, current = self.peer.socket()
            write_frame(stream, self.peer.proof(replay or current, secret, key))
            with self.assertRaises((ValueError,OSError)): read_frame(stream)
            stream.close(); sock.close()
    def test_verified_receive_restart_resume_and_lost_final_ack(self):
        data = b'x'*CHUNK_SIZE + '世界 🌉'.encode()
        sock, stream, _, _ = self.pair()
        self.assertEqual('READY', self.exchange(stream,self.init(data))['status'])
        self.assertEqual(CHUNK_SIZE, self.exchange(stream,self.chunk(data))['receivedBytes'])
        self.peer.close(); self.peer = NativePeer(self.root/'mac', self.key)
        _, stream, state, _ = self.peer.connect()
        self.assertEqual('paused', state['fileReceiveStatus'])
        ack = self.exchange(stream,self.init(data,resume=True))
        self.assertEqual(('READY',CHUNK_SIZE),(ack['status'],ack['receivedBytes']))
        self.assertEqual('COMPLETED', self.exchange(stream,self.chunk(data,1))['status'])
        self.assertEqual(data, next((self.peer.root/'ReceivedFiles').glob('verified-*')).read_bytes())
        self.peer.close(); self.peer = NativePeer(self.root/'mac', self.key)
        _, stream, _, _ = self.peer.connect()
        self.assertEqual('COMPLETED',self.exchange(stream,self.init(data,resume=True))['status'])
    def test_reference_python_partial_migrates_to_native(self):
        # Generate the old receiver filename/checkpoint exactly as the shipped engine did.
        _, stream, _, challenge = self.pair()
        data = b'a'*CHUNK_SIZE + b'tail'
        public = openssl('pkey','-in',str(self.key),'-pubout','-outform','DER')
        binding = [challenge['deviceId'],challenge['fingerprint'],'phone',base64.b64encode(public).decode()]
        self.peer.close()
        name = hashlib.sha256(json.dumps(binding).encode()).hexdigest()
        state = TransferState(self.peer.root/'TransferState'/('receiver-'+name+'.json'),binding)
        receiver = FileReceiver(self.peer.root/'ReceivedFiles',state=state)
        receiver.process(self.init(data),True); receiver.process(self.chunk(data),True); receiver.suspend()
        self.peer = NativePeer(self.root/'mac',self.key)
        _, stream, _, _ = self.peer.connect()
        self.assertEqual(CHUNK_SIZE,self.exchange(stream,self.init(data,resume=True))['receivedBytes'])
        self.assertEqual('COMPLETED',self.exchange(stream,self.chunk(data,1))['status'])
    def test_empty_bad_checksum_malformed_chunk_and_permission_revocation(self):
        _, stream, _, _ = self.pair()
        self.assertEqual('COMPLETED',self.exchange(stream,self.init(b'',transfer='empty'))['status'])
        data = b'test'
        bad = self.init(data,transfer='bad'); bad['sha256Checksum']='0'*64
        self.assertEqual('READY',self.exchange(stream,bad)['status'])
        self.assertEqual('VERIFICATION_FAILED',self.exchange(stream,self.chunk(data,transfer='bad'))['status'])
        self.exchange(stream,self.init(data,transfer='order'))
        chunk = self.chunk(data,transfer='order'); chunk['offset']=True
        self.assertEqual('FAILED',self.exchange(stream,chunk)['status'])
        self.exchange(stream,self.init(data,transfer='revoked'))
        self.peer.command(action='setFilesEnabled',enabled=False)
        self.peer.event(lambda e:e.get('event')=='state' and not e.get('filesEnabled'))
        self.assertEqual('REJECTED',self.exchange(stream,self.chunk(data,transfer='revoked'))['status'])
        self.assertFalse(list((self.peer.root/'ReceivedFiles').glob('.incoming-*')))
    def test_sender_interoperates_with_python_receiver_and_requires_original_session(self):
        _, stream, state, _ = self.pair()
        data = b'x'*CHUNK_SIZE + b'end'; path=self.root/'send 世界.txt'; path.write_bytes(data)
        self.peer.command(action='sendFile',path=str(path),connectionId='stale')
        self.peer.event(lambda e:e.get('event')=='error')
        self.peer.command(action='sendFile',path=str(path),connectionId=state['connectionId'])
        receiver=FileReceiver(self.root/'phone-received')
        try:
            while True:
                frame=read_frame(stream); ack=receiver.process(frame,True); write_frame(stream,ack)
                if ack['status']=='COMPLETED':break
            final=self.peer.event(lambda e:e.get('event')=='state' and e.get('fileSendStatus')=='completed')
            self.assertEqual(len(data),final['sentBytes']); self.assertEqual(data,receiver.last_saved.read_bytes())
        finally:receiver.abort()
    def test_sender_restart_resume_preserves_snapshot(self):
        _, stream, state, _ = self.pair()
        data=b'z'*CHUNK_SIZE+b'end'; path=self.root/'outgoing.txt';path.write_bytes(data)
        receiver=FileReceiver(self.root/'phone-received')
        try:
            self.peer.command(action='sendFile',path=str(path),connectionId=state['connectionId'])
            init=read_frame(stream);write_frame(stream,receiver.process(init,True))
            chunk=read_frame(stream);write_frame(stream,receiver.process(chunk,True))
            read_frame(stream) # Final chunk was sent but deliberately not delivered/acknowledged.
            self.peer.close();path.write_bytes(b'changed original')
            self.peer=NativePeer(self.root/'mac',self.key)
            _,stream,state,_=self.peer.connect()
            self.assertEqual('paused',state['fileSendStatus'])
            self.peer.command(action='resumeFileSend',connectionId=state['connectionId'])
            init=read_frame(stream);self.assertTrue(init['resume']);write_frame(stream,receiver.process(init,True))
            chunk=read_frame(stream);self.assertEqual(CHUNK_SIZE,chunk['offset'])
            write_frame(stream,receiver.process(chunk,True))
            self.peer.event(lambda e:e.get('event')=='state' and e.get('fileSendStatus')=='completed')
            self.assertEqual(data,receiver.last_saved.read_bytes())
        finally:receiver.abort()
    def test_cancel_receiver_stale_token_and_sender_cancel(self):
        _,stream,state,_=self.pair();data=b'abc'
        self.exchange(stream,self.init(data))
        receiving=self.peer.event(lambda e:e.get('fileReceiveStatus')=='receiving')
        self.peer.command(action='cancelFileReceive',transferToken='stale')
        self.peer.command(action='status');self.peer.event(lambda e:e.get('event')=='state' and e.get('fileReceiveStatus')=='receiving')
        self.peer.command(action='cancelFileReceive',transferToken=receiving['fileReceiveToken'])
        self.assertEqual('CANCELLED',read_frame(stream)['status'])
        self.assertFalse(list((self.peer.root/'ReceivedFiles').glob('.incoming-*')))
        path=self.root/'send.txt';path.write_bytes(b'test')
        self.peer.command(action='sendFile',path=str(path),connectionId=state['connectionId'])
        offer=read_frame(stream)
        self.peer.command(action='cancelFileSend')
        self.assertEqual('FILE_CANCEL',read_frame(stream)['type'])
        self.peer.event(lambda e:e.get('fileSendStatus')=='cancelled')
        self.assertFalse(list((self.peer.root/'OutgoingFiles').glob('.outgoing-*')))
    def test_notification_actions_are_session_scoped_bounded_and_not_replayed(self):
        _,stream,state,_=self.pair()
        self.peer.command(action='setNotificationsEnabled',enabled=True)
        self.peer.event(lambda e:e.get('event')=='state' and e.get('notificationsEnabled'))
        token='11111111-1111-1111-1111-111111111111'
        notice=dict(type='NOTIFICATION',notificationId='fixture',packageName='local.fixture',appName='Fixture',title='Test',text='世界',replyToken=token,dismissToken=token)
        write_frame(stream,notice);self.peer.event(lambda e:e.get('operation')=='post')
        action=dict(action='replyNotification',notificationId='fixture',actionToken=token,connectionId=state['connectionId'],replyText='你好 🌉')
        self.peer.command(**dict(action,connectionId='stale'));self.peer.event(lambda e:e.get('event')=='error')
        self.peer.command(**dict(action,replyText='🌉'*1025));self.peer.event(lambda e:e.get('event')=='error')
        self.peer.command(**action);self.assertEqual('你好 🌉',read_frame(stream)['replyText'])
        self.peer.command(**action);self.peer.event(lambda e:e.get('event')=='error')
        write_frame(stream,dict(type='NOTIFICATION_ACTION',actionType='REPLY_RESULT',notificationId='fixture',actionToken=token,status='REQUESTED'))
        self.peer.event(lambda e:e.get('operation')=='result')
        self.peer.command(action='dismissNotification',notificationId='fixture',actionToken=token,connectionId=state['connectionId'])
        self.assertEqual('DISMISS',read_frame(stream)['actionType'])
        self.peer.command(action='setNotificationsEnabled',enabled=False)
        self.peer.event(lambda e:e.get('operation')=='clear')
        self.peer.command(**action);self.peer.event(lambda e:e.get('event')=='error')
    def test_malformed_and_oversized_frames_disconnect_without_replacing_identity(self):
        self.pair()
        for data in [b'{"type":"HEARTBEAT","type":"FILE_INIT"}\n',b'{"x":NaN}\n',b'x'*(1024*1024+1)]:
            sock,stream,_,_=self.peer.connect()
            stream.write(data)
            with self.assertRaises((ValueError,OSError)):read_frame(stream)
            stream.close();sock.close()
        self.peer.connect()
    def test_same_key_renewal_preserves_live_and_new_sessions(self):
        _,stream,state,challenge=self.pair()
        before=(self.peer.root/'identity.crt').read_bytes()
        self.peer.command(action='renewCertificate')
        renewed=self.peer.event(lambda e:e.get('event')=='state' and e.get('lastAction')=='Mac certificate renewed. Existing pairing preserved.')
        self.assertEqual('valid',renewed['certificateStatus'])
        self.assertNotEqual(before,(self.peer.root/'identity.crt').read_bytes())
        self.assertEqual('HEARTBEAT',self.exchange(stream,{'type':'HEARTBEAT','seq':1,'isAck':False})['type'])
        _,_,_,new=self.peer.connect();self.assertEqual(challenge['fingerprint'],new['fingerprint'])
    def test_corrupt_partial_expired_checkpoint_and_symlink_are_not_restored(self):
        data=b'q'*CHUNK_SIZE+b'end'
        for mode in ('corrupt','expired','symlink'):
            with self.subTest(mode=mode):
                if mode=='corrupt': _,stream,_,_=self.pair()
                else: _,stream,_,_=self.peer.connect()
                self.exchange(stream,self.init(data,transfer=mode));self.exchange(stream,self.chunk(data,transfer=mode))
                self.peer.close()
                path=next((self.peer.root/'ReceivedFiles').glob('.incoming-*'))
                state=next((self.peer.root/'TransferState').glob('receiver-*.json'))
                if mode=='corrupt':path.write_bytes(b'corrupt')
                elif mode=='expired':
                    value=json.loads(state.read_text());value['incoming']['expires']=time.time()-1;state.write_text(json.dumps(value))
                else:
                    path.unlink();target=self.root/'untouched';target.write_bytes(b'untouched');path.symlink_to(target)
                self.peer=NativePeer(self.root/'mac',self.key)
                _,stream,_,_=self.peer.connect()
                self.assertEqual('REJECTED',self.exchange(stream,self.init(data,transfer=mode,resume=True))['status'])
                self.assertFalse(list((self.peer.root/'ReceivedFiles').glob('.incoming-*')))
                if mode=='symlink':self.assertEqual(b'untouched',target.read_bytes())
    def test_quota_limit_and_unknown_cancel_do_not_rewrite_checkpoint(self):
        _,stream,_,_=self.pair();data=b'q'*CHUNK_SIZE+b'end'
        self.exchange(stream,self.init(data));self.exchange(stream,self.chunk(data))
        state=next((self.peer.root/'TransferState').glob('receiver-*.json'))
        before=state.stat().st_mtime_ns
        self.assertEqual('CANCELLED',self.exchange(stream,dict(type='FILE_CANCEL',transferId='unknown'))['status'])
        self.assertEqual(before,state.stat().st_mtime_ns)
        self.exchange(stream,dict(type='FILE_CANCEL',transferId='fixture'))
        self.peer.command(action='setStorageLimit',bytes=250*1024*1024)
        self.peer.event(lambda e:e.get('storageLimit')==250*1024*1024)
        folder=self.peer.root/'ReceivedFiles'
        with (folder/'verified-existing').open('wb') as file:file.truncate(250*1024*1024)
        self.assertEqual('STORAGE_FULL',self.exchange(stream,self.init(b'abc',transfer='quota'))['status'])
        self.assertEqual(250*1024*1024,(folder/'verified-existing').stat().st_size)
    def test_clipboard_budget_survives_reconnect(self):
        _,stream,_,_=self.pair()
        frame=dict(type='CLIPBOARD',mimeType='text/plain',content='fixture')
        for _ in range(10):self.assertEqual('fixture',self.exchange(stream,frame)['content'])
        write_frame(stream,frame)
        with self.assertRaises((ValueError,OSError)):read_frame(stream)
        _,stream,_,_=self.peer.connect()
        write_frame(stream,frame)
        with self.assertRaises((ValueError,OSError)):read_frame(stream)
    def test_oversized_control_recovers_at_next_newline(self):
        self.peer.process.stdin.write(b'x'*16385+b'\n');self.peer.process.stdin.flush()
        self.peer.event(lambda e:e.get('event')=='error')
        self.peer.command(action='status');self.peer.event(lambda e:e.get('event')=='state')
        self.pair()
    def test_different_authenticated_phone_cannot_cancel_original_sender(self):
        _,stream,state,_=self.pair();path=self.root/'pending.txt';path.write_bytes(b'pending')
        self.peer.command(action='sendFile',path=str(path),connectionId=state['connectionId'])
        offer=read_frame(stream)
        self.peer.command(action='reissueCode')
        fresh=self.peer.event(lambda e:e.get('event')=='state' and e.get('lastAction')=='New pairing code ready')
        secret=parse_qs(urlparse(fresh['pairingURI']).query)['secret'][0]
        _,other,challenge=self.peer.socket()
        write_frame(other,self.peer.proof(challenge,secret,peer_id='other-phone'))
        self.assertEqual('AUTH_OK',read_frame(other)['type'])
        self.peer.event(lambda e:e.get('event')=='state' and e.get('connected') and e.get('fileSendStatus')=='paused')
        write_frame(other,dict(type='FILE_ACK',transferId=offer['transferId'],status='CANCELLED',receivedBytes=0))
        self.peer.command(action='status')
        saved=self.peer.event(lambda e:e.get('event')=='state' and e.get('connected'))
        self.assertEqual('paused',saved['fileSendStatus'])
        self.assertEqual(1,len(list((self.peer.root/'OutgoingFiles').glob('.outgoing-*'))))
    def test_python_identity_and_pairing_migrate_without_rotation(self):
        self.peer.close()
        reference=Companion(self.root/'reference',host='127.0.0.1',port=0)
        reference.peers={'phone':{'publicKey':base64.b64encode(openssl('pkey','-in',str(self.key),'-pubout','-outform','DER')).decode(),'name':'Fixture'}}
        reference.save_peers();pin=reference.pin;device=reference.device_id;reference.close()
        self.peer=NativePeer(self.root/'reference',self.key)
        _,_,_,challenge=self.peer.connect()
        self.assertEqual((device,pin),(challenge['deviceId'],challenge['fingerprint']))

if __name__=='__main__':unittest.main()

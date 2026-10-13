#!/usr/bin/env python3
"""Test-only control adapter: run Android's real transport tests against Swift."""
import json
from pathlib import Path
import subprocess
import sys

root, directory = map(Path, sys.argv[1:3])
mode = sys.argv[3]
selected = Path(sys.argv[4]) if len(sys.argv) > 4 and sys.argv[4] else None
engine = root / 'mac/native/.build/debug/MacBridgeEngine'
if not engine.is_file(): raise SystemExit('Build MacBridgeEngine before native Android interoperability tests')
process = subprocess.Popen([str(engine), '--test-mode', '--gui', '--state-dir', str(directory),
    '--host', '127.0.0.1', '--address', '127.0.0.1', '--port', '0', '--files', '--echo'],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
def command(**value):
    process.stdin.write(json.dumps(value,ensure_ascii=False).encode()+b'\n');process.stdin.flush()
try:
    first = True; actions=set(); sending=False; last_connection=''
    for line in process.stdout:
        event=json.loads(line)
        if first and event.get('event')=='state':
            first=False
            if mode=='renewed':command(action='renewCertificate')
            if mode in ('notifications','dismiss','reply'):command(action='setNotificationsEnabled',enabled=True)
            print('PAIRING_URI='+event['pairingURI'],flush=True)
        if event.get('event')=='notification':
            with (directory/'notifications.jsonl').open('a') as output:output.write(json.dumps(event,ensure_ascii=False)+'\n')
            if event.get('operation')=='post' and mode in ('dismiss','reply'):
                kind='replyToken' if mode=='reply' else 'dismissToken';token=event.get(kind)
                if token and token not in actions:
                    actions.add(token)
                    action=dict(action='replyNotification' if mode=='reply' else 'dismissNotification',
                        notificationId=event['notificationId'],actionToken=token,connectionId=event['connectionId'])
                    if mode=='reply':action['replyText']='Hello 世界 🌉\nThanks'
                    command(**action)
        if event.get('event')=='state' and selected:
            if event.get('connected') and not sending:
                sending=True;last_connection=event['connectionId'];command(action='sendFile',path=str(selected),connectionId=last_connection)
            elif mode=='resume' and event.get('connected') and event.get('fileSendStatus')=='paused' and event['connectionId']!=last_connection:
                last_connection=event['connectionId'];command(action='resumeFileSend',connectionId=last_connection)
            if event.get('fileSendStatus') in ('completed','cancelled','failed'):
                (directory/'sender-result.txt').write_text(event['fileSendStatus'])
finally:
    if process.poll() is None:process.terminate();process.wait(timeout=10)

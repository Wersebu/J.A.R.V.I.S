#!/usr/bin/env python3
"""Start the packaged production Core with isolated storage and a loopback fake Ollama.
Never connects to production or changes existing accounts/configuration.
"""
import argparse
import base64
import hashlib
import os
import struct
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import re
from pathlib import Path
import socket
import subprocess
import threading
import time
import urllib.request


class Ollama(BaseHTTPRequestHandler):
    payloads = []
    release_children = threading.Event()
    children_entered = set()

    def log_message(self, *args):
        pass

    def send(self, obj):
        body = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == '/api/tags':
            self.send({'models': [{'name': 'gpt-oss:20b', 'model': 'gpt-oss:20b', 'digest': 'smoke-digest',
                                  'size': 100, 'capabilities': ['completion', 'tools', 'thinking'],
                                  'details': {'family': 'gptoss', 'parameter_size': '20B', 'quantization_level': 'MXFP4'}}]})
        elif self.path == '/api/ps':
            self.send({'models': []})
        else:
            self.send({'version': 'smoke-fake'})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if self.path == '/api/show':
            self.send({'capabilities': ['completion', 'tools', 'thinking'],
                       'model_info': {'general.architecture': 'gptoss', 'gptoss.context_length': 131072}})
            return
        Ollama.payloads.append(body)
        match=re.search(r'=== CURRENT USER MESSAGE ===(.*?)=== END CURRENT USER MESSAGE ===',body.get('prompt',''),re.S)
        current=match.group(1) if match else body.get('prompt','')
        if 'held-child-smoke' in current and 'Zwroc tylko tytul' not in body.get('prompt', ''):
            Ollama.children_entered.add(re.search(r'held-child-smoke-[123]', current).group())
            if not Ollama.release_children.wait(45):
                raise AssertionError('Test did not release background children')
        answer = 'Smoke title' if 'Zwroc tylko tytul' in body.get('prompt', '') else json.dumps({'type': 'FINAL_ANSWER', 'answer': 'Verified smoke answer.'})
        if 'delegate-smoke' in current and 'Zwroc tylko tytul' not in body.get('prompt',''):
            answer=json.dumps({'type':'TOOL_REQUEST','goal':'Delegate independent smoke work using companion__submit','reason':'User requested independent background execution','context':{}})
        elif 'tool-context-smoke' in current and 'Zwroc tylko tytul' not in body.get('prompt',''):
            answer=json.dumps({'type':'TOOL_REQUEST','goal':'Inspect own task status using companion__get','reason':'Verify real backend task state','context':{}})
        if body.get('stream'):
            message={'role':'assistant','content':answer}
            native_text='\n'.join(m.get('content','') for m in body.get('messages',[]))
            if body.get('tools') and 'three-delegate-smoke' in native_text:
                assert body['options']['num_predict']==768 and body['think']=='low', (body['options'],body.get('think'))
                names={t['function']['name'] for t in body['tools']}
                assert names == {'companion__submit','companion__get','companion__cancel','companion__instruct'}, names
                submitted=sum(m.get('role')=='tool' and '"taskId"' in m.get('content','') and '"success":true' in m.get('content','').replace(' ','') for m in body['messages'])
                message={'role':'assistant','content':'','tool_calls':[{'id':f'child-{submitted+1}','function':{'name':'companion__submit','arguments':{'message':f'Complete held-child-smoke-{submitted+1} independently.','role':'RESEARCH'}}}]}
            elif body.get('tools') and any(t.get('function',{}).get('name')=='companion__submit' for t in body['tools']):
                message={'role':'assistant','content':'','tool_calls':[{'id':'smoke-call','function':{'name':'companion__submit','arguments':{'message':'Inspect tool-context-smoke task status using companion__get.','role':'RESEARCH'}}}]}
            elif body.get('tools') and not any(m.get('role')=='tool' for m in body.get('messages',[])):
                text='\n'.join(m.get('content','') for m in body.get('messages',[]))
                task_id=re.search(r'Backend context: companion task ([0-9a-f-]+)',text)
                if task_id:
                    message={'role':'assistant','content':'','tool_calls':[{'id':'smoke-get','function':{'name':'companion__get','arguments':{'taskId':task_id.group(1)}}}]}
            chunk={'response':answer,'message':message,'done':False}
            final={'done':True,'done_reason':'stop','eval_count':8,'eval_duration':1000000,'prompt_eval_count':12,'prompt_eval_duration':1000000,'load_duration':1}
            wire=(json.dumps(chunk)+'\n'+json.dumps(final)+'\n').encode()
            self.send_response(200)
            self.send_header('Content-Type','application/x-ndjson')
            self.send_header('Content-Length',str(len(wire)))
            self.end_headers()
            try:
                self.wfile.write(wire)
            except ConnectionError:
                pass  # Cancelling a held task intentionally closes its inference transport.
            return
        self.send({'response': answer, 'message': {'role': 'assistant', 'content': answer}, 'done': True,
                   'done_reason': 'stop', 'load_duration': 1, 'eval_count': 8, 'eval_duration': 1000000,
                   'prompt_eval_count': 12, 'prompt_eval_duration': 1000000})


def request(base, path, body=None, token=None, method=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(base + path, json.dumps(body).encode() if body is not None else None, headers, method=method)
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.load(r)


def user_text(events):
    return ''.join(e.get('text',e.get('metadata',{}).get('text',e.get('message','')))
                   for e in events if e.get('type')=='TOKEN' or e.get('event')=='ANSWER_TOKEN')


def websocket_chat(base, token, conversation, message):
    """Windows' authenticated /ws/jarvis chat path, using RFC6455 frames (stdlib only)."""
    from urllib.parse import urlparse
    url=urlparse(base)
    with socket.create_connection((url.hostname,url.port),timeout=15) as sock:
        key=base64.b64encode(os.urandom(16)).decode()
        sock.sendall((f'GET /ws/jarvis HTTP/1.1\r\nHost: {url.netloc}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer {token}\r\n\r\n').encode())
        wire=sock.makefile('rb')
        assert b'101' in wire.readline(), 'WebSocket handshake failed'
        headers={}
        while True:
            line=wire.readline()
            if line==b'\r\n': break
            k,v=line.decode().split(':',1);headers[k.lower()]=v.strip()
        assert headers['sec-websocket-accept']==base64.b64encode(hashlib.sha1((key+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
        payload=json.dumps({'conversationId':conversation,'message':message}).encode()
        mask=os.urandom(4)
        length=bytes([0x80|len(payload)]) if len(payload)<126 else bytes([0x80|126])+struct.pack('!H',len(payload))
        sock.sendall(bytes([0x81])+length+mask+bytes(b^mask[i%4] for i,b in enumerate(payload)))
        events=[]
        while True:
            head=wire.read(2)
            assert len(head)==2, 'WebSocket closed before completion'
            size=head[1]&127
            if size==126: size=struct.unpack('!H',wire.read(2))[0]
            elif size==127: size=struct.unpack('!Q',wire.read(8))[0]
            data=wire.read(size)
            if head[0]&15 != 1: continue
            event=json.loads(data);events.append(event)
            if event.get('type') in ('ERROR','CANCELLED'): raise AssertionError(event)
            if event.get('type')=='COMPLETED': return events


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', required=True)
    parser.add_argument('--jar', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    root = args.out.resolve()
    root.mkdir(parents=True, exist_ok=False)
    (root/'identity.md').write_text('You only have Calendar, Gmail and Knowledge. Companion agents are unavailable. Never delegate work.',encoding='utf-8')
    fake = ThreadingHTTPServer(('127.0.0.1', 0), Ollama)
    threading.Thread(target=fake.serve_forever, daemon=True).start()
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        port = s.getsockname()[1]
    base = f'http://127.0.0.1:{port}'
    config = {
        'server.address': '127.0.0.1', 'server.port': str(port),
        'jarvis.ollama.base-url': f'http://127.0.0.1:{fake.server_port}',
        'knowledge.root': str(root / 'knowledge'), 'knowledge.watch': 'false',
        'knowledge.vault.index-database': str(root / 'index.db'),
        'jarvis.memory.database-path': str(root / 'memory.db'),
        'jarvis.workspace.root': str(root / 'workspaces'),
        'jarvis.model.active-model-file': 'file:' + str(root / 'active-model.txt'),
        'jarvis.ai.identity-file': 'file:' + str(root / 'identity.md'),
        'jarvis.mcp.enabled': 'false', 'jarvis.companion.history.enabled': 'true',
        'jarvis.memory.autonomous.enabled': 'false', 'jarvis.memory.dreaming.enabled': 'false',
        'jarvis.memory.background.enabled': 'false', 'jarvis.diagnostics.conversation-trace.enabled': 'false'
    }
    process = None
    try:
        with (root / 'startup.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen([args.java, '-jar', str(args.jar.resolve())] + ['--' + k + '=' + v for k, v in config.items()],
                                       cwd=root, stdout=log, stderr=subprocess.STDOUT)
            end = time.monotonic() + 60
            health = None
            while time.monotonic() < end:
                if process.poll() is not None:
                    raise RuntimeError('Packaged Core exited: see startup.log')
                try:
                    health = request(base, '/api/health')
                    if health.get('modelStatus') == 'READY':
                        break
                except Exception:
                    pass
                time.sleep(.25)
            if not health or health.get('modelStatus') != 'READY':
                raise RuntimeError('Packaged Core did not become READY')
            auth = request(base, '/api/v1/auth/bootstrap', {'email': 'phase2@example.test', 'password': 'Isolated-Smoke-Only-2026!', 'displayName': 'Smoke'})
            token = auth['accessToken']
            request(base,'/api/v1/users/me/settings',{'globalPrompt':'Only Calendar, Gmail and Knowledge exist. Do not use Companion; background execution is impossible.'},token,method='PUT')
            caps = request(base, '/api/v1/companion/capabilities', token=token)
            assert caps['contractVersion'] == '1.5.0' and caps['asyncDelegation']
            state = request(base, '/api/v1/companion/state', token=token)
            metrics = state['metrics']
            assert metrics['gatewaySlotsConfigured'] == 4 and metrics['reservedInteractive'] == 1
            assert metrics['backgroundSubmitSpacingMs']==250
            (root/'capabilities.json').write_text(json.dumps(caps,indent=2),encoding='utf-8')
            (root/'state.json').write_text(json.dumps(state,indent=2),encoding='utf-8')
            answer = request(base, '/api/v1/chat', {'conversationId': 'smoke-conversation', 'message': 'Say hello without tools.'}, token)
            assert 'Verified smoke answer' in answer['response'], 'Unexpected synthetic chat response'
            task = request(base, '/api/v1/companion/tasks', {'message': 'Say hello without tools.', 'timeoutSeconds': 30}, token)
            end = time.monotonic() + 15
            while time.monotonic() < end:
                task = request(base, '/api/v1/companion/tasks/' + task['taskId'], token=token)
                if task['state'] in ('COMPLETED', 'FAILED', 'TIMED_OUT'):
                    break
                time.sleep(.1)
            assert task['state'] == 'COMPLETED', task['state']
            assert task['executionConversationId'] != 'smoke-conversation'
            single_events=websocket_chat(base,token,'smoke-delegation','Run one background agent for delegate-smoke.')
            (root/'single-chat-events.json').write_text(json.dumps(single_events,indent=2),encoding='utf-8')
            delegated={'response':user_text(single_events)}
            tasks=request(base,'/api/v1/companion/tasks',token=token)
            linked=[t for t in tasks if t.get('conversationId')=='smoke-delegation']
            assert len(linked)==1,'No real task created by the main conversation: '+delegated['response']
            assert linked[0]['taskId'] in delegated['response'],'Main response must contain the backend receipt'
            end=time.monotonic()+15
            while time.monotonic()<end:
                child=request(base,'/api/v1/companion/tasks/'+linked[0]['taskId'],token=token)
                if child['state'] in ('COMPLETED','FAILED','TIMED_OUT'):
                    break
                time.sleep(.1)
            assert child['state']=='COMPLETED','Background native tool context failed: '+str(child.get('error'))
            # Three sequential native submissions from ONE Windows chat message. Workers cannot finish until released.
            started=time.monotonic()
            events=websocket_chat(base,token,'smoke-three','Run three independent background agents for three-delegate-smoke: analyze alpha, beta and gamma.')
            linked3=[t for t in request(base,'/api/v1/companion/tasks',token=token) if t.get('conversationId')=='smoke-three']
            assert len(linked3)==3 and len({t['taskId'] for t in linked3})==3, linked3
            visible=user_text(events)
            assert all(t['taskId'] in visible for t in linked3), visible
            end=time.monotonic()+10
            while len(Ollama.children_entered)<3 and time.monotonic()<end: time.sleep(.05)
            assert len(Ollama.children_entered)==3, Ollama.children_entered
            assert all(request(base,'/api/v1/companion/tasks/'+t['taskId'],token=token)['state']=='RUNNING' for t in linked3)
            interactive=websocket_chat(base,token,'smoke-three','Say hello without tools while agents work.')
            assert 'Verified smoke answer.' in user_text(interactive), interactive
            assert len([t for t in request(base,'/api/v1/companion/tasks',token=token) if t.get('conversationId')=='smoke-three'])==3
            request(base,'/api/v1/companion/tasks/'+linked3[0]['taskId']+'/instructions',{'message':'Keep the report concise.'},token)
            request(base,'/api/v1/companion/tasks/'+linked3[2]['taskId']+'/cancel',{},token)
            Ollama.release_children.set()
            end=time.monotonic()+15
            while time.monotonic()<end:
                final=[request(base,'/api/v1/companion/tasks/'+t['taskId'],token=token) for t in linked3]
                if all(t['state'] in ('COMPLETED','CANCELLED','FAILED','TIMED_OUT') for t in final): break
                time.sleep(.1)
            assert [t['state'] for t in final]==['COMPLETED','COMPLETED','CANCELLED'], final
            assert any('Keep the report concise.' in b.get('prompt','') for b in Ollama.payloads), 'Instruction was accepted but never delivered to task inference'
            assert sum(bool(b.get('tools')) and any('three-delegate-smoke' in m.get('content','') for m in b.get('messages',[])) for b in Ollama.payloads)==3, 'Expected exactly three submission rounds and no final synthesis inference'
            lifecycle=request(base,'/api/v1/companion/events',token=token)['events']
            assert all(any(e.get('taskId')==t['taskId'] and e.get('type')=='task.completed' for e in lifecycle) for t in final[:2]), lifecycle
            catalogs=[{'names':[t['function']['name'] for t in b.get('tools',[])],'options':b.get('options'),'think':b.get('think')} for b in Ollama.payloads if b.get('tools')]
            (root/'native-catalogs.json').write_text(json.dumps(catalogs,indent=2),encoding='utf-8')
            (root/'windows-chat-events.json').write_text(json.dumps(events,indent=2),encoding='utf-8')
            assert Ollama.payloads and all(p.get('options', {}).get('num_ctx') == 24576 for p in Ollama.payloads)
            result = {'packagedJarStartup': 'PASS', 'modelStatus': health['modelStatus'],
                      'ordinaryChat': 'PASS', 'asynchronousTask': task['state'], 'mainConversationDelegation':'PASS', 'backgroundNativeToolContext':'PASS', 'contractVersion': caps['contractVersion'],
                      'gatewaySlotsConfigured': metrics['gatewaySlotsConfigured'], 'stablePhysicalContext': 24576,
                      'windowsThreeSequentialSubmissions':'PASS', 'threeRunningBeforeRelease':'PASS', 'interactiveUnderThreeTasks':'PASS', 'individualManagement':'PASS', 'fakeOllamaCalls': len(Ollama.payloads)}
            (root / 'verification.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
            print(json.dumps(result))
    finally:
        Ollama.release_children.set()
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        fake.shutdown()
        fake.server_close()


if __name__ == '__main__':
    main()

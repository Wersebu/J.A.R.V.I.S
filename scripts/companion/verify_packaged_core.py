#!/usr/bin/env python3
"""Start the packaged production Core with isolated storage and a loopback fake Ollama.
Never connects to production or changes existing accounts/configuration.
"""
import argparse
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
        answer = 'Smoke title' if 'Zwroc tylko tytul' in body.get('prompt', '') else json.dumps({'type': 'FINAL_ANSWER', 'answer': 'Verified smoke answer.'})
        if 'delegate-smoke' in body.get('prompt','') and 'Zwroc tylko tytul' not in body.get('prompt',''):
            answer=json.dumps({'type':'TOOL_REQUEST','goal':'Delegate independent smoke work using companion__submit','reason':'User requested independent background execution','context':{}})
        elif 'tool-context-smoke' in body.get('prompt','') and 'Zwroc tylko tytul' not in body.get('prompt',''):
            answer=json.dumps({'type':'TOOL_REQUEST','goal':'Inspect own task status using companion__get','reason':'Verify real backend task state','context':{}})
        if body.get('stream'):
            message={'role':'assistant','content':answer}
            if body.get('tools') and any(t.get('function',{}).get('name')=='companion__submit' for t in body['tools']):
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
            self.wfile.write(wire)
            return
        self.send({'response': answer, 'message': {'role': 'assistant', 'content': answer}, 'done': True,
                   'done_reason': 'stop', 'load_duration': 1, 'eval_count': 8, 'eval_duration': 1000000,
                   'prompt_eval_count': 12, 'prompt_eval_duration': 1000000})


def request(base, path, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(base + path, json.dumps(body).encode() if body is not None else None, headers)
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.load(r)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', required=True)
    parser.add_argument('--jar', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    root = args.out.resolve()
    root.mkdir(parents=True, exist_ok=False)
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
            delegated=request(base,'/api/v1/chat',{'conversationId':'smoke-delegation','message':'Run delegate-smoke in the background using companion tools.'},token)
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
            assert Ollama.payloads and all(p.get('options', {}).get('num_ctx') == 24576 for p in Ollama.payloads)
            result = {'packagedJarStartup': 'PASS', 'modelStatus': health['modelStatus'],
                      'ordinaryChat': 'PASS', 'asynchronousTask': task['state'], 'mainConversationDelegation':'PASS', 'backgroundNativeToolContext':'PASS', 'contractVersion': caps['contractVersion'],
                      'gatewaySlotsConfigured': metrics['gatewaySlotsConfigured'], 'stablePhysicalContext': 24576,
                      'fakeOllamaCalls': len(Ollama.payloads)}
            (root / 'verification.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
            print(json.dumps(result))
    finally:
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

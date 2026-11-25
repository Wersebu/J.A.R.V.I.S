"""Run a packaged Core with real auth/controllers and a loopback-only fake Ollama.

No integration launcher or production configuration/credentials/storage involved.
Report contains no test passwords or tokens. All subprocesses are owned here.
"""
import argparse
import base64
import io
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Ollama(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.reply({'models': [{'name': 'gpt-oss:20b', 'model': 'gpt-oss:20b', 'size': 1}]})

    def do_POST(self):
        self.rfile.read(int(self.headers.get('Content-Length', '0')))
        if self.path == '/api/show':
            self.reply({'model_info': {'general.architecture': 'gptoss', 'gptoss.context_length': 262144}})
        else:
            self.reply({'model': 'gpt-oss:20b', 'response': 'OK', 'done': True, 'load_duration': 1,
                        'eval_count': 1, 'eval_duration': 1000000})

    def reply(self, body):
        payload = json.dumps(body).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def request(base, path, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, headers=headers)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=10) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as response:
        return response.code, None


def websocket(port, token=None):
    key = base64.b64encode(secrets.token_bytes(16)).decode()
    auth = '' if token is None else 'Authorization: Bearer ' + token + '\r\n'
    payload = (f'GET /ws/companion HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n'
               'Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n'
               f'Sec-WebSocket-Key: {key}\r\n{auth}\r\n')
    with socket.create_connection(('127.0.0.1', port), timeout=10) as sock:
        sock.sendall(payload.encode())
        response = b''
        while b'\r\n\r\n' not in response:
            part = sock.recv(4096)
            if not part:
                break
            response += part
        status = int(response.split(b' ', 2)[1])
        if status == 101:
            sock.sendall(b'\x88\x80' + secrets.token_bytes(4))
        return status


def inspect(jar):
    with zipfile.ZipFile(jar) as z:
        manifest = z.read('META-INF/MANIFEST.MF').decode()
        assert 'Start-Class: com.jarvis.core.JarvisApplication' in manifest
        for name in z.namelist():
            assert 'com/jarvis/integration/' not in name, name
            assert 'AccidentalIntegrationController' not in name, name
            if name.startswith('BOOT-INF/lib/'):
                assert not any(x in name for x in ('mockito-', 'junit-', 'spring-boot-test')), name
                if name.endswith('.jar'):
                    with zipfile.ZipFile(io.BytesIO(z.read(name))) as lib:
                        assert not any(n.startswith('com/jarvis/integration/') for n in lib.namelist()), name


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--java', required=True)
    p.add_argument('--jar', required=True, type=Path)
    p.add_argument('--report', required=True, type=Path)
    args = p.parse_args()
    jar = args.jar.resolve()
    inspect(jar)
    repo = Path(__file__).resolve().parent.parent
    root = repo / 'data/startup-smoke'
    root.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='production-', dir=root)).resolve()
    stub = ThreadingHTTPServer(('127.0.0.1', 0), Ollama)
    threading.Thread(target=stub.serve_forever, daemon=True).start()
    with socket.socket() as reserve:
        reserve.bind(('127.0.0.1', 0))
        port = reserve.getsockname()[1]
    options = {
        'server.address': '127.0.0.1', 'server.port': port, 'spring.profiles.active': 'default',
        'spring.config.location': 'optional:classpath:/application.yml',
        'jarvis.memory.database-path': work / 'memory.db', 'knowledge.root': work / 'knowledge',
        'knowledge.vault.index-database': work / 'index.db', 'knowledge.watch': 'false',
        'jarvis.workspace.root': work / 'workspaces', 'jarvis.ai.identity-file': 'file:' + str(work / 'identity.md'),
        'jarvis.model.active-model-file': work / 'active-model.txt',
        'jarvis.ollama.base-url': f'http://127.0.0.1:{stub.server_port}',
        'jarvis.mcp.enabled': 'false', 'jarvis.web-search.enabled': 'false', 'jarvis.location.enabled': 'false',
        'jarvis.memory.background.enabled': 'false', 'jarvis.memory.autonomous.enabled': 'false',
        'jarvis.memory.dreaming.enabled': 'false', 'jarvis.companion.history.enabled': 'false',
        'jarvis.diagnostics.conversation-trace.enabled': 'false',
    }
    env = {k: v for k, v in os.environ.items() if not k.startswith(('JARVIS_', 'SPRING_', 'OLLAMA_'))
           and k not in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH')}
    log = work / 'startup.log'
    proc = None
    result = {'artifactInspection': 'PASS', 'storage': str(work), 'log': str(log), 'checks': []}
    try:
        with log.open('wb') as output:
            proc = subprocess.Popen([args.java, '-jar', str(jar), *[f'--{k}={v}' for k, v in options.items()]],
                                    cwd=work, env=env, stdout=output, stderr=subprocess.STDOUT)
            base = f'http://127.0.0.1:{port}'
            deadline = time.monotonic() + 90
            while True:
                if proc.poll() is not None:
                    raise RuntimeError('Production JAR exited; inspect isolated startup log')
                try:
                    status, health = request(base, '/api/health')
                    if status == 200 and health.get('modelStatus') == 'READY':
                        break
                except (OSError, ValueError):
                    pass
                if time.monotonic() > deadline:
                    raise TimeoutError('Production JAR readiness deadline exceeded')
                time.sleep(0.25)
            assert set(('status', 'version', 'modelStatus')).issubset(health)
            assert 'providerMode' not in health and 'environment' not in health
            result['checks'].append('Real production health endpoint and READY model warmup via loopback stub')
            assert request(base, '/api/v1/companion/capabilities')[0] == 401
            assert request(base, '/api/v1/auth/me')[0] == 401
            assert request(base, '/api/v1/auth/bootstrap-status')[1]['bootstrapRequired'] is True
            password = secrets.token_urlsafe(24)
            credentials = {'email': 'smoke@example.test', 'password': password, 'displayName': 'Isolated smoke'}
            status, account = request(base, '/api/v1/auth/bootstrap', credentials)
            assert status == 200
            status, account = request(base, '/api/v1/auth/login', credentials)
            assert status == 200
            token = account['accessToken']
            assert request(base, '/api/v1/auth/me', token=token)[0] == 200
            invalid_login = request(base, '/api/v1/auth/login', {**credentials, 'password': 'incorrect'})[0]
            # Preserve existing API behavior: global IllegalArgumentException advice
            # currently handles the wrapped login cause as 400. Do not alter auth in
            # a component-isolation hotfix; anonymous protected routes still use 401.
            assert invalid_login == 400
            result['existingInvalidLoginStatus'] = invalid_login
            result['checks'].append('Real isolated bootstrap/login/me, unauthorized requests and invalid password rejected')
            status, capabilities = request(base, '/api/v1/companion/capabilities', token=token)
            assert status == 200 and capabilities['contractVersion'] == '1.4.0'
            assert capabilities['providerMode'] == 'LIVE' and capabilities['taskPlans'] is True
            assert capabilities['durableHistory'] is False
            state = request(base, '/api/v1/companion/state', token=token)[1]
            assert 'serverInstanceId' in state and 'latestSequence' in state
            result['checks'].append('Authenticated Companion capabilities/state, API 1.4.0 and LIVE transport')
            assert websocket(port) == 401
            assert websocket(port, token) == 101
            result['checks'].append('Real Companion WebSocket rejects anonymous upgrade and accepts bearer authentication')
            result['result'] = 'PASS'
    finally:
        if proc is not None and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=5)
        stub.shutdown()
        args.report.resolve().parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()

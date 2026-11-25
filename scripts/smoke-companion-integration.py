"""Smoke the separate NONPRODUCTION JAR, without reusing live integration state."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import zipfile

spec = importlib.util.spec_from_file_location('jar_smoke', Path(__file__).with_name('smoke-production-jar.py'))
transport = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transport)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--java', required=True)
    p.add_argument('--jar', required=True, type=Path)
    p.add_argument('--report', required=True, type=Path)
    args = p.parse_args()
    jar = args.jar.resolve()
    with zipfile.ZipFile(jar) as archive:
        assert 'Start-Class: com.jarvis.integration.CompanionIntegrationServer' in archive.read('META-INF/MANIFEST.MF').decode()
    base_dir = Path(__file__).resolve().parent.parent / 'data/startup-smoke'
    base_dir.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='integration-', dir=base_dir)).resolve()
    with socket.socket() as reserve:
        reserve.bind(('127.0.0.1', 0))
        port = reserve.getsockname()[1]
    token, password = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    env = {k: v for k, v in os.environ.items() if not k.startswith(('JARVIS_', 'SPRING_', 'OLLAMA_'))
           and k not in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH')}
    env.update(JARVIS_INTEGRATION_TOKEN=token, JARVIS_INTEGRATION_PASSWORD=password,
               JARVIS_INTEGRATION_HISTORY=str(root / 'history.db'), JARVIS_INTEGRATION_PORT=str(port))
    proc = None
    result = {'storage': str(root), 'checks': []}
    try:
        with (root / 'startup.log').open('wb') as log:
            proc = subprocess.Popen([args.java, '-jar', str(jar)], cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
            base = f'http://127.0.0.1:{port}'
            deadline = time.monotonic() + 60
            while True:
                if proc.poll() is not None:
                    raise RuntimeError('Integration JAR exited; inspect isolated log')
                try:
                    status, health = transport.request(base, '/api/health')
                    if status == 200:
                        break
                except OSError:
                    pass
                if time.monotonic() > deadline:
                    raise TimeoutError('Integration startup deadline exceeded')
                time.sleep(0.25)
            assert health['providerMode'] == 'SIMULATED'
            assert transport.request(base, '/api/v1/companion/capabilities')[0] == 401
            status, account = transport.request(base, '/api/v1/auth/login', {'email': 'companion@example.test', 'password': password})
            assert status == 200 and account['accessToken'] == token
            cap = transport.request(base, '/api/v1/companion/capabilities', token=token)[1]
            assert cap['contractVersion'] == '1.4.0' and cap['providerMode'] == 'SIMULATED'
            assert transport.websocket(port, token) == 101
            result['checks'].append('Separate JAR startup, test login, bearer REST/WebSocket and SIMULATED 1.4.0 capabilities')
            status, task = transport.request(base, '/api/v1/companion/tasks', {'message': '[SIMULATED_PLAN] smoke', 'role': 'TASK'}, token)
            assert status == 202
            task_id = task['taskId']
            deadline = time.monotonic() + 20
            while True:
                task = transport.request(base, '/api/v1/companion/tasks/' + task_id, token=token)[1]
                if task['state'] in ('COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'):
                    break
                assert time.monotonic() < deadline
                time.sleep(0.1)
            assert task['state'] == 'COMPLETED'
            plan = transport.request(base, '/api/v1/companion/tasks/' + task_id + '/plan', token=token)[1]
            assert plan['status'] == 'COMPLETED' and len(plan['steps']) == 3
            result['checks'].append('SIMULATED native plan fixture completes with three steps in separate isolated storage')
            result['result'] = 'PASS'
    finally:
        if proc is not None and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=5)
        args.report.resolve().parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()

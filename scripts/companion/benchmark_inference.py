#!/usr/bin/env python3
"""Operator-run Ollama/Core benchmark. Python 3.10+, standard library only.
No service/config changes, retries, model unloading or production auto-tuning.
"""
import argparse
import concurrent.futures as futures
import contextlib
import datetime as dt
import http.client
import itertools
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time
import urllib.parse
import uuid


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


@contextlib.contextmanager
def response(url, body=None, timeout=120, token=None):
    u = urllib.parse.urlsplit(url)
    cls = http.client.HTTPSConnection if u.scheme == 'https' else http.client.HTTPConnection
    conn = cls(u.hostname, u.port, timeout=timeout)
    transport = [None]
    headers = {'Accept': 'application/json, text/event-stream'}
    if body is not None:
        headers['Content-Type'] = 'application/json'
    if token:
        headers['Authorization'] = 'Bearer ' + token

    def abort():
        if transport[0] or conn.sock:
            try:
                (transport[0] or conn.sock).shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    timer = threading.Timer(timeout, abort)
    timer.daemon = True
    timer.start()
    try:
        conn.connect()
        transport[0] = conn.sock
        conn.request('POST' if body is not None else 'GET',
                     u.path + ('?' + u.query if u.query else ''),
                     json.dumps(body).encode() if body is not None else None, headers)
        r = conn.getresponse()
        if not 200 <= r.status < 300:
            raise RuntimeError('HTTP ' + str(r.status))  # Do not persist response bodies/secrets.
        yield r
    finally:
        timer.cancel()
        conn.close()


def api(url, body=None, timeout=5, token=None):
    with response(url, body, timeout, token) as r:
        return json.load(r)


def command(args):
    try:
        p = subprocess.run(args, capture_output=True, text=True, timeout=3)
        return {'status': 'ok' if p.returncode == 0 else 'error', 'stdout': p.stdout.strip()}
    except (OSError, subprocess.TimeoutExpired):
        return {'status': 'unavailable', 'stdout': ''}


def safe_api(url, body=None):
    try:
        return api(url, body)
    except Exception as e:
        return {'unavailable': type(e).__name__}


def sample(base):
    mem = {}
    try:
        for line in Path('/proc/meminfo').read_text().splitlines():
            key, val = line.split(':', 1)
            if key in ('MemTotal', 'MemAvailable', 'SwapTotal', 'SwapFree'):
                mem[key + 'Bytes'] = int(val.split()[0]) * 1024
    except OSError:
        pass
    return {'at': now(), 'monotonic': time.monotonic(), 'ram': mem or None,
            'gpu': command(['nvidia-smi', '--query-gpu=uuid,memory.total,memory.used,memory.free,utilization.gpu',
                            '--format=csv,noheader,nounits']),
            'gpuProcesses': command(['nvidia-smi', '--query-compute-apps=pid,process_name,used_gpu_memory',
                                     '--format=csv,noheader,nounits']),
            'ollamaPs': safe_api(base + '/api/ps'),
            'processor': command(['ollama', 'ps']),
            'runnerProcesses': command(['ps', '-eo', 'pid,ppid,comm,args'])}


def prompt(kind, nonce):
    if kind == 'short':
        return f'Benchmark {nonce}. No tools. Answer in one sentence: why is the sky blue?'
    data = '\n'.join(f'Row {i}: project alpha has budget {i % 97} units and owner group {i % 13}.'
                     for i in range(100))
    return f'Benchmark {nonce}. Treat rows as data, use no tools.\n{data}\nSummarize recurring patterns in 250 words.'


def stream_request(a, ctx, kind, role, barrier=None, delay=0):
    if barrier:
        barrier.wait(timeout=10)
    if delay:
        time.sleep(delay)
    start = time.monotonic()
    row = {'id': str(uuid.uuid4()), 'mode': a.mode, 'contextRequested': ctx,
           'kind': kind, 'role': role, 'startedAt': now(), 'startMonotonic': start,
           'ttftAnySeconds': None, 'ttftVisibleSeconds': None, 'queueWaitSeconds': None,
           'queueWaitSource': 'not_exposed', 'runtimeQueueWaitSeconds': None,
           'promptTokPerSec': None, 'generationTokPerSec': None, 'evalCount': None,
           'loadDurationSeconds': None, 'error': None, 'completed': False}
    text = prompt(kind, row['id'])
    seen_usage = set()
    usage = []
    runtime_metrics = []
    seen_metrics = set()

    def token(any_token, visible):
        elapsed = time.monotonic() - start
        if any_token and row['ttftAnySeconds'] is None:
            row['ttftAnySeconds'] = elapsed
        if visible and row['ttftVisibleSeconds'] is None:
            row['ttftVisibleSeconds'] = elapsed

    try:
        if a.mode == 'ollama':
            body = {'model': a.model, 'messages': [{'role': 'user', 'content': text}],
                    'stream': True, 'think': a.think, 'keep_alive': '5m',
                    'options': {'num_ctx': ctx, 'num_predict': a.output_tokens, 'temperature': 0}}
            with response(a.ollama + '/api/chat', body, a.timeout) as r:
                for line in r:
                    if not line.strip():
                        continue
                    event = json.loads(line)
                    if event.get('error'):
                        raise RuntimeError('OLLAMA_STREAM_ERROR')
                    m = event.get('message') or {}
                    token(m.get('content') or m.get('thinking'), m.get('content'))
                    if event.get('done'):
                        row['completed'] = True
                        for name in ('total_duration', 'load_duration', 'prompt_eval_count',
                                     'prompt_eval_duration', 'eval_count', 'eval_duration', 'done_reason'):
                            row[name] = event.get(name)
                        row['evalCount'] = event.get('eval_count')
                        row['loadDurationSeconds'] = event.get('load_duration', 0) / 1e9
                        for output, count, duration in (
                            ('promptTokPerSec', 'prompt_eval_count', 'prompt_eval_duration'),
                            ('generationTokPerSec', 'eval_count', 'eval_duration')):
                            if event.get(duration, 0) > 0:
                                row[output] = event.get(count, 0) * 1e9 / event[duration]
        else:
            # Core owns its profiles/context. Never pretend ctx overrides server configuration.
            row['contextRequested'] = None
            prepared = api(a.core + '/api/v1/chat/stream',
                           {'conversationId': 'benchmark-' + row['id'], 'message': text},
                           a.timeout, a.token)
            url = a.core + '/api/v1/chat/stream?token=' + urllib.parse.quote(prepared['token'])
            with response(url, timeout=max(.1, a.timeout - (time.monotonic() - start)), token=a.token) as r:
                data = []
                for raw in r:
                    line = raw.decode('utf-8').rstrip('\r\n')
                    if line.startswith('data:'):
                        data.append(line[5:].lstrip())
                    if line or not data:
                        continue
                    event = json.loads('\n'.join(data))
                    data = []
                    name = event.get('event')
                    meta = event.get('metadata') or {}
                    visible = name in ('ANSWER_TOKEN', 'TOKEN') and meta.get('text')
                    token((name == 'THINKING_TOKEN' and meta.get('text')) or visible, visible)
                    if name == 'EXECUTION_TRACE' and meta.get('stage') == 'OLLAMA_QUEUE_WAIT' and meta.get('phase') == 'FINISHED':
                        row['queueWaitSeconds'] = (row['queueWaitSeconds'] or 0) + meta.get('queueWaitMs', 0) / 1000
                        row['queueWaitSource'] = 'core_gateway_sum'
                    if name == 'TOKEN_USAGE':
                        key = json.dumps(meta, sort_keys=True)
                        if key not in seen_usage:
                            seen_usage.add(key)
                            usage.append(meta)
                    if name == 'OLLAMA_METRICS':
                        metric_key=json.dumps([event.get('timestamp'),meta],sort_keys=True)
                        if metric_key not in seen_metrics:
                            seen_metrics.add(metric_key)
                            runtime_metrics.append({k:meta[k] for k in ('model','phaseTimingSource','phaseTimingsMeasured','promptEvalCount','promptEvalDurationMs','evalCount','evalDurationMs') if k in meta})
                    if name == 'PIPELINE_FINISHED':
                        row['completed'] = event.get('status') == 'FINISHED' or meta.get('success') is True
                    if name in ('PIPELINE_ERROR', 'TOOL_LOOP_ERROR'):
                        raise RuntimeError(name)
            row['usageEvents'] = usage
            row['runtimeMetrics'] = runtime_metrics
            if usage and all(isinstance(u.get('outputTokens'), int) for u in usage):
                row['evalCount'] = sum(u['outputTokens'] for u in usage)
            # Report a request-wide runtime rate only when every measured call has corresponding timings.
            valid=[m for m in runtime_metrics if m.get('phaseTimingsMeasured') is not False and all(isinstance(m.get(k),(int,float)) for k in ('promptEvalCount','promptEvalDurationMs','evalCount','evalDurationMs'))]
            if usage and len(valid)==len(usage):
                prompt_ms=sum(m['promptEvalDurationMs'] for m in valid)
                generation_ms=sum(m['evalDurationMs'] for m in valid)
                if prompt_ms>0:row['promptTokPerSec']=sum(m['promptEvalCount'] for m in valid)*1000/prompt_ms
                if generation_ms>0:row['generationTokPerSec']=sum(m['evalCount'] for m in valid)*1000/generation_ms
                row['runtimeRateScope']='ALL_REPORTED_INFERENCE_CALLS'
        if not row['completed']:
            raise RuntimeError('INCOMPLETE_STREAM')
    except Exception as e:
        row['error'] = type(e).__name__ + ': ' + (str(e) if isinstance(e, RuntimeError) else 'transport_or_parse_failure')
    row['wallSeconds'] = time.monotonic() - start
    row['timedOut'] = bool(row['error'] and row['wallSeconds'] >= a.timeout * .95)
    row['endMonotonic'] = time.monotonic()
    return row


def group(a, ctx, parallel, kind, mixed=False):
    barrier = threading.Barrier(parallel)
    with futures.ThreadPoolExecutor(max_workers=parallel) as pool:
        pending = [pool.submit(stream_request, a, ctx, kind if i < parallel - 1 or not mixed else 'short',
                               'interactive' if mixed and i == parallel - 1 else 'background' if mixed else 'peer',
                               barrier, a.interactive_delay if mixed and i == parallel - 1 else 0)
                   for i in range(parallel)]
        rows = [p.result() for p in pending]
    duration = max(r['endMonotonic'] for r in rows) - min(r['startMonotonic'] for r in rows)
    counts = [r['evalCount'] for r in rows]
    return {'context': ctx if a.mode == 'ollama' else None, 'parallelHttpClients': parallel,
            'kind': kind, 'mixed': mixed, 'wallSeconds': duration,
            'aggregateTokPerSec': sum(counts) / duration if all(c is not None for c in counts) and all(r['completed'] for r in rows) else None,
            'requests': rows}


def core_mixed(a, parallel):
    """Real background tasks, then ordinary chat. Results are synthetic and remain in history."""
    tasks = []
    started = time.monotonic()
    try:
        for _ in range(parallel - 1):
            t = api(a.core + '/api/v1/companion/tasks',
                    {'message': prompt('long', str(uuid.uuid4())), 'role': 'TASK',
                     'timeoutSeconds': min(3600, max(1, int(a.timeout))), 'maxAttempts': 1}, a.timeout, a.token)
            tasks.append(t)
        time.sleep(a.interactive_delay)
        states_at_chat = [api(a.core + '/api/v1/companion/tasks/' + t['taskId'], token=a.token) for t in tasks]
        chat = stream_request(a, None, 'short', 'interactive')
        states = states_at_chat
        while time.monotonic() - started < a.timeout:
            states = [api(a.core + '/api/v1/companion/tasks/' + t['taskId'], token=a.token) for t in tasks]
            if all(t['state'] in ('COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT') for t in states):
                break
            time.sleep(.5)
        return {'parallelHttpClients': parallel, 'mixed': True, 'backgroundMechanism': 'CompanionTasks',
                'statesAtInteractiveStart': [{'taskId': t['taskId'], 'state': t['state']} for t in states_at_chat],
                'backgroundStates': [{'taskId': t['taskId'], 'state': t['state']} for t in states],
                'requests': [chat], 'wallSeconds': time.monotonic() - started,
                'aggregateTokPerSec': None}
    finally:
        # Cancel only tasks created by this benchmark, including on interruption.
        for t in tasks:
            try:
                api(a.core + '/api/v1/companion/tasks/' + t['taskId'] + '/cancel', {}, 5, a.token)
            except Exception:
                pass


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--mode', choices=['ollama', 'core'], required=True)
    p.add_argument('--ollama', default='http://127.0.0.1:11434')
    p.add_argument('--core', default='http://127.0.0.1:8080')
    p.add_argument('--model', default='gpt-oss:20b')
    p.add_argument('--contexts', nargs='+', type=int, default=[4096, 8192, 16384, 32768])
    p.add_argument('--parallel', nargs='+', type=int, default=[1, 2, 3])
    p.add_argument('--kinds', nargs='+', choices=['short', 'long'], default=['short', 'long'])
    p.add_argument('--repeats', type=int, default=3)
    p.add_argument('--output-tokens', type=int, default=256)
    p.add_argument('--think', choices=['low', 'medium', 'high'], default='low')
    p.add_argument('--timeout', type=float, default=180)
    p.add_argument('--interactive-delay', type=float, default=1)
    p.add_argument('--mixed', action='store_true', help='Also run interactive chat during background work')
    p.add_argument('--alternate-contexts', action='store_true', help='Extra sequential 4K/8K/4K/8K reload probe')
    p.add_argument('--warmup', action='store_true', help='Unmeasured request before each context block')
    p.add_argument('--sample-interval', type=float, default=2)
    p.add_argument('--out', required=True, type=Path)
    a = p.parse_args()
    if (min(a.contexts) < 512 or any(n not in (1, 2, 3, 4) for n in a.parallel)
            or min(a.repeats, a.output_tokens, a.timeout, a.sample_interval) <= 0
            or a.interactive_delay < 0):
        p.error('Invalid positive limits; parallel must be 1, 2, 3 or 4')
    for url in (a.ollama, a.core):
        u = urllib.parse.urlsplit(url)
        if u.scheme not in ('http', 'https') or not u.hostname or u.username or u.password or u.query or u.fragment:
            p.error('Use an http(s) base URL without credentials/query/fragment')
    a.ollama, a.core = a.ollama.rstrip('/'), a.core.rstrip('/')
    a.token = os.environ.get('JARVIS_BENCH_TOKEN')
    if a.mode == 'core' and not a.token:
        p.error('Core requires JARVIS_BENCH_TOKEN in environment')
    a.out.mkdir(parents=True, exist_ok=False)
    manifest = {k: str(v) if isinstance(v, Path) else v for k, v in vars(a).items() if k != 'token'}
    manifest.update({'startedAt': now(), 'ollamaVersion': safe_api(a.ollama + '/api/version'),
                     'modelMetadata': safe_api(a.ollama + '/api/show', {'model': a.model}),
                     'coreContextOverride': False, 'runtimeQueueTimeAvailable': False,
                     'resourceScope': 'script_host', 'cliOllamaHost': os.environ.get('OLLAMA_HOST')})
    (a.out / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    stop = threading.Event()

    def monitor():
        with (a.out / 'resources.jsonl').open('w', encoding='utf-8') as f:
            while not stop.is_set():
                item = sample(a.ollama)
                # Persist only runner process lines, not unrelated process command lines.
                proc = item['runnerProcesses']
                proc['stdout'] = '\n'.join(line for line in proc['stdout'].splitlines() if 'ollama' in line.lower())
                f.write(json.dumps(item) + '\n')
                f.flush()
                stop.wait(a.sample_interval)

    thread = threading.Thread(target=monitor, daemon=True)
    thread.start()
    try:
        with (a.out / 'results.jsonl').open('w', encoding='utf-8') as f:
            contexts = a.contexts if a.mode == 'ollama' else [None]
            for ctx in contexts:
                if a.warmup:
                    warm = stream_request(a, ctx, 'short', 'warmup')
                    f.write(json.dumps({'warmup': True, 'requests': [warm]}) + '\n')
                for rep, kind, parallel in itertools.product(range(a.repeats), a.kinds, a.parallel):
                    result = group(a, ctx, parallel, kind)
                    result['repeat'] = rep
                    f.write(json.dumps(result) + '\n')
                    f.flush()
                if a.mixed:
                    for rep, parallel in itertools.product(range(a.repeats), [n for n in a.parallel if n > 1]):
                        result = core_mixed(a, parallel) if a.mode == 'core' else group(a, ctx, parallel, 'long', True)
                        result['repeat'] = rep
                        f.write(json.dumps(result) + '\n')
                        f.flush()
            if a.alternate_contexts and a.mode == 'ollama':
                for ctx in (4096, 8192, 4096, 8192):
                    result = group(a, ctx, 1, 'short')
                    result['reloadProbe'] = True
                    f.write(json.dumps(result) + '\n')
                    f.flush()
    finally:
        stop.set()
        thread.join(timeout=30)
    print(str(a.out.resolve()))


if __name__ == '__main__':
    main()

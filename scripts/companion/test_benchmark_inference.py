"""Local transport/measurement checks; no Ollama, GPU or Core required."""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import time
import unittest

import benchmark_inference as bench


class Handler(BaseHTTPRequestHandler):
    active = 0
    peak = 0
    lock = threading.Lock()
    behavior = 'ok'

    def log_message(self, *args):
        pass

    def send(self, content, mime='application/json'):
        self.send_response(200)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(len(content)))
        self.end_headers()
        self.wfile.write(content)
        self.wfile.flush()

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if self.path.endswith('/stream'):
            self.send(b'{"token":"test"}')
            return
        self.server.last_body = body
        with Handler.lock:
            Handler.active += 1
            Handler.peak = max(Handler.active, Handler.peak)
        try:
            time.sleep(.12 if Handler.behavior != 'stall' else .5)
            if Handler.behavior == 'error':
                self.send_error(503)
                return
            events = [{'message': {'thinking': 'reason'}}, {'message': {'content': 'answer'}}]
            if Handler.behavior != 'incomplete':
                events.append({'done': True, 'eval_count': 8, 'eval_duration': 1000000000,
                               'prompt_eval_count': 16, 'prompt_eval_duration': 2000000000,
                               'load_duration': 1000000})
            self.send(('\n'.join(json.dumps(e) for e in events) + '\n').encode())
        except (OSError, ConnectionError):
            pass
        finally:
            with Handler.lock:
                Handler.active -= 1

    def do_GET(self):
        events = [
            {'event': 'THINKING_TOKEN', 'metadata': {'text': 'reason'}},
            {'event': 'ANSWER_TOKEN', 'metadata': {'text': 'answer'}},
            {'event': 'TOKEN', 'metadata': {'text': 'answer'}},
            {'event': 'EXECUTION_TRACE', 'metadata': {'stage': 'OLLAMA_QUEUE_WAIT',
                 'phase': 'FINISHED', 'queueWaitMs': 250}},
            {'event': 'TOKEN_USAGE', 'metadata': {'callId': '1', 'outputTokens': 8}},
            {'event': 'TOKEN_USAGE', 'metadata': {'callId': '1', 'outputTokens': 8}},
            {'event': 'OLLAMA_METRICS', 'metadata': {'phaseTimingsMeasured': True, 'promptEvalCount': 16, 'promptEvalDurationMs': 2000, 'evalCount': 8, 'evalDurationMs': 1000}},
            {'event': 'PIPELINE_FINISHED', 'status': 'FINISHED', 'metadata': {'success': True}}
        ]
        self.send((''.join('data: ' + json.dumps(e) + '\n\n' for e in events)).encode(), 'text/event-stream')


class BenchmarkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = 'http://127.0.0.1:' + str(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        Handler.behavior = 'ok'
        Handler.peak = 0
        self.a = argparse.Namespace(mode='ollama', ollama=self.url, core=self.url, model='gpt-oss:20b',
                 think='low', output_tokens=64, timeout=2, token='dummy', interactive_delay=.01)

    def test_concurrent_http_and_measured_rates(self):
        for n in (1, 2, 3, 4):
            Handler.peak = 0
            result = bench.group(self.a, 4096, n, 'short')
            self.assertEqual(Handler.peak, n)
            self.assertGreater(result['aggregateTokPerSec'], 0)
            for r in result['requests']:
                self.assertIsNone(r['error'])
                self.assertEqual(r['generationTokPerSec'], 8)
                self.assertEqual(r['promptTokPerSec'], 8)
                self.assertIsNone(r['runtimeQueueWaitSeconds'])
                self.assertGreaterEqual(r['ttftVisibleSeconds'], r['ttftAnySeconds'])
        self.assertEqual(self.server.last_body['options']['num_ctx'], 4096)
        self.assertEqual(self.server.last_body['options']['num_predict'], 64)

    def test_core_sse_and_usage_deduplication(self):
        self.a.mode = 'core'
        r = bench.stream_request(self.a, None, 'short', 'peer')
        self.assertTrue(r['completed'])
        self.assertEqual(r['evalCount'], 8)
        self.assertEqual(r['queueWaitSeconds'], .25)
        self.assertIsNotNone(r['ttftVisibleSeconds'])
        self.assertEqual(r['generationTokPerSec'], 8)
        self.assertEqual(r['promptTokPerSec'], 8)

    def test_incomplete_stream_is_failure(self):
        Handler.behavior = 'incomplete'
        result = bench.group(self.a, 4096, 1, 'short')
        self.assertIn('INCOMPLETE_STREAM', result['requests'][0]['error'])
        self.assertIsNone(result['aggregateTokPerSec'])

    def test_http_error(self):
        Handler.behavior = 'error'
        r = bench.stream_request(self.a, 4096, 'short', 'peer')
        self.assertIn('HTTP 503', r['error'])

    def test_deadline_interrupts_stalled_transport(self):
        Handler.behavior = 'stall'
        self.a.timeout = .05
        r = bench.stream_request(self.a, 4096, 'short', 'peer')
        self.assertIsNotNone(r['error'])
        self.assertLess(r['wallSeconds'], .4)
        time.sleep(.55)  # Let the synthetic server finish before the next overlap test.


if __name__ == '__main__':
    unittest.main()

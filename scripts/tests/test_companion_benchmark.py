import importlib.util,json,threading,unittest
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
spec=importlib.util.spec_from_file_location('benchmark',Path(__file__).parents[1]/'benchmark-companion-inference.py')
benchmark=importlib.util.module_from_spec(spec);spec.loader.exec_module(benchmark)
class BenchmarkTest(unittest.TestCase):
    def test_stream_metrics_against_local_fake(self):
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                request=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                self.server.payload=request
                self.send_response(200);self.end_headers()
                for frame in [{'thinking':'plan'},{'response':'answer'},{'done':True,'eval_count':20,'eval_duration':2000000000,'load_duration':1000000}]:self.wfile.write((json.dumps(frame)+'\n').encode())
            def log_message(self,*args):pass
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            result=benchmark.measure('http://127.0.0.1:'+str(server.server_port),'mock-model',4096,0,0)
            self.assertEqual(10,result['tokens_per_second']);self.assertTrue(result['interactive'])
            self.assertGreaterEqual(result['first_visible_token_seconds'],result['ttft_seconds'])
            self.assertEqual(4096,server.payload['options']['num_ctx'])
        finally:server.shutdown();server.server_close();thread.join()
if __name__=='__main__':unittest.main()

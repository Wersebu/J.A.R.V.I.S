#!/usr/bin/env python3
"""Production JAR regression: multiplexed authenticated Windows chat + real HTTP web adapter.
Ollama and SearXNG are local fixtures, never production inference or live factual evidence.
"""
import argparse, base64, json, os, re, socket, struct, subprocess, threading, time, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from verify_packaged_core import request, user_text

QUESTIONS = ['Jaka jest aktualna pogoda w Warszawie?', 'Jaki jest aktualny kurs USD do PLN?', 'Sprawdź w internecie, kiedy wychodzi GTA VI.']
SOURCES = ['https://weather.example.test/warsaw', 'https://nbp.example.test/usd-pln', 'https://publisher.example.test/gta-vi']
ANSWERS = ['FIXTURE pogoda Warszawa: 12 C, obserwacja 2026-11-26.', 'FIXTURE USD/PLN kurs referencyjny: 4.1234 PLN, data 2026-11-26.', 'FIXTURE GTA VI: przykładowa data wydania 2027-01-01; nie jest to prawdziwa informacja.']

def topic(text):
    text=text.lower()
    return 0 if 'pogod' in text else 1 if 'usd' in text else 2

class Services(BaseHTTPRequestHandler):
    protocol_version="HTTP/1.1"
    calls=[]; searches=[]; holds={}; entered=set(); unavailable=False; empty=False; model_unavailable=False
    def log_message(self,*args): pass
    def respond(self, data, code=200, streaming=False):
        wire=(data if isinstance(data,bytes) else json.dumps(data).encode())
        self.send_response(code);self.send_header('Content-Type','application/x-ndjson' if streaming else 'application/json')
        self.send_header('Content-Length',str(len(wire)));self.send_header('Connection','close');self.end_headers();self.close_connection=True
        try:self.wfile.write(wire);self.wfile.flush()
        except ConnectionError:pass
    def do_GET(self):
        if self.path.startswith('/search?'):
            params=parse_qs(urlparse(self.path).query);q=params.get('q',[''])[0];Services.searches.append(params)
            assert params.get('format')==['json']
            if Services.unavailable:return self.respond({'error':'fixture unavailable'},503)
            i=topic(q)
            return self.respond({'results':[] if Services.empty else [{'title':q,'url':SOURCES[i],'content':q+' '+ANSWERS[i],'engine':'fixture','publishedDate':'2026-11-26'}]})
        if self.path=='/api/tags':return self.respond({'models':[{'name':'gpt-oss:20b','model':'gpt-oss:20b','digest':'concurrency-fixture','size':100,'details':{'family':'gptoss'}}]})
        return self.respond({'models':[]})
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if self.path=='/api/show':return self.respond({'capabilities':['completion','tools','thinking'],'model_info':{'general.architecture':'gptoss','gptoss.context_length':131072}})
        Services.calls.append(body)
        if Services.model_unavailable:return self.respond({"error":"fixture model unavailable"},503)
        prompt=body.get('prompt','');match=re.search(r'=== CURRENT USER MESSAGE ===(.*?)=== END CURRENT USER MESSAGE ===',prompt,re.S)
        current=match.group(1).strip() if match else prompt
        title=body.get('options',{}).get('num_predict')==128
        marker=re.search(r'interactive-hold-[0-9]+',current)
        if marker and not title:
            key=marker.group();Services.entered.add(key)
            if not Services.holds[key].wait(30):raise AssertionError('Fixture hold not released: '+key)
        answer='Fixture title' if title else json.dumps({'type':'FINAL_ANSWER','answer':'Fixture answer: '+current[:160]})
        message={'role':'assistant','content':answer}
        if body.get('tools'):
            names={t['function']['name'] for t in body['tools']}
            assert names=={'web__search_web','web__read_web_page','output__read'},names
            question=next((m['content'] for m in reversed(body['messages']) if m['role']=='user'),'')
            tool_messages=[m for m in body['messages'] if m['role']=='tool']
            if not tool_messages:
                message={'role':'assistant','content':'','tool_calls':[{'id':'web-search','function':{'name':'web__search_web','arguments':{'query':question,'maxResults':5,'profile':'CURRENT_FACT'}}}]}
            else:
                i=topic(question);message={'role':'assistant','content':ANSWERS[i]+' '+SOURCES[i]}
        if body.get('stream'):
            chunks=[{'response':answer,'message':message,'done':False},{'done':True,'done_reason':'stop','eval_count':8,'eval_duration':1000000,'prompt_eval_count':16,'prompt_eval_duration':1000000}]
            return self.respond(('\n'.join(json.dumps(c) for c in chunks)+'\n').encode(),streaming=True)
        self.respond({'response':answer,'message':message,'done':True,'eval_count':8,'eval_duration':1000000,'prompt_eval_count':16,'prompt_eval_duration':1000000})

class WS:
    def __init__(self,base,token):
        url=urlparse(base);self.sock=socket.create_connection((url.hostname,url.port),timeout=40);self.wire=self.sock.makefile('rb')
        key=base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((f'GET /ws/jarvis HTTP/1.1\r\nHost: {url.netloc}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer {token}\r\n\r\n').encode())
        assert b'101' in self.wire.readline()
        while self.wire.readline()!=b'\r\n':pass
        self.frames=[];self.condition=threading.Condition();self.closed=False
        threading.Thread(target=self.read,daemon=True).start()
    def read(self):
        try:
            while not self.closed:
                head=self.wire.read(2)
                if len(head)!=2:return
                size=head[1]&127
                if size==126:size=struct.unpack('!H',self.wire.read(2))[0]
                elif size==127:size=struct.unpack('!Q',self.wire.read(8))[0]
                data=self.wire.read(size)
                if head[0]&15==1:
                    with self.condition:self.frames.append(json.loads(data));self.condition.notify_all()
        except (OSError,ValueError):pass
    def send(self,payload):
        data=json.dumps(payload).encode();mask=os.urandom(4)
        length=bytes([128|len(data)]) if len(data)<126 else bytes([254])+struct.pack('!H',len(data))
        self.sock.sendall(bytes([129])+length+mask+bytes(b^mask[i%4] for i,b in enumerate(data)))
    def submit(self,conversation,message):
        payload={'requestId':str(uuid.uuid4()),'conversationId':conversation,'message':message};self.send(payload);return payload
    def wait(self,request_id,condition,timeout=15):
        end=time.monotonic()+timeout
        with self.condition:
            while True:
                own=[f for f in self.frames if f.get('requestId')==request_id]
                if condition(own):return own
                remaining=end-time.monotonic()
                if remaining<=0:raise AssertionError('Timeout '+request_id+': '+json.dumps(own[-5:]))
                self.condition.wait(min(remaining,.2))
    def done(self,payload,status='COMPLETED'):
        return self.wait(payload['requestId'],lambda es:any(e.get('type')==status for e in es))
    def close(self):
        self.closed=True
        try:self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:pass
        self.sock.close()

def wait_until(predicate,timeout=15):
    end=time.monotonic()+timeout
    while not predicate() and time.monotonic()<end:time.sleep(.03)
    assert predicate(),'Condition not reached'

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--java',required=True);parser.add_argument('--jar',type=Path,required=True);parser.add_argument('--out',type=Path,required=True);args=parser.parse_args()
    root=args.out.resolve();root.mkdir(parents=True,exist_ok=False)
    fake=ThreadingHTTPServer(('127.0.0.1',0),Services);threading.Thread(target=fake.serve_forever,daemon=True).start()
    with socket.socket() as port_socket:port_socket.bind(('127.0.0.1',0));port=port_socket.getsockname()[1]
    base=f'http://127.0.0.1:{port}';provider=f'http://127.0.0.1:{fake.server_port}'
    config={'server.address':'127.0.0.1','server.port':str(port),'jarvis.ollama.base-url':provider,'jarvis.web-search.base-url':provider,
            'jarvis.memory.database-path':str(root/'memory.db'),'knowledge.root':str(root/'knowledge'),'knowledge.watch':'false','knowledge.vault.index-database':str(root/'index.db'),
            'jarvis.workspace.root':str(root/'workspace'),'jarvis.ai.identity-file':'file:'+str(root/'identity.md'),'jarvis.model.active-model-file':str(root/'model.txt'),
            'jarvis.mcp.enabled':'false','jarvis.memory.autonomous.enabled':'false','jarvis.memory.dreaming.enabled':'false','jarvis.memory.background.enabled':'false','jarvis.diagnostics.conversation-trace.enabled':'false'}
    ws=None;process=None
    try:
        with (root/'startup.log').open('w',encoding='utf-8') as log:
            process=subprocess.Popen([args.java,'-jar',str(args.jar.resolve())]+['--'+k+'='+v for k,v in config.items()],cwd=root,stdout=log,stderr=subprocess.STDOUT)
            def ready():
                if process.poll() is not None:raise AssertionError('Core exited; see startup.log')
                try:return request(base,'/api/health').get('modelStatus')=='READY'
                except Exception:return False
            wait_until(ready,60)
            token=request(base,'/api/v1/auth/bootstrap',{'email':'concurrency@example.test','password':'Isolated-Concurrency-Fixture-2026!','displayName':'Fixture'})['accessToken']
            caps=request(base,'/api/v1/companion/capabilities',token=token)
            assert caps.get('interactiveChat',{}).get('multiplexing') is True,caps
            assert caps['interactiveChat']['sameConversationPolicy']=='FIFO'
            ws=WS(base,token)
            ws.send([]);ws.wait("",lambda es:any(e.get("type")=="CHAT_REJECTED" for e in es))
            held=[]
            for i in range(4):
                marker=f'interactive-hold-{i}';Services.holds[marker]=threading.Event();held.append(ws.submit(f'parallel-{i}',marker))
            wait_until(lambda:len(Services.entered)==4)
            queued=ws.submit('parallel-0','same conversation must be independent')
            ws.wait(queued['requestId'],lambda es:any(e.get('type')=='CHAT_QUEUED' and e.get('reason')=='CONVERSATION_ORDER' for e in es))
            cancelled_queue=ws.submit('parallel-0','cancelled before history admission')
            ws.wait(cancelled_queue['requestId'],lambda es:any(e.get('type')=='CHAT_QUEUED' for e in es))
            ws.send({'type':'CHAT_CANCEL','requestId':cancelled_queue['requestId'],'conversationId':'parallel-0'})
            ws.done(cancelled_queue,'CANCELLED')
            ws.send({'type':'CHAT_STEER','requestId':held[1]['requestId'],'conversationId':'parallel-1','message':'An explicit late instruction.'})
            ws.wait(held[1]['requestId'],lambda es:any(e.get('type')=='CHAT_STEER_ACCEPTED' for e in es))
            fifth=ws.submit('fifth','waiting for capacity')
            ws.wait(fifth['requestId'],lambda es:any(e.get('event')=='INFERENCE_QUEUED' and e.get('metadata',{}).get('reason')=='INFERENCE_CAPACITY' for e in es))
            ws.send({'type':'CHAT_CANCEL','requestId':fifth['requestId'],'conversationId':'fifth'});ws.done(fifth,'CANCELLED')
            for event in Services.holds.values():event.set()
            for p in held:ws.done(p)
            ws.done(queued)
            assert any(e.get('type')=='CHAT_STEER_UNCONSUMED' for e in ws.done(held[1]))
            messages=request(base,'/api/v1/conversations/parallel-0/messages',token=token)
            roles=[m['role'] for m in messages]
            assert [str(r).lower() for r in roles]==['user','assistant','user','assistant'],messages
            assert messages[0]['content']=='interactive-hold-0' and messages[2]['content']==queued['message'],messages
            assert [m['requestId'] for m in messages]==[held[0]['requestId']]*2+[queued['requestId']]*2,messages
            # Replay and exact resend retain identity and do not add another persisted user message.
            before=len(messages);ws.send(held[0]);time.sleep(.15)
            assert len(request(base,'/api/v1/conversations/parallel-0/messages',token=token))==before
            replay=WS(base,token)
            try:
                replay.send({'type':'CHAT_RESUME','requestId':held[0]['requestId'],'conversationId':'parallel-0','afterSeq':1})
                replayed=replay.done(held[0])
                assert all(e.get('seq',0)>1 for e in replayed if e.get('streamScope')=='REQUEST')
            finally:replay.close()
            one_results=[]
            for i,question in enumerate(QUESTIONS):
                p=ws.submit('web-'+str(i),question);events=ws.done(p);answer=user_text(events)
                assert SOURCES[i] in answer,answer
                assert not any(e.get('metadata',{}).get('tool')=='calendar' for e in events)
                one_results.append({'question':question,'answer':answer})
            combined=ws.submit('combined',' '.join(QUESTIONS));events=ws.done(combined)
            children=[t for t in request(base,'/api/v1/companion/tasks',token=token) if t.get('conversationId')=='combined']
            assert len(children)==3 and len({t['taskId'] for t in children})==3,children
            assert all(t['taskId'] in user_text(events) for t in children)
            def completed():
                return all(request(base,'/api/v1/companion/tasks/'+t['taskId'],token=token)['state']=='COMPLETED' for t in children)
            wait_until(completed)
            results=[request(base,'/api/v1/companion/tasks/'+t['taskId'],token=token)['result'] for t in children]
            assert all(any(source in result for result in results) for source in SOURCES),results
            Services.unavailable=True
            failed=ws.submit('unavailable',QUESTIONS[0]);failure=user_text(ws.done(failed))
            assert 'Nie zweryfikowano' in failure and '12 C' not in failure,failure
            Services.unavailable=False;Services.empty=True
            empty=ws.submit('empty',QUESTIONS[1]);empty_answer=user_text(ws.done(empty))
            assert '4.1234' not in empty_answer,empty_answer
            Services.empty=False
            Services.model_unavailable=True
            before_model_failure=sum(b.get('options',{}).get('num_predict')!=128 for b in Services.calls)
            model_failed=ws.submit('model-unavailable',QUESTIONS[0]);model_failure=user_text(ws.done(model_failed))
            assert 'Nie potwierdzam aktualnych danych' in model_failure,model_failure
            assert sum(b.get('options',{}).get('num_predict')!=128 for b in Services.calls)==before_model_failure+1,'Model failure must not trigger tool-less inference'
            Services.model_unavailable=False
            assert Services.searches and all(b.get('options',{}).get('num_ctx')==24576 for b in Services.calls)
            identities={p['requestId']:p['conversationId'] for p in held+[queued,cancelled_queue,fifth,combined,failed,empty]}
            for request_id,conversation in identities.items():
                frames=[e for e in ws.frames if e.get('requestId')==request_id and e.get('streamScope')=='REQUEST']
                assert frames and all(e['conversationId']==conversation for e in frames)
                assert len({e.get('type') for e in frames if e.get('type') in ('COMPLETED','CANCELLED','ERROR')})==1
                seqs={e['seq'] for e in frames};assert seqs==set(range(1,max(seqs)+1))
            result={'productionJar' :'PASS','mockedInference':True,'fakeSearxngHttp':'PASS','fourSameOwnerConversationsConcurrent':'PASS','sameConversationFifoHistory':'PASS','gatewayCapacityQueue':'PASS','requestCancellationIsolation':'PASS','queuedCancellationBeforeHistory':'PASS','explicitInstructionCorrelation':'PASS','requestReplay':'PASS','canonicalFrameIdentity':'PASS','retryNoDuplicateHistory':'PASS','ordinaryWebQuestions':one_results,'automaticThreeTaskDelegation':'PASS','providerUnavailable':'PASS','emptyResultsRejectFacts':'PASS','modelFailureNoToollessSynthesis':'PASS','physicalContext':24576}
            (root/'verification.json').write_text(json.dumps(result,indent=2,ensure_ascii=False),encoding='utf-8')
            (root/'events.json').write_text(json.dumps(ws.frames,ensure_ascii=False),encoding='utf-8')
            print(json.dumps({k:v for k,v in result.items() if k!='ordinaryWebQuestions'}))
    finally:
        for event in Services.holds.values():event.set()
        if ws:ws.close()
        if process:
            process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
        fake.shutdown();fake.server_close()

if __name__=='__main__':main()

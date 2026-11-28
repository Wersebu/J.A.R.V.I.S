#!/usr/bin/env python3
"""Real loopback-only backend verification. Run after Core has loaded a qualified model."""
import argparse, concurrent.futures, json, time, urllib.request, pathlib

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--port',type=int,default=8081)
    p.add_argument('--generate',action='store_true')
    p.add_argument('--tools',action='store_true')
    p.add_argument('--structured',action='store_true')
    p.add_argument('--parallel',action='store_true')
    p.add_argument('--label',choices=['warm','cold','unknown'],default='unknown')
    p.add_argument('--output',type=pathlib.Path,default=pathlib.Path('llamacpp-smoke.json'))
    args=p.parse_args()
    if not 1024<=args.port<=65535:p.error('port must be 1024..65535')
    base=f'http://127.0.0.1:{args.port}'
    def request(path,body=None):
        req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(req,timeout=180) as response:return json.load(response)
    report={'providerMode':'REAL_LLAMA_SERVER','label':args.label,'health':request('/health'),'models':request('/v1/models'),'tests':[]}
    if args.generate:
        model=report['models']['data'][0]['id']
        def run(kind):
            body={'model':model,'stream':False,'temperature':0,'max_tokens':256,'messages':[{'role':'user','content':'Reply with the single word OK.'}]}
            if kind=='structured':
                body['messages'][0]['content']='Return a JSON object with ok set to true.'
                body['response_format']={'type':'json_schema','json_schema':{'name':'smoke','strict':True,'schema':{'type':'object','properties':{'ok':{'type':'boolean'}},'required':['ok'],'additionalProperties':False}}}
            if kind=='tools':
                body['messages'][0]['content']='Call get_temperature for Warsaw. Do not answer from memory.'
                body['tools']=[{'type':'function','function':{'name':'get_temperature','description':'Get temperature for a city','parameters':{'type':'object','properties':{'city':{'type':'string'}},'required':['city']}}}]
            start=time.monotonic();r=request('/v1/chat/completions',body);elapsed=time.monotonic()-start
            msg=r['choices'][0]['message'];finish=r['choices'][0].get('finish_reason')
            passed=bool(msg.get('tool_calls')) if kind=='tools' else bool(msg.get('content','').strip())
            if kind=='structured':passed=passed and json.loads(msg['content']).get('ok') is True
            if finish=='length':passed=False
            return {'test':kind,'passed':passed,'elapsedSeconds':elapsed,'usage':r.get('usage'),'finishReason':finish,'toolCallCount':len(msg.get('tool_calls',[])),'toolsExecuted':False}
        report['tests'].append(run('text'))
        if args.structured:report['tests'].append(run('structured'))
        if args.tools:report['tests'].append(run('tools'))
        if args.parallel:
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:report['parallelTests']=list(pool.map(run,['text','text']))
    report['gpuGenerationVerified']=False
    args.output.write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps({'output':str(args.output),'tests':report['tests']}))
    if any(not t['passed'] for t in report['tests']):raise SystemExit(1)
if __name__=='__main__':main()

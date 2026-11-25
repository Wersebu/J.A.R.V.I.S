#!/usr/bin/env python3
"""Opt-in Linux Ollama benchmark. Does not change Ollama settings. Python standard library only."""
import argparse, concurrent.futures, json, pathlib, subprocess, threading, time, urllib.request

def measure(url, model, context, index, delay):
    time.sleep(delay)
    started=time.monotonic(); first=None; visible=None; count=0; evaluation_ns=0; load_ns=0; finished=False
    payload={"model":model,"prompt":"Explain a careful approach to planning a small software project in about 200 words.","stream":True,"options":{"num_ctx":context,"num_predict":256,"temperature":0}}
    request=urllib.request.Request(url.rstrip('/')+'/api/generate',data=json.dumps(payload).encode(),headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=600) as response:
        for line in response:
            frame=json.loads(line)
            if frame.get('error'):raise RuntimeError(frame['error'])
            if first is None and (frame.get('response') or frame.get('thinking')):first=time.monotonic()-started
            if visible is None and frame.get('response'):visible=time.monotonic()-started
            if frame.get('done'):finished=True;count=frame.get('eval_count',0);evaluation_ns=frame.get('eval_duration',0);load_ns=frame.get('load_duration',0)
    if not finished:raise RuntimeError("Ollama stream ended before completion")
    elapsed=time.monotonic()-started
    return {"request":index,"interactive":index==0,"ttft_seconds":first,"first_visible_token_seconds":visible,"model_load_seconds":load_ns/1e9,"latency_seconds":elapsed,"generated_tokens":count,"tokens_per_second":count/(evaluation_ns/1e9) if evaluation_ns else None}

def sample_vram(stop,samples):
    while not stop.is_set():
        try:
            result=subprocess.run(['nvidia-smi','--query-gpu=uuid,memory.used,memory.total','--format=csv,noheader,nounits'],capture_output=True,text=True,timeout=2,check=True)
            samples.append({"time":time.time(),"gpus":[{"uuid":parts[0].strip(),"used_mib":float(parts[1]),"total_mib":float(parts[2])} for parts in (line.split(",") for line in result.stdout.strip().splitlines())]})
        except (OSError,subprocess.SubprocessError,ValueError):pass
        stop.wait(.2)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run',action='store_true',help='Explicitly authorize inference load against the supplied endpoint')
    parser.add_argument('--url',default='http://127.0.0.1:11434');parser.add_argument('--model',default='gpt-oss:20b')
    parser.add_argument('--context',type=int,default=4096);parser.add_argument('--output',default='companion-benchmark.json');parser.add_argument('--repeats',type=int,default=3)
    args=parser.parse_args()
    if not args.run:parser.error('No requests sent. Supply --run only in an approved benchmark window.')
    if args.context<512 or args.repeats<1:parser.error('context >=512 and repeats >=1 required')
    rows=[]
    for simultaneous in (1,2,3):
        for repeat in range(args.repeats):
            samples=[];stop=threading.Event();sampler=threading.Thread(target=sample_vram,args=(stop,samples),daemon=True);sampler.start();started=time.monotonic()
            try:
                with concurrent.futures.ThreadPoolExecutor(max_workers=simultaneous) as pool:
                    # Start background requests first; interactive request arrives 250ms later.
                    futures=[pool.submit(measure,args.url,args.model,args.context,i,.25 if i==0 and simultaneous>1 else 0) for i in range(simultaneous)]
                    requests=[f.result() for f in futures]
                elapsed=time.monotonic()-started
                rows.append({"simultaneous":simultaneous,"repeat":repeat,"requests":requests,"aggregate_tokens_per_second":sum(r['generated_tokens'] for r in requests)/elapsed,"wall_seconds":elapsed,"vram_samples":samples})
            finally:stop.set();sampler.join(3)
            pathlib.Path(args.output).write_text(json.dumps({"model":args.model,"context":args.context,"endpoint":args.url,"note":"Client concurrency does not prove server parallelism. No daemon settings changed. Raw samples include other GPU processes.","runs":rows},indent=2))
    print(args.output)
if __name__=='__main__':main()

#!/usr/bin/env python3
"""Read-only operator check on the Core host. No inference, credentials or configuration changes."""
import argparse,json,time,urllib.request,urllib.parse,urllib.error

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url',default='http://127.0.0.1:8888');args=parser.parse_args()
    base=args.base_url.rstrip('/');parsed=urllib.parse.urlparse(base)
    if parsed.scheme not in ('http','https') or not parsed.hostname or parsed.username or parsed.password:
        raise SystemExit('Use an http(s) provider URL without embedded credentials')
    checks=[]
    for query in ['aktualna pogoda Warszawa','aktualny kurs USD PLN NBP','GTA VI release date Rockstar Games']:
        started=time.monotonic();record={'query':query}
        try:
            req=urllib.request.Request(base+'/search?'+urllib.parse.urlencode({'q':query,'format':'json'}),headers={'Accept':'application/json','User-Agent':'JARVIS-Provider-Readiness/1.0'})
            with urllib.request.urlopen(req,timeout=10) as response:
                body=json.load(response);results=body.get('results')
                record.update(httpStatus=response.status,jsonResultsArray=isinstance(results,list),resultsCount=len(results) if isinstance(results,list) else None)
        except urllib.error.HTTPError as error:record.update(httpStatus=error.code,error='HTTP_ERROR')
        except Exception as error:record.update(error=type(error).__name__)
        record['elapsedMs']=round((time.monotonic()-started)*1000);checks.append(record)
    print(json.dumps({'provider':base,'checks':checks,'inferenceInvoked':False},indent=2))
    return 0 if all(c.get('httpStatus')==200 and c.get('jsonResultsArray') for c in checks) else 1

if __name__=='__main__':raise SystemExit(main())

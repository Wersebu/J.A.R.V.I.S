#!/usr/bin/env python3
"""Validate published inference schemas and fixtures; optional isolated jsonschema library path."""
import argparse,copy,json,pathlib,sys
p=argparse.ArgumentParser();p.add_argument('--library-path',type=pathlib.Path);args=p.parse_args()
if args.library_path:sys.path.insert(0,str(args.library_path.resolve()))
from jsonschema import Draft202012Validator,FormatChecker
root=pathlib.Path(__file__).resolve().parents[1]
schema=json.loads((root/'docs/inference/schemas/inference.schema.json').read_text(encoding='utf-8'))
examples=json.loads((root/'docs/inference/schemas/inference.examples.json').read_text(encoding='utf-8'))
Draft202012Validator.check_schema(schema)
for example in examples:
    document=dict(schema);document['$ref']='#/$defs/'+example['schema']
    Draft202012Validator(document,format_checker=FormatChecker()).validate(example['value'])
negative=[('SwitchRequest',{'engine':'llamacpp','model':'a.gguf','parameters':{'command':'shell'}}),('SwitchRequest',{'engine':'llamacpp','model':'a.gguf','parameters':{'parallelSlots':2}}),('DownloadRequest',{'engine':'llamacpp','repository':'org/repo','revision':'main','file':'a.gguf','sha256':'b'*64})]
for name,value in negative:
    document=dict(schema);document['$ref']='#/$defs/'+name
    assert list(Draft202012Validator(document).iter_errors(value)),(name,value)
print(json.dumps({'validExamples':len(examples),'rejectedInvalidExamples':len(negative),'status':'PASSED'}))

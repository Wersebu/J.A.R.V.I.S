#!/usr/bin/env python3
"""Validate all versioned contract schemas and fixtures locally. Requires jsonschema>=4.23,<5."""
from pathlib import Path
import json
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
root=Path(__file__).resolve().parents[2]/'SHARED'
count=0
for directory in (root/'schemas').iterdir():
    if not directory.is_dir():continue
    schemas={path.stem.removesuffix('.schema'):dict(json.loads(path.read_text(encoding='utf-8')),**{'$id':path.resolve().as_uri()}) for path in directory.glob('*.schema.json')}
    registry=Registry().with_resources((schema['$id'],Resource.from_contents(schema)) for schema in schemas.values())
    for schema in schemas.values():Draft202012Validator.check_schema(schema)
    for path in directory.glob('*.example.json'):
        payload=json.loads(path.read_text(encoding='utf-8'));name='event' if 'eventId' in payload else 'state'
        Draft202012Validator(schemas[name],registry=registry,format_checker=FormatChecker()).validate(payload);count+=1
assert (root/'API_CONTRACT.md').read_bytes()==(root.parent/'SERVER/docs/companion/API_CONTRACT.md').read_bytes()
print(f'All schemas checked; {count} examples validated; contract copies match.')

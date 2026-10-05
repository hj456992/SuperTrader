#!/usr/bin/env python3
"""Conservative structural checks, NOT a PostgreSQL parser or DDL execution."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sql = (ROOT / 'schema.sql').read_text(encoding='utf-8')
api = (ROOT / 'api-contract.md').read_text(encoding='utf-8')
clean = re.sub(r'--[^\n]*', '', sql)

def split_top(text):
    result, start, depth, quote = [], 0, 0, False
    i = 0
    while i < len(text):
        ch = text[i]
        if ch == "'":
            if quote and i + 1 < len(text) and text[i + 1] == "'":
                i += 2
                continue
            quote = not quote
        elif not quote:
            depth += (ch == '(') - (ch == ')')
            assert depth >= 0, 'unbalanced parentheses'
            if ch == ',' and depth == 0:
                result.append(text[start:i].strip())
                start = i + 1
        i += 1
    assert depth == 0 and not quote, 'unbalanced SQL fragment'
    result.append(text[start:].strip())
    return result

def cols(value):
    return tuple(x.strip() for x in value.split(','))

expected = {'ep_document', 'ep_document_version', 'ep_source_chunk', 'ep_team',
            'ep_build', 'ep_build_document', 'ep_artifact', 'ep_artifact_revision',
            'ep_revision_source', 'ep_revision_dependency', 'ep_message',
            'ep_review', 'ep_clarification', 'ep_job', 'ep_event'}
tables = {}
fks = []
for match in re.finditer(r'CREATE TABLE (ep_\w+)\s*\((.*?)\);', clean, re.S):
    name, body = match.groups()
    assert name not in tables
    fields, unique = set(), set()
    for item in split_top(body):
        top = re.match(r'(PRIMARY KEY|UNIQUE)\s*\(([^)]+)\)', item)
        if top:
            unique.add(cols(top[2]))
            continue
        fk = re.match(r'FOREIGN KEY\s*\(([^)]+)\)\s*REFERENCES (ep_\w+)\(([^)]+)\)', item)
        if fk:
            fks.append((name, cols(fk[1]), fk[2], cols(fk[3])))
            continue
        if item.startswith('CHECK'):
            continue
        field = re.match(r'(\w+)\s+(uuid|text|integer|bigint|jsonb|timestamptz|boolean)\b', item)
        assert field, (name, 'unknown column/constraint', item)
        fields.add(field[1])
        if 'PRIMARY KEY' in item or re.search(r'\bUNIQUE\b', item):
            unique.add((field[1],))
        inline = re.search(r'REFERENCES (ep_\w+)\(([^)]+)\)', item)
        if inline:
            fks.append((name, (field[1],), inline[1], cols(inline[2])))
    tables[name] = {'fields': fields, 'unique': unique}
assert set(tables) == expected, set(tables) ^ expected
for match in re.finditer(r'ALTER TABLE (ep_\w+) ADD CONSTRAINT \w+\s+FOREIGN KEY\s*\(([^)]+)\)\s*REFERENCES (ep_\w+)\(([^)]+)\)', clean, re.S):
    fks.append((match[1], cols(match[2]), match[3], cols(match[4])))
assert len(re.findall(r'ALTER TABLE', clean)) == 6
for source, source_cols, target, target_cols in fks:
    assert source in tables and target in tables
    assert set(source_cols) <= tables[source]['fields'], (source, source_cols)
    assert target_cols in tables[target]['unique'], (target, target_cols, 'no referenced unique key')
    assert len(source_cols) == len(target_cols)
assert 'CREATE EXTENSION' not in clean.upper()
assert not re.search(r'\b(gen_random_uuid|uuid_generate_v4)\s*\(', clean)
assert clean.strip().startswith('BEGIN;') and clean.strip().endswith('COMMIT;')
assert clean.index('ALTER TABLE') > clean.index('CREATE TABLE ep_event')
for role in ('fallback', 'router'):
    assert re.search(r'CREATE UNIQUE INDEX ep_artifact_single_' + role + r'.*?WHERE kind = \'agent\' AND agent_role = \'' + role + r"'", clean, re.S)
for status in ('queued', 'running', 'succeeded', 'failed', 'cancelled', 'pending',
               'needs_reason', 'changes_requested', 'completed', 'summary.full', 'approve', 'reject'):
    assert "'" + status + "'" in sql and status in api, status
samples = re.findall(r'```json\n(.*?)\n```', api, re.S)
for sample in samples:
    json.loads(sample)
assert len(samples) >= 5
assert 'CREATE TABLE' not in api  # contract contains no hidden second schema
report = {
    'status': 'passed', 'tables': len(tables), 'foreignKeysChecked': len(fks),
    'cyclePointerAlters': 6, 'jsonExamplesParsed': len(samples),
    'checks': ['15 expected tables', 'FK source columns and referenced unique keys',
               'balanced table fragments', 'cycle migration order', 'partial role uniqueness',
               'no UUID extension/default function', 'state vocabulary', 'JSON examples'],
    'limitations': ['No PostgreSQL parser available', 'DDL not executed',
                    'No database connection', 'No runtime concurrency or constraint negative tests']
}
(ROOT / 'checks/backend-static-result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(report, ensure_ascii=False))

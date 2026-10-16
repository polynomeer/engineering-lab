#!/usr/bin/env python3
"""Exercise all 13 Career scenarios on one running API; verify saved evidence after restart."""
import argparse
import hashlib
import json
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CASES = [
    ('career-sequence', 'atomic-update', 'SUCCEEDED'),
    ('career/mcp-platform', 'architecture-compliant', 'SUCCEEDED'),
    ('career/mcp-platform', 'validation-valid', 'SUCCEEDED'),
    ('career/equity-system', 'deletion-after', 'SUCCEEDED'),
    ('career/equity-system', 'redis-lock-after', 'SUCCEEDED'),
    ('career/equity-system', 'registration-after', 'SUCCEEDED'),
    ('career/batch-excel', 'batch-memory-after', 'SUCCEEDED'),
    ('career/batch-excel', 'excel-memory-after', 'SUCCEEDED'),
    ('career/mds-distribution', 'settlement-guard-after', 'SUCCEEDED'),
    ('career/mds-distribution', 'idempotency-concurrent', 'SUCCEEDED'),
    ('career/creator-studio', 'session-rotation-concurrent', 'SUCCEEDED'),
    ('career/creator-studio', 'mapping-integrity-transfer', 'SUCCEEDED'),
    ('career/creator-studio', 'post-commit-retry', 'SUCCEEDED'),
    ('career/mcp-platform', 'architecture-violation', 'FAILED'),
    ('career/batch-excel', 'batch-memory-fail-after-measurement', 'FAILED'),
    ('career/mds-distribution', 'idempotency-processing-failure', 'FAILED'),
    ('career/creator-studio', 'post-commit-exhausted', 'FAILED'),
]


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()


class Api:
    def __init__(self, origin): self.origin = origin.rstrip('/')
    def open(self, path, data=None):
        request = urllib.request.Request(self.origin + path, data=canonical(data) if data is not None else None,
                                         headers={'Content-Type': 'application/json'})
        return urllib.request.urlopen(request, timeout=90)
    def json(self, path, data=None):
        with self.open(path, data) as response:
            content = response.read(16 * 1024 * 1024 + 1)
            if len(content) > 16 * 1024 * 1024: raise RuntimeError('API response exceeds acceptance bound')
            return json.loads(content)
    def evidence(self, run_id):
        result = {key: self.json(path) for key, path in {
            'run': f'/runs/{run_id}', 'execution': f'/dashboard/v1/runs/{run_id}/execution',
            'checks': f'/dashboard/v1/runs/{run_id}/checks?limit=100',
            'metrics': f'/runs/{run_id}/metrics?limit=100', 'artifacts': f'/runs/{run_id}/artifacts'}.items()}
        if result['checks']['nextAfter'] is not None: raise RuntimeError('Fixture check count exceeds one page')
        execution = result['execution']['execution']
        assert execution['definition']['kind'] == 'CONTAINER'
        assert execution['worker']['environment']['javaVersion'].startswith('21')
        assert execution['evidenceComplete'] == (result['run']['status'] == 'SUCCEEDED')
        assert result['checks']['checks'], 'No checks'
        assert result['artifacts'], 'No reports'
        for artifact in result['artifacts']:
            assert artifact['artifactId'].startswith('worker_')
            digest, size = hashlib.sha256(), 0
            with self.open(f'/runs/{run_id}/artifacts/{artifact["artifactId"]}/download') as response:
                assert response.headers['X-Artifact-SHA256'] == artifact['sha256']
                assert response.headers['Content-Disposition'].startswith('attachment;')
                while chunk := response.read(8192):
                    size += len(chunk)
                    assert size <= 16 * 1024 * 1024
                    digest.update(chunk)
            assert size == artifact['byteSize'] and digest.hexdigest() == artifact['sha256']
        digest, count, last = hashlib.sha256(), 0, None
        with self.open(f'/dashboard/v1/runs/{run_id}/export') as response:
            while line := response.readline(1048577):
                assert len(line) <= 1048576 and count < 100010
                last = json.loads(line)
                digest.update(canonical(last) + b'\n')
                count += 1
        assert last['type'] == 'end'
        assert last['checkCount'] == len(result['checks']['checks'])
        result['exportSha256'] = digest.hexdigest()
        return result


def accept(origin, output, verify=False):
    api = Api(origin)
    catalog = api.json('/experiments')
    expected = {json.loads((ROOT / 'experiments' / group / 'scenarios' / (name + '.json')).read_text())['experiment']
                for group, name, _ in CASES}
    actual = {item['id'] for item in catalog if item['id'].startswith('career.')}
    assert len(expected) == 13 and actual == expected, (actual, expected)
    for experiment in sorted(expected):
        detail = api.json('/dashboard/v1/experiments/' + experiment)
        assert detail['readiness'] == 'READY' and detail['executionKind'] == 'CONTAINER', detail
    if verify:
        index = json.loads((output / 'index.json').read_text())
        assert index['complete'] and len(index['runs']) == len(CASES)
        for entry in index['runs']:
            saved = json.loads((output / (entry['name'] + '.json')).read_text())
            assert canonical(api.evidence(entry['runId'])) == canonical(saved), entry['name']
            print('REOPEN PASS ' + entry['name'], flush=True)
        (output / 'reopen.json').write_text(json.dumps({'verifiedRuns': len(index['runs']), 'passed': True}) + '\n')
        return
    output.mkdir(parents=True, exist_ok=False)
    index = {'complete': False, 'runs': []}
    for group, name, expected_status in CASES:
        definition = json.loads((ROOT / 'experiments' / group / 'scenarios' / (name + '.json')).read_text())
        run_id = api.json('/runs', definition)['runId']
        entry = {'name': name, 'experiment': definition['experiment'], 'runId': run_id, 'expectedStatus': expected_status, 'status': 'SUBMITTED'}
        index['runs'].append(entry)
        (output / 'index.json').write_text(json.dumps(index, indent=2) + '\n')
        deadline = time.monotonic() + 1000
        while True:
            run = api.json('/runs/' + run_id)
            if run['status'] not in ('QUEUED', 'RUNNING'): break
            if time.monotonic() >= deadline: raise TimeoutError(run_id)
            time.sleep(1)
        assert run['status'] == expected_status, (name, run)
        snapshot = api.evidence(run_id)
        (output / (name + '.json')).write_text(json.dumps(snapshot, indent=2) + '\n')
        entry['status'] = run['status']
        (output / 'index.json').write_text(json.dumps(index, indent=2) + '\n')
        print(f'PASS {name}: {run_id} {expected_status}', flush=True)
    index['complete'] = True
    (output / 'index.json').write_text(json.dumps(index, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api', default='http://127.0.0.1:18088')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--verify-reopen', action='store_true')
    args = parser.parse_args()
    accept(args.api, args.output, args.verify_reopen)

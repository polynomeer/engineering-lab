#!/usr/bin/env python3
"""Freeze a local deployment from verified builds and cached images. No pull or shared cleanup."""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKERS = {'sequence': ('mcp-platform', 'sequence'), 'checks': ('mcp-platform', 'checks'),
           'equity': ('equity-system', 'equity'), 'memory': ('batch-excel', 'memory'),
           'mds': ('mds-distribution', 'mds'), 'creator': ('creator-studio', 'creator')}


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def docker(*args):
    return subprocess.run(['docker', *args], check=True, capture_output=True, text=True, timeout=120).stdout.strip()


def image_id(reference):
    value = docker('image', 'inspect', reference, '--format', '{{.Id}}')
    if not re.fullmatch(r'sha256:[a-f0-9]{64}', value):
        raise ValueError('Invalid image ID')
    return value


def manifest(module, name):
    path = ROOT / 'career-lab' / module / 'build/libs' / f'{name}-worker-manifest.json'
    if path.stat().st_size > 16384:
        raise ValueError('Manifest too large')
    value = json.loads(path.read_text())
    if (value['moduleId'] != module or value['javaMajor'] != 21
            or value['protocolVersion'] != 1 or value['schemaVersion'] != 1
            or not re.fullmatch(r'[A-Za-z0-9._-]+\.jar', value['jarFile'])):
        raise ValueError('Incompatible manifest')
    jar = path.parent / value['jarFile']
    if jar.is_symlink() or digest(jar) != value['artifactSha256']:
        raise ValueError('Worker jar hash mismatch')
    return value, jar


def assemble(base, jar, tag, sha, journal=None):
    token = str(uuid.uuid4())
    label = 'engineering-lab.deployment-builder'
    state = {'token': token, 'base': base, 'tag': tag, 'removed': False}
    def record():
        if journal: journal.write_text(json.dumps(state, indent=2) + '\n')
    record()
    with tempfile.TemporaryDirectory(prefix='lab-deployment-') as temporary:
        payload = Path(temporary) / 'lab'
        payload.mkdir(mode=0o755)
        shutil.copyfile(jar, payload / 'worker.jar')
        (payload / 'worker.jar').chmod(0o644)
        container = docker('create', '--pull=never', '--network=none', '--label', f'{label}={token}',
                           '--entrypoint', '/bin/true', base)
        if not re.fullmatch(r'[a-f0-9]{64}', container):
            raise RuntimeError('Builder creation unconfirmed; inspect Docker before retry')
        state['containerId'] = container
        record()
        try:
            docker('cp', str(payload), container + ':/opt/lab')
            docker('commit', '--change', f'LABEL engineering-lab.jar-sha256={sha}', container, tag)
            return image_id(tag)
        finally:
            actual = json.loads(docker('inspect', '--format', '{{json .}}', container))
            if (actual['Id'] != container or actual['Image'] != base
                    or actual['Config']['Labels'].get(label) != token or actual['State']['Status'] != 'created'):
                raise RuntimeError('Builder ownership changed; preserved for inspection')
            docker('rm', container)
            if docker('ps', '-a', '--no-trunc', '--quiet', '--filter', 'id=' + container):
                raise RuntimeError('Builder removal unconfirmed')
            state['removed'] = True
            record()


def prepare(destination, port):
    destination = destination.resolve()
    if any(c in str(destination) for c in "\r\n"):
        raise ValueError("Invalid deployment path")
    if destination.exists():
        raise ValueError('Use a new deployment directory; existing deployments are never overwritten')
    builds = {key: manifest(*entry) for key, entry in WORKERS.items()}
    controller = ROOT / 'build/libs/engineering-lab-0.0.1-SNAPSHOT.jar'
    frontend = ROOT / 'frontend/dist'
    if not controller.is_file() or not (frontend / 'index.html').is_file():
        raise ValueError('Build controller and frontend first')
    base, mysql, redis = [image_id(tag) for tag in ('eclipse-temurin:21-jre', 'mysql:8.0.39', 'redis:7.4-alpine')]
    destination.mkdir(parents=True)
    record = {'schemaVersion': 1, 'baseImage': base, 'mysqlImage': mysql, 'redisImage': redis, 'workers': {}}
    properties = ['server.address=127.0.0.1', f'server.port={port}',
                  'lab.storage.root=' + str(destination / 'store').replace('\\', '\\\\'),
                  'lab.career.sequence.execution-kind=CONTAINER']
    try:
        for key, (value, jar) in builds.items():
            tag = f'engineering-lab-local/{key}:{uuid.uuid4().hex}'
            image = assemble(base, jar, tag, value['artifactSha256'], destination / (key + '-builder.json'))
            record['workers'][key] = {'tag': tag, 'imageId': image, 'artifactSha256': value['artifactSha256'],
                                      'experimentIds': value['experimentIds']}
            (destination / 'deployment.json').write_text(json.dumps(record, indent=2) + '\n')
            prefix = f'lab.career.{key}.'
            properties += [prefix + 'enabled=true', prefix + 'recover-on-startup=true',
                           prefix + 'container.image-id=' + image,
                           prefix + 'container.artifact-sha256=' + value['artifactSha256']]
            if key != 'checks': properties.append(prefix + 'container.mysql-image-id=' + mysql)
            if key in ('creator', 'equity'): properties.append(prefix + 'container.redis-image-id=' + redis)
            print(f'{key}: {image}', flush=True)
        shutil.copyfile(controller, destination / 'controller.jar')
        shutil.copytree(frontend, destination / 'frontend/dist')
        (destination / 'frontend/server').mkdir()
        shutil.copyfile(ROOT / 'frontend/server/serve.mjs', destination / 'frontend/server/serve.mjs')
        record['controllerSha256'] = digest(destination / 'controller.jar')
        (destination / 'deployment.json').write_text(json.dumps(record, indent=2) + '\n')
        # Properties is the ready marker, written only after every image and frozen artifact succeeds.
        (destination / 'application.properties').write_text('\n'.join(properties) + '\n')
    except Exception:
        print(f'Partial deployment preserved at {destination}; inspect deployment.json before cleanup')
        raise
    print(f'Ready: {destination / "application.properties"}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination', type=Path)
    parser.add_argument('--port', type=int, default=18088)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535: parser.error('port must be 1024..65535')
    prepare(args.destination, args.port)

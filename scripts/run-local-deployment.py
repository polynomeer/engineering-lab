#!/usr/bin/env python3
"""Run one frozen local deployment component in the foreground; Ctrl-C stops that component."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('deployment', type=Path)
parser.add_argument('component', choices=['api', 'frontend'])
parser.add_argument('--frontend-port', type=int, default=4188)
args = parser.parse_args()
root = args.deployment.resolve()
record = json.loads((root / 'deployment.json').read_text())
if not (root / 'application.properties').is_file(): raise SystemExit('Incomplete deployment')
if args.component == 'api':
    jar = root / 'controller.jar'
    with jar.open('rb') as source:
        if hashlib.file_digest(source, 'sha256').hexdigest() != record['controllerSha256']:
            raise SystemExit('Controller jar hash mismatch')
    home = os.environ.get('LAB_JAVA25_HOME')
    if not home and sys.platform == 'darwin':
        home = subprocess.check_output(['/usr/libexec/java_home', '-v', '25'], text=True).strip()
    java = str(Path(home) / 'bin/java') if home else shutil.which('java')
    if not java: raise SystemExit('Java 25 required')
    os.execv(java, [java, '-jar', str(jar), '--spring.config.additional-location=' + (root / 'application.properties').as_uri()])
else:
    if not 1024 <= args.frontend_port <= 65535: raise SystemExit('Invalid frontend port')
    properties = dict(line.split('=', 1) for line in (root / 'application.properties').read_text().splitlines() if '=' in line)
    os.environ['LAB_API_TARGET'] = 'http://127.0.0.1:' + properties['server.port']
    os.environ['PORT'] = str(args.frontend_port)
    node = shutil.which('node')
    if not node: raise SystemExit('Node 24 required')
    os.execv(node, [node, str(root / 'frontend/server/serve.mjs')])

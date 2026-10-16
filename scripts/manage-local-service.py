#!/usr/bin/env python3
"""Manage this user's macOS LaunchAgents for one frozen Engineering Lab deployment."""
import argparse
import hashlib
import json
import os
import plistlib
import shutil
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path

PREFIX = 'com.pnomeer.engineering-lab'
COMPONENTS = ('api', 'frontend')
ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def launch(*args, required=True):
    result = subprocess.run(['/bin/launchctl', *args], capture_output=True, text=True, timeout=75)
    if required and result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result


def loaded(domain, label):
    result = launch('print', domain + '/' + label, required=False)
    if result.returncode == 0:
        return result.stdout
    if 'Could not find service' in result.stderr:
        return None
    raise RuntimeError(result.stderr.strip() or 'Cannot inspect launchd domain')


def free_port(port):
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(('127.0.0.1', port))
        probe.listen(1)


def definition(root, component, python, java_home, node, docker, token, frontend_port):
    directory = root / 'services'
    return {
        'Label': PREFIX + '.' + component,
        'ProgramArguments': [str(python), str(directory / 'runner.py'), str(root), component,
                             '--frontend-port', str(frontend_port)],
        'WorkingDirectory': str(root),
        'EnvironmentVariables': {
            'LAB_JAVA25_HOME': str(java_home),
            'PATH': ':'.join(dict.fromkeys([str(node.parent), str(docker.parent), '/usr/bin', '/bin', '/usr/sbin', '/sbin'])),
            'LAB_SERVICE_OWNER': token,
        },
        'RunAtLoad': True, 'KeepAlive': True, 'ThrottleInterval': 30,
        'ExitTimeOut': 60, 'LimitLoadToSessionType': 'Aqua',
        'StandardOutPath': str(directory / (component + '.stdout.log')),
        'StandardErrorPath': str(directory / (component + '.stderr.log')),
    }


def install(root, agents, domain, node, frontend_port):
    directory = root / 'services'
    if directory.exists():
        raise ValueError('Service directory exists; inspect status or uninstall first')
    properties = dict(line.split('=', 1) for line in (root / 'application.properties').read_text().splitlines() if '=' in line)
    record = json.loads((root / 'deployment.json').read_text())
    if sha(root / 'controller.jar') != record['controllerSha256']:
        raise ValueError('Controller jar hash mismatch')
    python = Path(sys.executable).resolve()
    node = node.absolute() if node else Path(shutil.which('node') or '/missing-node')
    docker = Path(shutil.which('docker') or '/missing-docker')
    java_home = Path(os.environ.get('LAB_JAVA25_HOME') or subprocess.check_output(
        ['/usr/libexec/java_home', '-v', '25'], text=True).strip())
    for executable in (python, node, docker, java_home / 'bin/java'):
        if not executable.is_file() or not os.access(executable, os.X_OK):
            raise ValueError('Executable unavailable: ' + str(executable))
    if not subprocess.check_output([str(node), '--version'], text=True).startswith('v24.'):
        raise ValueError('Node 24 required')
    ports = [int(properties['server.port']), frontend_port]
    if len(set(ports)) != 2 or any(not 1024 <= p <= 65535 for p in ports):
        raise ValueError('Use distinct ports in 1024..65535')
    for port in ports:
        free_port(port)  # Never kill an unknown process or boot-loop against occupied ports.
    token = str(uuid.uuid4())
    entries = []
    for component in COMPONENTS:
        value = definition(root, component, python, java_home, node, docker, token, frontend_port)
        path = agents / (value['Label'] + '.plist')
        if path.exists() or path.is_symlink() or loaded(domain, value['Label']):
            raise ValueError('Existing service preserved: ' + str(path))
        content = plistlib.dumps(value)
        entries.append({'label': value['Label'], 'path': str(path),
                        'sha256': hashlib.sha256(content).hexdigest(), 'content': content})
    directory.mkdir(mode=0o700)
    shutil.copyfile(ROOT / 'scripts/run-local-deployment.py', directory / 'runner.py')
    state = {'deployment': str(root), 'domain': domain, 'token': token,
             'runnerSha256': sha(directory / 'runner.py'),
             'entries': [{k: v for k, v in entry.items() if k != 'content'} for entry in entries]}
    # Write ownership intent first; partial installation remains inspectable/removable.
    (directory / 'owner.json').write_text(json.dumps(state, indent=2) + '\n')
    agents.mkdir(parents=True, exist_ok=True)
    for entry in entries:
        with Path(entry['path']).open('xb') as target:
            target.write(entry['content'])
        Path(entry['path']).chmod(0o600)
    manage(root, domain, 'start')


def ownership(root, domain):
    directory = root / 'services'
    state = json.loads((directory / 'owner.json').read_text())
    if state['deployment'] != str(root) or state['domain'] != domain:
        raise ValueError('Deployment/user ownership mismatch')
    expected_agents = Path.home() / 'Library/LaunchAgents'
    if len(state['entries']) != 2:
        raise ValueError('Invalid service ownership record')
    for entry, component in zip(state['entries'], COMPONENTS):
        if entry['label'] != PREFIX + '.' + component or Path(entry['path']) != expected_agents / (entry['label'] + '.plist'):
            raise ValueError('Unexpected service identity')
        path = Path(entry['path'])
        if path.is_symlink() or (path.exists() and sha(path) != entry['sha256']):
            raise ValueError('Changed plist preserved: ' + str(path))
    return state


def wait_removed(domain, entry):
    deadline = time.monotonic() + 65
    while loaded(domain, entry['label']):
        if time.monotonic() >= deadline:
            raise RuntimeError('Service removal unconfirmed: ' + entry['label'])
        time.sleep(0.2)


def manage(root, domain, action):
    state = ownership(root, domain)
    # Verify every identity before changing either component.
    services = []
    for entry in state['entries']:
        current = loaded(domain, entry['label'])
        if current and 'path = ' + entry['path'] not in [line.strip() for line in current.splitlines()]:
            raise ValueError('Loaded service ownership mismatch: ' + entry['label'])
        services.append((entry, current))
    if action == 'start':
        if sha(root / 'services/runner.py') != state['runnerSha256']:
            raise ValueError('Changed service runner; reinstall from a verified build')
        for entry, _ in services:
            if not Path(entry['path']).is_file():
                raise ValueError('Partial installation: uninstall and install again')
    for entry, current in (reversed(services) if action in ('stop', 'uninstall') else services):
        target = domain + '/' + entry['label']
        if action == 'status':
            print(target + ': ' + ('loaded' if current else 'not loaded'))
            if current:
                for line in current.splitlines():
                    if line.startswith(('\tstate =', '\tpid =', '\tlast exit code =')):
                        print('  ' + line.strip())
        elif action == 'start':
            launch('enable', target)
            if not current:
                launch('bootstrap', domain, entry['path'])
        else:
            launch('disable', target)  # Stop persists across login until explicit start.
            if current:
                launch('bootout', target)
            wait_removed(domain, entry)
            if action == 'uninstall':
                Path(entry['path']).unlink(missing_ok=True)
    if action == 'uninstall':
        # Keep logs and runner as evidence; allow a future install after archiving the directory.
        (root / 'services/owner.json').rename(root / 'services/uninstalled-owner.json')
    print(action + ' complete')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['install', 'start', 'stop', 'status', 'uninstall'])
    parser.add_argument('deployment', type=Path)
    parser.add_argument('--node', type=Path, help='Absolute Node 24 executable for install')
    parser.add_argument('--frontend-port', type=int, default=4188)
    args = parser.parse_args()
    if sys.platform != 'darwin' or os.getuid() == 0:
        parser.error('Run as the logged-in macOS user, without sudo')
    root = args.deployment.resolve()
    domain = 'gui/' + str(os.getuid())
    launch('print', domain)
    if args.action == 'install':
        install(root, Path.home() / 'Library/LaunchAgents', domain, args.node, args.frontend_port)
    else:
        manage(root, domain, args.action)


if __name__ == '__main__':
    main()

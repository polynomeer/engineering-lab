#!/usr/bin/env python3
"""Bounded offline snapshots, non-destructive restore and macOS scheduled backups."""
import argparse
import contextlib
import datetime
import fcntl
import hashlib
import importlib.util
import json
import os
import plistlib
import re
import shutil
import signal
import stat
import subprocess
import sys
import time
import urllib.request
import uuid
from pathlib import Path

spec = importlib.util.spec_from_file_location('service', Path(__file__).with_name('manage-local-service.py'))
service = importlib.util.module_from_spec(spec); spec.loader.exec_module(service)
MAX_FILES = 100000
MAX_BYTES = 10 * 1024**3
LABEL = 'com.pnomeer.engineering-lab.backup'


def read_json(path):
    if not stat.S_ISREG(path.lstat().st_mode) or path.stat().st_size > 32 * 1024**2:
        raise ValueError('Unsafe or oversized metadata: ' + str(path))
    return json.loads(path.read_text())


def write_json(path, value):
    temporary = path.with_name(path.name + '.partial')
    with temporary.open('w') as stream:
        json.dump(value, stream, indent=2); stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
    temporary.replace(path)


def files(root):
    result = []
    count = 0
    def visit(directory, depth=0):
        nonlocal count
        if depth > 64: raise ValueError('Directory depth limit exceeded')
        with os.scandir(directory) as entries:
            for entry in entries:
                count += 1
                if count > MAX_FILES: raise ValueError('Entry count limit exceeded')
                mode = entry.stat(follow_symlinks=False).st_mode
                if stat.S_ISDIR(mode): visit(Path(entry.path), depth+1)
                elif stat.S_ISREG(mode): result.append(Path(entry.path))
                else: raise ValueError('Links/special files are not supported: ' + entry.path)
                if len(result) > MAX_FILES: raise ValueError('File count limit exceeded')
    if root.is_symlink(): raise ValueError('Symlink root')
    visit(root)
    return sorted(result)


@contextlib.contextmanager
def lock(path, posix=False):
    fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        operation = fcntl.lockf if posix else fcntl.flock
        operation(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield
    finally: os.close(fd)


def properties(root):
    return dict(line.split('=', 1) for line in (root/'application.properties').read_text().splitlines() if '=' in line)


def request(root, path):
    with urllib.request.urlopen('http://127.0.0.1:' + properties(root)['server.port'] + path, timeout=10) as response:
        value = response.read(16 * 1024**2 + 1)
        if len(value) > 16 * 1024**2: raise ValueError('API size bound exceeded')
        return json.loads(value)


def busy(root):
    cursor = None
    for _ in range(1000):
        page = request(root, '/runs?limit=100' + ('' if cursor is None else '&before=' + str(cursor)))
        if any(run['status'] in ('QUEUED', 'RUNNING') for run in page['runs']): return True
        following = page['nextBefore']
        if following is None: return False
        if following == cursor: raise ValueError('Non-progressing Run cursor')
        cursor = following
    raise ValueError('Run scan bound exceeded')


def ready(root):
    deadline = time.monotonic() + 60
    while True:
        try:
            request(root, '/runs?limit=1'); return
        except (OSError, ValueError):
            if time.monotonic() >= deadline: raise
            time.sleep(1)


def resume(root, domain):
    journal = root/'backup-resume.json'
    if not journal.exists(): return
    state = read_json(journal)
    if state != {'deployment': str(root), 'domain': domain, 'resume': True}:
        raise ValueError('Unknown resume intent; manual inspection required')
    service.manage(root, domain, 'start')
    ready(root)
    journal.unlink()


def inventory(source):
    selected = []
    for name in ('controller.jar', 'deployment.json', 'application.properties', 'frontend', 'store', 'acceptance'):
        path = source/name
        if name == 'acceptance' and not path.exists(): continue
        if path.is_symlink(): raise ValueError('Symlink source')
        selected.extend(files(path) if path.is_dir() else [path])
    selected = [p for p in selected if p != source/'store/owner.lock']
    if any(not stat.S_ISREG(p.lstat().st_mode) for p in selected):
        raise ValueError('Non-regular source file')
    if len(selected) > MAX_FILES or sum(p.stat().st_size for p in selected) > MAX_BYTES:
        raise ValueError('Snapshot bounds exceeded')
    return selected


def copy_checked(source, destination, limit=MAX_BYTES):
    digest = hashlib.sha256(); count = 0
    fd = os.open(source, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as src, destination.open('xb') as dst:
        if not stat.S_ISREG(os.fstat(src.fileno()).st_mode): raise ValueError('Non-regular source')
        while chunk := src.read(1024 * 1024):
            count += len(chunk)
            if count > limit: raise ValueError('File size limit exceeded')
            digest.update(chunk); dst.write(chunk)
        dst.flush(); os.fsync(dst.fileno())
    destination.chmod(0o600)
    return {'size': count, 'sha256': digest.hexdigest()}


def snapshot(source, repository):
    selected = inventory(source)
    total = sum(p.stat().st_size for p in selected)
    if shutil.disk_usage(repository).free < total + 64 * 1024**2: raise ValueError('Insufficient backup disk space')
    name = 'backup-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + uuid.uuid4().hex
    partial = repository/(name + '.partial'); partial.mkdir(mode=0o700)
    metadata = {'version': 1, 'source': str(source), 'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'files': {}}
    remaining = MAX_BYTES
    for src in selected:
        relative = src.relative_to(source).as_posix()
        dest = partial/'payload'/relative; dest.parent.mkdir(parents=True, exist_ok=True)
        metadata['files'][relative] = copy_checked(src, dest, remaining)
        remaining -= metadata['files'][relative]['size']
    write_json(partial/'manifest.json', metadata)
    verify(partial)
    target = repository/name
    partial.rename(target)
    return target


def verify(archive):
    if archive.is_symlink(): raise ValueError('Symlink snapshot')
    metadata = read_json(archive/'manifest.json')
    records = metadata['files']
    if metadata['version'] != 1 or not 0 < len(records) <= MAX_FILES:
        raise ValueError('Invalid manifest')
    if not {'controller.jar', 'application.properties', 'deployment.json', 'store/runs.mv.db'} <= records.keys():
        raise ValueError('Incomplete deployment backup')
    total = 0
    for name, expected in records.items():
        relative = Path(name)
        if relative.is_absolute() or '..' in relative.parts or relative.as_posix() != name:
            raise ValueError('Unsafe manifest path')
        path = archive/'payload'/relative
        if any(p.is_symlink() for p in [path, *path.parents]): raise ValueError('Symlink in snapshot path')
        if not stat.S_ISREG(path.lstat().st_mode): raise ValueError('Non-regular snapshot file')
        total += path.stat().st_size
        if total > MAX_BYTES or path.stat().st_size != expected['size'] or service.sha(path) != expected['sha256']:
            raise ValueError('Snapshot hash/size mismatch: ' + name)
    actual = {p.relative_to(archive).as_posix() for p in files(archive)}
    if actual != {'manifest.json'} | {'payload/' + name for name in records}:
        raise ValueError('Unrecorded snapshot files')
    if read_json(archive/'payload/deployment.json')['controllerSha256'] != records['controller.jar']['sha256']:
        raise ValueError('Controller deployment hash mismatch')
    return metadata


def prune(repository, source, keep):
    if keep < 1: raise ValueError('Keep at least one backup')
    candidates = []
    for path in repository.iterdir():
        if re.fullmatch(r'backup-\d{8}T\d{6}Z-[a-f0-9]{32}', path.name) and path.is_dir() and not path.is_symlink() and (path/'manifest.json').is_file():
            metadata = read_json(path/'manifest.json')
            if metadata.get('source') == str(source): candidates.append((metadata['createdAt'], path))
            if len(candidates) > 10000: raise ValueError('Backup count limit exceeded')
    removed = []
    for _, path in sorted(candidates)[:-keep]:
        verify(path)  # Preserve altered, partial and unrelated snapshots.
        shutil.rmtree(path); removed.append(path.name)
    return removed


def backup(source, repository, keep):
    if source == repository or source in repository.parents or repository in source.parents:
        raise ValueError('Use a backup repository separate from the deployment')
    if Path(properties(source)['lab.storage.root']).resolve() != source/'store':
        raise ValueError('Only the frozen deployment store is supported')
    repository.mkdir(parents=True, exist_ok=True, mode=0o700)
    domain = 'gui/' + str(os.getuid())
    with lock(source/'backup-operation.lock'):
        resume(source, domain)
        state = service.ownership(source, domain)
        if not all(service.loaded(domain, e['label']) for e in state['entries']):
            raise ValueError('Both managed services must be loaded; stopped services are not enabled by backup')
        if busy(source):
            print('SKIPPED: active experiments'); return
        write_json(source/'backup-resume.json', {'deployment': str(source), 'domain': domain, 'resume': True})
        try:
            service.manage(source, domain, 'stop')
            with lock(source/'store/owner.lock', posix=True):
                # Do not open/close owner.lock again while holding the POSIX lock.
                target = snapshot(source, repository)
        finally:
            resume(source, domain)
        removed = prune(repository, source, keep)
        result = {'backup': str(target), 'verified': True, 'removed': removed}
        write_json(source/'backup-last-success.json', result)
        print(json.dumps(result))


def restore(archive, target, port):
    metadata = verify(archive)
    if target.exists() or target.is_symlink(): raise ValueError('Restore destination must not exist')
    if archive in target.parents or any(c in str(target) for c in '\r\n;\\'):
        raise ValueError('Unsafe restore location')
    if not 1024 <= port <= 65535: raise ValueError('Invalid restore port')
    partial = target.with_name(target.name + '.partial-' + uuid.uuid4().hex)
    partial.mkdir(parents=True, mode=0o700)
    remaining = MAX_BYTES
    for src in files(archive/'payload'):
        relative = src.relative_to(archive/'payload')
        dest = partial/relative; dest.parent.mkdir(parents=True, exist_ok=True)
        copied = copy_checked(src, dest, remaining)
        if copied != metadata['files'][relative.as_posix()]: raise ValueError('Snapshot changed during restore')
        remaining -= copied['size']
    config = properties(partial)
    config['lab.storage.root'] = str(target/'store')
    config['server.port'] = str(port)
    # Never reconcile copied resource ownership against the original deployment.
    for key in config:
        if key.endswith('.recover-on-startup'): config[key] = 'false'
    (partial/'application.properties').write_text(''.join(key+'='+value+'\n' for key,value in config.items()))
    write_json(partial/'restore.json', {'backup': str(archive), 'manifestSha256': service.sha(archive/'manifest.json'), 'automaticRecovery': False})
    partial.rename(target)
    print('Restored without starting services: ' + str(target))


def schedule(source, repository, keep, hour, remove=False):
    domain = 'gui/' + str(os.getuid()); path = Path.home()/'Library/LaunchAgents'/(LABEL+'.plist')
    receipt = source/'backup-schedule.json'
    if remove:
        state = read_json(receipt)
        if state['path'] != str(path) or path.is_symlink() or service.sha(path) != state['sha256']:
            raise ValueError('Changed backup schedule preserved')
        current = service.loaded(domain, LABEL)
        if current and 'path = '+str(path) not in [s.strip() for s in current.splitlines()]:
            raise ValueError('Loaded schedule ownership mismatch')
        if current and any(line.startswith('\tpid =') for line in current.splitlines()):
            raise ValueError('Backup job is running; wait before removing the schedule')
        service.launch('disable', domain+'/'+LABEL)
        if current: service.launch('bootout', domain+'/'+LABEL)
        service.wait_removed(domain, {'label': LABEL})
        path.unlink(); receipt.rename(source/('backup-schedule-removed-'+uuid.uuid4().hex+'.json'))
        print('Backup schedule removed; backups and tool copies retained'); return
    if not 0 <= hour <= 23 or keep < 1: raise ValueError('Invalid schedule')
    if path.exists() or path.is_symlink() or receipt.exists() or service.loaded(domain, LABEL):
        raise ValueError('Existing schedule preserved')
    state = service.ownership(source, domain)
    env = plistlib.loads(Path(state['entries'][0]['path']).read_bytes())['EnvironmentVariables']
    directory = source/('backup-tools-'+uuid.uuid4().hex); directory.mkdir(mode=0o700)
    for name in ('backup-local-deployment.py', 'manage-local-service.py'):
        shutil.copyfile(Path(__file__).with_name(name), directory/name)
    value = {'Label': LABEL, 'ProgramArguments': [sys.executable, str(directory/'backup-local-deployment.py'), 'backup', str(source), str(repository), '--keep', str(keep)],
             'EnvironmentVariables': env, 'WorkingDirectory': str(source),
             'StartCalendarInterval': {'Hour': hour, 'Minute': 0}, 'ProcessType': 'Background',
             'StandardOutPath': str(source/'services/backup.stdout.log'), 'StandardErrorPath': str(source/'services/backup.stderr.log')}
    content = plistlib.dumps(value)
    write_json(receipt, {'path': str(path), 'sha256': hashlib.sha256(content).hexdigest(), 'hour': hour, 'keep': keep})
    with path.open('xb') as stream: stream.write(content)
    path.chmod(0o600)
    service.launch('enable', domain+'/'+LABEL); service.launch('bootstrap', domain, str(path))
    print('Scheduled daily backup at local hour ' + str(hour))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    for name in ('backup', 'schedule'):
        p = sub.add_parser(name); p.add_argument('source', type=Path); p.add_argument('repository', type=Path)
        p.add_argument('--keep', type=int, default=7)
        if name == 'schedule': p.add_argument('--hour', type=int, default=3)
    p = sub.add_parser('verify'); p.add_argument('archive', type=Path)
    p = sub.add_parser('restore'); p.add_argument('archive', type=Path); p.add_argument('target', type=Path); p.add_argument('--port', type=int, default=18089)
    for name in ('resume', 'unschedule'):
        p = sub.add_parser(name); p.add_argument('source', type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    def interrupted(*_): raise InterruptedError('Backup interrupted; inspect resume journal')
    signal.signal(signal.SIGTERM, interrupted)
    if args.action == 'verify': verify(args.archive.resolve()); print('Verified')
    elif args.action == 'restore': restore(args.archive.resolve(), args.target.absolute(), args.port)
    elif args.action == 'backup':
        if args.keep < 1: parser.error('keep must be positive')
        backup(args.source.resolve(), args.repository.resolve(), args.keep)
    elif args.action == 'resume':
        with lock(args.source.resolve()/'backup-operation.lock'): resume(args.source.resolve(), 'gui/'+str(os.getuid()))
    else: schedule(args.source.resolve(), getattr(args, 'repository', Path('.')).resolve(), getattr(args, 'keep', 7), getattr(args, 'hour', 3), args.action == 'unschedule')


if __name__ == '__main__': main()

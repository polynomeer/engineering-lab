#!/usr/bin/env python3
"""LaunchAgent lifecycle failure boundaries; never call the real launchctl."""
import hashlib
import importlib.util
import json
import plistlib
import socket
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('service', Path(__file__).with_name('manage-local-service.py'))
service = importlib.util.module_from_spec(spec)
spec.loader.exec_module(service)


class ServiceTest(unittest.TestCase):
    def test_live_listener_is_not_treated_as_a_reusable_port(self):
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(('127.0.0.1', 0))
            listener.listen(1)
            with self.assertRaises(OSError):
                service.free_port(listener.getsockname()[1])

    def fixture(self, home):
        root = home / 'deployment'; (root / 'services').mkdir(parents=True)
        agents = home / 'Library/LaunchAgents'; agents.mkdir(parents=True)
        entries = []
        for component in service.COMPONENTS:
            value = service.definition(root, component, Path('/python'), Path('/java'), Path('/node'), Path('/docker'), 'owner', 4188)
            content = plistlib.dumps(value)
            path = agents / (value['Label'] + '.plist'); path.write_bytes(content)
            entries.append({'label': value['Label'], 'path': str(path), 'sha256': hashlib.sha256(content).hexdigest()})
        (root / 'services/owner.json').write_text(json.dumps({'deployment': str(root), 'domain': 'gui/501', 'entries': entries}))
        return root, entries

    def test_definition_survives_shell_free_paths_and_restart_policy(self):
        root = Path('/a space/deployment')
        value = service.definition(root, 'api', Path('/python'), Path('/java home'), Path('/node/bin/node'), Path('/docker/bin/docker'), 'owner', 4188)
        self.assertEqual(plistlib.loads(plistlib.dumps(value)), value)
        self.assertEqual(value['ProgramArguments'][2], str(root))
        self.assertTrue(value['KeepAlive']); self.assertTrue(value['RunAtLoad'])
        self.assertEqual(value['ThrottleInterval'], 30)
        self.assertEqual(value['ExitTimeOut'], 60)
        self.assertIn('/docker/bin', value['EnvironmentVariables']['PATH'])

    def test_changed_second_plist_blocks_all_mutations(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(service, 'launch') as launch:
            home = Path(temporary); root, entries = self.fixture(home)
            Path(entries[1]['path']).write_text('unrelated edit')
            with patch.object(Path, 'home', return_value=home):
                with self.assertRaisesRegex(ValueError, 'Changed plist'):
                    service.manage(root, 'gui/501', 'uninstall')
            launch.assert_not_called()
            self.assertTrue(Path(entries[0]['path']).exists())

    def test_loaded_identity_mismatch_blocks_all_mutations(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(service, 'launch') as launch:
            home = Path(temporary); root, _ = self.fixture(home)
            with patch.object(Path, 'home', return_value=home), patch.object(service, 'loaded', return_value='path = /unrelated.plist'):
                with self.assertRaisesRegex(ValueError, 'ownership mismatch'):
                    service.manage(root, 'gui/501', 'stop')
            launch.assert_not_called()

    def test_stop_disables_then_boots_out_frontend_before_api(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(service, 'launch') as launch:
            home = Path(temporary); root, entries = self.fixture(home)
            snapshots = ['path = ' + e['path'] for e in entries] + [None, None]
            with patch.object(Path, 'home', return_value=home), patch.object(service, 'loaded', side_effect=snapshots):
                service.manage(root, 'gui/501', 'stop')
            self.assertEqual([c.args for c in launch.call_args_list], [
                ('disable', 'gui/501/' + entries[1]['label']), ('bootout', 'gui/501/' + entries[1]['label']),
                ('disable', 'gui/501/' + entries[0]['label']), ('bootout', 'gui/501/' + entries[0]['label'])])
            self.assertTrue(all(Path(e['path']).exists() for e in entries))

    def test_async_bootout_waits_until_service_is_absent(self):
        with patch.object(service, 'loaded', side_effect=['state = exiting', None]), patch.object(service.time, 'sleep') as sleep:
            service.wait_removed('gui/501', {'label': 'owned'})
        sleep.assert_called_once_with(0.2)


if __name__ == '__main__': unittest.main()

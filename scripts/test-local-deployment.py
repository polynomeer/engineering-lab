#!/usr/bin/env python3
"""Failure boundaries for local deployment tooling; no Docker changes."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('deployment', Path(__file__).with_name('prepare-local-deployment.py'))
deployment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deployment)


class DeploymentTest(unittest.TestCase):
    def test_manifest_rejects_changed_bytes_and_path_escape(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(deployment, 'ROOT', Path(directory)):
            lib = Path(directory) / 'career-lab/example/build/libs'
            lib.mkdir(parents=True)
            jar = lib / 'worker.jar'
            jar.write_bytes(b'fixture')
            value = {'moduleId': 'example', 'javaMajor': 21, 'protocolVersion': 1, 'schemaVersion': 1,
                     'jarFile': 'worker.jar', 'artifactSha256': deployment.digest(jar)}
            manifest = lib / 'example-worker-manifest.json'
            manifest.write_text(json.dumps(value))
            self.assertEqual(deployment.manifest('example', 'example')[1], jar)
            jar.write_bytes(b'tampered')
            with self.assertRaises(ValueError): deployment.manifest('example', 'example')
            value['jarFile'] = '../worker.jar'
            manifest.write_text(json.dumps(value))
            with self.assertRaises(ValueError): deployment.manifest('example', 'example')

    def test_existing_deployment_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(deployment, 'docker') as docker:
            with self.assertRaises(ValueError): deployment.prepare(Path(directory), 18088)
            docker.assert_not_called()

    def test_changed_builder_ownership_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / 'worker.jar'; jar.write_bytes(b'fixture')
            calls = []
            def docker(*args):
                calls.append(args)
                if args[0] == 'create': return 'a' * 64
                if args[0] == 'commit': raise RuntimeError('commit failed')
                if args[0] == 'inspect':
                    return json.dumps({'Id': 'a' * 64, 'Image': 'sha256:' + 'b' * 64,
                                       'Config': {'Labels': {}}, 'State': {'Status': 'created'}})
                return ''
            with patch.object(deployment, 'docker', side_effect=docker):
                with self.assertRaisesRegex(RuntimeError, 'ownership changed'):
                    deployment.assemble('sha256:' + 'b' * 64, jar, 'test:fixture', 'c' * 64)
            self.assertFalse(any(args[0] == 'rm' for args in calls))


if __name__ == '__main__': unittest.main()

#!/usr/bin/env python3
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('backup', Path(__file__).with_name('backup-local-deployment.py'))
b = importlib.util.module_from_spec(spec); spec.loader.exec_module(b)


class BackupTest(unittest.TestCase):
    def fixture(self, base):
        base = base.resolve()
        source = base/'deployment'; source.mkdir()
        for name in ('store', 'frontend'):
            (source/name).mkdir()
        (source/'store/runs.mv.db').write_bytes(b'bounded database fixture')
        (source/'store/owner.lock').touch()
        (source/'store/report.bin').write_bytes(b'report evidence')
        (source/'controller.jar').write_bytes(b'controller')
        (source/'deployment.json').write_text(json.dumps({'controllerSha256': b.service.sha(source/'controller.jar')}))
        (source/'application.properties').write_text('lab.storage.root='+str(source/'store')+'\nserver.port=18088\nlab.career.sequence.recover-on-startup=true\n')
        (source/'frontend/index.html').write_text('frontend')
        repository=base/'backups'; repository.mkdir()
        return source, repository

    def test_roundtrip_preserves_bytes_and_refuses_existing_destination(self):
        with tempfile.TemporaryDirectory() as directory:
            source, repository=self.fixture(Path(directory)); archive=b.snapshot(source,repository)
            target=Path(directory)/'restored'
            b.restore(archive,target,18089)
            self.assertEqual((target/'store/runs.mv.db').read_bytes(), (source/'store/runs.mv.db').read_bytes())
            self.assertEqual(b.properties(target)['lab.storage.root'],str(target/'store'))
            self.assertEqual(b.properties(target)['lab.career.sequence.recover-on-startup'],'false')
            self.assertFalse((archive/'payload/store/owner.lock').exists())
            with self.assertRaises(ValueError): b.restore(archive,target,18090)

    def test_corruption_refuses_restore_before_destination_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            source, repository=self.fixture(Path(directory)); archive=b.snapshot(source,repository)
            (archive/'payload/store/report.bin').write_bytes(b'tampered')
            target=Path(directory)/'restore'
            with self.assertRaises(ValueError): b.restore(archive,target,18089)
            self.assertFalse(target.exists())

    def test_symlink_and_traversal_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source, repository=self.fixture(Path(directory))
            link=source/'store/link'; link.symlink_to(source/'controller.jar')
            with self.assertRaises(ValueError): b.snapshot(source,repository)
            link.unlink(); archive=b.snapshot(source,repository)
            value=b.read_json(archive/'manifest.json'); value['files']['../escape']={'size':0,'sha256':'bad'}
            b.write_json(archive/'manifest.json',value)
            with self.assertRaises(ValueError): b.verify(archive)

    def test_retention_keeps_latest_and_preserves_partial_and_unrelated(self):
        with tempfile.TemporaryDirectory() as directory:
            source, repository=self.fixture(Path(directory))
            old=b.snapshot(source,repository); newer=b.snapshot(source,repository); newest=b.snapshot(source,repository)
            partial=repository/'backup-interrupted.partial'; partial.mkdir()
            unrelated=repository/'notes'; unrelated.mkdir()
            self.assertEqual(b.prune(repository,source,2),[old.name])
            self.assertTrue(all(p.exists() for p in (newer,newest,partial,unrelated)))

    def test_copy_failure_resumes_previously_running_services(self):
        with tempfile.TemporaryDirectory() as directory:
            source, repository=self.fixture(Path(directory))
            with patch.object(b.service,'ownership',return_value={'entries':[{'label':'api'},{'label':'frontend'}]}), patch.object(b.service,'loaded',return_value='running'), patch.object(b,'busy',return_value=False), patch.object(b,'snapshot',side_effect=OSError('disk full')), patch.object(b,'ready'), patch.object(b.service,'manage') as manage:
                with self.assertRaisesRegex(OSError,'disk full'): b.backup(source,repository,7)
                self.assertEqual([c.args[-1] for c in manage.call_args_list],['stop','start'])
                self.assertFalse((source/'backup-resume.json').exists())

    def test_active_run_skips_without_stopping(self):
        with tempfile.TemporaryDirectory() as directory:
            source, repository=self.fixture(Path(directory))
            with patch.object(b.service,'ownership',return_value={'entries':[{'label':'api'}]}), patch.object(b.service,'loaded',return_value='running'), patch.object(b,'busy',return_value=True), patch.object(b.service,'manage') as manage:
                b.backup(source,repository,7); manage.assert_not_called()

    def test_concurrent_operation_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'operation.lock'
            with b.lock(path):
                with self.assertRaises(BlockingIOError):
                    with b.lock(path): pass

    def test_resume_failure_retains_recovery_intent(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); journal=root/'backup-resume.json'
            b.write_json(journal,{'deployment':str(root),'domain':'gui/501','resume':True})
            with patch.object(b.service,'manage'), patch.object(b,'ready',side_effect=OSError('unavailable')):
                with self.assertRaises(OSError): b.resume(root,'gui/501')
            self.assertTrue(journal.exists())

    def test_change_after_verification_does_not_publish_restore(self):
        with tempfile.TemporaryDirectory() as directory:
            source, repository=self.fixture(Path(directory)); archive=b.snapshot(source,repository)
            target=Path(directory)/'restored'; original=b.copy_checked
            def changed(src,dest,limit):
                if src.name=='controller.jar': src.write_bytes(b'changed during copy')
                return original(src,dest,limit)
            with patch.object(b,'copy_checked',side_effect=changed):
                with self.assertRaisesRegex(ValueError,'changed during restore'): b.restore(archive,target,18089)
            self.assertFalse(target.exists())

    def test_copy_budget_rejects_excess_before_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            source=Path(directory)/'source'; source.write_bytes(b'123')
            target=Path(directory)/'target'
            with self.assertRaisesRegex(ValueError,'size limit'): b.copy_checked(source,target,2)
            self.assertEqual(target.stat().st_size,0)

    def test_fifo_in_snapshot_is_rejected_without_reading(self):
        with tempfile.TemporaryDirectory() as directory:
            source, repository=self.fixture(Path(directory)); archive=b.snapshot(source,repository)
            target=archive/'payload/store/report.bin'; target.unlink(); os.mkfifo(target)
            with self.assertRaisesRegex(ValueError,'Non-regular'): b.verify(archive)


if __name__=='__main__': unittest.main()

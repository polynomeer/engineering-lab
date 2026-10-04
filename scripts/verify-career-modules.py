#!/usr/bin/env python3
"""Exercise real Gradle settings in disposable builds; never alter existing modules."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

REPOSITORY = Path(__file__).resolve().parent.parent


class ModuleDiscoveryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="career-module-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        shutil.copyfile(REPOSITORY / "career-lab/settings.gradle.kts", self.root / "settings.gradle.kts")
        self.environment = os.environ.copy()
        java_home = self.environment.get("CAREER_LAB_JAVA_HOME")
        if not java_home and sys.platform == "darwin":
            java_home = subprocess.check_output(["/usr/libexec/java_home", "-F", "-v", "21"], text=True).strip()
        if java_home:
            self.environment["JAVA_HOME"] = java_home

    def module(self, name, metadata=True, build=True):
        folder = self.root / name
        folder.mkdir()
        if metadata:
            (folder / "lab-module.properties").write_text(
                f"schemaVersion=1\nid={name}\ntitle=Test module\ncollection=career\n", encoding="utf-8")
        if build:
            (folder / "build.gradle.kts").write_text("", encoding="utf-8")
        return folder

    def gradle(self, success=True):
        wrapper = REPOSITORY / "career-lab" / ("gradlew.bat" if os.name == "nt" else "gradlew")
        result = subprocess.run([str(wrapper), "-p", str(self.root), "projects", "--console=plain"],
                                env=self.environment, capture_output=True, text=True, timeout=180)
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode == 0, success, output[-6000:])
        return output

    def test_new_modules_are_discovered_and_unmarked_folders_are_ignored(self):
        self.module("first-lab")
        self.module("new-career")
        self.module("scratch", metadata=False)
        output = self.gradle()
        self.assertIn(":first-lab", output)
        self.assertIn(":new-career", output)
        self.assertNotIn(":scratch", output)

    def test_mismatched_identity_is_rejected(self):
        folder = self.module("first-lab")
        declaration = folder / "lab-module.properties"
        declaration.write_text(declaration.read_text().replace("id=first-lab", "id=other"))
        self.assertIn("Module id must match its directory name", self.gradle(success=False))

    def test_unsupported_version_is_rejected(self):
        folder = self.module("first-lab")
        declaration = folder / "lab-module.properties"
        declaration.write_text(declaration.read_text().replace("schemaVersion=1", "schemaVersion=99"))
        self.assertIn("Unsupported module schema", self.gradle(success=False))

    def test_declared_module_requires_build(self):
        self.module("first-lab", build=False)
        self.assertIn("Declared module has no Gradle build", self.gradle(success=False))


if __name__ == "__main__":
    unittest.main()

import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("central_bundle", Path(__file__).with_name("check-central-bundle.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CentralBundleTest(unittest.TestCase):
    def check(self, extra=None, include_pom=True):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "bundle.zip"
            with zipfile.ZipFile(archive, "w") as bundle:
                bundle.writestr("com/debugbundle/core/3.1.0/core-3.1.0.jar", b"jar")
                if include_pom:
                    bundle.writestr("com/debugbundle/core/3.1.0/core-3.1.0.pom", b"pom")
                if extra:
                    bundle.writestr(extra, b"unexpected")
            module.validate_bundle(archive, "3.1.0", {"core"})

    def test_complete_component(self):
        self.check()

    def test_maven_310_local_metadata_is_rejected(self):
        for name in ("maven-metadata-local.xml", "_remote.repositories"):
            with self.assertRaisesRegex(ValueError, "invalid_central_bundle_entry"):
                self.check("com/debugbundle/core/" + name)

    def test_pom_is_required_for_every_component(self):
        with self.assertRaisesRegex(ValueError, "central_bundle_missing_pom:core"):
            self.check(include_pom=False)

    def test_version_drift_and_path_escape_are_rejected(self):
        for path in ("com/debugbundle/core/3.0.0/core-3.0.0.pom", "../core-3.1.0.pom"):
            with self.assertRaisesRegex(ValueError, "invalid_central_bundle_entry"):
                self.check(path)


if __name__ == "__main__":
    unittest.main()

import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest

from preview_package import package_preview, run_tool


class PreviewPackageTest(unittest.TestCase):
    def test_preview_rejects_a_foreign_host_disguised_as_loopback(self):
        source = Path(os.environ["HACG_PLUGIN_JAR"])
        with tempfile.TemporaryDirectory(prefix="hacg-preview-url-") as directory:
            with self.assertRaises(ValueError):
                package_preview(source, Path(directory), "http://127.0.0.1:18781@foreign.invalid")

    def test_real_plugin_is_signed_and_catalog_matches_certificate_and_bytes(self):
        source = Path(os.environ["HACG_PLUGIN_JAR"])
        with tempfile.TemporaryDirectory(prefix="hacg-preview-") as directory:
            output = Path(directory)
            result = package_preview(source, output, "http://127.0.0.1:18781")
            signed = output / "mihon-recommendation-hacg.jar"
            self.assertTrue(signed.is_file())
            verification = run_tool("jarsigner", ["-verify", "-verbose", str(signed)])
            self.assertIn("jar verified", verification.decode("utf-8").lower())
            certificate = run_tool("keytool", ["-exportcert", "-alias", "preview", "-keystore", str(output / "preview.p12"), "-storepass", "preview-local-only"])
            self.assertEqual(hashlib.sha256(certificate).hexdigest(), result["fingerprint"])
            descriptor = json.loads((output / "repo.json").read_text(encoding="utf-8"))
            index = json.loads((output / "recommendation-index-v1.json").read_text(encoding="utf-8"))
            self.assertEqual(result["fingerprint"], descriptor["fingerprint"])
            self.assertEqual(result["fingerprint"], index["signingKey"])
            self.assertEqual(hashlib.sha256(signed.read_bytes()).hexdigest(), index["packages"][0]["sha256"])
            self.assertEqual(1, index["packages"][0]["apiMinor"])
            self.assertEqual("http://127.0.0.1:18781/mihon-recommendation-hacg.jar", index["packages"][0]["jarUrl"])


if __name__ == "__main__":
    unittest.main()

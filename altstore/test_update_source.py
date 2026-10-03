"""Tests for update_source.py: python3 -m unittest discover -s altstore"""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import update_source

HERE = Path(__file__).resolve().parent
TEMPLATE = HERE / "source-template.json"
REPOSITORY = "IvanZagulin/lumina-reader"


def sha(n: int) -> str:
    return f"{n:064x}"


class UpdateSourceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.current = self.dir / "current.json"
        self.out = self.dir / "source.json"

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def run_build(self, build: int, keep: int = 5) -> int:
        version = f"1.0.{build}"
        return update_source.main(
            [
                "--template", str(TEMPLATE),
                "--current", str(self.current),
                "--out", str(self.out),
                "--repository", REPOSITORY,
                "--version", version,
                "--build", str(build),
                "--url", f"https://github.com/{REPOSITORY}/releases/download/ios-v{version}/LuminaReader-{version}.ipa",
                "--size", str(1000 + build),
                "--sha256", sha(build),
                "--date", "2026-10-03T12:00:00Z",
                "--keep", str(keep),
            ]
        )

    def publish(self) -> dict:
        """Reads the output and makes it the "published" source for the next run."""
        source = json.loads(self.out.read_text(encoding="utf-8"))
        self.current.write_text(self.out.read_text(encoding="utf-8"), encoding="utf-8")
        return source

    def test_first_build_without_published_source(self) -> None:
        self.current.write_text("{}", encoding="utf-8")
        self.assertEqual(0, self.run_build(7))
        source = self.publish()
        app = source["apps"][0]
        self.assertEqual("com.lumina.reader", app["bundleIdentifier"])
        self.assertEqual(["1.0.7"], [v["version"] for v in app["versions"]])
        entry = app["versions"][0]
        self.assertEqual("7", entry["buildVersion"])
        self.assertEqual(1007, entry["size"])
        self.assertEqual(sha(7), entry["sha256"])
        self.assertEqual("15.0", entry["minOSVersion"])
        # Legacy fields mirror the newest version.
        self.assertEqual("1.0.7", app["version"])
        self.assertEqual(entry["downloadURL"], app["downloadURL"])
        self.assertEqual(1007, app["size"])
        # Placeholders are filled in everywhere.
        self.assertNotIn("{repository}", json.dumps(source))
        self.assertEqual(
            f"https://raw.githubusercontent.com/{REPOSITORY}/automation/altstore-source/source.json",
            source["sourceURL"],
        )

    def test_missing_or_broken_published_source_is_treated_as_empty(self) -> None:
        self.assertEqual(0, self.run_build(3))  # current.json does not exist
        self.current.write_text("<html>not json</html>", encoding="utf-8")
        self.assertEqual(0, self.run_build(4))
        self.assertEqual(["1.0.4"], [v["version"] for v in self.publish()["apps"][0]["versions"]])

    def test_newest_first_rerun_replaces_and_history_is_capped(self) -> None:
        for build in (1, 2, 3, 2):  # the second "2" is a re-run of the same build
            self.assertEqual(0, self.run_build(build, keep=3))
            self.publish()
        for build in (4, 5):
            self.assertEqual(0, self.run_build(build, keep=3))
            source = self.publish()
        versions = [v["version"] for v in source["apps"][0]["versions"]]
        self.assertEqual(["1.0.5", "1.0.4", "1.0.3"], versions)

    def test_invalid_input_is_rejected(self) -> None:
        self.current.write_text("{}", encoding="utf-8")
        with self.assertRaises(SystemExit):
            update_source.main(["--template", str(TEMPLATE)])
        bad = [
            "--template", str(TEMPLATE), "--current", str(self.current), "--out", str(self.out),
            "--repository", REPOSITORY, "--version", "1.0.x", "--build", "9",
            "--url", "https://example.com/a.ipa", "--size", "10", "--sha256", sha(9),
            "--date", "2026-10-03T12:00:00Z",
        ]
        self.assertEqual(2, update_source.main(bad))
        self.assertFalse(self.out.exists())

    def test_template_matches_the_xcode_project(self) -> None:
        template = json.loads(TEMPLATE.read_text(encoding="utf-8"))
        project = (HERE.parent / "iosApp" / "project.yml").read_text(encoding="utf-8")
        app = template["apps"][0]
        self.assertIn(f"PRODUCT_BUNDLE_IDENTIFIER: {app['bundleIdentifier']}", project)
        self.assertEqual([app["bundleIdentifier"]], template["featuredApps"])
        self.assertIn('iOS: "15.0"', project)


if __name__ == "__main__":
    unittest.main()

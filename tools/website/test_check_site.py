#!/usr/bin/env python3
"""Tests for tools/website/check_site.py and vercel.json's deploy gate.

  python3 tools/website/test_check_site.py
"""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_site  # noqa: E402

GOOD = {
    "schema": 1,
    "promos": [
        {"id": "discord", "kind": "discord", "label": "DISCORD"},
        {"id": "website", "kind": "link", "label": "ORIGIN", "title": "originclient.org", "clickUrl": "https://originclient.org/"},
        {"id": "tips", "kind": "tips", "label": "TIP"},
    ],
    "discord": {"inviteCode": "z3SbasGCKR"},
    "network": {"enabled": False, "url": "https://originclient.org/ad/banner.html"},
}


def feed(**changes) -> str:
    f = json.loads(json.dumps(GOOD))
    f.update(changes)
    return json.dumps(f)


class PromoFeed(unittest.TestCase):
    def test_live_feed_is_good(self):
        self.assertEqual(check_site.promo_feed_problems((check_site.SITE / "launcher" / "promos.json").read_text()), [])

    def test_good(self):
        self.assertEqual(check_site.promo_feed_problems(feed()), [])

    def test_broken_json(self):
        self.assertTrue(check_site.promo_feed_problems('{"schema": 1, "promos": [,]}'))

    def test_wrong_schema(self):
        self.assertTrue(check_site.promo_feed_problems(feed(schema=2)))
        self.assertTrue(check_site.promo_feed_problems(feed(schema=True)))

    def test_link_needs_title_and_https(self):
        bad = json.loads(feed())
        bad["promos"][1].pop("title")
        self.assertTrue(check_site.promo_feed_problems(json.dumps(bad)))
        bad = json.loads(feed())
        bad["promos"][1]["clickUrl"] = "http://originclient.org/"
        self.assertTrue(check_site.promo_feed_problems(json.dumps(bad)))

    def test_placeholder_invite(self):
        self.assertTrue(check_site.promo_feed_problems(feed(discord={"inviteCode": "REPLACE_ME"})))

    def test_unknown_kind_and_duplicate_id(self):
        bad = json.loads(feed())
        bad["promos"].append({"id": "tips", "kind": "tips"})
        bad["promos"].append({"id": "x", "kind": "video"})
        self.assertEqual(len(check_site.promo_feed_problems(json.dumps(bad))), 2)


class Site(unittest.TestCase):
    def test_repo_site_passes(self):
        self.assertEqual(check_site.vercel_problems() + check_site.page_problems() + check_site.copy_problems(), [])


@unittest.skipUnless(shutil.which("node"), "node is needed to run vercel.json's ignoreCommand")
class VercelGate(unittest.TestCase):
    """Vercel runs ignoreCommand before each deploy: exit 0 skips the deploy, exit 1 builds it."""

    cmd = json.loads((check_site.SITE / "vercel.json").read_text())["ignoreCommand"]

    def run_in(self, files: dict[str, str]) -> int:
        with tempfile.TemporaryDirectory() as tmp:
            for rel, text in files.items():
                (Path(tmp) / rel).parent.mkdir(parents=True, exist_ok=True)
                (Path(tmp) / rel).write_text(text)
            return subprocess.run(["sh", "-c", self.cmd], cwd=tmp, capture_output=True).returncode

    def test_good_feed_deploys(self):
        self.assertEqual(self.run_in({"launcher/promos.json": feed()}), 1)            # root dir = website/
        self.assertEqual(self.run_in({"website/launcher/promos.json": feed()}), 1)    # root dir = repo

    def test_broken_feed_is_not_deployed(self):
        self.assertEqual(self.run_in({"launcher/promos.json": '{"schema": 1, "promos": [,]}'}), 0)
        self.assertEqual(self.run_in({"launcher/promos.json": feed(schema=2)}), 0)

    def test_no_feed_still_deploys(self):
        self.assertEqual(self.run_in({}), 1)


if __name__ == "__main__":
    unittest.main()

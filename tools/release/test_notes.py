#!/usr/bin/env python3
"""Tests for the release-notes filter (tools/release/notes.py).

  python3 tools/release/test_notes.py

tests/v1.0.57_commits.json is the real launcher-v1.0.56..v1.0.57 commit list
(non-merge title + touched files; for commits with player-facing files only the
first 3 of them are kept, which is all the filter needs).
"""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import notes  # noqa: E402

FIXTURE = Path(__file__).resolve().parent / "tests" / "v1.0.57_commits.json"
APP = ["src/OriginLauncher.App/X.cs"]


def lines(*records):
    return notes.player_lines([(s, f if f is not None else APP) for s, f in records])


class FilterRules(unittest.TestCase):
    def test_dev_ci_test_docs_titles_are_dropped(self):
        for t in ("Merge import from another Minecraft launcher", "docs: log outcomes", "MEMORY: 2026-10-19 notes",
                  "ci(boot-smoke): run Gradle on JDK 21", "test(launcher): torn log recovers",
                  "chore: bump", "Launcher shots: draw the focus frame",
                  "pre-1.20 frame log: time blitToScreen (dev-only)", "WIP: half done",
                  "Import drawer review round 1", "Bulk import interface skeleton",
                  "Setup: SettingsDelta.AddProfiles declared once", "Fix check_consistency drift",
                  "Import drawer implements IImportCloseHost, one refusal sentence"):
            self.assertEqual(notes.classify(t)[0], "skip", t)

    def test_real_features_with_camelcase_or_review_words_are_kept(self):
        # Code review (2026-10-19): the first filter wrongly dropped these.
        for t in ("Cosmetics: Y2K drop, new hats", "Add ModMenu to the mod list", "Add FreeLook camera",
                  "Fix macOS-style scroll", "Fix crash on iPhone skins", "Fix skeleton horses not spawning",
                  "Import drawer: Friends' list frame back, single review starts at the list pages' depth, toggle knob reverted",
                  "Friends redesign review fixes (1.21.1)", "Launcher restyle review fixes",
                  "1.21.1: loading mark comes to rest within ~1/3 s of the settle; OriginAssets hands each result over once",
                  "1.19.2: settle hold + OriginAssets; reload apply steps no longer run inline inside the buffer swap"):
            self.assertNotEqual(notes.classify(t)[0], "skip", t)

    def test_noise_only_diff_is_dropped_even_with_a_nice_title(self):
        got = lines(("Make the thing faster", ["tools/x.py", "docs/a.md", ".github/workflows/a.yml"]),
                    ("Nicer Play button", ["src/OriginLauncher.App/Play.xaml.cs"]))
        self.assertEqual(got["new"], ["Nicer Play button"])
        self.assertEqual(lines(("Faster", ["src/OriginLauncher.Tests/A.cs"]))["new"], [])

    def test_prefixes_become_plain_sentences(self):
        self.assertEqual(notes.classify("fix(launcher): friends list no longer flickers"),
                         ("fixes", "Friends list no longer flickers"))
        self.assertEqual(notes.classify("feat: new store tab"), ("new", "New store tab"))
        self.assertEqual(notes.classify("Launcher: rounder corners")[1], "Rounder corners")

    def test_wip_and_hash_tails_are_stripped_but_the_feature_stays(self):
        self.assertEqual(notes.classify("Import from another Minecraft launcher (WIP pending review)"),
                         ("new", "Import from another Minecraft launcher"))
        self.assertEqual(notes.classify("Loading mark rests (8f5c7ff3 port)")[1], "Loading mark rests")

    def test_revert_pairs_cancel(self):
        got = lines(("Add shiny thing", None), ('Revert "Add shiny thing"', None), ("Other change", None))
        self.assertEqual(got["new"], ["Other change"])

    def test_per_version_duplicates_collapse(self):
        got = lines(*((f"{v}: Friends redesign port", None) for v in ("1.16.5", "1.17.1", "26.3")))
        self.assertEqual(got["new"], ["Friends redesign port"])

    def test_only_noise_leaves_nothing(self):
        self.assertEqual(lines(("docs: x", ["docs/x.md"])), {"new": [], "fixes": []})


class RealRange(unittest.TestCase):
    def setUp(self):
        recs = json.loads(FIXTURE.read_text(encoding="utf-8"))
        self.got = notes.player_lines([(r["subject"], r["files"]) for r in recs])
        self.all = self.got["new"] + self.got["fixes"]

    def test_nothing_internal_survives(self):
        bad = ("shots", "frame log", "dev-only", "memory", "docs:", "ci(", "test(", "tests:", "iimport", "wip", "merge", "ci:")
        for line in self.all:
            for b in bad:
                self.assertNotIn(b, line.lower(), line)

    def test_player_features_survive(self):
        joined = "\n".join(self.all)
        for want in ("Friends redesign", "Cosmetics", "Import from another Minecraft launcher",
                     "Waypoints: draw beams", "Party chat", "Y2K"):
            self.assertIn(want, joined)

    def test_version_spam_collapsed(self):
        self.assertLessEqual(sum("Friends redesign port" in l for l in self.all), 1)
        self.assertLessEqual(sum(l.startswith("Cosmetics") for l in self.all), 2)
        self.assertLess(len(self.all), 60)


if __name__ == "__main__":
    unittest.main(verbosity=1)

#!/usr/bin/env python3
"""Tests for the Claude Lean hooks (.claude/hooks/lean/). Standard library only.

    python tools/lean/test_lean.py

Each test copies lean.py + run.sh into a throwaway project folder, so nothing
touches this repo's .claude/lean/ runtime data.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(REPO, ".claude", "hooks", "lean")


class Lean(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lean-test-")
        hooks = os.path.join(self.root, ".claude", "hooks", "lean")
        os.makedirs(hooks)
        for f in ("lean.py", "run.sh"):
            shutil.copy(os.path.join(SRC, f), hooks)
        self.lean = os.path.join(hooks, "lean.py")
        self.run_sh = os.path.join(hooks, "run.sh")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def hook(self, cmd, payload, raw=None, env=None):
        data = raw if raw is not None else json.dumps(dict({"session_id": "s1"}, **payload))
        r = subprocess.run([sys.executable, self.lean, cmd], input=data.encode(), capture_output=True,
                           env=dict(os.environ, **(env or {})), timeout=30)
        self.assertEqual(r.returncode, 0, r.stderr)
        return json.loads(r.stdout) if r.stdout.strip() else None

    def big_file(self, name="Big.java", kb=20):
        path = os.path.join(self.root, name)
        with open(path, "w") as f:
            f.write(("class Big { int x; }\n" * 60) * (kb * 1024 // 1260 + 1))
        return path

    # --- Bash trimming -------------------------------------------------------
    def gradle_log(self):
        noise = ["> Task :compileJava UP-TO-DATE line %d" % i for i in range(900)]
        err = ["src/Foo.java:12: error: cannot find symbol", "    foo.bar();", "       ^",
               "  symbol:   method bar()", "  location: class Foo"]
        warns = ["warning: [deprecation] old() in Foo has been deprecated %d" % i for i in range(50)]
        tail = ["", "FAILURE: Build failed with an exception.", "BUILD FAILED in 12s", "3 actionable tasks: 1 executed"]
        return "\n".join(noise[:400] + err + warns + noise[400:] + tail)

    def test_gradle_failure_is_trimmed_and_saved(self):
        log = self.gradle_log()
        out = self.hook("post-tool", {"tool_name": "Bash", "tool_input": {"command": "cd x && ./gradlew build"},
                                      "tool_response": {"stdout": log, "stderr": "", "interrupted": False,
                                                        "isImage": False}})
        new = out["hookSpecificOutput"]["updatedToolOutput"]
        self.assertEqual(out["hookSpecificOutput"]["hookEventName"], "PostToolUse")
        self.assertFalse(new["interrupted"])                     # other fields preserved
        text = new["stdout"]
        for must in ("error: cannot find symbol", "       ^", "location: class Foo", "BUILD FAILED in 12s",
                     "FAILURE: Build failed", "Full output: .claude/lean/logs/"):
            self.assertIn(must, text)
        self.assertLess(len(text), len(log) / 3)
        self.assertIn("50 warnings (1 unique shown)", text)
        logname = text.split("Full output: ")[1].split(" ")[0]
        with open(os.path.join(self.root, logname)) as f:
            self.assertEqual(f.read(), log)                       # nothing lost

    def test_persisted_output_preview_shows_the_end(self):
        # Claude Code saved a huge output to disk and passes only its start in stdout.
        full = os.path.join(self.root, "persisted.txt")
        with open(full, "w") as f:
            f.write(self.gradle_log())
        out = self.hook("post-tool", {"tool_name": "Bash", "tool_input": {"command": "./gradlew build"},
                                      "tool_response": {"stdout": self.gradle_log()[:2000], "stderr": "",
                                                        "persistedOutputPath": full, "persistedOutputSize": 9}})
        new = out["hookSpecificOutput"]["updatedToolOutput"]
        self.assertEqual(new["persistedOutputPath"], full)
        self.assertIn("error: cannot find symbol", new["stdout"])
        self.assertIn("BUILD FAILED in 12s", new["stdout"])
        self.assertLess(len(new["stdout"]), 2100 + len(full))

    def test_small_output_untouched(self):
        self.assertIsNone(self.hook("post-tool", {"tool_name": "Bash", "tool_input": {"command": "./gradlew build"},
                                                  "tool_response": {"stdout": "BUILD SUCCESSFUL\n", "stderr": ""}}))

    def test_non_runner_untouched(self):
        self.assertIsNone(self.hook("post-tool", {"tool_name": "Bash", "tool_input": {"command": "cat big.txt"},
                                                  "tool_response": {"stdout": self.gradle_log(), "stderr": ""}}))

    def test_dotnet_and_npm_detected(self):
        for cmd in ("dotnet test src/OriginLauncher.Tests", "npm ci", "npx playwright test", ".\\gradlew.bat build",
                    "python -m pytest -q"):
            out = self.hook("post-tool", {"tool_name": "Bash", "tool_input": {"command": cmd},
                                          "tool_response": {"stdout": self.gradle_log(), "stderr": ""}})
            self.assertIsNotNone(out, cmd)

    # --- Read guard ----------------------------------------------------------
    def read(self, path, agent=None, **extra):
        p = {"tool_name": "Read", "tool_input": dict({"file_path": path}, **extra)}
        if agent:
            p["agent_id"] = agent
        return p

    def test_repeat_read_denied_once_then_allowed(self):
        path = self.big_file()
        self.assertIsNone(self.hook("pre-tool", self.read(path)))
        self.hook("post-tool", self.read(path))
        out = self.hook("pre-tool", self.read(path))
        self.assertEqual(out["hookSpecificOutput"]["permissionDecision"], "deny")
        self.assertIn("repeat this exact Read call", out["hookSpecificOutput"]["permissionDecisionReason"])
        self.assertIsNone(self.hook("pre-tool", self.read(path)))   # escape hatch

    def test_changed_file_or_other_range_or_subagent_allowed(self):
        path = self.big_file()
        self.hook("post-tool", self.read(path))
        self.assertIsNone(self.hook("pre-tool", self.read(path, offset=10, limit=20)))
        self.assertIsNone(self.hook("pre-tool", self.read(path, agent="sub1")))
        with open(path, "a") as f:
            f.write("// edited\n")
        self.assertIsNone(self.hook("pre-tool", self.read(path)))

    def test_small_file_never_denied(self):
        path = os.path.join(self.root, "small.txt")
        with open(path, "w") as f:
            f.write("hi\n")
        self.hook("post-tool", self.read(path))
        self.assertIsNone(self.hook("pre-tool", self.read(path)))

    def test_compaction_resets_guard_and_returns_card(self):
        path = self.big_file()
        self.hook("prompt", {"prompt": "fix the zoom bug on 1.8.9"})
        self.hook("post-tool", {"tool_name": "Edit", "tool_input": {"file_path": path}})
        self.hook("post-tool", self.read(path))
        out = self.hook("session-start", {"source": "compact"})
        card = out["hookSpecificOutput"]["additionalContext"]
        self.assertEqual(out["hookSpecificOutput"]["hookEventName"], "SessionStart")
        self.assertIn("fix the zoom bug on 1.8.9", card)
        self.assertIn("Big.java", card)
        self.assertIsNone(self.hook("pre-tool", self.read(path)))   # context was summarized: allow
        self.assertIsNone(self.hook("session-start", {"source": "startup"}))

    # --- CI logs -------------------------------------------------------------
    def test_job_log_timestamps_stripped(self):
        lines = "\n".join("2026-10-13T00:54:25.3039270Z net.minecraft.Foo%d" % i for i in range(800))
        resp = [{"type": "text", "text": json.dumps({"job_id": 1, "logs_content": lines})}]
        out = self.hook("post-tool", {"tool_name": "mcp__github__get_job_logs", "tool_response": resp})
        new = out["hookSpecificOutput"]["updatedMCPToolOutput"]
        body = json.loads(new[0]["text"])
        self.assertTrue(body["logs_content"].startswith("net.minecraft.Foo0\nnet.minecraft.Foo1"))
        self.assertEqual(body["job_id"], 1)

    # --- safety --------------------------------------------------------------
    def test_garbage_input_fails_open(self):
        self.assertIsNone(self.hook("post-tool", None, raw="{not json"))
        self.assertTrue(os.path.exists(os.path.join(self.root, ".claude", "lean", "errors.log")))

    def test_off_switch(self):
        r = subprocess.run(["bash", self.run_sh, "post-tool"], input=b"{}", capture_output=True,
                           env=dict(os.environ, CLAUDE_LEAN="0"))
        self.assertEqual((r.returncode, r.stdout), (0, b""))

    def test_run_sh_finds_python(self):
        payload = json.dumps({"session_id": "s1", "tool_name": "Bash", "tool_input": {"command": "./gradlew build"},
                              "tool_response": {"stdout": self.gradle_log(), "stderr": ""}})
        r = subprocess.run(["bash", self.run_sh, "post-tool"], input=payload.encode(), capture_output=True)
        self.assertEqual(r.returncode, 0)
        self.assertIn("updatedToolOutput", r.stdout.decode())

    def test_statusline(self):
        r = subprocess.run([sys.executable, self.lean, "statusline"], capture_output=True, input=json.dumps({
            "session_id": "s1", "context_window": {"used_percentage": 34, "current_usage": {
                "input_tokens": 10, "cache_read_input_tokens": 100000, "cache_creation_input_tokens": 2000}},
            "rate_limits": {"five_hour": {"used_percentage": 41}}}).encode())
        self.assertEqual(r.stdout.decode(), "ctx 102k (34%) | 5h 41% | lean on")


if __name__ == "__main__":
    unittest.main(verbosity=1)

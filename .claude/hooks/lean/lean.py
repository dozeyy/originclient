#!/usr/bin/env python3
"""Claude Lean: deterministic hooks that keep Claude Code's context small.

Read .claude/hooks/lean/README.md first. Every Claude Code hook in
.claude/settings.json calls run.sh, which runs this file with one subcommand:

  pre-tool       PreToolUse   Read repeat guard (deny an unchanged re-read once)
  post-tool      PostToolUse  trim noisy build/test output (Bash), strip CI-log
                              timestamps (GitHub job logs), remember reads + edits
  prompt         UserPromptSubmit  remember the last few user requests
  session-start  SessionStart(compact)  re-inject the state card after compaction
  statusline     statusLine   context size, plan limits, what Lean saved

Why this shape (measured on this repo's own transcripts, 2026-10-17): ~99% of
input tokens are cache READS of the accumulated conversation, re-read on every
call. So the only things that cut usage are (1) keeping big text out of the
conversation in the first place and (2) compacting before it grows huge
(Claude Code's own auto-compact; Origin no longer pins autoCompactWindow). Nothing here calls a model.

Rules this file must keep:
- Fail OPEN. Any error -> exit 0 with no output, so Claude Code behaves exactly
  as if Lean weren't installed. Never exit 2 (that blocks the tool).
- Print to stdout ONLY the JSON reply (UserPromptSubmit/SessionStart stdout is
  added to Claude's context verbatim).
- Never hide information for good: trimmed output is saved in full under
  .claude/lean/logs/ and the trimmed text says where.
- Python 3.8+, standard library only, Windows (Git Bash) + Linux.
"""
import json
import os
import random
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
LEAN_DIR = os.path.join(ROOT, ".claude", "lean")          # gitignored runtime data
STATE_DIR = os.path.join(LEAN_DIR, "state")
LOG_DIR = os.path.join(LEAN_DIR, "logs")
STATS = os.path.join(LEAN_DIR, "stats.jsonl")

# --- Trimming thresholds --------------------------------------------------
PASS_LINES = 120        # outputs this small are never touched
PASS_CHARS = 8000
HEAD_LINES = 5
TAIL_LINES = 40         # the end of a build log holds the verdict
ERROR_CONTEXT = 8       # lines kept after an error (javac source+caret+symbol, stack frames)
MAX_ERROR_LINES = 150
MAX_WARNINGS = 25       # unique warnings kept; repeats are counted, not shown
MAX_OUT_CHARS = 12000
MIN_SAVING = 0.3        # don't bother replacing unless it saves >= 30%
KEEP_LOGS = 60

# --- Read repeat guard ----------------------------------------------------
# A denied call still costs one round trip (the whole context is re-read once),
# so only guard files big enough that re-reading them costs more than that.
GUARD_MIN_BYTES = 16 * 1024
GUARD_MIN_LINES = 300    # ranged reads shorter than this are never guarded

# Commands whose output is mostly progress noise around a few lines that matter.
RUNNERS = [
    ("gradle", re.compile(r"(^|[\s;&(])(sh\s+|bash\s+)?(\./|\.\\)?gradlew(\.bat)?(\s|$)|(^|[\s;&(])gradle\s")),
    ("dotnet", re.compile(r"\bdotnet\s+(build|test|restore|publish|pack|clean)\b")),
    ("npm", re.compile(r"\b(npm|pnpm|yarn)\s+(install|ci|i|test|run\s+(build|test|lint|check))\b"
                       r"|\bnpx\s+(--yes\s+)?(playwright\s+test|tsc|eslint|vitest|jest)\b")),
    ("pytest", re.compile(r"\bpytest\b|-m\s+(pytest|unittest)\b")),
    ("pip", re.compile(r"\bpip3?\s+install\b")),
    ("cargo", re.compile(r"\bcargo\s+(build|test|check|clippy)\b")),
    ("maven", re.compile(r"(^|[\s;&(])(\./)?mvnw?(\s|$)")),
    ("go", re.compile(r"\bgo\s+(build|test|vet)\b")),
]

ERR = re.compile(r"(?i)(\berror\b|\berrors?:|\bfailed\b|\bfailure\b|\bfatal\b|\bexception\b|\btraceback\b"
                 r"|\bpanic\b|cannot find symbol|undefined reference|\bFAIL\b|\u2717|\u2718|assertionerror)")
WARN = re.compile(r"(?i)\bwarn(ing)?\b|\bdeprecat")
SUMMARY = re.compile(r"(?i)(BUILD (SUCCESSFUL|FAILED)|build succeeded|time elapsed|total time|actionable tasks?"
                     r"|\btests? (run|passed|failed|summary)|\b\d+ (passed|failed|skipped)\b|passed!|failed!"
                     r"|added \d+ packages?|found \d+ vulnerabilit|^\s*Ran \d+ tests?|^\s*(OK|FAILED)\b)")
ANSI = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]")
GH_TIMESTAMP = re.compile(r"(?m)^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d+Z ")


# --- small utilities --------------------------------------------------------
def _now():
    return time.strftime("%Y-%m-%dT%H:%M:%S")


def _rel(path):
    try:
        return os.path.relpath(path, ROOT).replace("\\", "/")
    except ValueError:  # other drive on Windows
        return path.replace("\\", "/")


# Windows refuses os.replace() (PermissionError 13) while ANY other process has the
# target open -- a parallel hook or the statusline reading the same state file.
# Proven 2026-10-25: 4 writers + 2 readers on one session file -> 821 PermissionErrors
# and 821 orphaned .tmp files (the 111 in errors.log were exactly this). So: retry
# briefly, and never leave the temp file behind.
REPLACE_TRIES = 12


def _write_atomic(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = "%s.%d.%d.tmp" % (path, os.getpid(), random.randint(0, 1 << 30))
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(data)
        for i in range(REPLACE_TRIES):
            try:
                os.replace(tmp, path)
                return True
            except PermissionError:
                time.sleep(0.005 * (i + 1) + random.random() * 0.01)   # ~0.5 s worst case
        return False                     # still locked: skip this write (state is best-effort)
    finally:
        if os.path.exists(tmp):
            try:
                os.remove(tmp)
            except OSError:
                pass


def _stat(event, **fields):
    try:
        os.makedirs(LEAN_DIR, exist_ok=True)
        fields.update(t=_now(), event=event)
        with open(STATS, "a", encoding="utf-8") as f:
            f.write(json.dumps(fields) + "\n")
    except OSError:
        pass


def _state_path(key):
    safe = re.sub(r"[^A-Za-z0-9_-]", "_", key or "unknown")
    return os.path.join(STATE_DIR, safe + ".json")


def load_state(key):
    st = None
    for i in range(REPLACE_TRIES):
        try:
            with open(_state_path(key), encoding="utf-8") as f:
                st = json.load(f)
            break
        except PermissionError:         # mid-replace by another hook: wait, don't reset
            time.sleep(0.005 * (i + 1))
        except (OSError, ValueError):    # missing or corrupt -> fresh state
            st = {}
            break
    if st is None:                       # never readable: work on a blank copy, never save it
        st = {"_unreadable": True}
    st.setdefault("epoch", 0)          # bumped at every compaction: reads before it are gone from context
    st.setdefault("calls", 0)          # tool calls seen (for "N calls ago")
    st.setdefault("reads", {})         # guard key -> {mtime, size, epoch, call}
    st.setdefault("edited", [])        # files Claude changed, most recent last
    st.setdefault("prompts", [])       # last few user requests, verbatim (truncated)
    st.setdefault("last_denied", None)
    st.setdefault("saved_chars", 0)
    st.setdefault("blocked_reads", 0)
    return st


def save_state(key, st):
    if st.get("_unreadable"):            # saving would wipe the real file with blanks
        return False
    return _write_atomic(_state_path(key), json.dumps(st))


def reply(obj):
    sys.stdout.buffer.write(json.dumps(obj).encode("utf-8"))


# --- trimming ---------------------------------------------------------------
def _clean(line):
    line = ANSI.sub("", line)
    if "\r" in line:                    # progress bars redraw with \r; keep the final state
        line = line.rstrip("\r").split("\r")[-1]
    return line


# Normal outputs get the roomy view. Outputs Claude Code itself saved to disk
# (persistedOutputPath) are shown to Claude only as a ~2 KB preview, so they get
# the compact view, sized to fit that preview.
ROOMY = dict(head=HEAD_LINES, tail=TAIL_LINES, ctx=ERROR_CONTEXT, max_err=MAX_ERROR_LINES,
             max_warn=MAX_WARNINGS, max_chars=MAX_OUT_CHARS, line_cap=None)
COMPACT = dict(head=0, tail=12, ctx=2, max_err=18, max_warn=0, max_chars=1700, line_cap=160)


def trim_text(text, view=ROOMY, force=False):
    """Keep head, errors (+context), unique warnings, summaries and the tail.

    Returns (text, notes, line_count), or None when the text is small or
    trimming wouldn't save enough to be worth hiding anything."""
    if not isinstance(text, str):
        return None
    if not force and len(text) <= PASS_CHARS and text.count("\n") <= PASS_LINES:
        return None
    lines = [_clean(l) for l in text.splitlines()]
    if view["line_cap"]:
        lines = [l if len(l) <= view["line_cap"] else l[: view["line_cap"]] + " ..." for l in lines]
    n = len(lines)
    keep = set(range(min(view["head"], n))) | set(range(max(0, n - view["tail"]), n))
    warn_seen, warn_total, err_lines, err_dropped = set(), 0, 0, 0
    i = 0
    while i < n:
        line = lines[i]
        if ERR.search(line) and not WARN.search(line):
            if err_lines < view["max_err"]:
                end = min(n, i + 1 + view["ctx"])
                keep.update(range(i, end))
                err_lines += end - i
            else:
                err_dropped += 1
        elif SUMMARY.search(line):
            keep.add(i)
        elif WARN.search(line):
            warn_total += 1
            norm = re.sub(r"\d+", "#", line.strip())[:200]
            if norm not in warn_seen and len(warn_seen) < view["max_warn"]:
                warn_seen.add(norm)
                keep.add(i)
        i += 1

    out, last = [], -1
    for idx in sorted(keep):
        if idx > last + 1:
            out.append("    ... (%d lines skipped)" % (idx - last - 1))
        out.append(lines[idx])
        last = idx
    if last < n - 1:
        out.append("    ... (%d lines skipped)" % (n - 1 - last))
    body = "\n".join(out)
    if len(body) > view["max_chars"]:  # keep the verdict at the end, cut the middle
        tail = "\n".join(lines[max(0, n - view["tail"]):])
        body = body[: max(0, view["max_chars"] - len(tail) - 60)] + "\n    ... (cut to fit)\n" + tail
    notes = []
    if warn_total > len(warn_seen):
        notes.append("%d warnings (%d unique shown)" % (warn_total, len(warn_seen)))
    if err_dropped:
        notes.append("%d more error lines" % err_dropped)
    if not force and len(body) > len(text) * (1 - MIN_SAVING):
        return None
    return body, notes, n


def save_full_log(kind, text):
    os.makedirs(LOG_DIR, exist_ok=True)
    name = "%s-%s-%04d.log" % (time.strftime("%Y%m%d-%H%M%S"), kind, random.randint(0, 9999))
    path = os.path.join(LOG_DIR, name)
    with open(path, "w", encoding="utf-8", errors="replace") as f:
        f.write(text)
    try:  # keep the folder small
        logs = sorted(os.listdir(LOG_DIR))
        for old in logs[:-KEEP_LOGS]:
            os.remove(os.path.join(LOG_DIR, old))
    except OSError:
        pass
    return _rel(path)


def runner_kind(command):
    for kind, rx in RUNNERS:
        if rx.search(command or ""):
            return kind
    return None


def trim_bash(inp, st):
    resp = inp.get("tool_response")
    if not isinstance(resp, dict) or not isinstance(resp.get("stdout"), str) or resp.get("isImage"):
        return None
    kind = runner_kind((inp.get("tool_input") or {}).get("command", ""))
    if not kind:
        return None
    if resp.get("persistedOutputPath"):
        return trim_persisted(resp, kind, st)
    stdout, stderr = resp.get("stdout") or "", resp.get("stderr") or ""
    t_out, t_err = trim_text(stdout), trim_text(stderr) if isinstance(stderr, str) else None
    if not t_out and not t_err:
        return None
    full = stdout if not stderr else "=== stdout ===\n%s\n=== stderr ===\n%s" % (stdout, stderr)
    log = save_full_log(kind, full)
    new = dict(resp)
    notes = []
    if t_out:
        new["stdout"], o_notes, o_n = t_out
        notes += o_notes
    if t_err:
        new["stderr"], e_notes, e_n = t_err
        notes += e_notes
    total = len(stdout.splitlines()) + len(stderr.splitlines())
    footer = ("\n[Claude Lean] %s output trimmed from %d lines to errors, warnings, summary and the last %d lines%s. "
              "Full output: %s (grep it or Read it with offset/limit if you need more)."
              % (kind, total, TAIL_LINES, ("; " + ", ".join(notes)) if notes else "", log))
    new["stdout"] = (new.get("stdout") or "") + footer
    before = len(stdout) + len(stderr)
    after = len(new.get("stdout") or "") + len(new.get("stderr") or "")
    st["saved_chars"] += max(0, before - after)
    _stat("trim", tool="Bash", kind=kind, before=before, after=after)
    return {"hookSpecificOutput": {"hookEventName": "PostToolUse", "updatedToolOutput": new}}


def trim_persisted(resp, kind, st):
    """Claude Code saved a huge output to disk and shows Claude its FIRST ~2 KB.
    For a build log the first 2 KB is progress noise - swap the preview for the
    errors and the verdict at the end. The full output stays where it was saved."""
    path = resp["persistedOutputPath"]
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            text = f.read(20 * 1024 * 1024)
    except OSError:
        return None
    t = trim_text(text, COMPACT, force=True)
    if not t:
        return None
    body, notes, n = t
    new = dict(resp)
    new["stdout"] = ("[Claude Lean] %s output (%d lines) - errors and the last %d lines; full output saved at %s%s:\n%s"
                     % (kind, n, COMPACT["tail"], path, (" (" + ", ".join(notes) + ")") if notes else "", body))
    _stat("trim", tool="Bash", kind=kind + "-persisted", before=len(text), after=len(new["stdout"]))
    return {"hookSpecificOutput": {"hookEventName": "PostToolUse", "updatedToolOutput": new}}


def trim_job_logs(inp, st):
    """GitHub job logs: drop the per-line timestamps (~28 chars of every line).

    Deliberately NOT error-filtered: in this repo job logs are often the answer
    itself (ref-189 javap/SRG lookups), so only meaning-free bytes go."""
    resp = inp.get("tool_response")
    changed = [0, 0]

    def fix(s):
        if not isinstance(s, str) or len(s) < PASS_CHARS:
            return s
        try:
            obj = json.loads(s)
        except ValueError:
            obj = None
        if isinstance(obj, dict) and isinstance(obj.get("logs_content"), str):
            raw = obj["logs_content"]
            obj["logs_content"] = ANSI.sub("", GH_TIMESTAMP.sub("", raw))
            changed[0] += len(raw)
            changed[1] += len(obj["logs_content"])
            return json.dumps(obj, ensure_ascii=False)
        new = ANSI.sub("", GH_TIMESTAMP.sub("", s))
        changed[0] += len(s)
        changed[1] += len(new)
        return new

    if isinstance(resp, str):
        new = fix(resp)
    elif isinstance(resp, list):
        new = [dict(c, text=fix(c["text"])) if isinstance(c, dict) and isinstance(c.get("text"), str) else c
               for c in resp]
    elif isinstance(resp, dict) and isinstance(resp.get("content"), list):
        new = dict(resp, content=[dict(c, text=fix(c["text"])) if isinstance(c, dict) and isinstance(c.get("text"), str)
                                  else c for c in resp["content"]])
    else:
        return None
    if changed[0] - changed[1] < 1000:
        return None
    st["saved_chars"] += changed[0] - changed[1]
    _stat("trim", tool=inp.get("tool_name"), kind="ci-timestamps", before=changed[0], after=changed[1])
    return {"hookSpecificOutput": {"hookEventName": "PostToolUse", "updatedMCPToolOutput": new}}


# --- read repeat guard ------------------------------------------------------
def _read_key(inp):
    ti = inp.get("tool_input") or {}
    path = ti.get("file_path")
    if not path:
        return None, None
    path = os.path.normcase(os.path.abspath(path))
    agent = inp.get("agent_id") or "main"   # a subagent has its own context: never share its reads
    key = "|".join(str(x) for x in (agent, path, ti.get("offset"), ti.get("limit"), ti.get("pages")))
    return key, path


def pre_read(inp, st):
    key, path = _read_key(inp)
    if not key:
        return None
    limit = (inp.get("tool_input") or {}).get("limit")
    if isinstance(limit, int) and limit < GUARD_MIN_LINES:   # a short slice is cheaper than a round trip
        return None
    seen = st["reads"].get(key)
    if not seen:
        return None
    try:
        s = os.stat(path)
    except OSError:
        return None
    unchanged = seen["mtime"] == s.st_mtime and seen["size"] == s.st_size and seen["epoch"] == st["epoch"]
    if not unchanged or s.st_size < GUARD_MIN_BYTES:
        return None
    if st.get("last_denied") == key:     # escape hatch: an immediate identical retry always goes through
        st["last_denied"] = None
        return None
    st["last_denied"] = key
    st["blocked_reads"] += 1
    _stat("read_blocked", path=_rel(path), bytes=s.st_size)
    ago = st["calls"] - seen.get("call", st["calls"])
    return {"hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": "deny",
        "permissionDecisionReason": (
            "[Claude Lean] You already read %s with these same lines (%s) and the file hasn't changed since, "
            "so it is still in your context above - use that copy. If it is NOT in your context (for example "
            "it was summarized away), repeat this exact Read call and it will go through."
            % (_rel(path), "%d tool calls ago" % ago if ago > 0 else "just before this"))}}


def note_read(inp, st):
    key, path = _read_key(inp)
    if not key:
        return
    try:
        s = os.stat(path)
    except OSError:
        return
    st["reads"][key] = {"mtime": s.st_mtime, "size": s.st_size, "epoch": st["epoch"], "call": st["calls"]}
    if len(st["reads"]) > 400:          # bound the state file
        for k in sorted(st["reads"], key=lambda k: st["reads"][k]["call"])[:100]:
            del st["reads"][k]


def note_edit(inp, st):
    path = (inp.get("tool_input") or {}).get("file_path") or (inp.get("tool_input") or {}).get("notebook_path")
    if not path:
        return
    rel = _rel(os.path.abspath(path))
    if rel in st["edited"]:
        st["edited"].remove(rel)
    st["edited"] = (st["edited"] + [rel])[-60:]


# --- compaction state card ----------------------------------------------------
def _git(*args):
    try:
        r = subprocess.run(["git", "-C", ROOT] + list(args), capture_output=True, text=True, timeout=4)
        return r.stdout.strip() if r.returncode == 0 else ""
    except (OSError, subprocess.SubprocessError):
        return ""


def state_card(st):
    branch = _git("rev-parse", "--abbrev-ref", "HEAD") or "?"
    status = _git("status", "--short", "--branch").splitlines()
    commits = _git("log", "--oneline", "-5")
    parts = ["## Claude Lean state card (re-added automatically after compaction)",
             "Facts read from disk just now - trust these over the summary if they disagree.",
             "", "Branch: %s" % branch]
    if status:
        shown = status[:31]
        parts += ["Git status:"] + ["  " + l for l in shown]
        if len(status) > len(shown):
            parts.append("  ... %d more" % (len(status) - len(shown)))
    if commits:
        parts += ["Recent commits:"] + ["  " + l for l in commits.splitlines()]
    if st["edited"]:
        parts += ["Files changed this session (newest last):"] + ["  " + p for p in st["edited"][-30:]]
    if st["prompts"]:
        parts += ["The user's latest requests, verbatim (newest last):"]
        parts += ["  > " + p.replace("\n", "\n    ") for p in st["prompts"]]
    parts += ["", "Re-read a file only when you need its exact current text; trimmed command output is kept "
              "in full under .claude/lean/logs/."]
    return "\n".join(parts)


# --- statusline -------------------------------------------------------------
def statusline(inp):
    cw = inp.get("context_window") or {}
    cu = cw.get("current_usage") or {}
    ctx = sum(int(cu.get(k) or 0) for k in ("input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens"))
    bits = []
    if ctx:
        bits.append("ctx %dk" % round(ctx / 1000))
    pct = cw.get("used_percentage")
    if isinstance(pct, (int, float)):
        bits[-1:] = [(bits[-1] + " (%d%%)" % pct) if bits else "ctx %d%%" % pct]
    rl = inp.get("rate_limits") or {}
    for label, k in (("5h", "five_hour"), ("7d", "seven_day")):
        v = (rl.get(k) or {}).get("used_percentage")
        if isinstance(v, (int, float)):
            bits.append("%s %d%%" % (label, v))
    st = load_state(inp.get("session_id"))
    lean = []
    if st["saved_chars"]:
        lean.append("~%dk tok trimmed" % max(1, round(st["saved_chars"] / 4000)))
    if st["blocked_reads"]:
        lean.append("%d re-reads skipped" % st["blocked_reads"])
    bits.append("lean " + (", ".join(lean) if lean else "on"))
    sys.stdout.write(" | ".join(bits))


# --- entry ------------------------------------------------------------------
def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    raw = sys.stdin.buffer.read().decode("utf-8", "replace")
    inp = json.loads(raw) if raw.strip() else {}
    if cmd == "statusline":
        return statusline(inp)
    sid = inp.get("session_id")
    key = sid
    st = load_state(key)
    out = None
    tool = inp.get("tool_name") or ""
    if cmd == "pre-tool":
        if tool == "Read":
            out = pre_read(inp, st)
    elif cmd == "post-tool":
        st["calls"] += 1
        if tool == "Read":
            note_read(inp, st)
        elif tool in ("Edit", "Write", "MultiEdit", "NotebookEdit"):
            note_edit(inp, st)
        elif tool == "Bash":
            out = trim_bash(inp, st)
        elif tool.startswith("mcp__") and tool.endswith("get_job_logs"):
            out = trim_job_logs(inp, st)
    elif cmd == "prompt":
        p = (inp.get("prompt") or "").strip()
        if p and not p.startswith("<"):   # skip harness-injected envelopes
            st["prompts"] = (st["prompts"] + [p[:600] + (" ..." if len(p) > 600 else "")])[-3:]
    elif cmd == "session-start":
        if inp.get("source") == "compact":
            st["epoch"] += 1
            st["last_denied"] = None
            _stat("compact", session=sid)
            out = {"hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": state_card(st)}}
    save_state(key, st)
    if out:
        reply(out)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # fail open, but leave a trace for debugging
        try:
            os.makedirs(LEAN_DIR, exist_ok=True)
            with open(os.path.join(LEAN_DIR, "errors.log"), "a", encoding="utf-8") as f:
                f.write("%s %s %r\n" % (_now(), sys.argv[1:], e))
        except OSError:
            pass
    sys.exit(0)

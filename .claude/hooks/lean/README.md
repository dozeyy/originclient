# Claude Lean

Automatic, deterministic token savings for Claude Code in this repo. No extra
model calls, no network, standard-library Python. Runs anywhere this repo's
`.claude/settings.json` is loaded: Claude Code on Will's Windows PC (Git Bash)
and cloud sessions on GitHub. (A plugin would NOT run in cloud sessions — that's
why this lives in the repo.)

## Why it's shaped like this
Measured on this repo's transcripts (2026-10-17, `python tools/lean/report.py`):
~99% of input tokens are **cache reads** — the whole conversation re-read on
every call. Cost therefore grows with *context size × number of calls*. Writing
code (output) is a small share. So the levers are: keep big text out of the
conversation, and don't let the conversation grow huge.

## What it does
| Piece | Where | What |
|---|---|---|
| Compact earlier | `autoCompactWindow: 300000` in `.claude/settings.json` | Auto-compaction at 300k tokens instead of ~967k on 1M-context models. Replaying this repo's longest session: ~-35% on the cost proxy (a model, not a measurement). |
| State card | `lean.py session-start` | After every compaction, re-adds branch, git status, recent commits, files changed this session and the user's last 3 requests — straight from disk, so nothing important depends on the summary. |
| Build/test trimming | `lean.py post-tool` (Bash) | gradle / dotnet / npm / pytest / pip / cargo / maven / go output over 120 lines or 8 KB is cut to errors (+context), unique warnings, summaries and the last 40 lines. The full output is saved to `.claude/lean/logs/` and the path is printed. When Claude Code already saved a huge output to disk (it then shows only the first 2 KB), the preview is swapped for the errors + verdict. |
| CI log timestamps | `lean.py post-tool` (GitHub `get_job_logs`) | Drops the per-line timestamp (~28 chars/line). Not error-filtered: job logs here are often the answer itself (ref-189 lookups). |
| Repeat-read guard | `lean.py pre-tool` (Read) | Denies once a re-read of an unchanged file (>=16 KB, same lines) that is still in context. Repeating the identical call always goes through (escape hatch). Resets at compaction; subagents are tracked separately. |
| Meter | `lean.py statusline` | `ctx 102k (34%) | 5h 41% | 7d 12% | lean ~38k tok trimmed, 2 re-reads skipped` (terminal only). |
| Screenshot sheets | `tools/lean/contact_sheet.py`, run by `dev-shots.yml` | Every dev-shots folder gets `_sheet.jpg`: each screen once (smallest resolution), labelled, 1568 px wide. 33 PNGs -> 2 sheets (~4k image tokens instead of ~50-100k). |
| Lean agents | `.claude/agents/` | Will's `claude-config` agents that fit Origin (13 of 37), same opus/sonnet/haiku split, no preloaded skills, Origin contract: grep MEMORY.md, CI checks <= every 3 min, sheet-first screenshots, <= ~300-word reports. Same names as the global ones, so in Origin they take precedence. |
| Unused plugins off | `enabledPlugins` in `.claude/settings.json` | nimble, product-management, productivity, design off for this project (their skill lists + MCP servers rode along on every call). |
| Lean instructions | `CLAUDE.md` + `.claude/rules/` | CLAUDE.md cut from 28 KB to ~14 KB; mod/launcher detail moved into path-scoped rules that load only when those files are touched; `# Compact instructions` section. |

## Turning it off
- Everything except compaction: set `"CLAUDE_LEAN": "0"` in `.claude/settings.json` `env`
  (or for just your machine, in `.claude/settings.local.json`).
- Compaction size: delete the `autoCompactWindow` line (back to the default).
- Remove entirely: delete the `hooks`, `statusLine`, `autoCompactWindow` entries.

## Safety rules (keep them when editing lean.py)
- Fail open: any error -> exit 0, no output (Claude Code acts as if Lean weren't there).
  Errors are appended to `.claude/lean/errors.log`.
- Never exit 2. Only print the hook's JSON reply.
- Never lose information: anything trimmed is saved in full first.
- `run.sh` finds `python3`/`python`/`py` (skipping the Microsoft Store stub); no Python = no-op.

## Checking it
- `python tools/lean/test_lean.py` — hook tests (runs in a temp folder).
- `python tools/lean/report.py` — per-session tokens: calls, cache read/write, output,
  median/peak context, cost proxy, plus what Lean trimmed/skipped on this machine.
- `python tools/lean/report.py --compare <sessionA> <sessionB>` — A/B two sessions.

Runtime data (`.claude/lean/`: state, logs, stats) is gitignored and per machine.

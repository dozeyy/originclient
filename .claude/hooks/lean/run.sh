#!/usr/bin/env bash
# Claude Lean launcher (see .claude/hooks/lean/README.md).
# Finds a real Python 3 and runs lean.py with the hook's stdin. Fails OPEN: no
# Python, or CLAUDE_LEAN=0, means it exits 0 silently and Claude Code carries on
# exactly as if the hook didn't exist. Never exit 2 from here (that blocks tools).
[ "${CLAUDE_LEAN:-1}" = "0" ] && exit 0
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for py in python3 python py; do
  p="$(command -v "$py" 2>/dev/null)" || continue
  case "$p" in *WindowsApps*) continue ;; esac   # the Microsoft Store stub, not a real Python
  exec "$p" "$here/lean.py" "$@"
done
exit 0

#!/usr/bin/env bash
# Publish a dev-shots run's screenshots to the `claude/dev-shots` branch.
# Used by .github/workflows/dev-shots.yml (publish job); tested by test_ci_gates.py.
#
# Why this is not a plain `git push` of everything (run 39303597442, 39325641289):
#   * A run of every module is ~20 x 110 MB = ~2.2 GB of PNGs. Pushed as ONE commit it
#     died after ~5 min with "HTTP 500 / curl 22 / hung up" on all attempts, then hit
#     the job timeout. Now each module folder is its own push (~110 MB).
#   * The branch used to keep every old commit, so the repo only ever grew (10.7 GB,
#     614 folders). Now each publish is a fresh ROOT commit (no history) that holds the
#     branch's current tree plus this run's folders: the shots stay browsable, the old
#     history can be garbage-collected. The push is a force-push guarded by
#     --force-with-lease on the tip we started from, so a concurrent publisher (another
#     run, launcher-shots) is never overwritten: a lost lease means re-clone and redo.
#   * The clone is partial (trees only) + sparse (only this run's folders), depth 1.
#
# Env (all required unless noted):
#   URL      remote url (with token)          PUB     dir holding shots-<module>/ folders
#   SUFFIX   folder suffix ("" / "-world" / "-world-label")
#   SET, SHA, RUN_URL   text for the commit message and SOURCE.txt
#   WORK     scratch dir (default $RUNNER_TEMP or /tmp)
#   ATTEMPTS (default 6)   SLEEP_SCALE seconds (default 10; tests use 0)
#   BRANCH   (default claude/dev-shots)
set -u
BRANCH="${BRANCH:-claude/dev-shots}"
ATTEMPTS="${ATTEMPTS:-6}"
SLEEP_SCALE="${SLEEP_SCALE:-10}"
WORK="${WORK:-${RUNNER_TEMP:-/tmp}}"
: "${URL:?}" "${PUB:?}" "${SET:=design}" "${SHA:=unknown}" "${RUN_URL:=unknown}"
SUFFIX="${SUFFIX:-}"

folders=""
for d in "$PUB"/shots-*; do
  [ -d "$d" ] || continue
  m="$(basename "$d")"; folders="$folders ${m#shots-}$SUFFIX"
done
[ -n "$folders" ] || { echo "no shots to publish"; exit 0; }

allowed="$(echo $folders | sed 's/[.]/[.]/g; s/ /|/g')"
git config --global user.name "origin-dev-shots"
git config --global user.email "41898282+github-actions[bot]@users.noreply.github.com"

backoff() { sleep $(( $1 * SLEEP_SCALE + (SLEEP_SCALE > 0 ? RANDOM % SLEEP_SCALE : 0) )); }

for attempt in $(seq 1 "$ATTEMPTS"); do
  site="$WORK/site"; cd "$WORK" && rm -rf "$site"
  tip=""
  if timeout 120 git ls-remote --exit-code --heads "$URL" "$BRANCH" >/dev/null 2>&1; then
    if ! timeout 300 git clone -q --depth 1 --filter=blob:none --no-checkout --branch "$BRANCH" "$URL" "$site"; then
      echo "attempt $attempt: clone failed"; backoff "$attempt"; continue
    fi
    cd "$site" || exit 1
    # Patterns go in on stdin (argv would be path-mangled by MSYS bash on Windows).
    if ! { echo /SOURCE.txt; for m in $folders; do echo "/$m/"; done; } | git sparse-checkout set --no-cone --stdin \
       || ! timeout 300 git checkout -q "$BRANCH"; then
      echo "attempt $attempt: checkout failed"; backoff "$attempt"; continue
    fi
    tip="$(git rev-parse HEAD)"
  else
    mkdir -p "$site" && cd "$site" && git init -q && git checkout -q -b "$BRANCH"
  fi

  parent=""; expect="$tip"; ok=1
  for m in $folders; do
    rm -rf "$m"; cp -r "$PUB/shots-${m%"$SUFFIX"}" "$m"
    printf 'source: %s\nrun: %s\n' "$SHA" "$RUN_URL" > "$m/SOURCE.txt"
    printf 'last run: %s (%s)\n' "$RUN_URL" "$SHA" > SOURCE.txt
    git add -A
    # Safety net: a sparse checkout that came up empty would stage the whole branch as
    # deleted. Only deletions inside THIS run's folders are allowed.
    stray="$(git diff --cached --name-only --diff-filter=D | grep -vE "^($allowed)/" | head -n 3 || true)"
    if [ -n "$stray" ]; then
      echo "::error::publish would delete files outside this run's folders (e.g. $stray); refusing"; exit 1
    fi
    tree="$(git write-tree)"
    # A "published" that staged nothing (e.g. the folder fell outside the sparse patterns)
    # would be a silent no-op; the new folder's SOURCE.txt must be in the tree.
    git cat-file -e "$tree:$m/SOURCE.txt" 2>/dev/null || { echo "::error::$m/ was not staged; refusing to push an empty publish"; exit 1; }
    if [ -n "$parent" ]; then commit="$(git commit-tree "$tree" -p "$parent" -m "dev shots ($SET) $m for ${SHA:0:7}")"
    else commit="$(git commit-tree "$tree" -m "dev shots ($SET) for ${SHA:0:7} (history squashed)")"; fi
    if out="$(timeout 600 git push -q --force-with-lease="refs/heads/$BRANCH:$expect" "$URL" "$commit:refs/heads/$BRANCH" 2>&1)"; then
      parent="$commit"; expect="$commit"; echo "pushed $m (attempt $attempt)"
    else
      echo "attempt $attempt: push of $m failed: $(printf '%s' "$out" | tail -n 3)"; ok=0; break
    fi
  done
  if [ "$ok" = 1 ]; then echo "published:$folders (attempt $attempt)"; exit 0; fi
  backoff "$attempt"
done
echo "::error::could not publish to $BRANCH after $ATTEMPTS attempts (the shots are still in this run's artifacts)"
exit 1

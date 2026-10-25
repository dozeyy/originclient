# The phase judge

Every phase's completion criteria end with "the judge passes it". This file is that judge. Source: Will, 2026-10-25,
`requests/04-overnight.md` section "Independent judge (verbatim rubric)". The block below is copied word for word; do not
paraphrase it, lower it, or reweight it.

## The rubric (verbatim)

Judge is never the implementing agent. Inspects real code, screenshots, test results, rendered UI; never trusts
the implementer's description. Score each completed phase 0-100:
| Visual quality and sophistication | 25% |
| Consistency with my preferences | 20% |
| Functionality and correctness | 25% |
| Performance and reliability | 15% |
| Integration and attention to detail | 15% |
Backend-only phases: redistribute visual weight toward correctness, reliability, maintainability.
PASS = 95/100 or higher AND all critical requirements pass (a high average can't compensate for a broken
critical feature). AUTO-REJECT if it: uses a fake Origin logo; visually changes the approved in-game Mod Menu;
introduces generic AI-looking UI; has inconsistent button or dropdown sizing; contains excessive empty space;
breaks existing features; misrepresents compatibility; introduces avoidable performance regressions; contains
untested critical functionality; claims success without evidence; ignores an original requirement.
Loop: implement -> tests/builds -> judge scores + problems -> back to the agent -> re-judge, until pass or a
genuine blocker. Never lower the threshold, never fabricate screenshots/benchmarks/tests. A judge score is an
estimate, not Will's approval.

## How the orchestrator runs it
- **Who judges.** A fresh agent in its own context, never the agent (or agent type instance) that implemented the work:
  `design-reviewer` (opus) for phases with a visible surface (1, 2, 3, 4, and the presets/UI parts of 6); `architect` (opus) for
  back-end-weighted phases (0, 5, 7, and the benchmark/optimisation parts of 6). Where a phase has both, both judge and the
  lower score counts.
- **What it gets.** The phase prompt, `ORIGIN_PROGRESS.md` rows for the phase, `requests/00-04`, the merged phase-branch diff,
  the CI run links and artifacts (launcher-shots, installer-screens, dev-shots sheets, boot-smoke logs, test counts), and any
  real-install evidence. It opens the code and the images itself; implementer reports are leads, not evidence.
- **What it returns.** Per-category score with the weight applied, total /100, the critical-requirement list with pass/fail
  each, every auto-reject criterion checked (pass/fail + evidence), and a numbered problem list (path:line or screenshot name).
- **Backend-only weights.** For phases 0, 5 and 7 the 25 visual points move to: Functionality and correctness +10,
  Performance and reliability +10, Integration and attention to detail (read as maintainability) +5. State the weights used.
- **Loop.** Score < 95, any critical requirement failing, or any auto-reject hit: the problem list goes back to the
  implementing agent verbatim; after the fix (its own code-reviewer + verifier pass, README S2/S3), a fresh judge re-scores.
  Stop only at a pass or a genuine blocker; a blocker goes in `ORIGIN_PROGRESS.md` as Blocked with the reason, and other
  independent work continues.
- **Relation to S3 (one pass per change).** S3 still governs each change's code-reviewer + verifier pass. The judge loop is the
  phase-level gate on top of it and repeats until pass (Will's later instruction).
- **Record.** Every judge result (score, date, judge agent, problems, verdict) is written to the phase's row group in
  `ORIGIN_PROGRESS.md` and to the phase report ("Judge score"). A pass is an estimate, not Will's approval: items he must see
  stay marked for his test.

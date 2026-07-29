# 0006 — Agent scaffolding: small, declarative, and grown from incidents

**Status:** accepted · 2026-07-29

## The question

This repository is developed largely by an autonomous coding agent. How much
instruction, and in what form?

## The decision

**Declarative invariants in a short `CLAUDE.md`; enforcement in hooks;
knowledge in path-triggered rules and on-demand skills; nothing added in
anticipation.**

## Why

Two constraints pull against each other: the agent needs enough context to act
well, and instructions that over-specify *procedure* measurably degrade the
result.

The current evidence on the second point:

- Declarative interfaces (state the goal, not the steps) beat imperative ones
  for agent task success — one 2026 study reports +67% success and −43.5%
  interaction steps from that change alone.
- Constrained decoding and rigid step structures reduce functional correctness;
  fixed-granularity action sequences underperform policy-chosen ones because
  they overshoot.
- Static budgets — same procedure regardless of task difficulty — waste effort
  on easy work and starve hard work.
- Claude specifically tends to **over**-prompt (~45% in one comparison), so a
  long instruction file compounds rather than corrects the tendency.

And on placement, the current Claude Code guidance is a clean decision rule:
**must-happen → hook; contextual knowledge → skill; delegation boundary →
subagent; always-on guidance → keep it short in CLAUDE.md.** Instructions in
CLAUDE.md are requests; hooks are guarantees.

So: `CLAUDE.md` states *what must be true when a change is done* and leaves the
route to the agent. Anything that must happen regardless is a hook.

## What loads when

| Trigger | What arrives | Why there |
|---|---|---|
| every turn | `.claude/CLAUDE.md` (~100 lines) | invariants only — this is the file that gets read most and edited least |
| session start | `doc/status.md` + last 5 commits, with a staleness warning | the agent cannot choose work without knowing where things stand |
| editing `doc/design/**` | `.claude/rules/design_notes.md` | the format matters exactly when writing one |
| editing `bench/**` | `.claude/rules/measurement.md` | measurement discipline matters exactly when measuring |
| on demand / when the model judges it relevant | `.claude/skills/wat/` | detailed toolchain invocations; too long for always-on, needed only in the WAT loop |
| before `git push` | `bb check` runs; a red gate blocks the push | the one thing that must not depend on remembering |

`measurement.md` points at the `wat` skill, so the deterministic path trigger
surfaces the on-demand one. Skill auto-triggering is a model judgement; the
path rule is not.

## What we deliberately did not build

The sibling ClojureWasm project carries a 634-line `CLAUDE.md`, 32 rules, and 9
pre-commit hooks. Every one of those was bought with an incident, and it works
for a codebase with years of history. Copying it into an empty repository would
be cargo cult: rules that have never fired cost context on every turn and
mislead about which failures are real here.

Also not built, and named here so their absence is a decision rather than an
oversight:

- **A fixed Step 0–7 loop.** Precisely the rigid-procedure shape the evidence
  above argues against.
- **A two-tier test gate.** `bb check` takes 0.1s. It prints its own wall time,
  so when it drifts past ~60s that shows up on every run without anyone
  remembering to check.
- **A pre-commit gate.** The gate runs at `git push` instead: commits stay
  cheap, and nothing unverified leaves the machine.
- **A private scratch directory.** Everything load-bearing is committed and
  readable by anyone who clones. A file only I can open is not evidence.

## The rule for adding more

**A specific failure has to have happened twice.** Then the fix goes in the
cheapest layer that prevents it — hook first, rule second, prose last — and the
incident is named in the file it produced, so a later reader can tell whether
the rule still earns its place.

## What would falsify this

If the agent repeatedly fails in a way a rule would have prevented, the
minimalism is wrong and this note gets amended with the incident. That is the
expected way this file changes.

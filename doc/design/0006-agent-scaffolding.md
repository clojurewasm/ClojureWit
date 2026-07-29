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
| every turn | `.claude/CLAUDE.md` (~120 lines) | invariants only — this is the file that gets read most and edited least |
| session start | `doc/status.md` + last 5 commits, with a staleness warning | the agent cannot choose work without knowing where things stand |
| editing `doc/design/**` | `.claude/rules/design_notes.md` | the format matters exactly when writing one |
| editing `bench/**` | `.claude/rules/measurement.md` | measurement discipline matters exactly when measuring |
| `/next`, and every session start (the hook says to run it) | `.claude/skills/next/` | the loop: orient, choose, work, review, land, repeat — plus the failure modes this project actually has |
| on demand / when the model judges it relevant | `.claude/skills/wat/` | detailed toolchain invocations; too long for always-on, needed only in the WAT loop |
| entering a stage, or before a design note rests on an external fact | `.claude/skills/survey/` | how far to take "is this still true" — added 2026-07-29, see below |
| before `git push` | `bb check` runs; a red gate blocks the push | the one thing that must not depend on remembering |

Skill auto-triggering is a model judgement, so every skill has a deterministic
path to it as well: the session-start hook prints "run /next", and
`measurement.md` (path-triggered on `bench/**`) points at the `wat` skill. A
skill that can only be reached by the model deciding it is relevant will
sometimes not be reached.

`/next` is deliberately the *only* entry point. One command that always applies
beats several that each apply sometimes, because choosing between them is
itself a decision the model can get wrong at the worst moment — a cold start.

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

## The rule for adding more — and for taking it away

**A specific failure has to have happened twice.** Then the fix goes in the
cheapest layer that prevents it — hook first, rule second, prose last — and the
incident is named in the file it produced, so a later reader can tell whether
the rule still earns its place.

The symmetric half, which the first version of this note left implicit and
`.claude/CLAUDE.md` now states outright: **a rule is removed when its failure
can no longer happen, or when it has never fired.** Minimalism that only ever
ratchets upward is not minimalism, it is a slower version of the 634-line
`CLAUDE.md` this note was written to avoid. The trigger for reviewing is an
incident being recorded, not a calendar.

## Amendment, 2026-07-29 — the `survey` skill, added on one incident

**There is one incident of this shape, not two, and the skill was added
anyway.** Saying so plainly, because the first draft of this amendment reached
"two" by widening the category until an unrelated benchmark error fit — which
is the exact ratchet mechanism this note exists to resist, and it was caught in
review rather than by the rule.

The incident: **S0 was entered without anyone asking whether a WasmGC module
could be a component at all.** It can, but not through any path a runtime
executes today, so the boundary is linear memory — which changes the size of S4
(`doc/design/0007-*`). The material saying so was public the whole time.

Why not wait for the second occurrence, as the rule says:

- The rule's purpose is to stop rules being added for failures that will not
  recur. This one recurs **once per stage entry**, by construction — S1, S2, S3
  and S4 each rest on external facts nobody has checked yet.
- Its cost is a whole stage, not a rerun. B1 cost an afternoon and was caught
  by its own benchmark; this class is caught by nothing already here.

That is an argument for an exception, not a reinterpretation of the rule. The
rule stands as written.

What went in, and where the review pushed back:

- **A skill**, because surveying is judgement-laden and needed at stage
  boundaries only — always-on procedure is what this note argues against.
- **`/next` points at it**, so it is not reachable only by the model deciding
  it is relevant. An earlier draft made it *unconditional* on stage entry; that
  is the static budget this note rejects at "Why", and it was reverted to
  naming the minimum and leaving the depth to judgement.
- **`.claude/CLAUDE.md` gained two standing constraints** — check the outside
  world; one experiment characterises one variant — and its add-machinery
  paragraph became add/remove/reduce in place. The first draft instead added a
  whole section that restated the add-rule six lines below the existing one,
  which is the opposite of the Reduce it was arguing for. The second constraint
  let `.claude/rules/measurement.md` shed its bench-scoped duplicate, so the
  net change to always-on text is small.

## What would falsify this

If the agent repeatedly fails in a way a rule would have prevented, the
minimalism is wrong and this note gets amended with the incident. That is the
expected way this file changes. It has happened **once** so far — the
amendment above — which is not yet "repeatedly", and the exception is argued
there rather than counted.

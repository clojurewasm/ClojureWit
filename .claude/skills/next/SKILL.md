---
name: next
description: Pick up work on ClojureWit and keep going. Use this at the start of every session and after every landed change — including when the user says "続けて", "continue", "next", "resume", "go", "pick up where we left off", or just starts a session expecting you to know what to do. Also use it whenever you are unsure what to work on, because guessing without orienting is how this project's time gets wasted. This is the single entry point; everything else is reached from here.
---

# Continue work on ClojureWit

One command drives the loop. Run it, do a unit of work, land it, run it again.
`.claude/CLAUDE.md` says what "done" means; this says how to get from a cold
start to a landed change without wasting a turn.

## 1. Orient

Three things must be true before you choose work. The session-start hook
already printed the first.

- **`doc/status.md` is current.** If the hook warned that it is several commits
  behind, read it as history and reconstruct the present from `git log` before
  trusting it.
- **You know which stage we are in.** `doc/roadmap.md` has S0…S4 in order, each
  with a stop condition. Working ahead of the current stage is the single most
  likely way to waste effort here — S0 exists precisely because the design
  after it might change.
- **Any external fact you are about to rely on is still true.** This project
  sits on a fast-moving spec surface. Design notes carry dates; **if you are
  about to act on a dated external claim more than ~3 months old — a Wasm
  proposal's phase, a runtime's feature support, a tool's version — run
  `/survey` before building on it.** Correct it in place if it moved.
- **Entering a stage, survey what the stage stands on.** At minimum, run the
  thing that would fail if the stage's premise were wrong — how much further to
  go is yours to judge, and `/survey` says how to judge it. S0 was entered
  without anyone asking whether the module could be a component at all, and the
  answer changed the size of S4 (`doc/design/0007-*`).

## 2. Choose

Take what `doc/status.md`'s **Next** section names. If it names nothing, or
what it names is done, pick the smallest thing that moves the current stage and
say so in one line before starting.

When two options look equally good, prefer the one that **produces a
measurement or a falsifiable claim** over the one that produces more code.

## 3. Work

For anything with observable behaviour, write the check first — a failing test,
or a benchmark with the number you expect. This project's whole discipline is
that claims are checkable:

- **Semantics** → the oracle is `clojure` itself. A behaviour claim means: the
  same program, run both ways, compared. Not an assertion of what should happen.
- **Performance** → `bb bench-s0` and `.claude/rules/measurement.md`. The
  prediction is written down *before* the run.
- **Structure** → an ordinary unit test.

Need to consult Clojure's or a sibling project's source? `bb ref` clones them
into `.ref/` (gitignored). `bb ref clojure` for one. Never assume a path on
someone's machine.

## 4. Before you land it

Re-read the diff once, looking for these specifically. They are the failures
this project is actually prone to — not a generic checklist.

- **Am I building machinery for a failure that has not happened?** A new rule,
  hook, or abstraction needs an incident behind it (`doc/design/0006`).
- **Am I acting on an intuition I could have measured?** That has already
  happened once and cost a whole plan (`doc/design/0002`).
- **Did I state something as settled that is actually unverified?** Say
  `proposed`, and say what would falsify it.
- **Did I reference anything a stranger with a fresh clone cannot open?** Local
  paths, private notes, unlinked claims.
- **Did the design change without the design note changing?** If a prediction
  failed, amend the note that made it and name the prediction.

**When a decision closes off alternatives, get an independent critique before
committing to it.** Fork a subagent with fresh context and brief it to argue
*against* the decision — give it the constraints and ask for the strongest case
for a different choice. Its job is to be adversarial, not agreeable. Fold what
survives into the design note's "Alternatives rejected". A decision that has
never been argued against is not a decision, it is a default.

Then: `bb check` green → commit → push. The push hook re-runs the gate, so a
red gate cannot escape.

## 5. Keep going

Land it, update `doc/status.md` if the answer changed, and **start the next
unit immediately**. Task boundaries, design decisions, failed tests, and
"what should I do next" are all handled in flight. The only thing that ends a
session is the user saying so.

## Gotchas

- **The most expensive mistake here is working on the compiler before S0.**
  It feels productive and the design under it may not survive measurement.
- **`bb check` passing locally does not mean it passes on a clone.** It has
  already failed exactly that way once — empty directories git never tracked.
  If a change touches build or tooling, sanity-check it against a tree that
  does not have your local state.
- **Do not add a rule because a sibling project has one.** Their rules encode
  their incidents. `doc/design/0006` explains why copying them is a net loss.
- **Web search is not optional on spec facts, and is not sufficient either.**
  Wasm proposal phases, engine support matrices, and WASI versions all moved
  within the last year, and a confident wrong version number propagates into
  design decisions silently. Negative claims ("X does not support Y") rot
  fastest. `/survey` says how far to take it — the last move is always to build
  the smallest thing that would fail if the claim were false.
- **`.ref/` is a read-only lens.** Never copy code out of it; re-derive.
  Different projects, different constraints — that is the point of reading them.
- **If you cannot say what would falsify a claim, you are not ready to write it
  down as a decision.**

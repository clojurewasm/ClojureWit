# ClojureWit

Clojure ⇄ WebAssembly, joined at the WIT interface. Two deliverables:
`cljwit.host` (call Wasm components from JVM Clojure) and `cljwit` (compile
Clojure to a Wasm component). See `README.md` for the pitch,
`doc/roadmap.md` for what happens next, `doc/status.md` for where we are.

**Pre-alpha.** There is no compiler yet, and the first milestone is a set of
benchmarks that decide whether the design is viable at all. Acting as if the
project were further along than `doc/status.md` says is the main way to waste
time here.

**`/next` is the entry point.** It takes you from a cold start to a landed
change; run it at the start of a session and after every change.

## Durable state lives in three files

Everything that must survive a session is committed, public, and in one of:

| File | Holds | Changes |
|---|---|---|
| `doc/status.md` | Where we are, what's next, what's blocked | Often — whenever the answer changes |
| `doc/roadmap.md` | What we're trying to do and in what order | Rarely — when the plan actually changes |
| `doc/design/NNNN-*.md` | Decisions, with the reasoning and what was rejected | On each load-bearing decision |

Nothing load-bearing lives in chat, in a scratch directory, or in your head.
If it matters after this session, it is in one of those three.

## What "done" means for a change

A change is done when all of these hold. *How* you get there is yours to choose.

1. **`bb check` is green.** That is the gate — the same one CI runs.
2. **The claim is tested.** If you assert a behaviour, something executable
   checks it. If you assert a measurement, the command that produced it is
   recorded next to the number.
3. **A decision that closes off alternatives is written down** in
   `doc/design/`, with what you rejected and why. Decisions that only affect
   the change at hand belong in the commit message instead.
4. **`doc/status.md` matches reality** if the change moved us.
5. **It is committed and pushed.** Work that only exists locally does not exist.

## Standing constraints

- **Measure before you optimize the plan.** This project has already been wrong
  once by reasoning about which lever mattered instead of measuring it
  (`doc/design/0002-*`). Predictions go in writing *before* the benchmark runs,
  so being wrong is visible.
- **Prefer the ecosystem over reimplementation.** deps.edn, nREPL, clj-kondo,
  clojure-lsp, `clojure.test`, wasm-tools, binaryen, Wizer. Writing our own
  version of one of these needs a design note explaining why the existing one
  could not be used.
- **No private paths, no machine-specific assumptions.** Anything referenced in
  a committed file must be reachable by someone who just cloned the repo.
  Sibling projects are cited by URL, never by local path.
- **The outside world is checked, not remembered.** This project sits on a spec
  surface that moved twice in the last year. Every external claim a decision
  rests on carries a **date and a URL in the design note that uses it**, and is
  re-verified before it is built on again. `/survey` is how; `bb ref` clones the
  sources.
- **One experiment characterises one variant, not the domain.** If A and B
  differ in several ways, their difference names none of them; if a tool has
  one path and you ran it, you know about that path. Vary the suspected cause
  alone, and ask what a negative result was incapable of showing you. This has
  now cost this project three times in one day — twice in `bench/`, once in a
  design note that stated the opposite of what a second command proved
  (`doc/design/0007-*`).
- **Semantics are not negotiable for speed.** If a Clojure program observably
  behaves differently here than under `clojure`, that is a bug or a numbered,
  documented divergence — never an unremarked difference.

## What is mechanized (don't re-implement it in prose)

- `bb check` — the gate. `bb tasks` lists every sanctioned entry point.
- `bb ref` — clones the reference sources (Clojure, ClojureScript, the sibling
  projects) into `.ref/`. Never assume they exist on a path.
- Session start prints `doc/status.md`, recent commits, and points at `/next`.
- The gate reports its own wall time, so "it got slow" surfaces by itself.
- CI runs `bb check` and nothing else, so the local gate cannot drift from it.

**This scaffolding is a work product, revised like one — in all three
directions.** It is edited under the same standard of evidence as the code.

- **Add** machinery when a specific failure has happened twice, in the cheapest
  layer that prevents it — hook, then rule, then prose — naming the incident.
  Not in anticipation: the sibling projects carry dozens of rules each, every
  one paid for by an incident, and copying them here first would be cargo cult.
  An exception needs the argument written down (`doc/design/0006-*` has one).
- **Remove** when a rule's failure can no longer happen, or when it has never
  fired and nothing here would produce it. A wrong or unearned rule is more
  expensive than a missing one: it costs context on every turn and misleads
  about which risks are real.
- **Reduce** in preference to adding. Over-specified *procedure* measurably
  degrades agent results (`doc/design/0006-*`) — state what must be true and
  leave the route open.

Review it whenever an incident is recorded, not on a schedule.

## Working with the sibling projects

[ClojureWasm](https://github.com/clojurewasm/ClojureWasm) (Clojure runtime in
Zig) and [zwasm](https://github.com/clojurewasm/zwasm) solved adjacent problems and
are worth reading for **what went wrong** — their design notes and debt ledgers
are more useful than their code. Two cautions:

- Their conclusions are about *their* constraints (no JVM at runtime, own
  engine). ClojureWit's constraints are different: a JVM at build time, someone
  else's engine at run time. Inherited conclusions need re-deriving.
- Their scaffolding grew to fit codebases with years of history. Ours should fit
  ours.

## Autonomy

Work continues without asking for permission between units. Design decisions,
failed tests, refactors, and choosing what to do next are all handled in
flight — record the reasoning and keep going. The one thing that ends a session
is the user saying so.

Ask when the answer is genuinely outside the repo: a credential, a product
preference with no defensible default, or a change to what the project *is*.

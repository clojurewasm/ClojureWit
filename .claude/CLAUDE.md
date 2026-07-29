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

**Add machinery when a specific failure has happened twice — not in
anticipation.** The sibling projects carry dozens of rules each; every one of
them was paid for by an incident. Copying them here before the matching
incident would be cargo cult, and this repo is small enough that a wrong rule
is more expensive than a missing one.

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

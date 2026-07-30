# 0029 — The nREPL entry point: the compiler is the server, the engine is a child

**Status:** proposed · 2026-07-30 · adversarially reviewed the same day,
before implementation. The review inspected the actual nrepl 1.4.0
stack and changed five things, marked *(review)*: the handler is the
default middleware stack with eval intercepted, not four hand-rolled
ops; the session analysis API is a separate pair the corpus never
imports; the child protocol's two framing pins; the `def` printing
decision (a stable `user` presentation); and §6's hole is closed in
this unit's own commit, because `0026`'s recorded trigger for closing
it *is* this unit.

## The question

S2's remaining shape (`doc/roadmap.md`): the compiler runs on the JVM
and *is itself* the nREPL server, so CIDER/Calva connect with no extra
machinery. Every prerequisite is now decided — the assembler sits next
to the engine (`0026`), forms link over one runtime (`0028`), throws
classify (`0027`) — but nothing serves the protocol. What does the v0
server do on `eval`, who holds which half of the session, and what is
honestly out of scope?

## The decision

1. **One JVM process, `cljwit.repl`, serving nREPL as the default
   middleware stack with `eval` intercepted** *(review — the first
   draft said "a minimal clone/describe/eval/close handler", which
   hand-rolls exactly the parts that break: `clone` is session
   middleware behavior, not an op you implement, and a handler that
   does not answer unknown ops with `unknown-op`+`done` hangs CIDER on
   its first `lookup`)*. A `wrap-cljwit-eval` middleware declares it
   handles `"eval"` ahead of `interruptible-eval` — piggieback's exact
   shape — so session bookkeeping, `describe`, and unknown-op
   semantics come from `nrepl/nrepl` protocol-correct and for free.
   `clj -M:repl` starts it; the `-X` coordinate `0021` called
   provisional is superseded by this. Verified with the nrepl client;
   an editor connection is a manual check until someone performs it
   *(review)*.
2. **One long-lived node child, `dev/repl_engine.mjs`** — started by
   the server, speaking one JSON line per message on stdio, strict
   request→response alternation. Two framing pins *(review)*: **the
   child's stdout carries protocol lines exclusively** — diagnostics go
   to stderr, which the server inherits — and **the child exits on
   stdin EOF**, which is also the whole orphaning story when the JVM
   dies. It loads binaryen.js once (~170 ms, `0026`'s measured session
   cost), instantiates the session runtime once, holds the var ledger
   (`0028`'s instantiator half), and per form: assemble (explicit
   features, never `Features.All`), instantiate, merge exports —
   rejecting duplicate var exports loudly — call `entry`, answer
   `{"tag":"result"|"exn"|"trap"|"error", …}` in the corpus vocabulary.
   Crash recovery beyond EOF-exit is a named v0 hole.
3. **The compiler half of the session lives in a separate session API**
   *(review)*: `open-session`/`analyze-in-session!`/`close-session!`
   keep one analysis namespace alive across eval messages, so form 2's
   analysis resolves form 1's vars. **`analyze-forms` keeps its
   create-and-discard semantics unchanged, and the corpus lanes use
   only it** — the oracle's isolation rule (`0022` B.2) depends on the
   discard, and routing corpus entries through the session API would
   be the analysis-side version of the leak `0028`'s ledger makes
   loud. The contract is in both docstrings.
4. **What the REPL prints, v0**: an expression's value is a scalar and
   prints as its number (`0022` B.3's boundary, surfaced honestly); a
   `def` prints **`#'user/name`** — a *presentation* *(review)*: the
   session's analysis namespace is internal and gensym'd, the user
   never sees it, and the printed var is analysis state, not a JVM var
   anyone can deref. Namespaces are global across nREPL sessions,
   Clojure's own answer and the cheap parity choice. A classified
   `exn`/`trap` prints as an error with the trap-table row's class
   name. Anything the slice cannot express is the loud out-of-slice
   compile error it already is.
5. **Scope, named**: no watcher, no browser transport, no interrupt, no
   pretty-printing, no wasmtime session lane (deferred in `0028` §5).
   The engine child is node-only v0; the browser session reuses the
   same protocol the day a transport exists for it.
6. **The dev assembler enters the toolchain in this unit's commit**
   *(review — `0026` recorded exactly this trigger: "`tools.json`
   gains the dev assembler's version when the nREPL unit lands", and
   this is that unit; deferring again would be two notes pointing at
   each other forever)*. `tools.json` gains `binaryen-js` with a
   node-side probe, and `bb check` materializes `node_modules` with a
   guarded `npm ci` when it is absent — a network step only on first
   run or lock change, the same class of need as `bb ref`. The
   integration test still skips with a printed line if binaryen is
   somehow absent (the FFM tests' trade), but on CI it runs: server
   in-process, real nREPL client, eval through the whole pipe.

## Why

- The server/child split is `0026`'s decision made operational:
  assembly *must* live engine-side for the browser anyway, so the node
  child doing its own assembly keeps one code path for both targets.
- The JSON-line protocol is the session runner (`corpus/session.mjs`)
  turned resident — same instantiation, same ledger, same
  classification, one loop around it.
- nREPL rather than a bespoke socket because the roadmap's whole point
  for S2 is that editors connect with zero new machinery.

## Alternatives rejected

- **Evaluating on the JVM and cross-compiling later** (a normal Clojure
  nREPL now, wasm someday). Rejected because it is not this project: a
  REPL whose semantics are the JVM's is `0002`'s incident waiting to
  recur at the DX layer — every difference would be invisible until
  batch compilation.
- **piggieback middleware inside a host JVM REPL.** Right shape for
  nesting, heavier dependency surface, and nothing nests yet; the
  minimal handler serves the same editors today and does not preclude
  it.
- **JVM-side assembly (wasm-tools spawn) for the v0 child.** Works
  everywhere the gate runs, but contradicts `0026`'s decided placement
  and forks the code path the browser target needs; the 23 ms spawn is
  also above the budget `0026` bought for 1.3 ms.
- **A bespoke wire protocol to the editor.** nREPL exists; the child's
  JSON lines are internal plumbing, not an editor surface.

## What would falsify this

- **An editor round-trip measurably dominated by something other than
  the measured pieces** (analysis, 1.3 ms assembly, instantiation) —
  the v0 makes no latency claim, but the first complaint gets measured
  against `0026`'s numbers before anything is redesigned.
- **Session growth**: hundreds of forms → hundreds of live instances
  and an ever-longer ledger (`0028`'s named soak question). The nREPL
  server is where a soak test becomes runnable at all.
- **The CI hole biting** — a protocol regression landing while CI
  cannot run the pipe. If that happens once, the assembler's
  `tools.json` entry is due immediately, not eventually.

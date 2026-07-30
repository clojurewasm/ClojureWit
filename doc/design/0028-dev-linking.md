# 0028 — Dev-mode linking: one shared runtime, one module per form

**Status:** proposed · 2026-07-30 · adversarially reviewed the same day,
before implementation — and the review found a real blocker. Every §1
mechanic survived being run (mutable `(ref null eq)` globals cross
modules and the importer can set them; an imported tag catches a
cross-module throw; eqref signatures type-check structurally at
instantiation — two-module probes, both engines). What did not survive:
per-module K. §2a below is the fix, and *(review)* marks the other
three corrections: the ledger's ownership invariant made loud, the
wasmtime-CLI claim corrected from impossible to deferred-on-cost, and
the probe evidence cited instead of assumed.

## The question

Every decided piece of the dev loop assumes a shape nobody has emitted
yet: `0009`'s open world wants per-form modules over one heap, `0026`
measured that per-form assembly cost is linear in the re-declared
runtime preamble and named "forms *import* the shared runtime" as the
exit, and `0027` made cross-module throw identity explicitly a
tag-import question. And a REPL is not a REPL until form 2 can read
form 1's `def`. What exactly does dev mode emit, what does the runtime
module export, and who remembers which var lives where?

## The decision

1. **The runtime becomes its own module, instantiated once per
   session.** It exports, by name: the arithmetic and comparison ops
   (`add sub mul quot lt`), `truthy`, `box`/`unbox`, the `true` and
   `false` singletons, and the `clj-exn` tag with its `exn_class`
   scaffold (`0027` §3 lands here: one tag instance, every form imports
   it, so a throw in form N is catchable — later — in form M).
   **Types cross by structure, not by name**: the value types and the
   `$Fn` substrate are identical rec groups in every module, which is
   exactly the wire-format property `0009` measured and
   `test/cljwit/rec_group_identity_test.clj` pins — nothing to export.
2. **A dev-mode form compiles to a module that imports what it uses**
   — runtime pieces under `"rt"`, and session vars under `"vars"` by
   their fully qualified name. A form that `def`s a var **exports** the
   backing mutable global from its own module; every later form that
   mentions the var **imports** it. Re-`def` of an existing var
   imports and `global.set`s it — the open world's whole point, now
   across modules — and **never exports it again** *(review)*: the
   first definer owns the canonical global forever, and the session
   ledger *rejects* a duplicate var export, because the silent version
   of that bug is later forms reading a fresh null global as nil.
   `:prod` is untouched: one self-contained module, direct-linked,
   exactly as `0024` left it.

2a. **K is a session constant in linked mode — 20 — because per-module
   K breaks cross-form fn values** *(review — the blocker)*. `0024`
   sized the `$Fn` slot table per module; two forms with different max
   arity therefore emit *structurally different* rec groups, which by
   `0009`'s own identity rule are different types — probed: a closure
   built under a K=1 group, passed through a var global, **traps
   "illegal cast"** at a K=2 importer's `ref.cast (ref $Fn)`. So every
   linked-mode module — the runtime included, when it ever holds fn
   values — emits the same `$sig0..$sig20`/`$Fn` group. Twenty is not
   arbitrary: **Clojure itself refuses more than 20 positional
   params**, so the ceiling is JVM parity, not a new restriction, and
   arities beyond it arrive with `apply`/varargs, not with wider
   structs. The cost is ~21 nullable slots per closure struct in dev
   mode only; `:prod` keeps `0024`'s per-module K, where the module is
   whole-program and prunable. `0024` §2 carries the amendment.
3. **The session environment is the instantiator's ledger.** Whoever
   drives the session — the corpus lane today, the nREPL server next —
   holds one import object: the runtime instance's exports plus every
   var-global observed so far; after each instantiation it merges the
   new module's exports. The compiler's half of the same ledger is the
   analysis environment, which already persists vars across forms
   inside one `analyze-forms` call; the session makes that persistence
   explicit across calls.
4. **The corpus gains a third compiled lane, not new entries**: every
   existing entry's `:forms` vector *is* a session, so the dev-linked
   lane runs each form as its own module against one shared runtime
   instance and compares the last form's value — the same oracle, the
   same table. Entries with cross-form `def`s (`def-uses-def`,
   `fn-redef-dev`) stop being single-module simulations of a session
   and become the real thing.
5. **The linked lane lands on V8 first; the wasmtime half is deferred
   on cost, not capability** *(review — the first draft said the CLI
   could not link, which is false)*: `wasmtime run --preload` exists at
   47, preloads can import from earlier preloads, and a form module
   carrying its evaluation in a start section runs at instantiation —
   the review drove a three-module session through the CLI that way.
   The trade it costs: start-section evaluation means **a throwing form
   fails instantiation**, which any driver must classify as the form's
   outcome, not a broken lane. The node session runner is the shape
   the nREPL unit consumes next, so it lands now; the wasmtime linked
   lane (CLI `--preload` or `cljwit.host`'s FFM world) is recorded as
   feasible today and lands with the server unit — a scope choice,
   named to keep `0007`'s one-path lesson from being re-derived.

## Why

- Assembly cost: `0026` measured per-form cost linear in module text
  (0.92–1.94 ms over 4–8.7 KB); the runtime preamble is the bulk of
  every small form's text, and importing it caps the per-form module at
  roughly the form's own code.
- Cross-module type identity is not a hope: demonstrated for the `$Fn`
  substrate across assemblers in `0026`'s review, and pinned in the
  gate since `0009` for rec groups generally.
- Nominal things (functions, globals, tags) already have exactly one
  sharing mechanism in Wasm — import/export — so the only real design
  freedom was the namespace layout (`"rt"`/`"vars"`) and who keeps the
  ledger; both follow from the nREPL unit being the next consumer.

## Alternatives rejected

- **Keep duplicating the runtime per form.** Works today, and `0026`'s
  falsifier says exactly how it dies: the margin shrinks linearly as
  the runtime grows toward a core library. Import-shaped forms are the
  named exit, taken before the preamble grows rather than after.
- **A single mutable "session module" re-instantiated per form.** Loses
  the heap (every form's data dies with the previous instance), which
  is the opposite of `0009`'s world.
- **Registering var globals back into the runtime module's table**
  (a `vars` table indexed by number). An indirection layer with its own
  allocator, to avoid string-keyed import resolution the host already
  does per instantiation; and it erases the property that a form's
  module *says* which vars it touches.
- **Exporting types by name.** Not a thing Wasm has for GC types, and
  not needed — rec-group identity is structural; chasing nominal type
  sharing would reinvent what `0009` already measured as free.

## What would falsify this

- **A var global imported before its defining module exports it** — the
  ledger's ordering assumption. Clojure's own forward-reference story
  (`declare`, unbound vars) is out of the slice today (`(def y y)`
  refuses); if `declare` lands, the ledger needs a declared-but-unbound
  representation, and this note gains an amendment.
- **Import resolution cost growing with session length** — hundreds of
  var imports per form would show up in `0026`-style measurements; the
  per-form import list is the form's own free variables, which for
  human-written forms stays small. Re-measure if a generated-code
  session contradicts that.
- **An engine cost cliff for many-module sessions** (thousands of live
  instances in one store). Unmeasured; the nREPL unit's soak test is
  where it would surface, and `0026`'s per-form numbers bound the
  entry cost.

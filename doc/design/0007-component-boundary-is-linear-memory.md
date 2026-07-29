# 0007 — The component boundary is linear memory, not GC

**Status:** accepted · 2026-07-29

## The question

`README.md` promises a Clojure namespace compiled into "a self-contained
`.wasm` component that Rust, Go, JavaScript, Python — or another Clojure
program — can call". `0004` designs the runtime heap out of WasmGC structs.
Nobody had checked whether those two halves meet.

They meet, but not where the pitch assumes, and the seam has a shape worth
knowing before S1 designs the type mapping across it.

## What is actually true, as of 2026-07-29

**No Canonical ABI that anything can execute lowers WasmGC references across a
component boundary.** That is a narrower claim than "GC cannot cross", and the
difference is where the frontier actually sits:

| | state | checked by |
|---|---|---|
| **the spec** | no GC. `canonopt ::= string-encoding=… \| …` has no `gc` production; the "GC ABI Option" appears twice, both times as a forward link to [component-model#525] | `.ref/component-model` @ 2026-07-28, `design/mvp/{Explainer,CanonicalABI}.md` |
| **the proposal** | *pre-proposal*, opened 2025-06-03, still open, open design questions on `variant`/`option`/`result` lowering | [component-model#525] |
| **the validator** | **accepts it**, behind an off-by-default flag: `wasm-tools validate --features=gc,cm-gc` | run, below |
| **the runtime** | **panics.** `wasmtime 47.0.1 -W component-model-gc=y` gets past validation, then `internal error: entered unreachable code` at `component/func/options.rs:154` | run, below |
| **the component builder** | no GC path at all — `wasm-tools component new --help` mentions `gc` zero times, and emits only the linear-memory lowering | run, below |

The last row is what the compiler actually has to live with, and its shape was
measured rather than inferred, with `wasm-tools 1.254.0`:

| what was built | result |
|---|---|
| core module using `struct.new`/`struct.get` internally, exporting `func(s32, s32) -> s32` | `component new` **succeeds** — no linear memory anywhere in the component |
| the same module, WIT signature changed to `func(string) -> string` | `error: module does not export a memory named 'memory'` |
| …with `(memory (export "memory") 1)` added | `error: module does not export a function named 'cabi_realloc'` |

So, **under the linear-memory lowering — the only one that any runtime
executes and the only one `component new` emits**:

- **Scalar-only exports cost nothing.** A pure-WasmGC component is buildable
  today, with no linear memory at all.
- **Any aggregate WIT type** — `string`, `list`, `record`, `variant`,
  `resource` — requires the module to export `memory` and `cabi_realloc`, and
  requires every value crossing the boundary to be **copied between the GC heap
  and linear memory**.

For completeness, because the first version of this note got it wrong: a
hand-written `(canon lift … gc)` *does* validate today, passing a WIT `string`
as `(ref (array i8))` with no memory and no `cabi_realloc`. It just cannot run.

**GC references do cross freely between core modules inside one component**
(`(core instance (instantiate $app (with "lib" …)))` passing a `(ref $t)`
validates). So a Clojure component may be several core modules sharing one GC
heap; only the *lifted* interface pays a copy.

## The decision

**GC inside, linear memory at the seam.** The compiler emits:

- the Clojure heap as WasmGC structs and arrays, per `0004` — unchanged;
- a linear memory used **only** by the Canonical ABI, never by Clojure values;
- `cabi_realloc` as an exported guest function, allocating within it;
- marshalling code at each export and import that lowers GC values into that
  memory and lifts them back.

A namespace whose exported signatures are scalar-only skips all of it. That is
a real optimization, not a special case, and the compiler should report which
exports achieved it.

## Why this is a cost and not a blocker

The sibling [zwasm] implements the Canonical ABI, and sizes the work from the
other side (`.dev/component_model_survey.md` in `bb ref zwasm`). It names
`cabi_realloc` as "the ONE real coupling into the core" — exactly what the
experiment above hit — and gives three figures, which are worth quoting
together rather than picking from:

| | lines |
|---|---|
| zwasm v1's Component Model layer, as estimated in that survey | ~5,600 |
| zwasm v2's realized `src/feature/component/` today | **8,574** |
| wasmtime's, as the same survey notes | ~28k runtime + 10k environ |

The honest reading is the middle one: **an implementation that got there is
~50% over its own estimate**, and the reference implementation is 4× that
again. Requiring **zero core-VM changes** is the part that holds across all
three — it is a layer, not a rewrite.

That is a bounded subsystem someone reachable has already built. It is not a
research problem. It *is* substantially more than "the Canonical ABI is written
by hand" implied when `doc/roadmap.md` S4 was written.

## Measured, 2026-07-30 (B6)

The copy this note predicted qualitatively now has a price, and a lever.

- **A 4 KB aggregate argument costs 339 ns to lower**, against **35 ns** for a
  linear-memory language moving the same bytes — **~10×**.
- **Naively it costs 2544 ns.** The difference is representation: WasmGC has no
  array↔memory bulk copy, so lowering is a per-element loop, and holding byte
  payloads as `(array i64)` rather than `(array i8)` moves eight bytes per
  iteration for **7.5×** less. `0008` licenses that choice — no program can
  observe it — so **the compiler should hold byte payloads wide.**
- **The gap has an exact shape.** `array.copy` moves the same bytes GC-to-GC in
  51 ns, near memcpy class. The bulk move exists and simply cannot reach linear
  memory. **An array↔memory copy instruction would be worth a further 6.6×**,
  and is a much smaller ask than [component-model#525].
- Scalar-only exports still cost nothing.

Numbers, controls and threats in `doc/design/0002-measure-first.md`.

## What this changes

- **S4 is bigger than one line of roadmap suggested**, and it now has a
  concrete inventory: WIT type mapping, lift/lower per type, `cabi_realloc`,
  resource handle tables.
- ~~**S0 does not measure the thing the pitch rests on.**~~ **B6 measured it**
  (2026-07-30): ~10× a linear-memory language per aggregate argument, with a
  7.5× representation lever inside that. See above.
- **`cljwit.host` (S1) is unaffected in kind**: as the *caller*, it lifts and
  lowers on the JVM side, where there is no WasmGC at all. The type mapping it
  settles is the same one the compiler needs, which is the reason `0001` put it
  first — that reasoning survives contact with this finding and is strengthened
  by it.

## Alternatives rejected

- **Wait for [component-model#525] to ship.** It would remove the copy
  entirely, and it is closer than "pre-proposal" sounds — the validator already
  accepts it. Rejected as a plan anyway: it is not in the spec, no runtime
  executes it, and its open design questions are on `variant`/`option`/`result`
  — which is to say, on exactly the sum types a Clojure ABI is made of. Worth
  *watching* closely: if it becomes executable during S3, the marshalling layer
  turns from a requirement into an optimization. Nothing should be scheduled
  behind it.
- **Use linear memory for the Clojure heap too**, making the boundary free.
  This is the ClojureWasm approach and `0001` already rejected it: shipping our
  own GC costs binary size and the engine's ability to optimize across our
  allocations. The boundary copy is much cheaper than that.
- **Export only scalars, and pass everything else as an opaque handle.**
  Tempting, and it does dodge the copy. Rejected as the default because it
  inverts the project's goal: a Rust caller that must hold Clojure handles and
  call accessors has not been given a component that "just works", it has been
  given a foreign-object protocol. Worth offering; not worth defaulting to.

## What would falsify this

**Not** the three `component new` invocations — they will keep producing those
errors no matter what the GC ABI does, which is how the first version of this
note stayed wrong. The falsifier has to probe the frontier itself:

```sh
bb ref component-model                     # is `gc` a canonopt in the spec yet?
grep -n 'canonopt ::=' .ref/component-model/design/mvp/Explainer.md

wasmtime -W component-model-gc=y --invoke 'f("hi")' lift-str.wasm
```

Today the first says no and the second **panics**. When that panic becomes an
answer, the marshalling layer becomes optional and this note is superseded.

## Correction, 2026-07-29

The first committed version of this note claimed outright that "WasmGC
references cannot cross a component boundary", on the strength of the three
`component new` runs above. That was falsified within the hour, with this
repo's own pinned `wasm-tools`, by hand-writing a `(canon lift … gc)` that
validates. The runs were correct; the generalisation from them was not — they
characterise one tool's default path, not the domain.

That is the third time in one day this project has generalised from an
experiment that varied only one thing (see `doc/status.md`). The rule against
it now sits in `.claude/CLAUDE.md` rather than only in the bench-scoped
`.claude/rules/measurement.md`, because it has now fired outside `bench/`.

[component-model#525]: https://github.com/WebAssembly/component-model/issues/525
[zwasm]: https://github.com/clojurewasm/zwasm

# 0014 — `cljwit.host`'s API

**Status:** accepted · 2026-07-30 · written before any code existed, which is
the point; implemented in `src/cljwit/host.clj`, and E is amended below by what
the implementation found

## The question

`cljwit.host` lets JVM Clojure call a Wasm component. Everything under it is
settled: the binding mechanism (`0011`), the cost (`0013`), the type mapping
(`0012`), and — as of `bb spike-reflect` — that a component describes its own
exports, parameter names and types at run time. What is not settled is what a
user of `cljwit.host` types.

`doc/roadmap.md` states S1 as "`require` a component as a namespace and call
it". That is the *surface* this aims at; this note is about the primitive
underneath it, and says why the namespace surface has to be a layer rather than
the foundation.

## The decision

### A. Runtime reflection is the primitive; code generation is a later layer

The component's own type is read at load time and marshallers are built from
it. No WIT file at run time, no macro, no generated namespace.

```clojure
(with-open [rt (host/engine)]                        ;; process-lifetime
  (with-open [art (host/compile rt "echo.wasm")]     ;; artifact-lifetime
    (with-open [c (host/instantiate art)]            ;; call-scope
      ((:echo-string c) "hello"))))                  ;; => "hello"
```

A `defcomponent`-style macro that emits vars — for completion, `clj-kondo`, and
arity checking — is built **on** this. It cannot be built under it, and S1 is
not finished until it exists.

### B. The exact WIT name is the identity; keywords are an alias where they round-trip

Exports are keyed by their WIT name as a **string**. A keyword alias is added
only when the name is a legal Clojure keyword that reads back equal to itself.

```clojure
(:echo-string c)                          ;; alias exists — plain label
(c "wasi:cli/run@0.2.0#run")              ;; no alias — the version bars it
(c "[constructor]fields")                 ;; no alias — annotations bar it
```

**Exports are flat, not nested.** A function inside an interface is keyed
`"pkg:name/iface@ver#func"` — the spelling WIT and `wasm-tools` already use,
and the same string the core module exports it under. An earlier draft nested
interfaces under a keyword namespace; that died with the keyword identity.

The handle is callable as a function of one name, so both forms are one lookup.
Lookup of an absent name **throws**, naming the nearest legal export.

### C. Three lifetimes, three closeables — engine, compiled artifact, instance

They differ by three orders of magnitude in cost and by everything in scope:

| | what it is | measured |
|---|---|---:|
| `engine` | process-wide, shared by everything | ~0 ms warm |
| `compile` | Cranelift compilation of one component | **1.6 ms** (2.4 KB) … **19.2 ms** (103 KB) |
| `instantiate` | a store plus an instance | 0.02 ms in C, **0.057 ms through `cljwit.host`** |

**Corrected 2026-07-30:** the 0.02 ms is a C probe's. `cljwit.host` measures
**56.9 µs** for the same step, because `instantiate` makes many FFM calls and
each pays the flat crossing `0013` priced. The ratio the decision rests on
survives — compilation is still 30–300× instantiation — but the absolute figure
was not this library's.

Compilation is 80–1000× instantiation and scales with module size. Fusing them
would make `wasmtime_component_serialize`/`deserialize_file` unreachable, and
would give every component its own engine — so no two could ever be linked or
share a value, because a `Linker` and every compiled artifact are engine-scoped.

### D. Every entry into a store checks **non-concurrency**, not thread identity

`wasmtime/store.h`: *"It is safe to move a `wasmtime_store_t` to any thread at
any time. A store generally cannot be concurrently used, however."* So the
invariant is one-at-a-time, not one-thread-forever. A compare-and-set on an
in-call flag, thrown on contention with the name of the thread already inside.

It guards **every** entry, not only calls: `close`, and dropping a lifted
`own<T>` handle, both take the context and are the same memory-unsafety.

**Amended 2026-07-30, after host imports were shown to work.** The flag does
two jobs, and only one of them is contention. A host import calling back into
the instance that is executing is *nesting*, not concurrency — and the
component model forbids that too: measured, `wasm trap: cannot enter component
instance`. So throwing is right, and throwing *before* wasmtime traps is better,
because a trap poisons the instance and every later call fails with the same
message, hiding the cause. The flag now records the thread holding it, so
re-entry says so (`:cljwit/error :reentrant`) instead of blaming a second
thread that does not exist.

### E. Arguments reuse a buffer; results are lifted eagerly and never retained

A result payload is **invalid after the next call on that function**. So every
call fully lifts its result into JVM-owned values before returning and hands
out nothing backed by the buffer. A lifted `own<T>` needs
`wasmtime_component_resource_any_clone` to outlive it.

**It must *not* call `wasmtime_component_val_delete` on a result wasmtime
produced.** Amended 2026-07-30, after the first implementation: that function
"will deallocate the contents of the value but not the value pointer itself",
and on a `result` or `variant` — the two kinds carrying a payload pointer — the
*second* call aborts the JVM in a wasmtime panic that cannot unwind. Every
single call succeeds, so nothing short of calling twice finds it. wasmtime
recycles its own result allocation: measured, 300k `result` calls grow RSS by
3.3 MB against 7.9 MB for the same number of scalar calls.

Argument buffers are built once per export and reused, which is safe for the
same reason `0013` gives: the values are written immediately before the call.

**This survives host imports**, which was not obvious and is now measured: the
nested call that would clobber the buffer cannot happen, because a component
instance cannot be re-entered at all.

## Why

**A, because reflection is complete and codegen is not reversible.** The nested
accessors all exist — `record_type_field_nth`, `variant_type_case_nth`,
`enum_type_names_nth`, `flags_type_names_nth`, `result_type_ok`/`_err`,
`option_type_ty`, `list_type_element`, `map_type_key`/`_value` — so a
marshaller can be built for any of `0012`'s rows without reading WIT. Given
that, a macro-first API would add a build step to buy what can be added later,
and `0009` decided that development is the open-world mode. An API whose
primitive is a macro cannot become dynamic; one whose primitive is a map can
grow a macro.

**B, because keywords cannot hold a real WIT name and fail silently when they
try.** Verified:

```
(keyword "wasi.cli" "run@0.2.0")  prints :wasi.cli/run@0.2.0
                                  reads back :wasi.cli/run   — and = is false
(keyword "local.res" "[constructor]fields")  → reader throws "Invalid token"
```

Every real WASI export carries `@0.2.0`, and resource methods are spelled
`[constructor]f`, `[method]f.g`, `[static]f.h`. The truncation is the dangerous
half: it produces a *different valid keyword*, so `pr-str`/`read-string`, EDN
config and spec all corrupt the name — and `0012` leans on plain data
round-tripping through `pr-str`. Worse, the collision is blessed by the spec:
`Explainer.md:2827` makes `foo` and `[constructor]foo` strongly-unique and
legal in one scope, so stripping annotations collides *by construction*.

**C, because the three costs are not one cost.** Measured on this machine with
the pinned wasmtime: compiling the 2.4 KB echo component takes 1.6 ms and a
103 KB one 19.2 ms, while instantiating takes 0.02 ms and reflecting the whole
type takes 3 µs. `store.h` says stores "are cheap to create and cheap to
dispose… one-off stores are common in embeddings", which is exactly the
per-request shape `0003`'s server lane wants: **one engine, one compiled
artifact, a store per request.**

**D, because affinity forbids the shape the project is aiming at.** A handle
created at startup and used from an executor, a `future`, a virtual thread per
request — all legal under wasmtime's actual rule and all forbidden by a thread
check. Non-concurrency is the same handful of nanoseconds against a ~393 ns
call (`0013`) and catches the real bug.

**E, because reuse clobbers.** Two ordinary sequential calls, same thread, no
imports involved:

```
call 1 -> 0x728c1c5a0  "AAAAAAAA"
call 2 -> 0x728c1c580  "BBBBBBBB"
call 1's pointer now reads: \xef\xa4l\xc3...
```

wasmtime allocates the payload on the host heap per call regardless, so
retaining the outer struct saves 32 bytes and buys a use-after-free. Eager
lifting is what `cljwit.host` does anyway to produce Clojure values; this note
just makes it a rule rather than an accident.

## Alternatives rejected

- **A `defcomponent` macro as the primitive**, the shape `wit-bindgen` and every
  WIT binding generator uses. It buys editor completion, arity checking and
  `clj-kondo` awareness — real things the first version gives up. Rejected as
  the *primitive* because it forecloses the dynamic mode `0009` requires; it is
  the intended second layer and S1's stated surface.
- **Keywords as the identity**, with versions and annotations stripped. This
  was the first draft of B. Rejected on the evidence above: it loses
  information silently and collides on names the spec permits together.
- **A single `call` function** — `(host/call c :echo-string "hi")`. Impossible
  to pass to `map`. Rejected: exports should be ordinary functions.
- **A bare map with `nil` on a miss.** Rejected: instantiation knows every legal
  name, so a typo should throw with the near miss. Silent `nil` is the main
  thing a dynamic API can do *worse* than codegen, and there is no reason to.
- **One handle owning engine, compilation and instance.** This was the first
  draft of C. Rejected on the measurements: it welds a 19 ms compile to a
  0.02 ms instantiation, makes the serialized-artifact path unreachable, and
  makes composition impossible.
- **A thread-affinity check.** The first draft of D. Rejected: it enforces a
  stronger invariant than wasmtime's, forbidding legal handoff.
- **Locking the store** instead of failing on contention. Rejected because it
  makes a single-threaded resource look shareable and hides contention; an
  exception naming the thread already inside is more useful than silent
  serialisation.
- **Retaining result buffers.** The first draft of E, justified by a "5–10% of
  a call" figure that **no measurement in this repo supports**. Rejected on
  correctness before cost.
- **Freeing the result with `val_delete`.** What E said to do until the
  implementation aborted the JVM on the second call. See above.

## What would falsify this

- **Host imports.** The moment a guest can call back into Clojure, D's
  compare-and-set sees legal re-entrancy as contention. This is the falsifier
  most likely to fire, because WASI needs imports.
- **`resource` in `0012`.** `own`/`borrow` are the one type family with no
  obvious Clojure value, and the only one whose mishandling is a JVM-killing
  use-after-free rather than a wrong answer.
- **`0012`'s error contract.** It says a thrown `result` carries "the lifted `E`
  **along with the WIT type name**". **Reflection cannot supply that** — the C
  API exposes field, case, parameter and flag names but no accessor for a
  type's own name. Either `0012`'s `ex-data` shrinks, or the name comes from
  the codegen layer, which means A is sufficient for calling but not for
  diagnostics.
- **An instantiate-per-request deployment.** 0.02 ms is fine; 19 ms is not. If
  a shape needs per-request *compilation*, the serialized-artifact path has to
  be in the API rather than merely reachable.
- **WIT labels are not all lower-kebab.** `Explainer.md:2705` admits acronyms —
  `get-JSON` is legal. It maps to `:get-JSON` without trouble, but any inverse
  mapping has to respect the case-folding rule at :2827.

## Resources

- `dev/resources/zoo.wit` — a component this project did not design its
  marshaller against: resources with constructors and methods, flags, tuples,
  and types nested several deep, built with `wasm-tools component embed
  --dummy` so no guest can quietly match what the host expects. It is the
  standing counterweight to this note's own recorded failure mode.
- `bb spike-reflect` — the self-description A rests on.
- `0012` — the type mapping the marshallers implement.
- `0013` — the cost budget the per-call decisions are measured against.
- `test/cljwit/valtype_enum_test.clj` — the two-enum trap a reflection-driven
  marshaller walks straight into.

## What the first draft got wrong

Recorded rather than silently fixed, as `0012` was. Of five decisions, an
adversarial review overturned three and found the fourth unsafe:

- **E was dangerous**, and its stated hazard was the wrong one. The draft
  dismissed buffer reuse as risky only under guest→host re-entrancy, which
  cannot happen yet. Two sequential calls already clobber. Its justification
  was a performance figure with no measurement behind it — the exact thing
  `.claude/CLAUDE.md` rule 2 forbids, written by the author of that rule's most
  recent incident.
- **B and C were argued from a toy example.** `wasi:cli/run` has no version and
  no annotations, and `echo.component.wasm` compiles in 1.6 ms. Both decisions
  collapse on the first real WASI world.
- **D cited a constraint without reading the header that states it.** wasmtime
  says non-concurrency; the draft asserted affinity.

The pattern across all four: **each was checked against the artifact already in
this repo, and none against the artifact the design is for.** That is the same
shape as `0007`'s false generalisation and `0013`'s retraction — a negative
result from the easy case, read as a result about the domain.

# 0017 — Host imports: letting a component call Clojure

**Status:** proposed · 2026-07-30 · the mechanism is verified (`bb
spike-import`); this is the API over it, and no code exists

## The question

`cljwit.host` can call a component. It cannot yet be called *by* one, which
means it refuses every component that imports anything — including all of WASI.
`0014` named this its most-likely-to-fire falsifier.

What is settled, measured 2026-07-30:

- **An FFM upcall stub reaches Clojure from inside a component call.** `bb
  spike-import`: `run(20) = 41`, the host saw `[20]`. Pure Clojure, bound to a
  `reify` — no bytecode generation and no C shim, the same answer `0011`
  reached for the outbound direction.
- **A component's imports reflect exactly like its exports** — interface,
  parameter names, types, result — through
  `wasmtime_component_type_import_count`/`_nth`.
- **A host callback cannot re-enter the instance that is executing**: `wasm
  trap: cannot enter component instance`. `0014` D and E survive.
- **`wasmtime_component_linker_add_wasip2` supplies all of WASI 0.2 in one
  call**, so hand-writing `wasi:cli` is not the price of entry.
- **`wasmtime_error_new(const char *)` exists**, so a Clojure exception has
  somewhere to go.

## The decision

### A. Imports are supplied at `instantiate`, keyed by the same names as exports

```clojure
(host/instantiate art {:imports {"local:imp/host@0.1.0#twice" (fn [v] (* 2 v))}})
```

The identity is the exact WIT name, `iface#func`, exactly as `0014` B decided
for exports. One naming rule for both directions.

### B. The host reflects what the component needs and refuses a mismatch, both ways

A missing import throws at `instantiate`, **naming it**. wasmtime's own message
is `function implementation is missing` with no name, which is a worse version
of the `nil`-on-typo failure `0014` already rejected.

An import supplied but not needed throws too. A typo silently ignored is the
same failure wearing the other hat.

### C. Marshalling is the same compiled lift and lower, run in the opposite direction

An import's parameters are *lifted* into Clojure and its result *lowered* back
— the mirror of an export. `lift-fn` and `lower-fn` already compile from a
reflected type tree; nothing new is needed but the direction.

### D. A Clojure exception becomes a `wasmtime_error_t *`, and never unwinds into native frames

The callback catches `Throwable`, converts it with `wasmtime_error_new`, and
returns it. A JVM exception propagating through a native frame is undefined
behaviour, and this project has already lost a JVM to a non-unwinding panic
(`0012`'s surrogate `char`).

### E. WASI is a flag, not a map

```clojure
(host/instantiate art {:wasi true})
```

calls `add_wasip2`. Supplying a hundred functions by hand to run `wasi:cli` is
not an API, and the C API already refuses to make you.

## Why

**A, because two naming rules would be one too many.** `0014` B paid for the
exact-WIT-string identity with measurements about keywords; imports do not get
to relitigate it.

**B, because instantiation is the only moment the host knows both sides.** It
has the component's requirements from reflection and the user's map in hand. A
mismatch discovered later is discovered by wasmtime, in a message with no name
in it.

**D, because the failure mode is the worst one available.** Every other error
in this library is an `ex-info`. An exception crossing into wasmtime's frames
is a crash with no stack trace, and the cost of preventing it is a `try`.

## Alternatives rejected

- **Imports on the artifact rather than the instance.** The linker is
  engine-scoped, so this would be possible — and it would make every instance
  share one set of host functions, which is wrong the moment two requests want
  different state. `0014` C put per-request state on the instance.
- **A separate `link` step** between `compile` and `instantiate`. Honest about
  where the linker lives, and a fourth lifetime for something that has no
  independent lifetime: nothing can use a linker except to instantiate.
- **Letting a missing import fail lazily**, at the first call. Cheaper to
  implement and it moves the error away from the mistake.
- **Requiring an explicit `wasi:cli` map even when `add_wasip2` exists.**
  Consistent, and unusable.
- **Passing the raw `wasmtime_component_val_t` to the Clojure function.**
  Fastest, and it makes every import a chance to corrupt memory.

## What would falsify this

- **An import that is not a function.** A component may import a *type* or a
  *resource*; `wasmtime_component_item_t` has kinds for both. A: the map is
  keyed by function name and has nowhere to put them.
- **The upcall stub's lifetime.** It is bound to an `Arena`, and it must
  outlive every call. If the instance's arena is the wrong scope — say the stub
  must outlive the *linker* rather than the instance — A is in the wrong place.
- **`add_wasip2` needing a WASI context on the store.**
  `wasmtime_context_set_wasi` exists and takes a config; whether `add_wasip2`
  works without one, and what E should do about the config, is unverified.
- **Whether a returned error surfaces to the outer caller as an error rather
  than a trap that poisons the instance.** D assumes it does. Unmeasured.
- **The cost.** An upcall is not free and nothing here has been timed. If a
  guest calls a host import in a loop, the number that matters is per-upcall,
  and `0013`'s discipline applies: predict, then measure.

## Resources

- `dev/resources/imp.{wit,wat}` and `bb spike-import` — the mechanism.
- `0014` — the naming rule, the lifetimes, and the concurrency check this
  inherits.
- `0016` — the other direction of the same marshalling.

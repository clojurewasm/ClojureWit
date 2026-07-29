---
name: wat
description: Build, validate, run, and inspect hand-written WebAssembly text (.wat) in this repo — with the exact wasm-tools, node, wasmtime, and wasm-opt invocations needed for WasmGC, tail calls, and exception handling. Use this whenever writing or running anything under bench/, hand-writing WAT, debugging a Wasm module, or measuring on V8 vs wasmtime. Reach for it before guessing at command-line flags, because the feature flags for GC and tail calls are easy to get silently wrong.
---

# Hand-written WAT in this repo

S0 is entirely hand-written WAT — no compiler exists yet. These are the exact
invocations; the flags are the part that goes wrong.

## Build and validate

```sh
wasm-tools parse f.wat -o f.wasm      # wat -> wasm
wasm-tools validate f.wasm            # validate (features are on by default in recent versions)
wasm-tools print f.wasm               # wasm -> wat, to see what you actually built
wasm-tools dump f.wasm                # section-level view; useful when sizes surprise you
```

Confirm the toolchain understands the features you are using before blaming the
module:

```sh
wasm-tools validate --help | grep -iE 'gc|tail|exception'
```

## Run

```sh
# wasmtime (server lane) — no adaptive optimization, so this is the pessimistic number
wasmtime run --invoke main f.wasm

# node (V8 lane) — has speculative inlining; needs a JS harness to instantiate
node harness.mjs f.wasm
```

## Optimize

```sh
wasm-opt -O3 --enable-gc --enable-tail-call --enable-exception-handling f.wasm -o f.opt.wasm
```

Report both the unoptimized and optimized numbers. `wasm-opt` has been measured
at ~1.9× on GC-heavy code, and attributing that to our own design would be
wrong.

## Gotchas

- **`wasm-opt` needs its feature flags spelled out.** Without `--enable-gc` it
  will refuse — or worse, an older build will accept and mangle. Check
  `wasm-opt --version` against `tools.json`.
- **Mutually recursive types must share one `(rec ...)` block**, or they are not
  the types they appear to be. The reason is *not* nominality — this line said
  the opposite until it was measured on 2026-07-29. **Type identity is the
  canonicalised rec group plus the index within it.** Two modules declaring an
  identical group get the *same* type, even compiled separately and never
  linked. Part of that identity, all measured: field types, **field
  mutability**, `final` versus open to subtyping, a **declared supertype**, the
  other members of the group, and the **index within it** (two byte-identical
  entries at index 0 and 1 are different types). Not part of it: the `(rec …)`
  wrapper around a singleton, and type-section order. Identity is transitive —
  a group whose text is unchanged changes identity if a group it references
  did. The rec group is a wire format between compilation units;
  `doc/design/0009-*` and `test/cljwit/rec_group_identity_test.clj` pin this.
- **`wasm-opt --closed-world` silently breaks cross-unit type sharing.** With
  it, `-O2`/`-O3`/`--gufa` fold a `ref.test` against a shared type to
  `i32.const 0` **when that type appears in no exported signature** — nothing
  tells Binaryen the objects come from outside. Correct under a closed world,
  catastrophic for a unit sharing a heap, and it fails as a wrong answer, not
  an error. Never apply it to anything that shares a heap with separately
  compiled code. Plain `-O3` without `--closed-world` is safe.
- **`ref.cast` failure traps; `br_on_cast` branches.** Use `br_on_cast` /
  `br_on_cast_fail` for dispatch. A trap in a benchmark shows up as a wrong
  number, not an error, if you are catching at the harness level.
- **Tail calls are `return_call` / `return_call_ref`.** A plain `call` in tail
  position is not a tail call and will grow the stack — which only shows up as
  a crash at depth.
- **`i31` is 31 bits, signed.** `i31.get_s` sign-extends, `i31.get_u` does not.
  Getting this wrong produces correct-looking small numbers and wrong large
  ones.
- **The V8 harness needs the result consumed.** Instantiate, loop inside Wasm,
  and print the accumulated result. Otherwise the engine deletes the loop and
  you measure nothing at spectacular speed.
- **Do not compare a `node` number to a `wasmtime` number as if one were
  "the" answer.** They answer different questions; both go in the table.

## Where things live

`bench/s0/` holds the S0 modules and `bench/s0/README.md` the contract for what
each one measures. Predictions live in `doc/design/0002-measure-first.md` and
are written before the run.

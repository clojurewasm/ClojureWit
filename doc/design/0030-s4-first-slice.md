# 0030 — S4's first slice: utf8 strings, a bump arena, per-export trampolines

**Status:** proposed · 2026-07-30 · adversarially reviewed the same day.
The review strengthened three sections with spec citations the first
draft hedged around (the arena's safety is provable, not hoped; the
worked core signature; utf8 as identity-class on this host today), made
the echo slice's honest scope explicit, and caught a delegated figure
absorbed unverified — the survey said 87 round-trip assertions, the
test says 80 — four citations were spot-checked and this number was
not among them; the fifth check is the one that mattered.

## The question

S4's premise is verified (`bb spike-s4`: a cljwit-compiled component
answers 6765 through `cljwit.host`), and the entry survey re-read the
Canonical ABI at component-model `73b7ad51` (2026-07-28), re-verified
`0007`'s frontier by running it, and read the two closest prior arts
(Kotlin's WasmGC→component pipeline, zwasm's lift/lower layer). It
ended with exactly three decisions the first real slice cannot dodge:
the string encoding and what stands behind it on the guest heap, the
`cabi_realloc` design, and the codegen strategy. This note takes them.
Survey citations live here so the claims stay checkable; four were
spot-checked verbatim before this note was written.

## The decision

1. **String encoding: `utf8`, declared in every `canon lift`.** The
   spec default (Explainer.md:1401 — "If no `string-encoding` option
   is specified, the default is `utf8`"), Kotlin's shipped choice
   (its wit-bindgen fork's `STRING_TO_MEM` is
   `encodeToByteArray()` — utf8 — at kotlin/src/lib.rs:708-709), and
   zwasm's staging lesson (debt D-502: utf8-only shipped and tested
   for weeks; the late pain was threading the option, not the codec).
   `latin1+utf16` — which the Explainer (line 1398) pitches as the
   JVM-family encoding — is **named as the revisit**, gated on the
   day cljwit has a general string heap representation whose code
   units are UTF-16-shaped; deciding it now would couple this slice
   to an S5 decision nothing measures yet. And the double-transcode
   worry resolves in utf8's favor *today* *(review)*: `cljwit.host`
   already feeds wasmtime UTF-8 bytes on every string path
   (`host.clj` 236/385 lower, 143/249/396 lift), so a `utf8` guest
   makes the host-side transcode identity-class, while
   `latin1+utf16` would *add* one on this exact path. The B6-class
   measurement belongs to the revisit, not to licensing this slice.
2. **The v0 guest string is boundary-only: utf8 bytes in
   `(array i8)`.** No general string type lands here — literals stay
   out of the compile slice until S5 — but the marshalling layer needs
   *something* to lift into and lower out of, and a byte array is the
   smallest thing the Canonical ABI's `(ptr, code-units)` pair maps
   onto. B6's wide-payload lever (`(array i64)`, 7.5×) is **not**
   taken yet: for payloads under ~30 KB the call dominates the copy
   (`0013`/B6), and taking the lever before a measured workload wants
   it would be `0002`'s incident shape. The lever stays licensed
   (`0008`) and priced (B6), one representation swap away.
3. **`cabi_realloc` is a bump arena with a grow-last-block fast path,
   reset at each export entry.** The shape Kotlin's stdlib ships
   (`MemoryAllocation.kt`: bump allocation, grow-in-place when the
   growing block is the newest — exactly what `store_string`'s
   optimistic utf8 transcode calls for, CanonicalABI.md:2857-2860)
   and the shape zwasm found to be "the ONE real coupling into the
   core". The free point is the *next entry's* reset — and that is
   **provably conservative, not hoped** *(review)*: the Canonical ABI
   lifts results *inside* the call (`canon_lift` runs
   `lift_flat_values` before returning, CanonicalABI.md:3657-3670;
   `post-return` exists only to free afterwards, and is a no-op in
   wasmtime 47 per `0013`), so any conforming host has copied out
   before the call completes, and `cljwit.host` lifts eagerly on top
   of that. Reentrancy cannot reach the reset in the sync world:
   `may_enter` is cleared for the call's duration and reentry *traps*
   (CanonicalABI.md:127, 166-180). Dated constraint carried from the
   survey: spec #680 (2026-07-24) gives `realloc` fresh-thread
   semantics — no layout change, but nothing in our `realloc` may
   depend on context state.
4. **Per-export specialized trampolines, emitted by the compiler — no
   generic interpreted layer.** What every wit-bindgen backend and
   zwasm's fused store/load converge on, and where the spill and
   asymmetry rules get baked in rather than consulted: flat params
   over 16 spill through memory, flat results over 1 spill through a
   single pointer (CanonicalABI.md:3138-3140, 3611-3614), and the
   **retptr asymmetry** zwasm recorded as a lesson — a lifted export
   returning a non-flat value takes no return-area param and *returns*
   the pointer; a lowered import takes the caller's return area *in*
   (zwasm lesson 2026-06-20, read in `.ref/`, quoted in the survey).
   The worked example, so no implementer re-derives it wrong
   *(review)*: a lifted sync `func(string) → string` has core
   signature **`(func (param i32 i32) (result i32))`** — the params
   are the caller's (ptr, code-units), and the single result is a
   pointer the *callee returns* to its own 4-aligned 8-byte
   (ptr, code-units) pair (`flatten_functype`'s lift branch,
   CanonicalABI.md:3142-3155). Emitting a `(param i32)` return-area
   export instead is zwasm's exact recorded bug.
5. **The slice order**: `func(string) -> string` (echo — forces
   realloc, both directions, the retptr shape) and `list<u8>` first;
   flat records as the memcpy-class case after; variants/options
   later — each landing with round-trip coverage against
   `cljwit.host`, whose lanes already marshal every `0012` row from
   the host side (80 assertions in `roundtrip_test.clj` — measured,
   after the survey's figure of 87 failed verification *(review)*),
   so every guest-side slice has a finished counterpart to meet.
   Honest scope *(review)*: the echo body is **identity on an opaque
   value** — no Clojure-level string exists in the slice, so echo
   tests trampolines and realloc, not string semantics; lift into
   `(array i8)`, hand the same reference back, lower out.
6. **The roadmap's sizing figure gains its decomposition**: zwasm's
   8,574 lines verified by `wc -l`, but the lift/lower + realloc +
   handle-table core analogous to S4 is **~2,250 lines** — the rest
   is a component binary decoder, a validator, and WASI 0.3 async,
   all engine-side work this project delegates to wasm-tools and
   wasmtime. `doc/roadmap.md` carries a one-line pointer here.

## Why

- Every load-bearing external claim above was read at a pinned commit
  or run on the pinned toolchain on 2026-07-30, by a fresh-context
  survey whose citations carry paths and line numbers, four of them
  spot-checked verbatim afterward — the `0022`-incident protocol.
- `0007` re-verified by running, unchanged: no `gc` canonopt exists in
  the grammar (Explainer.md:1381-1391); #525 is an open pre-proposal
  idle since 2026-04-23; wasm-tools 1.254 validates `(canon lift … gc)`
  behind `cm-gc`; wasmtime 47 panics on the call path at the same line
  `0007` recorded. The boundary is linear memory, still.
- Kotlin — the closest production prior art — crosses the boundary
  with a per-byte loop and a bump arena. B6's 10× is the frontier,
  not a local deficiency; nobody has a bulk GC↔memory path.

## Alternatives rejected

- **`latin1+utf16` now.** The JVM-fit argument is real, but it binds
  the boundary encoding to a string representation that does not
  exist, and wasmtime transcodes host-side either way (`0012` L6).
  Revisit with the string heap type, as a measured decision.
- **A generic runtime marshalling interpreter.** zwasm needed one — it
  is a host serving arbitrary components; a compiler knows every
  signature at build time, and the trampoline is exactly the code a
  generic layer would interpret.
- **Taking B6's wide-representation lever now.** Unmeasured need;
  the swap is local when a workload demands it.
- **`post-return` for arena reset.** Deprecated and a no-op in
  wasmtime 47 (`0013`, measured); resetting at next entry needs no
  cooperation that does not exist.
- **Building `wit` parsing.** `wasm-tools component embed` consumes
  the WIT; the compiler only needs the WAT-level `canon` scaffolding
  `0007` already hand-verified.

## What would falsify this

- **The echo slice failing under `component new`'s export-name
  contract** (`memory`, `cabi_realloc`) — `0007` measured the
  requirement list once; the slice is its second, executable check.
- **Arena-reset-at-entry surprising some host after all** — the spec
  says a conforming host lifts before the call returns
  (CanonicalABI.md:3657-3670), so this would mean a host violating
  the ABI; the slice's round-trip test covers it for `cljwit.host`,
  and a pipelined-calls test is named future coverage.
- **#680's fresh-thread semantics acquiring teeth** (a wasmtime
  release actually running realloc on a fresh context) — nothing in
  the bump arena depends on context state, by construction; the
  constraint is carried so a future `realloc` never grows that
  dependency.
- **The utf8 default colliding with a measured JVM-string workload**
  — that is the `latin1+utf16` revisit trigger, with B6-style
  numbers.

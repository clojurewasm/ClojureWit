# 0011 — The JVM reaches a component, and what that costs to write

**Status:** accepted · 2026-07-30

## The question

`0001` makes `cljwit.host` the first deliverable and `0005` assumes it reaches
Wasm through Java's FFM and wasmtime. `0005`'s S1 survey established that the
symbols resolve. **Resolving a symbol is not calling a function**, and the gap
between those is where a library's shape gets decided — so before designing any
API, run the whole path once by hand.

## The decision

**Confirmed: a value crosses.** `bb spike-host` builds a component and calls it
from the JVM, end to end — engine, store, component, linker, instantiate,
export index, function, call — returning 42 from `add(17, 25)`.

`dev/cljwit/spike/component_call.clj` is that path with **no abstraction on
purpose**, so what a library must wrap is visible rather than guessed at. It is
a spike and is not `cljwit.host`; this note records what it found so the design
can start from facts.

## What it found

Four constraints, each of which would have been a wrong assumption:

**1. Clojure cannot call `MethodHandle.invokeExact`.** It is
signature-polymorphic: the JVM requires the *call site* to state the exact type
statically, which Clojure's reflective interop cannot express. It fails with
`No matching field found: invokeExact`. `invokeWithArguments` works and is what
the spike uses — **at the cost of boxing every argument and return**.

This looked like the largest open design question for `cljwit.host` — and the
measurement below says it is not. The way out is not bytecode generation: an
interface proxy gets a static call site from pure Clojure, and in any case the
boxing is ~16% of a component call rather than the bulk of it.

**2. `Arena.allocateFrom` is unreachable reflectively.** It is declared on
`SegmentAllocator`, and Clojure resolves against the concrete
`jdk.internal.foreign.MemorySessionImpl$1`, which is not exported — so the
method is invisible even with an `^Arena` hint on the local. The spike builds
its C strings by hand instead. Expect more of this: **the FFM API's useful
methods are spread across interfaces whose implementations are not exported.**

**3. Struct layouts have to be measured.** The spike carries
`wasmtime_component_val_t` = 32 bytes, kind at 0, union at 8;
`wasmtime_component_instance_t` = 16; `wasmtime_component_func_t` = 24;
`WASMTIME_COMPONENT_S32` = 5. All obtained by compiling a C program against the
pinned headers, because a wrong offset here is a segfault rather than an
exception — and a segfault takes the JVM with it.

**4. `--enable-native-access` is required**, or the JVM warns and will
eventually refuse. `bb spike-host` passes it.

## The open question, with a prediction recorded before measuring

**2026-07-30, before the run.** Constraint (1) says the spike boxes. Whether
that matters depends on two numbers nobody has: what boxing costs per call, and
what a component call costs in total.

There is also a third option neither this note nor `0005` considered:
`MethodHandleProxies/asInterfaceInstance` wraps a `MethodHandle` behind a
single-method interface, and the interface's method *is* a static call site. A
`definterface` supplies that interface from pure Clojure, so **if it is fast,
`cljwit.host` can stay pure Clojure with no bytecode generation and no C shim.**

Predicted: `invokeWithArguments` **~50–150 ns** per trivial native call
(boxing plus a generic path); the proxy **within a few ns of a direct call**,
because the JIT can see through it; and a real scalar
`wasmtime_component_func_call` **~200–1000 ns**, which would make the boxing
10–40% of a component call — significant but not fatal. **Falsified if the
proxy is no better than `invokeWithArguments`**, in which case pure Clojure has
no fast path and the choice is bytecode or C. *(Result below: the proxy is 63×
better, and both other predictions were too optimistic by 2.5× or more.)*

## Measured 2026-07-30 — and the prediction was wrong in the direction that matters

`bb spike-overhead`, median of 21 runs, Apple M4 Pro, pinned toolchain:

| | ns per call |
|---|---|
| `invokeWithArguments`, trivial native call | **396** |
| **interface proxy**, same call | **7.7** |
| component call `add(s32,s32) -> s32` + `post_return` | **2880** |

**1. Pure Clojure has a fast path, and it is 51× the reflective one.**
`MethodHandleProxies/asInterfaceInstance` behind a `definterface` gives a
static call site at **7.7 ns**, against 396 for `invokeWithArguments`. The
argument varies with the loop index, so neither path is a folded constant —
with a fixed argument the proxy measured 6.2 ns, and the difference is the
check working. So
constraint (1) above does **not** force bytecode generation or a C shim, which
is what it looked like before anyone measured. `cljwit.host` can be pure
Clojure.

**2. But that is not where the time goes.** A scalar component call costs
**microseconds** — the prediction said 200–1000 ns — so the boxing is a modest
fraction of it. The exact fraction is not established: see the decomposition
below, which found the measurement unstable. Removing the boxing entirely buys a sixth. The dominant cost is
wasmtime's component call itself.

**3. And that cost is structural, not a mistake in the spike.** Omitting
`post_return` was the obvious suspect; adding it makes the number *worse* by
roughly one more `invokeWithArguments`, so the call was never missing it. The
real reason is that **the C API offers only the dynamic path**:
`wasmtime_component_func_call` takes `wasmtime_component_val_t*` and there is
no typed equivalent — the core-module API has `wasmtime_func_call_unchecked`,
the component API has nothing like it. Checked against the pinned headers.

**4. Against B6, this inverts the emphasis.** B6 measured a 4 KB aggregate
crossing at 339 ns and flagged that it had not isolated a per-call constant.
That constant is ~2.5 µs. **For any payload under roughly 30 KB, the call
dominates the copy** — so the boundary's cost is mostly *per call*, not per
byte, and an API that encourages many small calls will hurt far more than one
that moves large values.

**Where the 2.5 µs lives — prediction recorded 2026-07-30, before the run.**
"Is 2.5 µs acceptable" reads as a product question, but it decomposes into an
engineering one: the same C API can call a **core module** three ways, so the
component figure can be split against them without changing host, library or
machine.

| | what it isolates |
|---|---|
| core `wasmtime_func_call_unchecked` | the raw call — no `Val` marshalling |
| core `wasmtime_func_call` | + wasmtime's dynamic `Val` convention |
| component `wasmtime_component_func_call` | + the Component Model's canonical ABI |

Predicted: unchecked **~50–100 ns**, core dynamic **~300–600 ns**, so the
Component Model itself accounts for **~2.3 µs of the 2.9** and the lever is
neither our binding nor dynamic calling. **Falsified if core dynamic is itself
~2.5 µs**, in which case the cost is wasmtime's `Val` convention and a typed
path — which the component API lacks but the core API has — would be the whole
answer.

### The decomposition ran and is not trustworthy. Recorded rather than quoted.

Three runs, same command, same machine, minutes apart:

| ns/call | run 1 | run 2 | run 3 |
|---|---|---|---|
| core `call_unchecked` | 4858 | 8760 | 5280 |
| core `func_call` | 1990 | 3562 | 2141 |
| `invokeWithArguments`, trivial | 398 | 768 | 421 |
| **interface proxy, trivial** | **19.4** | **7.3** | **7.3** |
| component call | 2841 | 4874 | 3046 |

**Two things are wrong with it and one thing is solid.**

*Wrong:* `call_unchecked` — wasmtime's raw, no-marshalling path — comes in
**2.4× slower than the checked one, reproducibly in all three runs**. A fast
path cannot be slower than the path it optimises, so the spike is using it
incorrectly even though it returns the right answer. Until that is explained
the arm is meaningless, and with it the decomposition.

*Also wrong:* everything routed through `invokeWithArguments` swings **~1.8×
run to run** — 398/768/421 on the trivial call, and the wasm calls move with
it. A number that cannot be reproduced is worse than no number, so **the
prediction above is neither confirmed nor falsified**, and the 2.9 µs figure in
the table further up should be read as "microseconds, order of magnitude" and
not as 2.9.

*Solid:* **the interface proxy is 7.3 ns in two runs out of three and never
moves with the rest.** So the case for it is stronger than speed alone — the
reflective path is not just ~50× slower, it is *unpredictable*, which is what
per-call allocation looks like. That is the one conclusion this run supports.

**Redone properly, prediction recorded 2026-07-30.** The hot loop now calls
through an interface proxy rather than `invokeWithArguments`, every error
return is checked, and each path runs in its own JVM. `call_unchecked` is
dropped: it was used incorrectly and an arm nobody can explain does not belong
in a table. Predicted: the numbers **stop swinging** (the proxy was the only
stable thing in the old run), and they come in **lower**, because ~400 ns of
per-call boxing leaves the loop. Component and core-dynamic should keep roughly
their old *ratio* — that part was consistent across all three runs at ~1.4× —
so the Component Model adds tens of percent, not multiples. **Falsified if the
proxy-bound numbers still swing**, which would mean the instability was never
the binding.

**The methodological fault was the spike's own.** It measured every path in one
JVM, through the mechanism that is both the slowest and the least stable, so it
contaminated its own numbers.

### Redone, and now quotable — `bb spike-cost`, 2026-07-30

Proxy-bound hot loop, every error return checked, one JVM per path. Two runs,
median of 21 each:

| | run 1 | run 2 | spread within a run |
|---|---|---|---|
| trivial native call, proxy | 7.5 | 7.1 | — |
| **core module call** | **1575.4** | **1573.6** | 1.03× / 1.01× |
| **component call** | **1929.9** | **1926.1** | 1.05× / 1.03× |

The prediction holds on both halves that mattered: the swinging stopped —
within-run spread fell from ~1.8× to **1.01–1.05×**, and the two runs agree to
**0.2%** — and the numbers came in lower. The ratio prediction was close but
wrong: **1.23×, not ~1.4×**.

**So the cost is neither the binding nor the Component Model.**

| | ns | share |
|---|---|---|
| the JVM→native binding | **7** | 0.4% |
| the Component Model over a core call | **355** | 18% |
| **wasmtime's core call itself** | **1567** | **81%** |

An interface proxy makes the binding free — 7 ns against a 1.9 µs call — which
settles the calling-convention question outright: **`cljwit.host` is pure
Clojure and the binding is not worth optimising further.** And the Component
Model's 23% is real but modest; `0007`'s canonical-ABI machinery is not what is
expensive here.

**What is expensive is one call into wasmtime, at ~1.57 µs**, which is far more
than a wasm call should cost. The likely reason is that this is the *checked*
path: `wasmtime_func_call` validates signatures and marshals `wasmtime_val_t`
on every call. The core C API has `wasmtime_func_call_unchecked` for exactly
that, and **the component API has no equivalent** — so if the 1.57 µs is the
checked path's price, ~1.9 µs is the floor for component calls through this C
API, and the lever is the Rust API's typed component calls behind a shim.

That makes the next question concrete rather than a matter of preference:
**can `call_unchecked` be driven correctly, and does it collapse the 1.57 µs?**

The header settles the first half — *"This function is faster than that
function"* — and the contract is exactly what the old spike did: a
`wasmtime_val_raw_t[]` with arguments from index 0, results overwriting them,
length `max(nargs, nresults)`. So the old "2.4× slower" was contamination, not
misuse, and it only needed the clean harness.

**Prediction, 2026-07-30, before the run.** `call_unchecked` lands at
**1.2–1.4 µs** — a 10–25% improvement — because the bulk of a host-to-wasm call
is store entry, the trampoline and stack-limit setup rather than `Val`
marshalling. **Falsified if it drops below ~500 ns**, which would mean
marshalling was most of the 1.57 µs and a typed component path is the whole
answer rather than a modest one.

### The answer is no, and it contradicts the documentation

| ns/call | run 1 | run 2 | spread within a run |
|---|---|---|---|
| core call, **checked** | 1561.5 | 1681.7 | 1.01× / 1.02× |
| core call, **unchecked** | **4732.8** | **4690.5** | 1.02× / 1.02× |
| component call | 1907.5 | 2012.9 | 1.03× / 1.01× |

**`call_unchecked` is 3× slower than the checked path**, reproducibly, in the
clean harness — 1.02× within-run spread and 1% between runs, so this is not the
contamination that made the first attempt untrustworthy. The result survived
the fix that was supposed to explain it away.

The header says the opposite in as many words: it describes the unchecked path
as the faster one and the checked one as the safe default. The usage matches
the documented contract — `wasmtime_val_raw_t[]`, arguments from index 0,
results overwriting them, length `max(nargs, nresults)` — and the call returns
the right answer every time.

**Recorded as an open contradiction between documentation and measurement, not
explained.** Either the C API's unchecked path carries a cost the header does
not describe, or there is a precondition it does not state. Reading
`crates/c-api` in the wasmtime source (`bb ref wasmtime`) is the next step and
has not been taken.

**What it settles regardless:** `call_unchecked` is not the lever. The checked
path at ~1.6 µs is the fastest thing this C API offers, the component path is
~1.9 µs, and **the ~355 ns the Component Model adds remains the only part of
the cost this project could remove by changing its own choices.** If ~1.9 µs is
too slow for what `cljwit.host` is for, the lever is outside the C API
entirely — the Rust API's typed calls behind a shim.

The prediction was wrong, and not in the direction its own falsifier named: it
predicted an improvement and got a 3× regression.

## Why this shape

The alternative was to design `cljwit.host`'s API first and discover these while
implementing it. All four are the kind of constraint that changes an API rather
than an implementation — (1) especially, which decides whether the library can
be pure Clojure at all — so finding them costs less now than after there is
something to rewrite.

## Alternatives rejected

- **Design the API first, spike later.** Rejected above.
- **Bind through JNI or a small C shim** instead of FFM. Not rejected on
  evidence — untried. It would sidestep (1) and (2) by moving the exact call
  sites into C, at the cost of a build step and a per-platform artifact, which
  is exactly what FFM was supposed to remove. Worth measuring against the
  bytecode-emitting option when (1) is decided.
- **Wait for a pure-JVM runtime.** [Endive] targets the Component Model on the
  JVM with no native dependency but does not have it yet (`0005`). Waiting on
  someone else's schedule is what `0007` rejected; watch it, do not plan on it.
- **Commit the built component.** Rejected: it is a build output, and building
  it inside `bb spike-host` also checks that the toolchain still produces what
  the spike expects.

## What would falsify this

- `bb spike-host` failing on a fresh clone inside `nix develop`, which is how
  it is meant to be run and the only way it is claimed to work.
- The boxing in (1) turning out to cost little enough that the bytecode
  question is moot — that is a measurement nobody has taken, and it should be
  taken before the API hardens.
- x86_64 Linux behaving differently. Untried; the struct layouts in particular
  are platform ABI facts and were measured on arm64 only.

[Endive]: https://bytecodealliance.org/articles/endive-and-the-next-chapter-of-webassembly-on-the-jvm

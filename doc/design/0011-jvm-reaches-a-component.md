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

This is the largest open design question for `cljwit.host`. A host that makes
one call per user call pays that boxing on every crossing, on top of B6's
~10× copy cost for aggregates. The way out is emitting bytecode with a static
call site rather than reflecting, which is a real piece of work and should be
decided with a measurement rather than by taste.

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

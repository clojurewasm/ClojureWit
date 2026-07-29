# 0017 — Host imports: letting a component call Clojure

**Status:** proposed · 2026-07-30 · rewritten after an adversarial review ran
every claim. Four of the first draft's five decisions changed, one of them
because the draft would have aborted the JVM.

## The question

`cljwit.host` can call a component. It cannot yet be called *by* one, so it
refuses every component that imports anything — including all of WASI.

What is settled, measured 2026-07-30:

- **An FFM upcall stub reaches Clojure from inside a component call.** `bb
  spike-import`: `run(20) = 41`. Pure Clojure, bound to a `reify`.
- **Imports reflect exactly like exports**, through
  `wasmtime_component_type_import_count`/`_nth`.
- **A host callback cannot re-enter the executing instance**: `wasm trap:
  cannot enter component instance`. `0014` D and E survive.
- **`wasmtime_error_new` exists**, so a Clojure exception has somewhere to go.

## The decision

### A. Imports are supplied at `instantiate`, keyed by the same names as exports

```clojure
(host/instantiate art {:imports {"local:imp/host@0.1.0#twice" (fn [v] (* 2 v))}})
```

The identity is the exact WIT name, `iface#func`, as `0014` B decided. One
naming rule for both directions.

**The stubs should not be per-instance, even though the functions are.**
`Linker.upcallStub` measures **~7 µs warm and 30–160 µs cold**, against a 20 µs
instantiate; a fresh closure per request means a fresh stub and a fresh linker
per request. The shape that avoids it — one stub per import *name*, cached on
the artifact, dispatching through the store's data pointer
(`wasmtime_store_new(engine, data, finalizer)` / `wasmtime_context_get_data`,
both present in 47.0.1) to the per-instance Clojure function — keeps A's
per-request state and makes stubs and linker engine-scoped. **Unimplemented and
unverified**; recorded because the naive shape has a measured cost.

### B. The host checks for *extra* imports, and lets wasmtime report missing ones

An import supplied but not needed throws at `instantiate`, naming it. A typo
silently ignored is the `nil`-on-typo failure `0014` rejected.

**Missing imports are wasmtime's to report.** The first draft claimed its
message was `function implementation is missing` "with no name". That was
false — it quotes only the innermost line of a chain `ok!` already reads:

```
linker_instantiate: component imports instance `local:imp/host@0.1.0`,
  but a matching implementation was not found in the linker
Caused by:
    0: instance export `twice` has the wrong type
    1: function implementation is missing
```

Interface and function are both named. And a host-side check is not merely
redundant, it is **unimplementable** alongside E: reflection reports WASI
imports identically to user imports, so with WASI on, every WASI import would
look missing, and wasmtime does not expose the set `add_wasip2` defines. A
`wasi:` prefix rule is not a substitute — a component can import
`wasi:notreal/thing@0.2.0`, which passes any prefix rule and fails anyway.

### C. Marshalling is **not** the export direction run backwards

An import's arguments are lifted the same way — the argument buffer is
wasmtime's and is invalid after the call, exactly as `0014` E found for
results, so eager lifting carries over.

**The result side does not.** wasmtime takes ownership of what the callback
writes and **frees it**. Measured: returning a `malloc`'d buffer works;
returning a pointer into static storage aborts with
`POINTER_BEING_FREED_WAS_NOT_ALLOCATED` inside
`wasmtime_component_linker_instance_add_func`'s closure.

**Confirmed here, and the shape of the failure is the finding.** With an Arena
pointer, *one call succeeds*; 2000 abort the process (`rc=134`). A test that
called once would have licensed the corruption. `host.clj`'s `lower-fn`
allocates **every** payload from a `java.lang.foreign.Arena` — string bytes, list/tuple/record element buffers,
flags name vectors, and the boxed vals behind `option`/`result`/`variant`.
Handing wasmtime an Arena pointer to `free()` is heap corruption, so `lower-fn`
needs an allocator parameter rather than a hard-wired arena.

**Amended after implementing it: `wasmtime_component_val_new` is not needed.**
This note said a boxed payload — `option`, `result`, `variant` — required it.
Measured: with `identity` in its place, 2000 calls survive, the same threshold
that catches an Arena pointer. The header's purpose for `val_new` is moving an
*embedder-owned* val onto wasmtime's heap; once the byte allocator is `malloc`
the val is already there, so calling it would allocate twice and free once. An
RSS comparison over 300k calls could not separate the two — JVM heap noise
dominates — so the argument is the header's wording, not a measurement.

This inverts `0014` E as well: there the host must never `val_delete` a result;
here the host must never *retain* one.

### D. A Clojure exception becomes a `wasmtime_error_t *` — and kills the instance

The callback catches `Throwable`, converts with `wasmtime_error_new`, returns
it. A JVM exception unwinding through native frames is fatal: measured, it
prints the Clojure stack trace and then `Unrecoverable uncaught exception
encountered. The VM will now exit`.

**But the designed path is not an ordinary error.** Measured: the message does
reach the caller, and then the store is poisoned — the next call gets `wasm
trap: cannot enter component instance` without entering the callback, and a
*new instance in the same store* fails the same way.

So `cljwit.host` must **mark the instance dead** when an import throws, and say
so. Otherwise a user sees two `ex-info`s that look alike — a WIT `result` err,
which leaves the instance usable, and a host-import throw, which does not — and
every later call reports a trap with the cause gone. `0016` recorded exactly
that failure shape.

The `catch` handler must also be incapable of throwing: `(ex-message e)` can be
`nil`, and the C string for `wasmtime_error_new` needs an arena alive inside
the callback.

### E. WASI is a config, not a flag

```clojure
(host/instantiate art {:wasi {}})     ;; deny-by-default
```

`add_wasip2` alone **aborts the process**. Measured: it succeeds, `instantiate`
succeeds, and the first WASI call panics in
`crates/c-api/src/store.rs:105` — `called Option::unwrap() on a None value` —
then `fatal runtime error: failed to initiate panic, aborting`. Adding
`wasmtime_context_set_wasi(cx, wasi_config_new())` makes the same binary print
a number.

So `:wasi` must carry a `wasi_config_t`, which is a design surface of its own —
argv, env, stdio, preopens, `inherit_network`, `allow_ip_name_lookup`. **The
default is a security decision**: `inherit_env` would leak the host
environment into the guest. Deny-by-default, and every capability named
explicitly.

The config is consumed by `set_wasi` even on error, so it is per-instantiate,
not per-artifact. `add_wasip2` costs **0.033–0.037 ms**, against `0014` C's
0.02 ms instantiate — so turning WASI on nearly triples instantiation.

### F. Resource imports are inside this unit, not after it

A host interface that hands out a handle — which is every one of `wasi:io`'s —
declares a `resource` item that a map keyed `iface#func` cannot express.
Measured: `instantiate` fails with `instance export 'token' has the wrong type
/ resource implementation is missing`. It needs
`wasmtime_component_linker_instance_add_resource` with a destructor callback (a
second upcall shape) and `wasmtime_component_resource_host_new` to mint
handles.

The first draft listed this as a falsifier. It is not one waiting to fire.

The shapes, read from the pinned headers so the next unit starts on them:

```c
wasmtime_component_resource_type_new_host(uint32_t ty);
wasmtime_component_linker_instance_add_resource(
    linker_instance, name, name_len, resource_type,
    void (*destructor)(void *, wasmtime_context_t *, uint32_t rep),
    void *data, void (*finalizer)(void *));
wasmtime_component_resource_host_new(bool owned, uint32_t rep, uint32_t ty);
```

So a host resource is a `u32` type tag, a destructor upcall of a *second*
shape, and handles minted per call. `resource_host_to_any` is how one becomes
a val — and it panics outside a call scope, which is the landmine already
recorded above.

**Tried, 2026-07-30, and it does not work yet.**
`dev/cljwit/spike/host_resource.clj` drives
`dev/resources/hres.{wit,wat}` — a guest that mints a host resource, reads it
through a `borrow`, and drops it. Every individual piece succeeds:
`add_resource`, both `add_func`s and `instantiate`; `mint` with
`host_to_any` returning no error; `peek` with `any_to_host` returning the right
rep; and the destructor upcall firing with that rep. **The crash is inside
`wasmtime_component_func_call` itself, after the destructor and before it
returns**, in `error::source`.

Leading suspect: ownership of the `any` that `host_to_any` produces. The spike
writes it into the result val *and* deletes the `host_t` it came from, and
nothing has established which of those wasmtime expects. The spike is not a
`bb` task, because a task that aborts the JVM is worse than none.

## Alternatives rejected

- **Imports on the artifact, sharing one set of host functions.** Wrong the
  moment two requests want different state. But see A: the *stubs* can be
  artifact-scoped without the *state* being, through the store data pointer,
  and the first draft rejected artifact scope on the assumption that they
  could not.
- **A separate `link` step.** A fourth lifetime for something with no
  independent lifetime.
- **Letting a missing import fail lazily**, at the first call. Moves the error
  away from the mistake — and is now moot, since wasmtime reports it at
  instantiate with both names.
- **A host-side missing-import check.** The first draft's B. Removed: its
  stated justification was false, and it cannot coexist with E.
- **`{:wasi true}`.** The first draft's E. It aborts the process.
- **Passing the raw `wasmtime_component_val_t` to the Clojure function.**
  Fastest, and it makes every import a chance to corrupt memory.

## What would falsify this

- **The store-data-pointer shape in A.** Unimplemented. If the data pointer is
  already spoken for, or dispatch through it costs more than the 7 µs it saves,
  A stays naive.
- **Marking the instance dead being wrong** if some import errors turn out not
  to poison the store. Measured for one shape; not for a trap raised inside the
  callback versus an error returned from it.
- **The `malloc` requirement in C.** Measured through one string result. Lists,
  records and nested vals are assumed to follow; `wasmtime_component_val_new`'s
  "the val provided is taken" wording says they should.
- **A deny-by-default WASI config being unusable** — if the common case needs
  five capabilities named every time, the shape is wrong even if the default is
  right.
- **The upcall cost.** ~7 µs warm is measured; per-call overhead is not, and
  `0013`'s discipline applies before anyone optimises it.

## What the first draft got wrong

Recorded as `0012`, `0013` and `0016` were.

- **E would have aborted the JVM** on the first WASI call, in a shape the note
  presented as the convenient one.
- **B's evidence was false**, and one command contradicted it. Quoting the
  innermost line of an error chain the library already prints in full is the
  same shape as `0007`.
- **C was asserted symmetric because both directions use the same word.**
  Ownership runs the opposite way, and the existing `lower-fn` would have
  handed wasmtime Arena pointers to `free`.
- **F was filed as a falsifier** rather than as work, which would have made
  every `wasi:io`-shaped interface fail after the design was declared done.

The pattern: four of the five rest on what an API *looks like it should do*.
The review ran them.

## Resources

- `dev/resources/imp.{wit,wat}` and `bb spike-import` — the mechanism.
- `0014` — the naming rule, lifetimes and concurrency check this inherits.
- `0016` — the other direction, and the poisoning failure shape D must avoid.

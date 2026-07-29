# 0018 — Host-defined resources in `cljwit.host`

**Status:** proposed · 2026-07-30 · the mechanism is `bb spike-hres`; this is
the API over it, and no code exists

## The question

`0017` A keys imports by `iface#func`. A **resource is a type, not a
function**, so that map has nowhere to put one — and every `wasi:io` interface
declares one, so this is not an edge case.

What is settled, measured 2026-07-30:

- **The round trip works** (`bb spike-hres`): a guest mints a host resource,
  reads it through a `borrow`, and drops it; the host's destructor fires with
  the right rep.
- **A host resource is a `u32` type tag plus a destructor upcall**, and the
  destructor returns `wasmtime_error_t *`, not `void`. Getting that wrong
  crashes the JVM *after* the callback has run correctly.
- **A handle carries only a `u32` rep.** `resource_host_new(owned, rep, ty)`
  mints one; `any_to_host` + `host_rep` reads it back. There is nowhere to put
  a Clojure value, so the host must keep the mapping.
- **The marshaller can tell a host resource from a guest one by type.** A
  valtype's union carries `own`/`borrow` → `resource_type_t *`, and
  `resource_type_equal` compares it against the types the host registered.

## The decision

### A. Resources are declared in their own map, keyed by the same WIT name

```clojure
(host/instantiate
  art {:resources {"local:hres/host@0.1.0#token" {:drop (fn [v] (close! v))}}
       :imports   {"local:hres/host@0.1.0#mint" (fn [v] (open-thing v))
                   "local:hres/host@0.1.0#peek" (fn [t] (:n t))}})
```

The identity stays the exact WIT name (`0014` B, `0017` A). What changes is
that a resource is a different *kind* of thing, so it gets a different key in
the options map rather than a different value shape under the same one.

### B. A host resource is any Clojure value; the host owns the rep table

The import that returns one returns an ordinary value. `cljwit.host` assigns it
a `u32` rep, keeps `rep → value` for the instance, and mints the handle. A
`borrow` parameter of that type arrives as the value, not as a rep or a handle.

The guest's drop removes the entry and calls `:drop` with the value, if given.

### C. The table is per-instance and dies with it

Reps are only meaningful inside one store. The table lives beside the handle
registry `0016` D already keeps, and `Instance/close` clears it — after
closing outstanding guest handles, before deleting the store.

### D. `:drop` may not throw

It runs inside wasmtime's destructor trampoline, which is the same place `0017`
D's rule applies: a JVM exception unwinding through native frames exits the VM.
Anything thrown is caught and converted, and the instance is marked dead, as an
import's exception already is.

## Why

**A, because a resource is not a function and pretending otherwise costs
later.** `0017` F recorded the shape; the alternative — one map whose values
are sometimes functions and sometimes maps — makes a typo in the value shape
into a silent misregistration.

**B, because the rep is a `u32` and nothing else fits through it.** Any design
where the Clojure value crosses the boundary is impossible; the only question
is who keeps the table, and the host is the only party that sees both sides.

**D, because the failure is the worst one available**, and it is the same
failure `0017` D already pays a `try` to prevent.

## Alternatives rejected

- **One `:imports` map with a value shape that says "resource".** See A.
- **Exposing the rep to Clojure.** Smallest, and it makes a capability an
  integer anyone can forge — the same argument `0016` A made against integer
  handles for guest resources.
- **A global rep table.** Reps are store-scoped; a global one would collide
  between instances and leak.
- **Letting `:drop` propagate.** It cannot: the frame it would unwind through
  is native.
- **Requiring the user to mint handles explicitly**, with a `host/resource`
  constructor called inside the import. Honest about what happens, and it
  makes every `wasi:io`-shaped import carry ceremony that the type already
  determines.

## What would falsify this

- **A resource type used by two instances of the same artifact.** The type is
  registered on the *linker*, which `0014` C creates per instantiate, so each
  instance has its own — unverified, and if linkers turn out shareable the
  table's scope is wrong.
- **`borrow` parameters of a host type arriving as something other than a
  handle to a live rep** — the spike read one, but only inside the call that
  minted it. A borrow held across two guest calls is untested.
- **The type comparison being too coarse.** `resource_type_equal` is the only
  discriminator; if two distinct host types compare equal, values would be
  handed to the wrong `:drop`.
- **`own` parameters** — a guest handing a host resource *back*. The spike only
  covers the guest receiving one.
- **The cost.** A rep table lookup per borrow and a `swap!` per mint, on a call
  that `0013` prices at ~0.4 µs. Unmeasured.

## Resources

- `bb spike-hres` and `dev/resources/hres.{wit,wat}` — the mechanism.
- `0017` — the import API this extends, and the exception rule D inherits.
- `0016` — the guest-resource side, and the argument against integer handles.

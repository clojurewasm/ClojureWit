# 0018 — Host-defined resources in `cljwit.host`

**Status:** A and B implemented (`src/cljwit/host.clj`, one host resource type
per instance); C, D and E still `proposed` · 2026-07-30 · third version. Two adversarial rounds have
each falsified something load-bearing; what they found is kept below, because
it is most of the evidence.

## The question

`0017` A keys imports by `iface#func`. A **resource is a type, not a
function**, so that map has nowhere to put one — and every `wasi:io` interface
declares one.

`bb spike-hres` proves the mechanism. `0017` A settled the scope: the linker is
**per-instantiate**, so a resource type, its destructor stub and its rep table
all belong to one instance and the destructor's `data` pointer identifies it.

## The decision

### A. Resources are declared in their own map, keyed by **any** name of the type

```clojure
(host/instantiate
  art {:resources {"local:hres/host@0.1.0#token" {:drop (fn [v] (close! v))}}
       :imports   {"local:hres/host@0.1.0#mint" (fn [v] (open-thing v))
                   "local:hres/host@0.1.0#peek" (fn [t] (:n t))}})
```

A separate key because a resource is a different *kind* of thing; one map whose
values are sometimes functions and sometimes maps makes a typo in the value
shape a silent misregistration.

**One type can have many names, and the host clusters them.** A `use`d
resource appears once per interface that uses it, and an alias
(`type headers = fields`) appears again — a world importing
`wasi:http/types@0.3.0` and `wasi:http/handler@0.3.0` yields **ten resource
items for five types**. Reflection cannot tell a declaration from an alias, but
it can tell they are the same type: **two *reflected* items compare equal**
under `wasmtime_component_resource_type_equal`. Verified on a component where
one interface `use`s another's resource:

```
2 resource item(s)
eq(local:u2/types@0.1.0#token , local:u2/a@0.1.0#token) = 1
```

So `cljwit.host` clusters the reflected items by that equality, gives each
class **one `u32` tag and one `:drop`**, and registers it under every name in
the class. The user names any one of them; naming two is an error the host
reports, because there is one destructor slot and two `:drop`s cannot both
have it.

This is not fastidiousness. Registering two names of one type under *distinct*
tags makes `instantiate` fail with a message naming neither.

### B. A host resource is any Clojure value; identity is the rep, not the value

The import returning one returns an ordinary value. `cljwit.host` assigns a
`u32` rep, stores `rep → [value]` — a one-element vector, so a legitimately
`nil` value is distinguishable from a missing rep — and mints the handle.

`:drop` is called **per handle, not per value**. Two `mint` calls returning the
same object are two resources; the guest asked for two and will drop two.

### C. Lifting an `own` parameter transfers ownership out of the table

A guest handing a resource back — `eat: func(t: token) -> u32` — gives the host
ownership, and **there is no guest drop afterwards, ever**. Measured on the
first draft: `dtor calls=0` with the entry outstanding.

So lifting an `own` **removes the entry and hands the caller something
closeable** — the value wrapped in an `AutoCloseable` that carries the
`:drop` — rather than a bare value with no stated obligation. `0016` A decided
exactly this for the other direction, and for the same reason: a transferred
capability is not its bytes. Handing `(fn [t] (:n t))` a raw value silently
leaks the fd, and `wasi:http/handler.handle: func(request: request)` takes an
`own` in precisely that position.

Lifting a `borrow` leaves the entry in place and hands over the value. The
distinction is static — `own` is valtype kind 21, `borrow` 22 — so the
marshaller knows without asking.

**The host must do nothing else with a received `own`.** Measured:
`resource_any_drop` on it returns `unknown handle index` (wasmtime already
removed it at lower time) and `resource_any_delete` on it **aborts the
process**. Only the `resource_host_t *` from `any_to_host` is ours to
`host_delete`.

`wasmtime/component/val.h` is explicit that this is ours to define: *"host
resources do not have a 'drop' operation. It's up to the host to define what it
means to drop an owned resource."*

### D. `Instance/close` drains the table **after** the handle walk, to a fixed point

`wasmtime_store_delete` calls no host destructors — measured — so closing must
call `:drop` itself. But it is **two walks in a fixed order**, not one step:

1. `0016` D's walk, dropping outstanding *guest* handles.
2. Then the resource table, **repeatedly until it stops growing**.

Because a guest destructor can call host imports, and can **mint a new host
resource while the instance is closing** — measured: a host `mint` ran with
`during_close=1` and produced a fresh rep. A snapshot taken before step 1 never
drops it, which is the leak D exists to prevent.

A `:drop` that throws here is collected and rethrown after teardown completes.
This is a *different call site* from E's — host-initiated, not a guest
destructor upcall — which is why the two rules differ.

### E. A `:drop` that throws during a *guest* drop does not kill the instance

It is caught, the destructor returns `NULL` so the guest's drop succeeds, and
the failure is **recorded on the instance and readable** — `host/drop-failures`
returns them, and `close` rethrows the first if any remain unread. "Recorded"
with no reader would re-create the silent failure `0016` exists to eliminate:
the guest proceeds as though the resource were released while the host's file
is still open and nobody is told.

Converting it to a `wasmtime_error_t *` also works — measured, the message
reaches the caller — but it **poisons the store**, so one failed `close!` on
one file would destroy an otherwise healthy component at a point the guest did
not ask to fail.

This is where `0018` departs from `0017` D, and deliberately: an import that
throws has failed the call the guest made, and killing the instance is
proportionate. A destructor that throws has failed a cleanup the guest merely
permitted.

## Alternatives rejected

- **One `:imports` map with a value shape that says "resource".** See A.
- **Requiring the user to name every alias of a type.** `wasi:http` would make
  that ten keys for five types, and registering two of them under distinct tags
  breaks instantiation outright.
- **Registering only the name the user wrote.** wasmtime wants every name of
  the class, under one tag; leaving one out fails with a message naming the
  interface, not the omission.
- **Exposing the rep to Clojure.** Makes a capability an integer anyone can
  forge — `0016` A's argument against integer handles for guest resources.
- **Value-as-identity.** Two mints of one object would be one resource, and the
  second guest drop would find nothing.
- **Clearing the table at close.** Measured to leak every survivor.
- **Converting a `:drop` failure to a wasmtime error.** See E.
- **Engine-scoped resource types and stubs.** `0017` A, measured: 1.3× on the
  linker, +16 µs per import on a 57 µs instantiate, against routing every
  destructor through a store data pointer.

## What would falsify this

- **A component whose reflected items cluster wrongly** — `resource_type_equal`
  is the whole of A, and if two genuinely distinct types ever compare equal,
  values reach the wrong `:drop`.
- **The rep table's cost.** A `swap!` per mint and a lookup per borrow, on a
  call `0013` prices at ~0.4 µs. Unmeasured, and cheap only if the table stays
  small.
- **Whether one `:drop` per type is what users want** when a type has several
  names with different meanings — `headers` and `trailers` are the same type in
  `wasi:http` and are not the same idea.
- **The upcall stub count.** One per resource type per instantiate, on top of
  one per import — the falsifier `0017` A named, reached sooner here.

**Nested `own` is the ordinary case, not a falsifier.** Minting inside a
`list<token>` and an `option<token>` both work, and the transfer rule composes
elementwise — measured. `wasi:http`'s `request.new` takes
`option<request-options>`, so this is the domain's normal shape.

**An invariant worth stating rather than relying on:**
`wasmtime_component_func_call` deletes whatever is in the result val *before*
writing it, so an uninitialised out-slot is a segfault — and only on the
success path, because a trapping call returns first. `host.clj` is safe today
because it allocates from a zeroed `Arena`. That is an accident until it is
written down.

## What the first two drafts got wrong

Recorded rather than silently fixed.

- **The first draft called a discriminator settled that does not exist.**
  Nothing reflected compares equal to `resource_type_new_host(ty)` — verified,
  both compare 0.
- **The second draft then concluded the wrong thing from that.** Reflected
  items compare equal *to each other*, which is the mechanism A now uses; the
  comparison that fails is reflected-against-host-registered, and only that
  one. Recording "the discriminator does not exist" was a second error on top
  of the first.
- **The second draft's uniqueness claim was false.** "Only the imported one
  appears in the import list, so exactly one key is legal" — a `use`d resource
  appears once per interface that uses it, and `wasi:http` yields ten items for
  five types.
- **It had no rule for an `own` parameter**, which leaks an OS handle per
  transfer for exactly the interfaces this note exists to serve.
- **It said the table "dies with" the instance**, when `store_delete` runs no
  destructors.
- **Its `:drop` alternatives listed only one that is impossible** — letting the
  exception propagate — and missed the one that matters.

Two of its falsifiers were deleted for not firing: a `borrow` held across guest
calls resolves correctly, and distinct host tags are distinct.

## Resources

- `bb spike-hres` and `dev/resources/hres.{wit,wat}` — the mechanism.
- `0017` A — the linker scope this rests on, and the measurement that settled it.
- `0016` D — the close rule D mirrors.

# 0018 — Host-defined resources in `cljwit.host`

**Status:** deferred · 2026-07-30 · the first draft proposed an API; an
adversarial review falsified the fact it called settled and showed two of its
decisions turn on a question `0017` A has already argued should be reopened.
What the review established is recorded here, because it is most of the work.

## The question

`0017` A keys imports by `iface#func`. A **resource is a type, not a
function**, so that map has nowhere to put one — and every `wasi:io` interface
declares one.

`bb spike-hres` proves the mechanism: a guest mints a host resource, reads it
through a `borrow`, and drops it, and the destructor fires with the right rep.
The API over it is what is deferred.

## The decision

**Do not design this yet. Settle whether the linker is per-instantiate or
engine-scoped first** (`0017` A), because it determines the rep table's scope,
how a destructor knows which instance it is dropping for, and whether a store
data pointer is needed. Writing the API now means writing it against a shape
that has already been argued should change.

`wasmtime_component_linker_instance_add_resource` costs one upcall stub per
resource type per instantiate — 7 µs warm, 30–160 µs cold against `0014` C's
20 µs instantiate — so a handful of host resource types roughly triples
instantiation in the per-request shape `0014` C is built around. That is the
same question, not a separate one.

## What the review established, and what it cost the first draft

### The reflection-time discriminator does not exist

The draft called this settled: *"a valtype's union carries `own`/`borrow` →
`resource_type_t *`, and `resource_type_equal` compares it against the types
the host registered."* Measured, on this repo's own `hres` component:

```
resource item token       == resource_type_new_host(7)?  0
func mint result own      == resource_type_new_host(7)?  0
```

**Nothing reflected from a component ever compares equal to a host-registered
type.** What does compare equal is the component's own import items — a
`mint` result matches the interface's `token` item — which is a different
mechanism, and it maps names to types rather than types to tags. At *runtime*,
`resource_any_type(any)` does return the registered tag, but `0014` A builds
marshallers once from the reflected type; a per-value type comparison is a
different design with a per-value allocate/compare/delete cost.

Two traps in the same area, both measured:

- **Type identity is per-component-load, and the pointers are reused.**
  Distinct types printed the same `resource_type_t *` address while comparing
  unequal, and a second load of the same file produced a type unequal to the
  first load's. Anything keying a map by the pointer maps every host resource
  to whichever it read last.
- **A `use`d resource is one type under many names.** In a component where one
  interface `use`s another's resource, both names denote one type — and the
  same name can denote *two* types (the imported one and the exported one).
  `wasi:io/streams` `use`s `pollable` from `wasi:io/poll`, and
  `wasi:filesystem`, `wasi:sockets` and `wasi:http` all `use` the stream types,
  so keying `:resources` by "the exact WIT name" does not determine which key
  the user must write.

### An `own` parameter leaks, and the header says why

The draft's only removal rule was "the guest's drop removes the entry". When a
guest hands a host resource *back* — `eat: func(t: token) -> u32` — there is no
guest drop, ever. Measured: `dtor calls=0, table entries=1` after the call.

`wasmtime/component/val.h`: *"unlike `wasmtime_component_resource_any_t` host
resources do not have a 'drop' operation. It's up to the host to define what it
means to drop an owned resource and handle that appropriately."*

So for `wasi:io`-shaped interfaces — where the value is a file or a socket —
the draft would leak one OS handle per transfer. And it made the leak
undiagnosable: the Clojure function was to receive "the value" for both
`borrow` and `own`, so it could not tell it had just been handed ownership.
The information is there and static: `own` is valtype kind 21, `borrow` 22.

### `store_delete` runs no destructors

The draft said the table "dies with" the instance. Measured: deleting the store
leaves `dtor calls=0` with an entry outstanding. So every host resource the
guest still holds at teardown — the normal case for a component that trapped —
would be dropped on the floor. `Instance/close` must **run** `:drop` over the
survivors, which is the mirror of `0016` D that the draft cited without
copying.

### D survives, and its alternatives list was wrong

A destructor returning an error does reach the caller with its message and does
poison the store, exactly as `0017` D found for imports — so "mark the instance
dead" is right. But the draft's only rejected alternative was "letting `:drop`
propagate", which is impossible and therefore not an alternative. The real
choice is between converting to a wasmtime error — which destroys an otherwise
healthy instance because one `close!` failed — and catching, recording on the
instance, and returning `NULL`, so the guest's drop succeeds and the failure
surfaces where a user can see it.

### Two falsifiers deleted, because they do not fire

- **A `borrow` held across two guest calls works.** The guest stashed a handle
  and borrowed it on later calls; the rep resolved both times and the eventual
  drop fired correctly.
- **Distinct host types are distinct.** `resource_type_equal(host(7), host(8))`
  is 0, and a guest passing the wrong resource never reaches the host — wasmtime
  refuses with `handle index used with the wrong type`. (Its message says
  "guest-defined" for two host-defined resources, which is worth remembering
  next to `0016`'s lesson about trusting error text.)

### Value-as-identity is not injective

The draft made the Clojure value the resource. Two `mint` calls returning the
same object — a cached connection, an interned keyword, `nil` — get two reps
and one value, and two guest drops call `:drop` twice on it. `0016` A's own
argument against integer handles applies here and the draft did not answer it.

## What the next attempt has to decide

1. Linker scope, first (`0017` A) — it fixes the table's scope and the
   destructor's routing.
2. Removal on `own` transfer, since the guest will never drop it.
3. Running `:drop` at instance close rather than clearing.
4. How a resource type is named, given that `use` makes names many-to-one and
   one name ambiguous.
5. Whether identity is the value or a host-minted token.

## Resources

- `bb spike-hres` and `dev/resources/hres.{wit,wat}` — the mechanism.
- `0017` A — the linker question this waits on.
- `0016` D — the close rule this has to mirror.

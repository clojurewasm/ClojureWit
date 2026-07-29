# 0016 — `own<T>` handles, in both directions

**Status:** accepted · 2026-07-30 · A, B, C and D are implemented and tested
against a guest that really implements a resource · rewritten after an adversarial review built
the guest this note said it was missing. The file was `0016-resource-handles.md`
until that review showed `borrow` is not a question this note can ask.

## The question

`own<T>` is the last of `0012`'s rows that blocks a real component: two of
`dev/resources/zoo.wit`'s eight exports return one, and **4 of its 8 are
callable today**.

`borrow<T>` is *not* in scope, and not by choice — **a `borrow` cannot appear
in a return position at all**:

```
$ wasm-tools component embed --dummy   # lend: func() -> borrow<c>
error: function `lend` returns a type which contains a `borrow<T>`
       which is not supported
```

The check is structural, so `option<borrow<c>>` is refused too, and
`CanonicalABI.md` agrees: `lift_borrow` asserts a `Subtask` scope, which only
an *import* has. Since this note leaves host imports out of scope, no `borrow`
handle can reach Clojure. What *is* live is **lowering** into a `borrow`
parameter — `[method]counter.bump(self: borrow<counter>, by: u32)` — and that
is decided below.

## What is actually true, measured 2026-07-30

From the pinned wasmtime 47.0.1 headers and from a hand-written WAT guest that
really implements a resource with an observable destructor:

| | |
|---|---|
| in a val | `wasmtime_component_resource_any_t *`, kind 21 |
| own vs borrow | **static**, from the reflected type: `VALTYPE_OWN` 21, `VALTYPE_BORROW` 22. `host.clj` already prints `:result :own` and `:params [["self" :borrow] …]`. `resource_any_owned` is a runtime check for the same fact |
| `resource_any_clone(r)` | a new handle; must itself be `_delete`d |
| `resource_any_drop(ctx, r)` | the component-model drop. Takes a context, may run the guest destructor, returns an error |
| `resource_any_delete(r)` | host memory only, **once per handle**, required *whatever `drop` did* |

**Misusing a handle does not crash.** This note's first draft said resources
are "the only row where being wrong is a use-after-free that drops the JVM".
That is false for this API. Every misuse returns a recoverable
`wasmtime_error_t*`:

| misuse | wasmtime's answer |
|---|---|
| drop twice | `unknown handle index 1` |
| drop two clones of one resource | `unknown handle index 1` |
| drop after transferring the `own` to the guest | `unknown handle index 1` |
| drop a stale handle **whose table slot was reused** | `host-owned resource was already de-allocated` — and the new occupant survived |

That last row is the one that matters: wasmtime tracks handle *identity*, not
just the table index, so a stale close cannot silently destroy an unrelated
live resource. **The one genuine use-after-free is `drop` through a context
whose store has been deleted**, and that is what D below is for.

**Missing the clone leaks rather than dangles.** `0014` E's rule holds for
handles — with one reused result val, `func_call` deletes the previous handle
before writing — but it `delete`s without `drop`ping, so the destructor never
runs and the header warns the resource "will be leaked into the store and a
trap may be raised". The cost of getting this wrong is a leak, not a fault.

## The decision

### A. A handle is an opaque `AutoCloseable`, not Clojure data

It carries a cloned `resource_any_t *` and the instance it came from — and
**not** whether it is owned, because that is static in the compiled marshaller.

`0012` maps every other WIT type onto data that `pr-str` round-trips. A handle
cannot be: it is a capability, and a copy of its bytes is not a copy of it.

### B. `close` is `try { drop } finally { delete }`, and marks the handle closed either way

`delete` is required per handle regardless of what `drop` did — the header says
so and it is measured: `delete` survives after two drops, after a failed drop,
and after `val_delete`. Either CAS ordering alone is wrong, and both failure
modes are the bug already fixed once in `Instance/close`: mark first and a
throwing `drop` leaks the host memory forever; mark last and a retry re-drops.

A `drop` that errors throws, after the `delete`. Closing twice is a no-op.

### C. Lowering an `own` transfers ownership, and the handle becomes closed

`consume: func(c: counter) -> u32` takes the resource. After the call the guest
owns it; the host's handle is stale. Measured: dropping it afterwards gives
`unknown handle index 1`.

So lowering an `own` marks the handle closed — `delete`d, not `drop`ped — and

```clojure
(with-open [c ((i "…#[constructor]counter"))]
  ((i "…#consume") c))
```

exits cleanly instead of throwing over the body's value from `finally`.

**Lowering into a `borrow` parameter does not.** The handle stays live and
droppable; measured across two `bump` calls followed by a successful drop.
The distinction is static, so the marshaller knows which it is.

### D. The instance closes outstanding handles before deleting its store

`own` is not only a return type: `list<counter>`, `option<counter>`,
`record { a: counter }` and `result<counter, string>` are all legal and
`host.clj`'s `lift-fn` is compositional, so **one call can yield handles the
caller never destructured**. `with-open` has no shape for that.

Every handle lifted is registered with its instance. `Instance/close` closes
what is still open, in order, *before* `wasmtime_store_delete` — which is also
the only fix for the one real use-after-free, since a handle outliving its
store has a dangling context.

This is a rule about the *type*, not the return slot. `0012` A's own words:
"a type mapping first, and calling-convention sugar on top of it — never
instead of it."

## Alternatives rejected

- **A handle as an integer rep.** Makes every arithmetic operation a way to
  forge a capability.
- **`Cleaner`/finalizer-driven drop.** `drop` enters the store, so it would
  trip `0014` D at an arbitrary moment. Store deletion already bounds the leak.
- **Reference counting on the Clojure side.** The header already counts
  (`drop` per resource, `delete` per handle); a second scheme can only
  disagree with the first.
- **Leaving `own` unsupported and doing only borrow parameters.** Would leave
  every constructor unusable, which is every resource-shaped API.
- **Deciding `borrow` in return position.** The first draft did, at the length
  of a decision, a rationale and a falsifier. It cannot occur.
- **Trusting `with-open` alone for nested handles.** See D: the caller cannot
  close what it never saw.

## What would falsify this

- **Handle registration costing more than it saves.** D puts a registry write
  on every lift of an `own`. Unmeasured; a component returning handles in a hot
  loop is where it would show.
- **A guest that drops a resource the host still holds a handle to**, without
  the host having transferred it. Nothing in the ABI forbids the guest from
  doing this to a resource it owns; C only covers transfers the host makes.
- **`Instance/close` ordering being insufficient** if a handle is shared
  between two instances. Not possible today — a handle carries its instance —
  but linking two components would make it possible.
- **The clone-on-lift being unnecessary** if a future wasmtime stops recycling
  the result val. Measured true today, and the failure is a silent resource
  leak, so it needs re-checking on upgrade.

## What C cost, and what the error was really about

Lowering an `own` failed for a while with

```
call local:res/bag@0.1.0#consume: mismatched resource types
```

for a handle that came from *this instance's own* constructor and worked fine
as a `borrow`. The prediction recorded before looking was that the guest was at
fault — the host writes identical bytes for `own` and `borrow`, so it seemed to
have no way to differ. **That was wrong**, and reading the built component
showed why: `consume` takes `(own 8)` and `[constructor]counter` returns
`(own 8)`, the same exported type. The component was correct.

The fault was `transfer!` calling `resource_any_delete` **during lowering**.
`func_call` reads the pointer the argument val holds, so the host freed it
before wasmtime read it, and wasmtime reported a type mismatch — an error about
the wrong thing entirely. Deleting after the call fixes it; `0016` C now says
so explicitly, because "delete, never drop" was true and still not enough.

**The error text is what made this a bounded question.** Before `ok!` read
wasmtime's message, this was `call … failed`.

## Notes for whoever writes the guest

**It exists now:** `dev/resources/res.{wit,wat}`, exercised by
`test/cljwit/host_test.clj`. It has a constructor, a `borrow`-self method, an
`own` parameter that transfers, and a `list<counter>` result — one shape for
each decision above. `bb check` builds it from the committed `.wat`, so this
note no longer rests on headers alone for what a component *is*; it still does
for what marshalling one *does*, which is the next commit.

The `--dummy` output does not reveal this and it cost the review an hour: **a
`borrow` self parameter, lowered into the component that *defines* the
resource, arrives as the rep itself, not a handle index** — an explicit ABI
optimisation (`CanonicalABI.md:3088`). Calling `counter_rep` on it traps with
`unknown handle index 64` and then poisons the instance, so every later call
fails with `cannot enter component instance` and the original cause is gone.

Separately, `wasmtime_component_resource_host_to_any` outside a call scope does
not return an error — it panics: `BUG: no current scope`, at
`crates/wasmtime/src/runtime/vm/component/resources.rs:272`. That is a landmine
for host imports, which are out of scope here.

## Resources

- `dev/resources/zoo.wit` — four of its eight exports are resource methods, and
  four of eight are callable.
- `0012` — the mapping this is the last blocking row of.
- `0014` C, D and E — the lifetime, concurrency and result-validity rules this
  inherits.

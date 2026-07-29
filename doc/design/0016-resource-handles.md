# 0016 — Resource handles in Clojure

**Status:** proposed · 2026-07-30 · rests on the pinned headers and one
feasibility check; the artifact that would falsify it does not exist yet and is
named below

## The question

`own<T>` and `borrow<T>` are the last of `0012`'s rows with no mapping and no
implementation. They are also the only ones where being wrong is **a
use-after-free that drops the JVM** rather than a wrong answer, which is why
this is a note and not a commit.

Four of the eight exports in `dev/resources/zoo.wit` are resource methods.

## What is actually true, measured 2026-07-30

From the pinned wasmtime 47.0.1 headers:

| | |
|---|---|
| in a val | `wasmtime_component_resource_any_t *resource`, kind 21 |
| `resource_any_owned(r)` | `own` or `borrow` — the only way to tell |
| `resource_any_clone(r)` | a **new handle**; must itself be `_delete`d |
| `resource_any_drop(ctx, r)` | the component-model drop. **Takes a context**, so it is an entry into the store, and *"may invoke WebAssembly if it's a guest-owned resource with a destructor"*. Needed **once per logical resource** |
| `resource_any_delete(r)` | host-side memory only. **Once per handle**, and *"after `drop` is called it's still required"* |

So a handle has **two independent lifetimes** — the component-model resource
and the host-side wrapper — and they are released by different calls with
different cardinalities. Nothing in Clojure's vocabulary has that shape.

Two further constraints from work already landed:

- **`0014` E:** a result payload is invalid after the next call on that
  function. A handle arriving in a result must be `clone`d immediately or it is
  already garbage by the next call.
- **`0014` D:** `drop` takes a context, so it is subject to the
  non-concurrency check, exactly like a call and like `close`.

**A guest that really implements a resource is hand-writable.** Checked with
`wasm-tools component embed --dummy` on a one-resource WIT: the core module
imports `counter_new` / `counter_rep` / `counter_drop` from
`cm32p2|_ex_<iface>` and exports `[constructor]counter`,
`[method]counter.bump`, their `_post` variants, and `counter_dtor`. That is
fiddly but ordinary WAT, so resources can be tested here the same way every
other row was.

## The decision

### A. A handle is an opaque `AutoCloseable` object, not a Clojure value

It is not a map, a keyword, or a number. It carries a cloned
`resource_any_t *`, the instance it belongs to, and whether it is owned. It
prints as `#cljwit/resource[counter 0x…]` and supports nothing else.

`0012` maps every other WIT type onto data that `pr-str` round-trips. A handle
cannot be one: it is a capability, and a copy of its bytes is not a copy of it.

### B. `own` is closed by the user; closing is `drop` then `delete`

```clojure
(with-open [c ((i "…#[constructor]counter") 0)]
  ((i "…#[method]counter.bump") c 5))
```

`close` performs `resource_any_drop` (through the instance's non-concurrency
check) and then `resource_any_delete`, in that order, because the header
requires both and requires that order. Closing twice is a no-op. Using a closed
handle throws.

### C. A `borrow` lifted from a result is closed when the call returns

A borrow is valid for the duration of the call that produced it. Rather than
hand out something that becomes unsafe at an invisible moment, a lifted borrow
is **already closed** by the time the caller sees it, and using it throws with
a message saying so.

### D. The host never constructs a guest resource

`resource_host_new` exists for host-*defined* resources, which is the import
side and out of scope until imports exist. The only handles the host holds are
ones a guest gave it.

## Why

**A, because the alternative silently breaks `0012`'s only universal
promise** — that a lifted value is ordinary Clojure data. If a handle were a
map, `(pr-str h)` then `read-string` would produce something that looks like a
handle and points at freed memory.

**B, because the two-level lifetime cannot be hidden.** A `Cleaner` would run
`drop` at an unpredictable moment on an unpredictable thread, and `drop` enters
the store — so it would trip `0014` D at random, or worse, run while a call is
in flight. `0014` C rejected GC lifetimes for the same reason and this is the
sharper case.

**C, because the safe window is the call and nothing in Clojure marks it.**
Handing back a live-but-doomed object is the shape that produces
use-after-free; handing back a closed one produces an exception with a
sentence explaining the rule.

## Alternatives rejected

- **A handle as an integer rep.** Smallest, and it makes every arithmetic
  operation in Clojure a way to forge a capability.
- **`Cleaner`/finalizer-driven drop.** See B. Also `0014` C.
- **Reference counting on the Clojure side.** The header already does this
  (`drop` once per resource, `delete` once per handle); a second scheme on top
  can only disagree with the first.
- **Not supporting `own` at all, only `borrow` within a call.** Tempting and
  much smaller. Rejected because a constructor returns `own`, so this would
  make every resource-shaped API unusable rather than partly usable.
- **Handing back a live borrow and documenting the rule.** This is what a C
  API does. In a language where values escape into closures and lazy seqs by
  default, a documented rule is not a mechanism — `0015` measured a lazy seq
  crossing between two stores with no error at all.

## What would falsify this

- **A guest that actually implements a resource, which does not exist yet.**
  Everything above rests on headers and on a `--dummy` build that traps when
  called. The first thing to write is that guest; if the handle round trip does
  not work as described, this note is guesswork.
- **`drop` being required for a `borrow`.** The headers say `drop` is
  once-per-logical-resource and `owned` distinguishes the two, but they do not
  say what `drop` on a borrow does. If it is required, C is wrong.
- **A resource surviving its instance.** `close` on the instance does not walk
  outstanding handles. If a handle outlives its store and `delete` then faults,
  the handle has to hold a strong reference and the instance a registry.
- **`0014` E's clone requirement being wrong for handles** — if wasmtime does
  not recycle a resource val the way it recycles a string, the immediate clone
  is unnecessary and costs a `delete` per call.

## Resources

- `dev/resources/zoo.wit` — four of its eight exports are resource methods.
- `0012` — the mapping this is the last row of.
- `0014` C, D and E — the lifetime, concurrency and result-validity rules this
  inherits rather than re-derives.

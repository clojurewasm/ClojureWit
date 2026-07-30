# 0020 — Generated namespaces: the ergonomics layer, as a file

**Status:** accepted · 2026-07-30 · the shape `0015` accepted for later, picked
up now that its two blockers — marshalling and host imports — are gone.
Rewritten after an adversarial review: D inverted (the throwing variant lost),
C gained the per-interface exit its collision rule needed, and the sha moved
from the component bytes to the API. This layer **opens S2** — its option keys
are `cljwit.edn`'s keys, so the file becomes the edn's implementation rather
than its rival — and S1 closes without it.

## The question

`0014`'s primitive is honest but anonymous: `(i "local:zoo/shapes@0.3.0#take-flags")`
gives an editor nothing to complete, clj-kondo nothing to check, and a reader
no `:arglists`. `0015` established what the ergonomic layer must *not* be — a
macro (clj-kondo cannot see through it, and the gate goes red outside `nix
develop`), an ambient instance (laziness contaminates across instances
whatever the mechanism), a `require` hook (hides the dependency from every
tool that reads `ns` forms) — and named the survivor: **a generated `.clj`
file, with the instance as an explicit argument.** This note decides what that
file looks like.

## The decision

### A. One dev-time function, one readable file

```clojure
(require '[cljwit.host.gen :as gen])
(gen/write-ns! "resize.wasm" {:ns 'acme.resize :dir "src"})
;; => "src/acme/resize.clj"
```

`cljwit.host.gen` is a **separate namespace** from `cljwit.host`: generation
is a dev-time act that reads a component's type, and requiring it in
production buys nothing. The generated file is ordinary checked-in source —
reviewed in diffs, seen by clj-kondo, clojure-lsp, and CIDER exactly because
it is not clever. Its header names the source component, a sha-256 **of the
`describe` output** — the API, not the bytes, so a toolchain bump that
changes the `@producers` section without changing a signature does not churn
the header — and the form that regenerates it.

**What the file cannot do is notice it is stale.** Nothing reads the header
at run time; a `.wasm` swapped under an unregenerated file produces no diff
anywhere. The *loud* half is already covered — marshalling is rebuilt from
the live component at instantiate, so a renamed export fails
`:no-such-export` with a did-you-mean and a changed argument list fails
`:wrong-arity`. The silent half is exactly the regenerate-and-diff check
that `cljwit.edn` (S2) owes its components; the API hash makes it a string
compare. Until S2 exists, staleness detection is the reviewer's, and this
paragraph is the warning.

### B. Every var takes the instance first

```clojure
(defn thumbnail
  "WIT: thumbnail: func(img: list<u8>, w: u32, h: u32) -> list<u8>"
  [i img w h]
  ((i "thumbnail") img w h))
```

`0015` measured why ambience loses: `binding` conveys to exactly the paths
that race (`future`, `pmap`) and a lazy seq realised under a second instance
runs against the second silently. The explicit argument is what makes handing
an instance to a `future` a visible act. Arity and names now exist
statically: `(thumbnail i img)` is a clj-kondo warning and an
`ArityException` naming `acme.resize/thumbnail`, not a gensym (`0015`'s
macro-arity finding was about interned gensyms; a named `defn` does not have
that problem).

Two warts, named rather than hidden. A resource method takes **two**
contexts — `(counter-bump i c 5)` — and the signature permits a handle from
instance A with instance B. The runtime catches that mismatch when lowering
the handle (`:wrong-instance`, since a `Handle` carries its context), which
is a call-time error, not the visible act B advertises for instances; the
docstring of every method var says which instance the handle must come from.

### C. Names: the last WIT segment, and a collision is an error

The exact WIT string stays the runtime key inside the generated body — `0014`
B is not renegotiated. The *var* takes the last segment, munged only where
WIT's grammar demands it:

| WIT export | var |
|---|---|
| `plain` | `plain` |
| `local:zoo/shapes@0.3.0#take-flags` | `take-flags` |
| `…#[constructor]counter` | `counter` |
| `…#[method]counter.bump` | `counter-bump` |
| `…#[static]counter.reset` | `counter-reset` |

The stripping **collides by construction** — the same finding that made
`0014` B keep exact WIT strings at the key level. Both shapes were built and
verified against the pinned wasm-tools during this note's adversarial
review: a world exporting **two versions of one interface**
(`acme:api/svc@1.0.0#run` / `…@2.0.0#run` — every function collides
pairwise), and a single legal interface where `[method]counter.bump`
strips to the same name as a plain export `counter-bump`.

So a collision **fails generation** with the colliding WIT names in the
message, and there are two exits at two granularities:

- **`:interface`** — generate one namespace per interface:
  `(gen/write-ns! wasm {:ns 'acme.api.v1 :interface "acme:api/svc@1.0.0"})`.
  One option per interface, stable across regenerations. This is the exit
  for the two-version world, where per-function renames would cost
  O(functions) and have to be re-supplied verbatim forever.
- **`:rename`** — `{:rename {"t:c/i#counter-bump" 'counter-bump2}}`, for a
  leaf collision inside one interface.

Silent suffixing (`run-2`) stays rejected: an API whose names depend on
reflection order breaks on the component author's next reorder.
Deterministic failure with a named exit follows `0014`'s nil-on-typo rule.

A generated name that shadows `clojure.core` — `count`, `map`, `get` are
all legal WIT identifiers — gets `(:refer-clojure :exclude [...])` computed
into the `ns` form, because the measured alternative is a load-time warning
per consumer and clj-kondo exiting 2, which takes this repo's own gate red.

### D. One var per export, tagged — and `unwrap` lives in the library

**Amended in review: the first draft generated a throwing `(f …)` plus a
tagged `(f* …)`, and that inverted `0012`.** `0012`'s accepted decision is
that the tagged value *is* the mapping and throwing is opt-in sugar; giving
the throwing form the short, completion-first name is opt-out, not opt-in —
and it hands `(map #(f i %) coll)` exactly the laziness hazard `0012`
documents. It also encoded a type-level fact in the file: a stale throwing
wrapper over an export whose return stopped being a `result` fails in
undefined ways, silently.

So: **one var per export, returning the value as `0012` maps it** — tagged,
for a `result` return — and one function in the runtime library:

```clojure
(host/unwrap (add i 2 3))   ; ok payload, or ex-info with :wit/error
```

Opt-in at the call site, zero generated duplication, and the sugar cannot go
stale because it is not generated.

### E. Reflection moves to the artifact, and becomes public

Generation needs exports and signatures without instantiating. The walk that
`instantiate` does over the component type is extracted and exposed:

```clojure
(host/describe artifact)
;; => {"thumbnail" {:params [["img" {:kind :list :element :u8}] …]
;;                  :result {:kind :list :element :u8}}, …}
```

`describe` is the single source the generator consumes, so the generated
file agrees with what `instantiate` finds **at generation time** — staleness
after that is A's problem, not E's. Docstrings render these trees in WIT
syntax, and they are **structural**: reflection has no declared type names
(`0012`'s amendment), so `take-flags: func(p: perms) -> perms` documents as
`flags{read, write, exec}`, never as `perms`.

## Why

- **Generated source is the only shape all three static tools see** —
  clj-kondo, clojure-lsp, the reader of a diff. `0015` measured the macro
  failing all three.
- **The build step it adds is one the macro also paid**, per `0015`
  (`FileNotFoundException macroexpanding`), and here it is skippable: the
  file, once committed, needs no `.wasm` and no libwasmtime to load. `bb
  reflection` outside `nix develop` stays green.
- **The instance argument is the honest concurrency story.** `0014` D's one
  store, one call rule is visible in the signature instead of hidden in a
  dynamic scope.

## Alternatives rejected

- **The macro, ambience, and the `require` hook** — rejected by `0015` on
  measurements; nothing here reopens them.
- **Interning vars at instantiate** (`intern` from a plain function). Vars
  appear at run time, so clj-kondo and lsp are exactly as blind as with the
  macro, minus even the macro's syntactic marker.
- **A namespace per interface as the only mode.** The common one-interface
  world would pay a file explosion for nothing. It survives as the
  `:interface` *exit* instead — the first draft rejected it outright, on the
  strength of `zoo.wit` not colliding, which is this project's recorded
  failure shape: checking the artifact in the repo, not the artifact the
  design is for.
- **Two generated vars per `result` export** (the first draft's D). Inverted
  `0012`, doubled the surface, and created the one staleness mode the
  runtime cannot catch.
- **Silent collision suffixes.** An API whose names depend on reflection
  order breaks on the component author's next reorder.
- **Emitting `defprotocol`/`definterface` for call-site speed.** `0013`:
  the binding is not worth optimising — a component call is ~800 ns; a map
  lookup in the fn body is noise.

## What would falsify this

- A WIT export name whose last segment is not a readable Clojure symbol
  after the munge table above — the generator refuses it today; a real
  component hitting that refusal reopens C. (Names that *shadow* — `count`,
  `map` — are already handled by the computed `:refer-clojure :exclude`.)
- A real component whose interface count makes even `:interface`-split
  namespaces unreadable — reopens C's granularity.
- clj-kondo flagging the generated shape itself — the gate lints real
  generated output carrying a `clojure.core` shadow and a `result` return
  (`gen_test`, skipped where clj-kondo is absent; the dev shell has it),
  and the review measured arity warnings working through this shape.

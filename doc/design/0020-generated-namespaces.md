# 0020 — Generated namespaces: the ergonomics layer, as a file

**Status:** proposed · 2026-07-30 · the shape `0015` accepted for later, picked
up now that its two blockers — marshalling and host imports — are gone.

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
it is not clever. Its header names the source component, its sha-256, and the
form that regenerates it, so drift is visible where drift is looked for: in
version control.

### B. Every var takes the instance first

```clojure
(defn thumbnail
  "WIT: thumbnail: func(img: list<u8>, w: u32, h: u32) -> list<u8>"
  [i img w h]
  ((i "thumbnail") img w h))
```

`0015` measured why ambience loses: `binding` conveys to exactly the paths
that race (`future`, `pmap`) and a lazy seq realised under a second instance
runs against the second silently. The explicit argument is not a compromise —
it is what makes handing an instance to a `future` a visible act. Arity and
names now exist statically: `(thumbnail i img)` is a clj-kondo warning and an
`ArityException` naming `acme.resize/thumbnail`, not a gensym (`0015`'s
macro-arity finding was about interned gensyms; a named `defn` does not have
that problem).

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

Two exports that munge to one name — two interfaces exporting `run`, say —
**fail generation** with both WIT names in the message and a `:rename` option
(`{:rename {"pkg:a/x@1.0.0#run" 'x-run}}`) as the exit. Silent suffixing
(`run-2`) would bake an iteration order into an API; a namespace per
interface multiplies files for a collision that `zoo.wit` — this repo's
worst-case shape — does not have. Deterministic failure with a named exit
follows `0014`'s nil-on-typo rule.

### D. `result` in return position gets `0012`'s sugar here

This is the layer `0012` deferred to: `(f i args)` returns the ok payload and
throws `ex-info` with the lifted error under `:wit/error` (the shrunk
contract — no type name); `(f* i args)` returns `[:ok v]` / `[:err e]`
untouched. Two vars, generated together, only for exports whose *return* is a
`result`. Everywhere else one var, no throwing — `0012`'s laziness argument
stands.

### E. Reflection moves to the artifact, and becomes public

Generation needs exports and signatures without instantiating, and users have
asked the same question of instances (`host/exports`, `host/signature`). The
walk that `instantiate` does over the component type is extracted and exposed:

```clojure
(host/describe artifact)
;; => {"thumbnail" {:params [["img" {:kind :list :element :u8}] …]
;;                  :result {:kind :list :element :u8}}, …}
```

`describe` is useful on its own — a REPL user's first question about an alien
`.wasm` — and it is the single source the generator consumes, so the
generated file can never disagree with what `instantiate` will find.

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
- **A namespace per WIT interface.** Buys collision-freedom `zoo.wit` does
  not need, at the cost of a file explosion and an import dance for the
  common one-interface world. Reopen if a real component's interface count
  makes the flat namespace unreadable.
- **Silent collision suffixes.** An API whose names depend on reflection
  order breaks on the component author's next reorder.
- **Emitting `defprotocol`/`definterface` for call-site speed.** `0013`:
  the binding is not worth optimising — a component call is ~800 ns; a map
  lookup in the fn body is noise.

## What would falsify this

- A WIT export name whose last segment is not a readable Clojure symbol
  after the munge table above — the generator refuses it today; a real
  component hitting that refusal reopens C.
- A component whose regenerated file churns on every toolchain bump (sha in
  the header making diffs noisy) — would argue for dropping the sha line.
- clj-kondo flagging the generated shape itself — would demand a header
  directive, and is checkable in this repo's own gate by generating against
  a fixture and linting the output.

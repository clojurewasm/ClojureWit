# ClojureWit

**Clojure and WebAssembly, joined at the interface.**

ClojureWit makes Clojure a first-class citizen of the WebAssembly component
ecosystem — in **both** directions:

- **Call Wasm from Clojure.** `require` a component written in any language as
  an ordinary Clojure namespace.
- **Call Clojure from anything.** Compile a Clojure namespace into a
  self-contained `.wasm` component that Rust, Go, JavaScript, Python — or another
  Clojure program — can call without knowing Clojure exists.

The name is from [WIT], the language-neutral interface definition language of the
[Component Model]. WIT is the seam; ClojureWit is what sits on the Clojure side
of it.

> **Status: pre-alpha.** There is no compiler yet. The feasibility benchmarks
> are measured and the design survived them — the verdict is
> [`doc/design/0010`](doc/design/0010-s0-verdict.md). `cljwit.host` works
> today; its API is not yet stable. See [`doc/roadmap.md`](doc/roadmap.md).

## Two deliverables

| | What | State |
|---|---|---|
| **`cljwit.host`** | A plain Clojure library: call Wasm components from JVM Clojure. No new dialect, no compiler. | **works, pre-alpha** |
| **`cljwit`** | The compiler: Clojure → Wasm component (WasmGC, tail calls, Wasm 3.0). | design |

`cljwit.host` ships first and stands on its own. It also settles the WIT ↔ Clojure
type mapping that the compiler needs, so the hard part gets solved once.

## `cljwit.host` in five minutes

Requirements: **Java 22+** (`java.lang.foreign`; developed on 25) and
**libwasmtime ≥ 43** — `brew install wasmtime` provides it, or unpack a
[`*-c-api` release tarball](https://github.com/bytecodealliance/wasmtime/releases)
and point `CLJWIT_WASMTIME_LIB` at the shared library
([`doc/design/0019`](doc/design/0019-finding-libwasmtime.md) has the full
resolution order).

```clojure
;; deps.edn
{:deps {io.github.clojurewasm/ClojureWit
        {:git/sha "..."}}  ;; pre-alpha: pin a sha, expect breakage
 :aliases {:dev {:jvm-opts ["--enable-native-access=ALL-UNNAMED"]}}}
```

```clojure
(require '[cljwit.host :as host])

;; Three lifetimes, three orders of magnitude apart (0014):
(with-open [e (host/engine)                     ; process-wide, share it
            a (host/compile e "component.wasm") ; milliseconds — cache it
            i (host/instantiate a)]             ; ~0.06 ms — one per request is fine
  ;; Exports are discovered from the component itself. Names are the exact
  ;; WIT strings; keywords work where the name reads back as itself.
  ((i "greet") "world")                          ; => "hello, world"
  ((i "wasi:cli/run@0.2.3#run")))                ; interface functions too

;; The guest can call *you*: imports are ordinary Clojure functions,
;; and WASI capabilities are named explicitly or absent (deny-by-default).
(host/instantiate a {:imports {"acme:log/sink@1.0.0#write" println}
                     :wasi    {:inherit-stdout true :args ["-v"]}})
```

WIT values arrive as the Clojure data you would guess: `record` → map,
`variant` → `[:tag payload]`, `enum` → keyword, `flags` → set, `option` →
value-or-nil, `result` → `[:ok v]`/`[:err e]` (with `host/unwrap` as the
opt-in throw-on-err sugar), resources → `AutoCloseable` handles. The full
mapping — and why it is not negotiable — is
[`doc/design/0012`](doc/design/0012-wit-clojure-type-mapping.md).

Prefer names an editor can complete and clj-kondo can arity-check? Generate
an ordinary namespace and check it in
([`doc/design/0020`](doc/design/0020-generated-namespaces.md)):

```clojure
(require '[cljwit.host.gen :as gen])
(gen/write-ns! "component.wasm" {:ns 'acme.component})
;; => "src/acme/component.clj" — then: (acme.component/greet i "world")
```

With more than one component, declare them once in `cljwit.edn` at the
project root and let `cljwit.project` reconcile
([`doc/design/0021`](doc/design/0021-cljwit-edn.md)):

```clojure
;; cljwit.edn
{:components {acme.resize {:wasm "components/resize.wasm"}}}
```

```clojure
(require '[cljwit.project :as project])
(project/sync!)    ; regenerate what changed
(project/check)    ; CI: throw if anything drifted — stale, edited,
                   ; missing, or orphaned
```

## Why now

Three things landed that were not true a year ago:

| | When | What changed |
|---|---|---|
| **Wasm 3.0** | 2025-09 | GC, tail calls, exception handling became a W3C standard |
| **WasmGC everywhere** | 2024-12 | Chrome 119+, Firefox 120+, Safari 18.2+ |
| **WASI 0.3.0** | 2026-06 | Native async in the Component Model (`async func`, `stream<T>`, `future<T>`) |

A garbage-collected, higher-order language can now target Wasm without shipping
its own GC — and can be consumed as a component by any other language.

## Shape of it

```clojure
;; Use a component written in another language, as a namespace
(ns app.core
  (:require ["./resize.wasm" :as resize]))

(resize/thumbnail bytes 128 128)
```

```clojure
;; Publish a namespace as a component other languages can call
(ns acme.handler
  {:wit/world "acme:api/handler"})

(defn ^{:wit/export "handle"} handle [req]
  {:status 200 :body "hello"})
```

## Design notes

Design decisions live in [`doc/design/`](doc/design/), the plan in
[`doc/roadmap.md`](doc/roadmap.md), and the current state in
[`doc/status.md`](doc/status.md). Start with
[`doc/design/0001-scope-and-shape.md`](doc/design/0001-scope-and-shape.md).

## Getting set up

With [Nix]:

```sh
nix develop          # exact toolchain
bb tasks             # what you can run
```

Without Nix, install the tools in [`tools.json`](tools.json) and check them:

```sh
bb check-tools
```

## Related

- [ClojureWasm](https://github.com/clojurewasm/ClojureWasm) — a sibling project
  in this org: a Clojure **runtime** in Zig that *executes* Wasm. ClojureWit is a
  **compiler** that *emits* Wasm. They compose: a component built with ClojureWit
  can be required by ClojureWasm.
- [clj.wasm](https://github.com/kanaka/clj.wasm) — earlier exploration of the same
  question, and the source of several ideas here.

## License

EPL-2.0. See [LICENSE](LICENSE).

[WIT]: https://component-model.bytecodealliance.org/design/wit.html
[Component Model]: https://component-model.bytecodealliance.org/
[Nix]: https://nixos.org/

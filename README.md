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

> **Status: pre-alpha, design + feasibility phase.** There is no compiler yet.
> The first milestone is a set of benchmarks that decide whether the core design
> is viable at all — see [`doc/roadmap.md`](doc/roadmap.md). Nothing here is
> stable; do not build on it.

## Two deliverables

| | What | State |
|---|---|---|
| **`cljwit.host`** | A plain Clojure library: call Wasm components from JVM Clojure. No new dialect, no compiler. | planned |
| **`cljwit`** | The compiler: Clojure → Wasm component (WasmGC, tail calls, Wasm 3.0). | design |

`cljwit.host` ships first and stands on its own. It also settles the WIT ↔ Clojure
type mapping that the compiler needs, so the hard part gets solved once.

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

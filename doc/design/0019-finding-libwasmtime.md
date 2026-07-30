# 0019 — How a shipped `cljwit.host` finds libwasmtime

**Status:** accepted · 2026-07-30 · closes the question `0005` and `0011` left
open ("how a *shipped* library finds the shared object is still open").

## The question

`cljwit.host` binds libwasmtime through `java.lang.foreign`, which needs a
path (or a `dlopen`-able name) at run time. Until now the only source was the
`CLJWIT_WASMTIME_LIB` environment variable, set by this repo's nix flake — a
machine of the developer's, assumed. A user who adds this library as a git
dep and runs `(host/engine)` gets an exception about an environment variable
they have never heard of. That is the wrong first five minutes.

## What the ecosystem does (2026-07-30)

- **wasmtime-java bundles per-platform JNI libraries inside the JAR** and
  extracts one to a temp file at load
  (<https://github.com/kawamuray/wasmtime-java>). wasmtime-py does the same
  with wheels. This is the right end state for a *published artifact* — and
  this project publishes none yet.
- **wasmtime's own C API ships as `*-c-api` release tarballs with no
  conventional install location**
  (<https://github.com/bytecodealliance/wasmtime/blob/main/crates/c-api/README.md>);
  the user unpacks it anywhere.
- **Homebrew's `wasmtime` formula builds and `cmake --install`s the C API**,
  so `brew install wasmtime` puts `libwasmtime.dylib` under the brew prefix
  (<https://github.com/Homebrew/homebrew-core/blob/HEAD/Formula/w/wasmtime.rb>).
  One command, and the most likely install path for a macOS user.

## The decision

Resolution order, first source wins:

1. **`(host/engine {:lib path})`** — the explicit argument.
2. **`cljwit.wasmtime.lib` system property** — per-JVM, settable from
   `deps.edn` `:jvm-opts`, which is where Clojure users configure a process.
3. **`CLJWIT_WASMTIME_LIB` environment variable** — per-shell; what the nix
   flake and CI already set. Unchanged.
4. **The conventional `cmake --install` prefixes** — `/opt/homebrew/lib`,
   `/usr/local/lib`, `/usr/lib` — probed for `System/mapLibraryName`'s
   platform name (`libwasmtime.dylib` / `libwasmtime.so` / `wasmtime.dll`).
5. **The system loader** — the bare platform name handed to `dlopen`, which
   honors `LD_LIBRARY_PATH` / `DYLD_LIBRARY_PATH` and the OS defaults.

Two failure rules, both about not lying:

- **An explicit source that fails to load is an error, not a fallthrough.**
  A typo'd `:lib` that silently probed onward could load a *different*
  wasmtime than the one named — `0014` rejected exactly this shape as
  nil-on-typo. Sources 1–3 name a specific file; if it does not load, the
  exception names it and stops.
- **A library that loads but lacks the component API is named for what it
  is.** The component model entered the C API between wasmtime 40 and 43
  (`0005`), so an old libwasmtime *loads* and then fails at the first
  `wasmtime_component_*` symbol. The raw failure is
  `NoSuchElementException: No value present`, which says nothing. The
  binding now throws with the symbol, the library it was missing from, and
  the version floor.

## Alternatives rejected

- **Bundling the native library in the JAR** (wasmtime-java's shape). Right
  once artifacts are published; there are none, and a git dep cannot carry
  per-platform binaries without committing them. Revisit at first release —
  the resolution order above stays useful under it (a bundled lib becomes
  source 0).
- **Download-on-demand** (onnxruntime's shape). A supply-chain surface —
  checksum pinning, a cache directory, proxy handling — for a problem `brew
  install wasmtime` already solves. Needs its own design if ever.
- **Probing before honoring the environment variable.** Would make the nix
  flake's pin lose to whatever homebrew has installed, which is exactly the
  "two toolchains disagreeing silently" failure `tools.json` exists to
  prevent.

## What would falsify this

A platform where the conventional prefixes or `mapLibraryName`'s output are
wrong — Windows maps to `wasmtime.dll` and has no `/usr/local/lib`, so
sources 4–5 quietly contribute nothing there and 1–3 carry it. If a Windows
user shows up, this note gets a `%LOCALAPPDATA%`-shaped amendment.

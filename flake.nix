{
  description = "ClojureWit — Clojure and WebAssembly, joined at the interface";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  inputs.flake-utils.url = "github:numtide/flake-utils";

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let pkgs = import nixpkgs { inherit system; };
      in {
        devShells.default = pkgs.mkShell {
          # Keep this list in sync with tools.json (bin/check-tools reads that
          # file, so a drift between the two shows up as a failing `bb check`).
          packages = with pkgs; [
            gitMinimal           # `bb ref`. The macOS /usr/bin/git shim breaks once
                                 # nix clears DEVELOPER_DIR, so git must come from
                                 # here — but the full `git` drags perl and its
                                 # docs into the closure and took CI from ~50s to
                                 # ~20min. clone and pull is all `bb ref` needs.
            temurin-bin-25       # JVM: Clojure host. 22+ for the FFM/wasmtime path.
            clojure
            babashka
            clj-kondo
            cljfmt

            wasmtime             # server runtime; component model + WASI 0.3
            wasmtime.lib         # libwasmtime, for the JVM/FFM host path (S1)
            wasmtime.dev         # wasmtime.h, for the C control in dev/spike
            wasm-tools           # wat<->wasm, validate, component new
            binaryen             # wasm-opt: the Wasm->Wasm optimizer
            nodejs               # V8 lane: WasmGC + speculative inlining
          ];
          shellHook = ''
            # cljwit.host reaches wasmtime through java.lang.foreign, which needs
            # an absolute path to the shared library. Committed files may not
            # contain one (.claude/CLAUDE.md), so nix computes it here. How a
            # *shipped* cljwit.host finds the library is still open — see
            # doc/design/0005.
            export CLJWIT_WASMTIME_LIB="${pkgs.wasmtime.lib}/lib/libwasmtime${pkgs.stdenv.hostPlatform.extensions.sharedLibrary}"
            # The C control that dev/spike/call_cost.c is needs the headers from
            # the *pinned* wasmtime, not whatever a bare nixpkgs resolves to.
            export CLJWIT_WASMTIME_INCLUDE="${pkgs.wasmtime.dev}/include"
            echo "ClojureWit dev shell — 'bb tasks' to see what you can run"
          '';
        };
      });
}

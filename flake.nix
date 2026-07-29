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
            git                  # `bb ref`. The macOS /usr/bin/git shim breaks once
                                 # nix clears DEVELOPER_DIR, so it must come from here.
            temurin-bin-25       # JVM: Clojure host. 22+ for the FFM/wasmtime path.
            clojure
            babashka
            clj-kondo
            cljfmt

            wasmtime             # server runtime; component model + WASI 0.3
            wasm-tools           # wat<->wasm, validate, component new
            binaryen             # wasm-opt: the Wasm->Wasm optimizer
            nodejs               # V8 lane: WasmGC + speculative inlining
          ];
          shellHook = ''
            echo "ClojureWit dev shell — 'bb tasks' to see what you can run"
          '';
        };
      });
}

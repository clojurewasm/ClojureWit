# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This project is pre-alpha; nothing is stable and there are no releases yet.

## [Unreleased]

### Added
- Repository, toolchain, and design notes. No compiler yet — see
  `doc/roadmap.md` for what has to be true before one is worth writing.
- `bb check` as the single gate, run identically by developers and CI.
- A pinned toolchain: `flake.nix` for exactness, `tools.json` as the shared
  version SSOT for machines without Nix.
- **S0 B1** — hand-written WasmGC protocol dispatch, measured on V8 and
  wasmtime against a JVM Clojure baseline, with two controls that separate the
  vtable loads from the indirect call. `bb bench-s0` runs it. First real numbers
  in the project: `doc/design/0002-measure-first.md`.

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
- **`/survey`** — the reconnaissance discipline: search, read the primary
  source, read what someone who solved it wrote down, then *build the smallest
  thing that would fail if the claim were false*. Run on stage entry. `bb ref`
  gained the reference sources it needs.
- **`doc/design/0007`** — the component boundary is linear memory, not GC.
  Found by the first survey; sizes S4 and adds an open question to the roadmap.
- **S0 B1** — hand-written WasmGC protocol dispatch, measured on V8 and
  wasmtime against a JVM Clojure baseline, with three controls forming a curve
  in how far the call target sits from the receiver. `bb bench-s0` runs it.
  First real numbers in the project: `doc/design/0002-measure-first.md`.

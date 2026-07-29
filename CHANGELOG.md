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

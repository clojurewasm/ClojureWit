# 0021 — `cljwit.edn`: the project file, starting as a list of components

**Status:** accepted · 2026-07-30 · S2's first unit. `0020` promised its
option keys would be this file's keys, and deferred the drift check here.
Rewritten after an adversarial review ran its claims: the drift contract,
the transaction semantics, the path story and the CI command all changed,
and two of the first draft's factual claims were measured false.

## The question

`0020` generates one namespace per call, and what it wrote can go stale
silently — the file cannot notice a swapped `.wasm`, and nothing regenerates
it but a human remembering to. A project with three components has three
invocations to remember, in the right directories, with the same options as
last time. shadow-cljs answered the same question with a project file; the
roadmap names `cljwit.edn` as S2's first deliverable. What goes in it now,
and what does not?

## The decision

### A. One file, found in the working directory, components keyed by namespace

```clojure
;; cljwit.edn
{:components {acme.resize {:wasm "components/resize.wasm"}
              acme.api.v1 {:wasm      "components/api.wasm"
                           :interface "acme:api/svc@1.0.0"
                           :dir       "src/gen"}}}
```

Each value is `0020`'s option map minus `:ns`, which moved into the key —
**a duplicate namespace is now unrepresentable** rather than validated, and
the map diffs one line per component. What still needs a written check is
the sibling collision edn cannot see: `acme.foo-bar` and `acme.foo_bar`
munge to one file path.

**Discovery is the working directory, plus `:file`.** `(project/sync!)`
reads `./cljwit.edn` — the same rule shadow-cljs uses, and the same anchor
`-X` already gives `deps.edn` — and a monorepo passes
`{:file "sub/cljwit.edn"}`. No walking up: two `cljwit.edn`s in one tree is
a question this note refuses to answer implicitly. `:wasm` and `:dir` are
resolved relative to the file's directory **for I/O only**; the string
printed into a generated header stays the edn-relative one, because a
committed header carrying an absolute path is a machine-specific assumption
in a committed file. (`write-ns!` grew a `:source` override to keep those
two apart.)

Unknown keys are an error naming the key — at the top level, in an entry,
**and in `write-ns!` itself**: the review found the REPL path silently
dropping `:renmae` today, which is `0014`'s nil-on-typo live in the
function this file wraps. The check lives in `gen`, so both paths inherit
it. Every failure `sync!` reports carries its provenance —
`{:file "cljwit.edn" :ns acme.api.v1}` — because "identical to the REPL
error" is the wrong goal for entry 3 of 5.

### B. `sync!` returns data; `check` throws with the list in its message

Both live in **`cljwit.project`**, not `cljwit.host.gen`: reading,
validating and reconciling the project file is not "generation over a
list", and the roadmap already names two more consumers of this file (the
nREPL entry point, the watcher). A `-X` coordinate pasted into CI YAML is
a public API; it should name the file's owner.

```clojure
(project/sync!)    ; plan everything, then write; {:wrote [...] :unchanged [...]}
(project/status)   ; touch nothing; {:ok [...] :stale [...] :modified [...]
                   ;                 :missing [...] :orphans [...]}
(project/check)    ; status, printed, thrown if anything is not :ok
```

- **Plan all, then write all.** `source-for` is pure, so validation
  failures in any entry abort before the first byte is written — no
  half-synced tree. Failures are collected across entries, not
  first-wins.
- **Drift is tiered, because byte-equality misdiagnoses the certain case.**
  The first draft said "differs from what regeneration would produce" and
  the review pointed out what that flags: **every generated file in every
  user project, on the first cljwit release that touches emission** — with
  a message blaming their component. So: the `exports-hash` header line
  differs → **`:stale`**, the component's API changed, regenerate. Hash
  matches but bytes differ → **`:modified`** — a hand edit, a changed
  option, or a newer generator, and the header's new `generator:` line
  says which of those it was. This supersedes `0020` A's "the API hash
  makes it a string compare" — the hash alone misses hand edits and
  `:rename` changes; the bytes alone cannot name what happened. Both
  tiers cost one `describe` of one `.wasm`.
- **Orphans are findings, not garbage collection.** A file under a
  configured `:dir` that carries the generated header but no entry —
  someone deleted the entry and left the namespace loadable — is exactly
  the silent staleness this unit exists to kill, so `status` reports it.
  Nothing is deleted: removal is the human's, with the list in hand.
- **`check` is the CI verb**, and it enumerates on stdout and in the
  exception message — measured: `-X` shows a caller only `ex-message`;
  `ex-data` lands in a temp-file report CI discards.

### C. The CI invocation is an alias, and says its prerequisites out loud

```clojure
;; deps.edn
:aliases {:cljwit {:jvm-opts ["--enable-native-access=ALL-UNNAMED"]
                   :exec-fn  cljwit.project/check}}
```

`clojure -X:cljwit` exits non-zero on drift. The bare
`clojure -X cljwit.project/check` of the first draft prints three JEP-472
restricted-method warnings today and is scheduled to become an error, so
the alias is the documented form. And the prerequisite is stated rather
than implied: **this check runs the generator, so the runner needs
libwasmtime** (`0019`) — a heavier ask than the `bb` this note's first
draft rejected as too demanding. A CI without the native library cannot
run `check`; committing the generated files and reviewing their diffs is
what it has instead.

The `-X` coordinate is **provisional until the S2 server process is
designed** — the shadow-cljs model has a long-lived process end up owning
this file and the engine, and that design may want to own the CLI too.

### D. What is *not* in the file yet

A file watcher, an nREPL entry point, compiler options — the rest of S2 —
earn their keys when they exist. A config surface that anticipates its
features is the scaffolding failure `0006` documents, in user-facing form.

## Why

- **The drift check has to live above the generated files.** `0020` settled
  that a generated file cannot check itself; only the thing that knows every
  component→namespace pair can. This file is that thing, and `status` makes
  the knowledge executable in both directions — entries without files,
  files without entries.
- **Saved arguments beat remembered ones.** The regeneration command is
  already printed in every generated header; this file is the same
  information once, for all of them, diffable.
- **shadow-cljs is the precedent, at its actual strength.** Its lesson is
  the narrow one: a project file the tool reads and the human edits, at the
  root, in edn, found in the working directory. It has no
  find-from-anywhere property and neither does this.

## Alternatives rejected

- **Metadata in the generated files instead of a central file.** Loses the
  complete set — no `:missing`, no `:orphans`.
- **A `deps.edn` alias carrying the config.** tools.deps passes exec args,
  it does not define config schemas. (An alias still *carries the JVM
  flags* — C — which is a different job.)
- **babashka tasks as the interface.** This repo uses bb; a library user
  need not.
- **Regenerating inside `require` / at load time.** Reopens `0015`'s
  measured rejections through the back door.
- **A vector of entries with `:ns` inside** (the first draft). Made the
  file's one invariant — one owner per namespace — something to validate
  instead of something unrepresentable.
- **Byte-equality as the whole drift contract** (the first draft). Certain
  to misfire on the first generator release; see B.
- **`sync!` throwing on drift.** Hostile from a REPL; data composes,
  `check` throws.

## What would falsify this

- A second S2 feature whose config does not fit "a map of `write-ns!`
  argument maps" — the file would need a second top-level key, and this
  note an amendment saying which.
- A user's cljfmt config reformatting generated files, so that `:modified`
  fires on every checked file. The generator's output is stable under this
  repo's cljfmt (measured in review); a divergent user config would force
  emission to normalize harder or `:modified` to diff structurally.
- The S2 server design wanting different ownership of the file and the
  `-X` coordinate — the coordinate is declared provisional for exactly
  that.

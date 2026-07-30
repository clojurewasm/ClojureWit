# 0012 — The WIT ⇄ Clojure type mapping

**Status:** accepted for the rows an executable test covers — which now
includes `tuple` and `flags`, through `cljwit.host` rather than the hand-rolled
echo test (`test/cljwit/host_test.clj`). `proposed` for the rest
(`own`/`borrow`, `map`, `list<T,N>`, `stream`/`future`, `error-context`) — see "What would falsify this". Rewritten 2026-07-30
after review found the first draft wrong in most of its hard cases; what it got
wrong is recorded at the end rather than quietly fixed. · 2026-07-30

## The question

`0001` says `cljwit.host` ships first partly because it "forces the WIT ↔
Clojure type mapping (the hardest shared problem) to be solved once". The
compiler needs the same mapping in the other direction, so getting it wrong
costs twice.

Spec read at `.ref/component-model` commit `73b7ad51` (2026-07-28) —
[WebAssembly/component-model], `design/mvp/{Explainer,CanonicalABI,WIT}.md`.
Prior art read at `.ref/wit-bindgen` — [bytecodealliance/wit-bindgen], whose
C backend is the closest existing answer for a language with no native
`option`, `result` or sum types.

## The decision

**A type mapping first, and calling-convention sugar on top of it — never
instead of it.** The first draft conflated the two and defined `result` only
for return position, which left `list<result<u32, string>>` with no meaning.

### The types

`gate` is the spec's own feature marker (`Explainer.md:53-70`): unmarked ships
in WASI 0.2, 🔀 in WASI 0.3, and 🗺️ 🔧 📝 are **in no shipped release**.

| WIT | gate | Clojure | |
|---|---|---|---|
| `bool` | | `true` / `false` | |
| `s8 s16 s32 s64 u8 u16 u32` | | integer | all fit a `long` — but see the lowering note |
| `u64` | | integer or `BigInt` | L2 |
| `f32` `f64` | | double | L3, L4 |
| `char` | | `Integer` code point | L5 |
| `string` | | `String` | L6 |
| `list<T>` | | vector | |
| `tuple<A,B,…>` | | vector | distinguished from `list` by the type |
| `record` | | map, keyword keys | labels are already kebab-case |
| `enum` | | keyword | |
| `flags` | | set of keywords | 1–32 members, per `CanonicalABI.md:2294` |
| `variant` | | `[:case-name value]`, `[:case-name]` when the case has no payload | |
| `option<T>` | | value, `nil` for `none` | L1 |
| `result<T,E>` | | `[:ok v]` / `[:err e]`, either payload omitted when absent | see sugar |
| `own<T>` | | handle, closed by the caller | see resources |
| `borrow<T>` | | handle; **received** ones are call-scoped | see resources |
| `list<T,N>` | 🔧 | vector, length checked | not in a shipped release |
| `map<K,V>` | 🗺️ | **vector of `[k v]` pairs** | L7; not in a shipped release |
| `stream<T>` `future<T>` | 🔀 | not mapped in S1 | shipped in WASI 0.3; deferred because async has no mapping yet |
| `error-context` | 📝 | not mapped in S1 | not in a shipped release |

### The sugar

**`result` in return position also gets a throwing wrapper.** `(f args)`
returns the ok payload and throws on error; `(f* args)` returns the tagged
value. Two vars per function, generated together. The throw carries the lifted
`E` in `ex-data` under `:wit/error`, so `catch` can dispatch on the payload
even though the class is always `ExceptionInfo`.

**Amended 2026-07-30: the contract shrank — no WIT type name.** The first
draft promised the declared type name (`error-code`) alongside the payload.
Nothing can supply it: the pinned wasmtime 47.0.1 type-reflection API exposes
member names — record fields, variant cases, enum and flags names — but no
function returns the *declared name* of a valtype (verified against every
`wasmtime_component_*type*` symbol in the pinned headers, 2026-07-30), and
`0015` declined to be the codegen layer that would read it out of the WIT
text. What remains is enough to dispatch on: `0012`'s own finding is that the
lifted payload is self-describing — an `enum` error is a keyword, a `variant`
error a `[:tag payload]`, a `record` error a map keyed by field. A codegen
layer that reads WIT text may *add* the name later; the base contract must not
promise what reflection cannot see.

Everywhere else — parameters, record fields, list elements, variant payloads —
`result` is the tagged value and nothing throws.

## Why

**Because `result` is WIT's recovery channel, not its panic channel.**
`Explainer.md:720-725` says so normatively: "explicit `result` or `variant`
types must be used in the function return type" for error recovery.
`wasi:filesystem` returns `result<_, error-code>` for a missing file. Making
the recovery channel throw inverts it — and wit-bindgen's C backend, the
closest prior art for a language without sum types, represents `result` as a
struct with a `bool is_err` discriminant, a value, not an exception.

**Because throwing composes badly with Clojure's own laziness.**
`(map #(component-call %) coll)` is a lazy seq; a throw fires at realisation,
in whatever dynamic scope forces it — possibly outside the `with-open` holding
the resources the call used. In a transducer it discards the successfully
lifted prefix. `0008` names laziness as observable and non-negotiable. The
tagged value has none of these hazards; the sugar has all of them and is opt-in.

**Because `ex-info` is not free.** It extends `RuntimeException` with a
writable stack trace, so constructing one fills in the stack — plausibly
comparable to the ~µs component call it reports on (`0011`). Unmeasured, and
named as such: a `result`-per-call interface should not pay it by default.

**Because the type-directed argument only ever justified non-injectivity.**
The first draft's headline rule, "lowering is type-directed; lifting is not",
contradicted its own next sentence. Both directions know the type. What the
type buys is that the mapping **need not be globally injective** — `enum`,
`flags` and `string` can share keywords and strings without colliding. It does
*not* rescue a mapping that is ambiguous *within* a type, which is what
nested `option` and `map` are.

## Resources

The C API has **one** resource val kind — `WASMTIME_COMPONENT_RESOURCE`, no
`own`/`borrow` split at the boundary — but the two differ in who drops
(`Explainer.md:672-673`): an `own` is destroyed when the value is dropped; a
`borrow` "must be dropped before the current export call returns".

- **`own<T>` received** → the caller closes it. `with-open` is correct.
- **`borrow<T>` received** → call-scoped; the callee drops it. `with-open` on
  one is a double-drop.
- **`borrow<T>` passed** → still ours. `with-open` is correct.

**Closing is two operations, not one.** Per wasmtime's `component/val.h`:
`..._drop` once per logical resource — omitting it "will be leaked into the
store **and a trap may be raised**", so a dropped handle is not merely a leak —
and `..._delete` once per *handle*, where `..._clone` makes another handle
needing another `delete` and no further `drop`. A single `Closeable/close`
therefore cannot be the whole story and the wrapper has to track both counts.

## Alternatives rejected

- **`result` throwing as the type mapping** (the first draft). Rejected above:
  it has no meaning in non-return position, inverts WIT's error channel, and
  fights laziness. Kept as opt-in sugar because the ergonomic argument for
  `(resize/thumbnail bytes 128 128)` returning a thumbnail is real.
- **Returning the exception object without throwing.** Equivalent to the tagged
  form but loses the case label, which `variant`-shaped errors need.
- **`char` as `Character`.** Cannot hold `U+10000..U+10FFFF` — half the space —
  and truncates silently, the failure `0008` forbids.
- **`char` as a one-character `String`.** The strongest alternative, and what
  ClojureScript effectively does since it has no char type: lossless, composes
  with `str`/`subs`. Rejected because it makes `list<char>` and `string`
  indistinguishable in Clojure, and because wit-bindgen's C backend reaches the
  same `uint32_t` answer — with a `// TODO: better type?` beside it, which is
  an honest signal that nobody likes this and nobody has better.
- **`map<K,V>` as a Clojure map.** Rejected on three counts, all from the spec:
  the key space is a restricted subset (`Explainer.md:624` — no floats, no
  aggregates); WIT `map` despecialises to `(list (tuple K V))` and **the
  Component Model does not prevent duplicate keys** (`Explainer.md:840-848`),
  so lifting into a Clojure map silently drops entries; and it is ordered where
  a Clojure map is not, which `0008` puts on the far side of the boundary. A
  vector of pairs is lossless and `(into {} …)` is one call away.
- **Resource lifetime by GC finalisation.** The first draft rejected this by
  citing a Cloudflare article about JavaScript's `FinalizationRegistry` — wrong
  platform, and exactly the inherited-conclusion error `.claude/CLAUDE.md`
  warns about. Re-derived: the JVM's `java.lang.ref.Cleaner` is not the
  deprecated `finalize()` and is the standard backstop under explicit close.
  **Not rejected — deferred**: a `Cleaner` that calls `drop`+`delete` if the
  caller forgets turns a possible trap into a late release, and whether that is
  worth the machinery is an implementation decision, not this note's.
- **`defrecord` for `record`, tagged maps for `variant`, lists for `tuple`.**
  Rejected for uniformity: plain maps and vectors round-trip through `pr-str`,
  work with `clojure.spec`, and need no generated classes. Recorded because the
  first draft rejected nothing here at all.

## Lossy mappings — *not* `0008` divergences

The first draft numbered these as divergences under `0008` and got the category
wrong. `0008`'s test is "run the same source under `clojure` and under cljwit
and compare"; a `cljwit.host` program **cannot** run under `clojure`, so there
is no behaviour to diverge from. These are lossy points in a new library's API.
They are lettered here, and divergence numbers stay reserved for the compiler,
where the oracle exists.

- **L1 — nested `option`. Demonstrated, not predicted.**
  `test/cljwit/roundtrip_test.clj` echoes `option<option<u32>>` and shows the
  boundary keeping `none`, `some(none)` and `some(some(9))` distinct — then
  applies this note's rule and shows the first two collapsing to `nil`.
  **Nothing is lost crossing the wire; the loss is that `nil` is the only thing
  `none` can become, so a second level has nowhere to go.** Round-tripping a
  nested option is unsupported. Wrapping every `some` would be injective and
  would make single-level `option` — overwhelmingly the common case —
  unidiomatic. The assertion is in the test, so changing the mapping breaks a
  test rather than a paragraph.
- **L2 — `u64` above 2^63.** Lifts as `BigInt`, so the *type* of a lifted `u64`
  depends on its value.
- **L3 — `f32` narrowing.** `(= 0.1 (double (float 0.1)))` is **false**. No
  `f32` round-trips exactly. Lowering a double outside `f32` range is
  **undecided**: JVM Clojure's `float` throws, `.floatValue` gives `Infinity`.
- **L4 — NaN.** `CanonicalABI.md:2415-2427` canonicalises NaN, discarding sign
  and payload, and `:2734`'s `maybe_scramble_nan32` lets the host substitute a
  *random* pattern. Observable through `Double/doubleToRawLongBits`.
- **L5 — `char` and the surrogate hole.** Valid values are
  `0..0xD7FF` and `0xE000..0x10FFFF` (`CanonicalABI.md:2775-2779`); an
  `Integer` in `[0xD800,0xDFFF]` is representable in Clojure and **traps** on
  lowering.
- **L6 — `string`.** A Java `String` may hold an unpaired surrogate; a WIT
  `string` is a sequence of scalar values, so such a string cannot be lowered.
  There is also a ceiling: `MAX_STRING_BYTE_LENGTH = (1<<28)-1`
  (`CanonicalABI.md:2493`). Encoding is a `canonopt`
  (`Explainer.md:1383-1385`) the *compiler* will have to choose; for the host
  wasmtime does.
- **L7 — `map`.** Duplicate keys and ordering, above.
- **Not lossy, but a lowering constraint the table missed.** The unsigned types
  fit a `long` when lifted, and that is what the row says — but the *slot* is as
  wide as the WIT type, and Clojure's `int` cast is checked. Lowering a
  perfectly valid `u32` of 2^32−1 throws `integer overflow` unless the write is
  `unchecked-int`, and lifting one gives −1 unless it is widened. Found by the
  round-trip test failing on `4294967295`.
- **L8 — integer range on lowering is undecided.** `CanonicalABI.md:3435`:
  "component-level values are assumed in-range" — the ABI does not check, so
  the host must, and whether lowering `300` into a `u8` throws or wraps is not
  yet chosen.
- **L9 — traps and `result` errors are indistinguishable.** An invalid `char`,
  an oversized string or bad UTF-8 all trap and surface as JVM exceptions
  alongside the sugar's `ex-info`. `0008` puts error dispatch on the
  non-negotiable side, so this needs a distinct exception type before the API
  hardens.
- **L10 — `nil` is overloaded.** `none`, a function with no result, `result`
  with an absent ok payload, and a variant case with no payload all reach
  Clojure as `nil` or `[:case]`. Unambiguous given the type; worth stating.

## What would falsify this

**An echo component — one export per type returning its argument — driven from
Clojure.** It now exists: `test/cljwit/roundtrip_test.clj`, 87 assertions,
part of `bb check`. The guest is `dev/resources/echo.wit` plus a hand-written
`echo.wat`; no Rust toolchain is involved, and `cabi_realloc` cost 25 lines
rather than the thousands the first draft feared.

Every row it covers produced at least one correction to this note — `bool`'s
one-byte union member, `u32` above 2^31−1, nested `option`, `f32` narrowing,
the surrogate hole, `enum` and `variant` lifting as *names*. The rows it does
not cover are still `proposed`, and the estimate that each would be expensive
should be distrusted: over-estimating this test's cost happened three times.

**What the whole set showed, which no single row did.** All four sum types
carry a name or a discriminant plus an **optional payload pointer** — `enum` a
`wasm_name_t`, `variant` a `{wasm_name_t; val *}`, `result` a `{bool; val *}`,
`option` a bare pointer, NULL for `none`. The two vector types are both
`{size_t; T *}`. So a keyword, a `[:tag payload]`, and a map keyed by field
name are **the host representation read straight**, not a Clojure-side
convenience — and because a payload carries its own kind tag, lifting never
needs to know which case it is in.

The one type that breaks the pattern is `record`: its element is
`{wasm_name_t name; val val;}` with the val **inline**, 48 bytes, not behind a
pointer. Every sum type indirects; the product type does not. That is the
shape a compiler would have to emit, so it is worth knowing before S4 rather
than after.

The first draft priced that at "thousands of lines" of hand-written canonical
ABI, citing `0007`. **That was wrong**: `0007` sizes writing an *engine's* ABI.
The echo *guest* needs none — `wasm-tools component new` is already pinned and
already used by `bb spike-host`, and `wit-bindgen` generates the guest side for
Rust or C. The real cost is a guest crate plus a Clojure driver over the `0011`
spike, and **the scalar half could have been landed as a test already.**

`(= v (echo v))` is the right assertion for `bool`, the integers, `string`,
`list`, `tuple`, `record`, `enum`, `flags` and `variant` — and wrong for the
rest, each for its own reason:

| row | assertion |
|---|---|
| `f32` | round to `f32` before comparing |
| any float, NaN | compare `doubleToRawLongBits`; `(= NaN NaN)` is false |
| any float, `-0.0` | `=` **passes spuriously** — `(= 0.0 -0.0)` is true, so a lost sign of zero goes undetected |
| `char` | generator must emit `Integer`s; `(= \a 97)` is false |
| `result` | the sugar throws, so assert on the thrown `ex-data`; the tagged form uses `=` |
| `own` | a lifted handle is a fresh object — assert on the underlying resource, not `=` |
| `map` | assert length on the WIT side; dedup and reorder are invisible to `=` |

## What the first draft got wrong

Recorded rather than silently fixed, because the pattern is the project's own:
it summarised `Explainer.md`'s grammar line and read neither the gate legend
forty lines above nor the despecialisation prose two hundred below.

It presented `map`, `list<T,N>` and `error-context` — none in a shipped
release — as peers of `bool`, while deferring `stream`/`future`, which *are*
shipped. It defined `result` only for return position. It pointed two table
rows at the wrong lossy cases. It rejected GC finalisation on JavaScript
evidence. It named two lossy mappings where there are ten. And it justified not
testing with a cost it need not pay.

[WebAssembly/component-model]: https://github.com/WebAssembly/component-model
[bytecodealliance/wit-bindgen]: https://github.com/bytecodealliance/wit-bindgen

---
paths:
  - "doc/design/**"
---

# Design notes

`doc/design/NNNN-slug.md` is where a decision goes when it **closes off
alternatives someone might otherwise reopen**. Everything smaller belongs in the
commit message.

## Shape

```
# NNNN — Title

**Status:** proposed | accepted | superseded by NNNN · YYYY-MM-DD

## The question               ← what was actually undecided
## The decision
## Why                        ← including the evidence, with its source
## Alternatives rejected      ← and what each would have bought
## What would falsify this    ← for anything not yet measured
```

Numbers are sequential and permanent. A superseded note keeps its number and
gains a `Status:` pointer; it is never deleted, because the reasoning that was
wrong is the most useful part later.

## Gotchas

- **Writing the note after the code decides nothing.** The note exists to make
  the choice visible while it is still a choice. If you have already
  implemented it, the note will rationalize rather than reason.
- **"Alternatives rejected" with one entry is a warning sign.** If nothing else
  was seriously considered, the decision was probably not load-bearing enough
  to need a note — or the alternatives were not looked for.
- **Unmeasured claims must say so.** Status `proposed` and a "what would
  falsify this" section. A note that reads as settled but rests on intuition is
  worse than no note, because the next reader will not re-check it.
- **Do not cite a path outside this repository.** Sibling projects, papers, and
  vendor docs are cited by URL. A reference someone cannot open is not evidence.
- **Amend rather than accumulate.** When a prediction in an old note turns out
  wrong, edit that note and say which prediction failed. A trail of corrections
  spread across new notes is unreadable within a year.

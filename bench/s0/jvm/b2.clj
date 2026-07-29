(ns s0.jvm.b2
  "B2 baseline — JVM Clojure protocol dispatch at a *megamorphic* call site.

   The same ring walk as bench/s0/b2_megamorphic.wat: eleven nodes over ten
   types, so one call site sees ten receiver classes. This is the case Clojure's
   per-call-site cache is worst at — a static field holding the last seen class,
   which every step invalidates — and the case doc/design/0004-* predicts a
   vtable slot wins, because a slot has no cache to thrash.

   B1 is the same code with one receiver type. The difference between them, on
   each lane, is what megamorphism costs there."
  (:require [s0.jvm.harness :as h]))

(defprotocol PNext
  (pnext [this]))

(defprotocol PLink
  (set-next! [this n]))

;; The walk ends on whichever of the ten types (mod n 11) lands on, so reading
;; the answer needs an accessor that works for all of them. Called once per run,
;; outside the timed loop's hot path.
(defprotocol PTag
  (tag-of [this]))

;; Ten types rather than a macro that generates them: a benchmark should show
;; the reader exactly what is being measured.
(deftype T0 [^:unsynchronized-mutable nxt ^long tag]
  PNext (pnext [_] nxt)
  PLink (set-next! [_ n] (set! nxt n))
  PTag  (tag-of [_] tag))
(deftype T1 [^:unsynchronized-mutable nxt ^long tag]
  PNext (pnext [_] nxt)
  PLink (set-next! [_ n] (set! nxt n))
  PTag  (tag-of [_] tag))
(deftype T2 [^:unsynchronized-mutable nxt ^long tag]
  PNext (pnext [_] nxt)
  PLink (set-next! [_ n] (set! nxt n))
  PTag  (tag-of [_] tag))
(deftype T3 [^:unsynchronized-mutable nxt ^long tag]
  PNext (pnext [_] nxt)
  PLink (set-next! [_ n] (set! nxt n))
  PTag  (tag-of [_] tag))
(deftype T4 [^:unsynchronized-mutable nxt ^long tag]
  PNext (pnext [_] nxt)
  PLink (set-next! [_ n] (set! nxt n))
  PTag  (tag-of [_] tag))
(deftype T5 [^:unsynchronized-mutable nxt ^long tag]
  PNext (pnext [_] nxt)
  PLink (set-next! [_ n] (set! nxt n))
  PTag  (tag-of [_] tag))
(deftype T6 [^:unsynchronized-mutable nxt ^long tag]
  PNext (pnext [_] nxt)
  PLink (set-next! [_ n] (set! nxt n))
  PTag  (tag-of [_] tag))
(deftype T7 [^:unsynchronized-mutable nxt ^long tag]
  PNext (pnext [_] nxt)
  PLink (set-next! [_ n] (set! nxt n))
  PTag  (tag-of [_] tag))
(deftype T8 [^:unsynchronized-mutable nxt ^long tag]
  PNext (pnext [_] nxt)
  PLink (set-next! [_ n] (set! nxt n))
  PTag  (tag-of [_] tag))
(deftype T9 [^:unsynchronized-mutable nxt ^long tag]
  PNext (pnext [_] nxt)
  PLink (set-next! [_ n] (set! nxt n))
  PTag  (tag-of [_] tag))

;; Matches $ring-len in the WAT: prime, so the driver can refuse an n whose
;; expected answer is also what a loop running zero iterations returns.
(def ring-len 11)

(def ^:private ctors [->T0 ->T1 ->T2 ->T3 ->T4 ->T5 ->T6 ->T7 ->T8 ->T9])

(defn- ring
  "Eleven nodes over ten types — node i has type (mod i 10), so the walk below
   presents ten distinct classes to one call site."
  []
  (let [nodes (mapv (fn [i] ((ctors (mod i 10)) nil i)) (range ring-len))]
    (dotimes [i ring-len]
      (set-next! (nodes i) (nodes (mod (inc i) ring-len))))
    (nodes 0)))

(def ^:private head (ring))

(defn- mono-ring
  "Eleven nodes, all T0 — the JVM counterpart of bench_mono. Lets B2's JVM row
   be compared against a monomorphic site built the same way, rather than
   against B1, whose types are declared in a different file."
  []
  (let [nodes (mapv #(->T0 nil %) (range ring-len))]
    (dotimes [i ring-len]
      (set-next! (nodes i) (nodes (mod (inc i) ring-len))))
    (nodes 0)))

(def ^:private mono-head (mono-ring))

(defn walk
  "n protocol dispatches; returns the final node's tag, which is (mod n 11)."
  ^long [^long n]
  (loop [o head i n]
    (if (zero? i)
      (long (tag-of o))
      (recur (pnext o) (dec i)))))

(defn walk-mono
  "The same walk over a ring of one type — the JVM's monomorphic baseline for
   this file's types."
  ^long [^long n]
  (loop [o mono-head i n]
    (if (zero? i)
      (long (tag-of o))
      (recur (pnext o) (dec i)))))

(defn -main [& args]
  (h/main {"dispatch" walk "mono" walk-mono} args))

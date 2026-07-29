(ns s0.jvm.b1
  "B1 baseline — JVM Clojure protocol dispatch at a monomorphic call site.

   The same ring walk as bench/s0/b1_protocol.wat: every step is one protocol
   call whose result is the receiver of the next, so the two lanes measure the
   same dependency chain. `pnext` here compiles to Clojure's call-site cache —
   a static field holding the last seen class, an instanceof fast path, and a
   MethodImplCache behind it. That machinery is what doc/design/0004-* claims a
   vtable slot can replace."
  (:require [s0.jvm.harness :as h]))

(defprotocol PNext
  (pnext [this]))

(defprotocol PLink
  (set-next! [this n]))

(deftype Node [^:unsynchronized-mutable nxt ^long tag]
  PNext
  (pnext [_] nxt)
  PLink
  (set-next! [_ n] (set! nxt n)))

(def ring-len 10)

(defn- ring
  "A cycle of `k` nodes, all of one type — so the call site below sees exactly
   one receiver class and the JVM's cache never misses. That is the JVM at its
   best, which is the baseline B1 has to be measured against."
  [^long k]
  (let [nodes (mapv #(->Node nil %) (range k))]
    (dotimes [i k]
      (set-next! (nodes i) (nodes (mod (inc i) k))))
    (nodes 0)))

(def ^:private head (ring ring-len))

(defn walk
  "n protocol dispatches; returns the final node's tag, which is (mod n 10)."
  ^long [^long n]
  (loop [o head i n]
    (if (zero? i)
      (.tag ^Node o)
      (recur (pnext o) (dec i)))))

(defn walk-direct
  "Control matching bench_direct in the WAT: the same ring walk with the
   dispatch removed, calling the generated protocol interface straight. What is
   left is the cost of chasing the ring, so `walk` minus this is dispatch."
  ^long [^long n]
  (loop [o head i n]
    (if (zero? i)
      (.tag ^Node o)
      (recur (.pnext ^s0.jvm.b1.PNext o) (dec i)))))

(defn -main [& args]
  (h/main {"dispatch" walk "direct" walk-direct} args))

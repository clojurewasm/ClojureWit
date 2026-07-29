(ns s0.jvm.b3
  "B3 baseline — JVM Clojure boxed arithmetic.

   The same accumulate-by-one loop as bench/s0/b3_arith.wat, with the operands
   boxed so `+` goes through `clojure.lang.Numbers.add(Object, Object)` — the
   double dispatch doc/design/0004-* claims an inline i31 path replaces.

   `unboxed` is the floor: the identical loop on primitive longs, which is what
   the JVM does when it can prove the types. Boxed minus unboxed is what the
   JVM pays for not knowing."
  (:require [s0.jvm.harness :as h]))

(set! *unchecked-math* false)

(defn boxed
  "n boxed additions. `acc` and `one` are Objects, so `+` cannot be inlined to
   a primitive add and goes through Numbers. Returns n."
  ^long [^long n]
  (let [one (Long/valueOf 1)]
    (loop [acc (Long/valueOf 0) i n]
      (if (zero? i)
        (long acc)
        (recur (+ acc one) (dec i))))))

(defn unboxed
  "The floor: the same loop on primitives."
  ^long [^long n]
  (loop [acc 0 i n]
    (if (zero? i)
      acc
      (recur (unchecked-add acc 1) (dec i)))))

(defn -main [& args]
  (h/main {"boxed" boxed "unboxed" unboxed} args))

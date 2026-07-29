(ns cljwit.spike.ffm-shape
  "Does an FFM downcall get expensive because of its *shape*?

   `0013` measured the same `wasmtime_func_call` at 75 ns from C and 1645 ns
   through FFM, while a trivial one-int downcall costs 7 ns in the same run.
   Two explanations survive that: FFM is slow for seven-parameter,
   five-pointer signatures, or something about entering wasmtime from a JVM
   thread is. This varies only the callee — same binding, same proxy, same
   argument shape, a C function that does nothing."
  (:require [clojure.string :as str])
  (:import [java.lang.foreign Arena Linker SymbolLookup MemorySegment
            FunctionDescriptor ValueLayout]
           [java.lang.invoke MethodHandleProxies]))

(definterface Shape7
  (^java.lang.foreign.MemorySegment call
   [^java.lang.foreign.MemorySegment cx ^java.lang.foreign.MemorySegment f
    ^java.lang.foreign.MemorySegment args ^long nargs
    ^java.lang.foreign.MemorySegment res ^long nres
    ^java.lang.foreign.MemorySegment trap]))

(definterface IntToInt (^int call [^int x]))

(def ^:private ADDR ValueLayout/ADDRESS)
(def ^:private I32 ValueLayout/JAVA_INT)
(def ^:private I64 ValueLayout/JAVA_LONG)

;; No primitive hints: Clojure caps those at four arguments per fn.
(defn- report [label f n reps warmup]
  (dotimes [_ warmup] (f n))
  (let [runs (sort (repeatedly reps #(let [t0 (System/nanoTime)
                                           acc (f n)
                                           dt  (- (System/nanoTime) t0)]
                                       (when (= acc ::never) (println))
                                       (/ (double dt) n))))
        v    (vec runs)]
    (println (format "%-34s %8.1f ns/call   [%.1f .. %.1f]"
                     label (nth v (quot reps 2)) (first v) (peek v)))))

(defn -main [& _]
  (let [linker (Linker/nativeLinker)]
    (with-open [arena ^Arena (Arena/ofConfined)]
      (let [dylib (str "target/libnoop7"
                       (if (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
                         ".dylib" ".so"))
            lookup (SymbolLookup/libraryLookup ^String dylib arena)
            fd     (FunctionDescriptor/of ADDR (into-array java.lang.foreign.MemoryLayout
                                                           [ADDR ADDR ADDR I64 ADDR I64 ADDR]))
            bind   (fn [nm] ^Shape7 (MethodHandleProxies/asInterfaceInstance
                                     Shape7
                                     (.downcallHandle linker
                                                      ^MemorySegment (.orElseThrow (.find lookup nm))
                                                      fd (into-array java.lang.foreign.Linker$Option []))))
            p      (bind "noop7")
            busy   (bind "busy7")
            ;; Five live segments, as wasmtime_func_call is given.
            s      (fn [] ^MemorySegment (.allocate arena (long 32)))
            [cx f args res trap] (repeatedly 5 s)
            abs-p  ^IntToInt (MethodHandleProxies/asInterfaceInstance
                              IntToInt (.downcallHandle
                                        linker
                                        ^MemorySegment (.orElseThrow
                                                        (.find (.defaultLookup linker) "abs"))
                                        (FunctionDescriptor/of I32 (into-array java.lang.foreign.MemoryLayout [I32]))
                                        (into-array java.lang.foreign.Linker$Option [])))]
        (report "trivial native call (1 int)"
                (fn [^long k]
                  (loop [i 0 acc 0]
                    (if (< i k) (recur (inc i) (unchecked-add acc (long (.call abs-p (int (- i)))))) acc)))
                2000000 21 5)
        (report "noop, wasmtime_func_call's shape"
                (fn [^long k]
                  (loop [i 0 acc 0]
                    (if (< i k)
                      (recur (inc i)
                             (unchecked-add acc (.address ^MemorySegment
                                                 (.call ^Shape7 p cx f args (long 2) res (long 1) trap))))
                      acc)))
                2000000 21 5)
        (report "same shape, ~75 ns of real work"
                (fn [^long k]
                  (loop [i 0 acc 0]
                    (if (< i k)
                      (recur (inc i)
                             (unchecked-add acc (.address ^MemorySegment
                                                 (.call ^Shape7 busy cx f args (long 2) res (long 1) trap))))
                      acc)))
                500000 21 5)))))

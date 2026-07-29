#!/usr/bin/env bb
;; S0 benchmark driver — `bb bench-s0`. See bench/s0/README.md for the contract
;; and .claude/rules/measurement.md for what makes a number here trustworthy.
;;
;;   bb bench-s0                      every benchmark, default sizes
;;   bb bench-s0 B1                   just B1
;;   bb bench-s0 --n 5000000 --reps 9 a quicker, noisier pass
;;
;; Everything it prints — machine, versions, per-lane medians, the command to
;; reproduce — is what gets pasted into doc/design/0002-measure-first.md.

(ns s0.run
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def out-dir "bench/s0/out")

;; `:export`/`:variant` name the Wasm export and the JVM function, so a module
;; and its control are two entries over one .wat file rather than two files.
;; `:expect` is what one invocation must return for a given n — a benchmark that
;; is fast because it is computing the wrong thing has to fail, not just print.
(def benchmarks
  [{:id "B1"
    :what "vtable-slot protocol dispatch, monomorphic — 3 loads + call_ref"
    :wat "bench/s0/b1_protocol.wat" :export "bench"
    :jvm "s0.jvm.b1" :variant "dispatch"
    :expect #(mod % 11)}
   {:id "B1L"
    :what "control: call_ref, target one load off the receiver — the collapsed vtable"
    :wat "bench/s0/b1_protocol.wat" :export "bench_one_load"
    :expect #(mod % 11)}
   {:id "B1i"
    :what "control: call_ref with the target in hand — no load off the receiver"
    :wat "bench/s0/b1_protocol.wat" :export "bench_indirect"
    :expect #(mod % 11)}
   {:id "B1c"
    :what "control: the same ring walk with the dispatch removed"
    :wat "bench/s0/b1_protocol.wat" :export "bench_direct"
    :jvm "s0.jvm.b1" :variant "direct"
    :expect #(mod % 11)}
   {:id "B2"
    :what "the same site with ten receiver types — where the design's claim lives"
    :wat "bench/s0/b2_megamorphic.wat" :export "bench"
    :jvm "s0.jvm.b2" :variant "dispatch"
    :expect #(mod % 11)}
   ;; B1's shape rebuilt inside B2's type graph. B2 minus this is megamorphism
   ;; with hierarchy depth held fixed; B2 minus B1 would vary both.
   {:id "B2m"
    :what "control: the same type graph, one receiver type — B1's shape at B2's depth"
    :wat "bench/s0/b2_megamorphic.wat" :export "bench_mono"
    :jvm "s0.jvm.b2" :variant "mono"
    :expect #(mod % 11)}
   ;; Pairs with B2m, not B2: same ring, same callee, dispatch removed. No JVM
   ;; row, because on the JVM a "direct call" to a protocol method is an
   ;; interface call — a different construct, and subtracting differently-built
   ;; controls prices the controls. Whether C2 devirtualises is answered by B2
   ;; against B2m instead.
   {:id "B2c"
    :what "control: the one-type ring and the same callee, called directly"
    :wat "bench/s0/b2_megamorphic.wat" :export "bench_direct"
    :expect #(mod % 11)}
   ;; B5 — the lever B1 identified. Compare against B2m (generic dispatch, same
   ;; ring) and B2c (the direct-call floor, same ring and callee); the miss
   ;; variant against B2 (generic dispatch, mixed ring).
   {:id "B5"
    :what "guarded specialisation, every step hits — what the lever buys"
    :wat "bench/s0/b2_megamorphic.wat" :export "bench_guarded_hit"
    :expect #(mod % 11)}
   {:id "B5x"
    :what "guarded specialisation, 2 of 11 steps hit — what it costs when wrong"
    :wat "bench/s0/b2_megamorphic.wat" :export "bench_guarded_miss"
    :expect #(mod % 11)}
   ;; B7 — the crossover. Five rings of two types, varying only how often the
   ;; guard hits, each measured guarded and generic so the comparison is on one
   ;; ring at a time.
   {:id "B7g1"
    :what "crossover: guarded, 1 of 11 steps hit"
    :wat "bench/s0/b2_megamorphic.wat" :export "b7_guarded_1"
    :expect #(mod % 11)}
   {:id "B7n1"
    :what "crossover: generic dispatch on the same ring, 1 of 11 would hit"
    :wat "bench/s0/b2_megamorphic.wat" :export "b7_generic_1"
    :expect #(mod % 11)}
   {:id "B7g2"
    :what "crossover: guarded, 2 of 11 steps hit"
    :wat "bench/s0/b2_megamorphic.wat" :export "b7_guarded_2"
    :expect #(mod % 11)}
   {:id "B7n2"
    :what "crossover: generic dispatch on the same ring, 2 of 11 would hit"
    :wat "bench/s0/b2_megamorphic.wat" :export "b7_generic_2"
    :expect #(mod % 11)}
   {:id "B7g4"
    :what "crossover: guarded, 4 of 11 steps hit"
    :wat "bench/s0/b2_megamorphic.wat" :export "b7_guarded_4"
    :expect #(mod % 11)}
   {:id "B7n4"
    :what "crossover: generic dispatch on the same ring, 4 of 11 would hit"
    :wat "bench/s0/b2_megamorphic.wat" :export "b7_generic_4"
    :expect #(mod % 11)}
   {:id "B7g5"
    :what "crossover: guarded, 5 of 11 steps hit"
    :wat "bench/s0/b2_megamorphic.wat" :export "b7_guarded_5"
    :expect #(mod % 11)}
   {:id "B7n5"
    :what "crossover: generic dispatch on the same ring, 5 of 11 would hit"
    :wat "bench/s0/b2_megamorphic.wat" :export "b7_generic_5"
    :expect #(mod % 11)}
   {:id "B7g0"
    :what "crossover: guarded, 0 of 11 steps hit"
    :wat "bench/s0/b2_megamorphic.wat" :export "b7_guarded_0"
    :expect #(mod % 11)}
   {:id "B7n0"
    :what "crossover: generic dispatch on the same ring, 0 of 11 would hit"
    :wat "bench/s0/b2_megamorphic.wat" :export "b7_generic_0"
    :expect #(mod % 11)}
   {:id "B7g3"
    :what "crossover: guarded, 3 of 11 steps hit"
    :wat "bench/s0/b2_megamorphic.wat" :export "b7_guarded_3"
    :expect #(mod % 11)}
   {:id "B7n3"
    :what "crossover: generic dispatch on the same ring, 3 of 11 would hit"
    :wat "bench/s0/b2_megamorphic.wat" :export "b7_generic_3"
    :expect #(mod % 11)}
   {:id "B7g6"
    :what "crossover: guarded, 6 of 11 steps hit"
    :wat "bench/s0/b2_megamorphic.wat" :export "b7_guarded_6"
    :expect #(mod % 11)}
   {:id "B7n6"
    :what "crossover: generic dispatch on the same ring, 6 of 11 would hit"
    :wat "bench/s0/b2_megamorphic.wat" :export "b7_generic_6"
    :expect #(mod % 11)}
   {:id "B7g9"
    :what "crossover: guarded, 9 of 11 steps hit"
    :wat "bench/s0/b2_megamorphic.wat" :export "b7_guarded_9"
    :expect #(mod % 11)}
   {:id "B7n9"
    :what "crossover: generic dispatch on the same ring, 9 of 11 would hit"
    :wat "bench/s0/b2_megamorphic.wat" :export "b7_generic_9"
    :expect #(mod % 11)}
   {:id "B7g11"
    :what "crossover: guarded, 11 of 11 steps hit"
    :wat "bench/s0/b2_megamorphic.wat" :export "b7_guarded_11"
    :expect #(mod % 11)}
   {:id "B7n11"
    :what "crossover: generic dispatch on the same ring, 11 of 11 would hit"
    :wat "bench/s0/b2_megamorphic.wat" :export "b7_generic_11"
    :expect #(mod % 11)}
   ;; B4 — the cast, on two axes: depth of the target, and variety of the input.
   ;; Every row is B4n plus one ref.cast.
   {:id "B4n"
    :what "floor: the same walk with no cast at all"
    :wat "bench/s0/b4_cast.wat" :export "cast_none"
    :expect #(mod % 11)}
   {:id "B4d2"
    :what "ref.cast to a type at depth 2, one input type"
    :wat "bench/s0/b4_cast.wat" :export "cast_depth_2"
    :expect #(mod % 11)}
   {:id "B4d3"
    :what "ref.cast to a type at depth 3, one input type"
    :wat "bench/s0/b4_cast.wat" :export "cast_depth_3"
    :expect #(mod % 11)}
   {:id "B4d4"
    :what "ref.cast to a type at depth 4, one input type"
    :wat "bench/s0/b4_cast.wat" :export "cast_depth_4"
    :expect #(mod % 11)}
   {:id "B4d5"
    :what "ref.cast to a type at depth 5, one input type"
    :wat "bench/s0/b4_cast.wat" :export "cast_depth_5"
    :expect #(mod % 11)}
   {:id "B4d6"
    :what "ref.cast to a type at depth 6, one input type"
    :wat "bench/s0/b4_cast.wat" :export "cast_depth_6"
    :expect #(mod % 11)}
   {:id "B4v1"
    :what "ref.cast to depth 5, one input type — the variety axis, held at 1"
    :wat "bench/s0/b4_cast.wat" :export "cast_variety_1"
    :expect #(mod % 11)}
   {:id "B4v10"
    :what "ref.cast to depth 5, ten input types — the axis B2 questioned"
    :wat "bench/s0/b4_cast.wat" :export "cast_variety_10"
    :expect #(mod % 11)}
   ;; B3 — boxed arithmetic. The answer is n itself (add 1, n times), which is
   ;; never what an empty loop returns, so check-n! is satisfied for every n.
   {:id "B3"
    :what "i31 fast-path add, with the overflow check and a reachable slow path"
    :wat "bench/s0/b3_arith.wat" :export "bench"
    :jvm "s0.jvm.b3" :variant "boxed"
    :expect identity}
   {:id "B3n"
    :what "control: the same i31 round-trip without the overflow check"
    :wat "bench/s0/b3_arith.wat" :export "bench_nocheck"
    :expect identity}
   ;; B6 — the component boundary. n counts whole 4 KB payload copies, so the
   ;; driver's ns/op is ns per 4 KB. Run these at a much smaller n than the
   ;; dispatch benchmarks: `bb bench-s0 B6l8 B6l64 B6lift B6mc B6ac --n 20000`.
   {:id "B6l8"
    :what "boundary: (array i8) -> linear memory, one byte per iteration"
    :wat "bench/s0/b6_boundary.wat" :export "lower_i8"
    :expect #(if (zero? %) % (inc %))}
   {:id "B6l64"
    :what "boundary: (array i64) -> linear memory, eight bytes per iteration"
    :wat "bench/s0/b6_boundary.wat" :export "lower_i64"
    :expect #(if (zero? %) % (inc %))}
   {:id "B6lift"
    :what "boundary: linear memory -> (array i8), the lifting direction"
    :wat "bench/s0/b6_boundary.wat" :export "lift_i8"
    :expect #(if (zero? %) % (inc %))}
   {:id "B6mc"
    :what "floor: memory.copy — what a linear-memory language pays"
    :wat "bench/s0/b6_boundary.wat" :export "memcpy"
    :expect #(if (zero? %) % (inc %))}
   {:id "B6ac"
    :what "reference: array.copy — the bulk move that exists, GC to GC"
    :wat "bench/s0/b6_boundary.wat" :export "arraycopy"
    :expect #(if (zero? %) % (inc %))}
   {:id "B3u"
    :what "floor: the same loop unboxed — a raw i32 add"
    :wat "bench/s0/b3_arith.wat" :export "bench_unboxed"
    :jvm "s0.jvm.b3" :variant "unboxed"
    :expect identity}])

;; --- shelling out ----------------------------------------------------------

(defn- sh
  "Runs a command, returns stdout. A failure prints the tool's own stderr and
   stops the run — a benchmark that half-ran produces numbers worse than none."
  [& args]
  (let [{:keys [exit out err]}
        (apply p/shell {:out :string :err :string :continue true} args)]
    (when-not (zero? exit)
      (println (str "\n✗ failed: " (str/join " " args)))
      (print err)
      (flush)
      (System/exit exit))
    out))

(defn- timed-sh
  "Wall-clock nanoseconds for a whole process, stdout and stderr discarded."
  [& args]
  (let [t0 (System/nanoTime)
        _  (apply sh args)]
    (- (System/nanoTime) t0)))

;; --- statistics ------------------------------------------------------------

(defn- median [xs]
  (let [v (vec (sort xs))
        n (count v)]
    (if (odd? n)
      (double (nth v (quot n 2)))
      (/ (+ (nth v (dec (quot n 2))) (nth v (quot n 2))) 2.0))))

;; --- lanes -----------------------------------------------------------------

(def ^:private build
  "wat -> wasm, validated, plus a wasm-opt -O3 variant. Both are reported:
   wasm-opt has been measured at ~1.9x on GC-heavy code elsewhere, and letting
   that land in our column would be crediting binaryen's work to this design.
   Memoized because a benchmark and its control share one .wat file."
  (memoize
   (fn [wat]
     (let [base (str/replace (fs/file-name wat) #"\.wat$" "")
           raw  (str out-dir "/" base ".wasm")
           opt  (str out-dir "/" base ".opt.wasm")]
       (fs/create-dirs out-dir)
       (sh "wasm-tools" "parse" wat "-o" raw)
       (sh "wasm-tools" "validate" raw)
       ;; Every feature any benchmark uses has to be named or wasm-opt refuses
       ;; the whole module — bulk-memory-opt was added when B6 introduced
       ;; memory.copy, and the failure is a build error rather than a wrong
       ;; number, which is the good kind.
       (sh "wasm-opt" "-O3" "--enable-gc" "--enable-tail-call"
           "--enable-reference-types" "--enable-exception-handling"
           "--enable-bulk-memory" "--enable-bulk-memory-opt"
           raw "-o" opt)
       {:raw raw :opt opt}))))

(defn- check-n!
  "Refuses an n whose expected answer is the one a loop that ran zero iterations
   would also give. These benchmarks return a position in a ring, so at a
   multiple of the ring length the walk ends where it started and the result
   check below cannot tell a full benchmark from an empty one. Found by
   deliberately emptying the loop and watching the check pass."
  [{:keys [id expect]} n]
  (when (= (expect n) (expect 0))
    (println (format "\n✗ %s: n=%d makes the result check vacuous — it expects %s,"
                     id n (expect n)))
    (println "  which is also what a loop running zero iterations returns. Pick another n.")
    (System/exit 1)))

(defn- summarize
  "Checks the lane computed what the benchmark claims before reporting a time.
   A wrong-but-fast number is exactly the kind that gets quoted, so a mismatch
   stops the run rather than printing."
  [label {:keys [expect]} {:keys [n]} samples result]
  (when-not (= (expect n) result)
    (println (format "\n✗ %s: returned %s, expected %s" label result (expect n)))
    (System/exit 1))
  ;; The wasmtime lane has no per-run samples — it derives one number from a
  ;; slope — so timing keys are absent rather than fabricated there.
  (cond-> {:result result}
    (seq samples) (assoc :ns-per-op  (/ (median samples) (double n))
                         :min-per-op (/ (double (apply min samples)) n))))

(defn- jvm-lane [{:keys [jvm variant] :as bm} {:keys [n reps warmup] :as opts}]
  (let [m (edn/read-string (sh "clojure" "-M:bench" "-m" jvm variant
                               (str n) (str reps) (str warmup)))]
    (summarize "JVM Clojure" bm opts (:samples m) (:result m))))

(defn- node-lane [bm wasm {:keys [n reps warmup] :as opts}]
  (let [m (json/parse-string (sh "node" "bench/s0/harness.mjs" wasm (:export bm)
                                 (str n) (str reps) (str warmup))
                             true)]
    (summarize (str "V8 " wasm) bm opts (:samples m) (:result m))))

(defn- wasmtime-lane
  "wasmtime has no in-guest clock and no way to loop across invocations, so this
   times the whole process at n and at 2n and takes the slope. Process spawn,
   module compilation and instantiation are identical in both and cancel out.
   `reps` samples are taken at each of the two points rather than split between
   them: .claude/rules/measurement.md asks for a median over at least 20 runs,
   and a median over 10 is not that."
  [bm wasm {:keys [n reps] :as opts}]
  (let [export (:export bm)
        at     (fn [k] (median (repeatedly reps #(timed-sh "wasmtime" "run"
                                                           "--invoke" export wasm (str k)))))
        t1     (at n)
        t2     (at (* 2 n))
        result (parse-long (str/trim (last (str/split-lines
                                            (sh "wasmtime" "run" "--invoke" export wasm (str n))))))]
    (assoc (summarize (str "wasmtime " wasm) bm opts [] result)
           :ns-per-op (/ (- t2 t1) (double n)))))

;; --- reporting -------------------------------------------------------------

(defn- machine []
  (let [os (sh "uname" "-sm")]
    (str (str/trim os) " · "
         (str/trim (if (str/starts-with? os "Darwin")
                     (sh "sysctl" "-n" "machdep.cpu.brand_string")
                     (sh "bash" "-c" "grep -m1 'model name' /proc/cpuinfo | cut -d: -f2"))))))

(defn- versions []
  (str/join " · " (for [[tool & args] [["wasmtime" "--version"]
                                       ["wasm-opt" "--version"]
                                       ["wasm-tools" "--version"]
                                       ["node" "--version"]
                                       ["java" "-version"]]]
                    (-> (if (= tool "java")
                          (:err (p/shell {:out :string :err :string} "java" "-version"))
                          (apply sh tool args))
                        str/split-lines first str/trim))))

(defn- report-row [label {:keys [ns-per-op min-per-op result]} jvm-ns-per-op]
  (println (format "  %-26s %8.3f %11s %10s %9s"
                   label
                   ns-per-op
                   (if min-per-op (format "%.3f" min-per-op) "—")
                   (str result)
                   (if jvm-ns-per-op
                     (format "%.2fx" (/ ns-per-op jvm-ns-per-op))
                     "—"))))

(defn- run-benchmark [{:keys [id what wat export] :as bm} opts]
  (check-n! bm (:n opts))
  (println (format "\n%s — %s" id what))
  (println (format "  n=%d  reps=%d  warmup=%d (in-process lanes only)  (%s, export %s)"
                   (:n opts) (:reps opts) (:warmup opts) wat export))
  (println (format "  %-26s %8s %11s %10s %9s"
                   "lane" "ns/op" "min ns/op" "result" "vs JVM"))
  (let [{:keys [raw opt]} (build wat)
        ;; A Wasm-only control has no JVM counterpart; the "vs JVM" column is
        ;; then left empty rather than filled against some other benchmark's
        ;; baseline.
        jvm  (when (:jvm bm) (jvm-lane bm opts))
        base (:ns-per-op jvm)]
    (when jvm (report-row "JVM Clojure" jvm nil))
    (report-row "V8 (node)" (node-lane bm raw opts) base)
    (report-row "V8 (node, wasm-opt -O3)" (node-lane bm opt opts) base)
    (report-row "wasmtime" (wasmtime-lane bm raw opts) base)
    (report-row "wasmtime (wasm-opt -O3)" (wasmtime-lane bm opt opts) base)))

;; --- entry point -----------------------------------------------------------

(defn- positive-long [flag s]
  (let [v (some-> s parse-long)]
    (when-not (and v (pos? v))
      (println (format "✗ %s needs a positive integer, got %s" flag (pr-str s)))
      (System/exit 1))
    v))

(def ^:private flags {"--n" :n "--reps" :reps "--warmup" :warmup})

(defn- parse-args [args]
  (loop [args args opts {:n 20000000 :reps 20 :warmup 5} ids []]
    (if-let [a (first args)]
      (if-let [k (flags a)]
        (recur (drop 2 args) (assoc opts k (positive-long a (second args))) ids)
        (recur (rest args) opts (conj ids a)))
      [opts (set ids)])))

(let [[opts ids] (parse-args *command-line-args*)
      selected   (if (seq ids) (filter (comp ids :id) benchmarks) benchmarks)]
  (when (empty? selected)
    (println "no such benchmark. known:" (str/join ", " (map :id benchmarks)))
    (System/exit 1))
  (println "S0 dispatch benchmarks")
  (println " " (machine))
  (println " " (versions))
  (println "  reproduce: bb bench-s0" (str/join " " *command-line-args*))
  (doseq [bm selected] (run-benchmark bm opts))
  (println)
  (println "Numbers here are medians. Paste them into the measured column of")
  (println "doc/design/0002-measure-first.md together with the two lines above."))

(ns s0.jvm.harness
  "Timing loop shared by the JVM baselines. The Wasm lanes use
   bench/s0/harness.mjs; both report raw per-run nanoseconds and let
   bench/s0/run.clj do the statistics, so there is one definition of median.")

(defn samples
  "Calls (f n) `warmup` times to let C2 settle, then `reps` more, timing each.
   Returns {:samples [<ns per run>] :checksum <xor of all> :result <last>}. The
   checksum keeps every result consumed, because a result nothing reads is a
   loop the JIT is entitled to delete; :result is what the driver checks, since
   an xor over an even number of identical runs is 0 whatever they computed."
  [f ^long n ^long reps ^long warmup]
  (let [warm (loop [i 0 c 0]
               (if (< i warmup)
                 (recur (inc i) (bit-xor c (long (f n))))
                 c))]
    (loop [i 0 c warm last-v 0 acc (transient [])]
      (if (< i reps)
        (let [t0 (System/nanoTime)
              v  (long (f n))
              t1 (System/nanoTime)]
          (recur (inc i) (bit-xor c v) v (conj! acc (- t1 t0))))
        {:samples (persistent! acc) :checksum c :result last-v}))))

(defn main
  "Entry point every baseline's -main delegates to. `variants` maps the name the
   driver passes to the function to measure, so one namespace can hold a
   benchmark and its control. Command line is `<variant> <n> <reps> <warmup>`;
   the samples are printed as EDN on stdout."
  [variants args]
  (let [[variant & more] args
        f   (or (get variants variant)
                (throw (ex-info (str "no such variant: " variant)
                                {:known (sort (keys variants))})))
        num (fn [i default] (Long/parseLong (or (nth more i nil) default)))]
    (prn (samples f (num 0 "20000000") (num 1 "20") (num 2 "5")))))

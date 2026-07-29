(ns cljwit.spike.call-cost
  "What one call costs, measured so the number can be quoted.

;; A `MemorySegment.get` whose layout argument is untyped resolves reflectively,
;; and a reflective 4-byte read costs ~1.5 us. That is not a style preference
;; here: it silently became the headline number of two design notes.
(set! *warn-on-reflection* true)

   Replaces the decomposition in `cljwit.spike.call-overhead`, which measured
   every path in one JVM through `invokeWithArguments` and so contaminated
   itself — see `doc/design/0011`. Three changes:

     the hot loop calls through an interface proxy, not invokeWithArguments,
     because the proxy was the only stable thing in the old run;
     every error return is checked, so a call that silently failed cannot be
     timed as if it succeeded;
     one path per JVM — `bb spike-cost` runs it once per path.

   `wasmtime_func_call_unchecked` is back. It measured 2.4× *slower* than the
   checked path in the contaminated harness, which the header rules out (it
   states plainly that the unchecked path is the faster one), so that was the
   contamination rather than misuse.

   Usage: `bb spike-cost` — or `clojure -M:dev -m cljwit.spike.call-cost <path>`
   with one of: trivial, core, core-raw, component."
  (:require [clojure.java.io :as io])
  (:import [java.lang.foreign Arena FunctionDescriptor Linker MemorySegment
            SymbolLookup ValueLayout]
           [java.lang.invoke MethodHandle MethodHandleProxies]))

;; One interface per hot signature. The interface method is a static call site,
;; which is the thing Clojure cannot write by hand.
(definterface IntToInt
  (^int call [^int x]))

(definterface ComponentCall
  (^java.lang.foreign.MemorySegment call
   [^java.lang.foreign.MemorySegment f ^java.lang.foreign.MemorySegment cx
    ^java.lang.foreign.MemorySegment args ^long nargs
    ^java.lang.foreign.MemorySegment res ^long nres]))

(definterface CoreRawCall
  (^java.lang.foreign.MemorySegment call
   [^java.lang.foreign.MemorySegment cx ^java.lang.foreign.MemorySegment f
    ^java.lang.foreign.MemorySegment argsres ^long n
    ^java.lang.foreign.MemorySegment trap]))

(definterface CoreCall
  (^java.lang.foreign.MemorySegment call
   [^java.lang.foreign.MemorySegment cx ^java.lang.foreign.MemorySegment f
    ^java.lang.foreign.MemorySegment args ^long nargs
    ^java.lang.foreign.MemorySegment res ^long nres
    ^java.lang.foreign.MemorySegment trap]))

(def ^:private ^java.lang.foreign.AddressLayout ADDR ValueLayout/ADDRESS)
(def ^:private ^java.lang.foreign.ValueLayout$OfInt I32 ValueLayout/JAVA_INT)
(def ^:private ^java.lang.foreign.ValueLayout$OfLong I64 ValueLayout/JAVA_LONG)
(def ^:private ^java.lang.foreign.ValueLayout$OfByte I8 ValueLayout/JAVA_BYTE)
(def ^:private VAL-SIZE 32)
(def ^:private UNION 8)
(def ^:private KIND-S32 5)

(defn- handle [^SymbolLookup lookup ^Linker linker nm ret & args]
  (let [seg ^MemorySegment (.orElseThrow (.find lookup (name nm)))
        fd  ^FunctionDescriptor
        (if ret
          (FunctionDescriptor/of ret (into-array java.lang.foreign.MemoryLayout args))
          (FunctionDescriptor/ofVoid (into-array java.lang.foreign.MemoryLayout args)))]
    (.downcallHandle linker seg fd (into-array java.lang.foreign.Linker$Option []))))

(defn- setup-call
  "Setup only. Boxing here costs nothing — these run once."
  [^MethodHandle mh & args]
  (.invokeWithArguments mh ^java.util.List (vec args)))

(defn- ok!
  "wasmtime returns a non-null error pointer on failure. Timing a call that
   failed is how a fast wrong number happens."
  [what v]
  (let [^MemorySegment e v]
    (when-not (.equals MemorySegment/NULL e)
      (throw (ex-info (str what " failed") {:error-ptr (.address e)})))
    v))

(defn- report
  "Median and spread over `reps` runs of `n` calls. The spread is printed
   because the previous attempt at this measurement was defeated by it."
  ;; Not primitive-hinted: Clojure allows at most four primitive parameters,
  ;; and these are loop bounds rather than a hot path.
  [label f n reps warmup]
  (dotimes [_ warmup] (f n))
  (let [runs (vec (repeatedly reps #(let [t0 (System/nanoTime)
                                          v  (f n)
                                          t1 (System/nanoTime)]
                                      [(- t1 t0) v])))
        ns   (vec (sort (map first runs)))
        per  (fn [x] (/ (double x) (double n)))]
    (println (format "%-34s %9.1f ns/call   (min %.1f  max %.1f  spread %.2fx  checksum %d)"
                     label
                     (per (nth ns (quot reps 2)))
                     (per (first ns)) (per (peek ns))
                     (/ (double (peek ns)) (double (first ns)))
                     (reduce + (map second runs))))))

(defn- lib [] (or (System/getenv "CLJWIT_WASMTIME_LIB")
                  (throw (ex-info "CLJWIT_WASMTIME_LIB unset — run in `nix develop`" {}))))

(defn -main [& [path]]
  (let [linker (Linker/nativeLinker)]
    (with-open [arena0 (Arena/ofConfined)]
      (let [^Arena arena arena0
            lookup (SymbolLookup/libraryLookup ^String (lib) arena)
            fx     (fn [nm ret & args] (apply handle lookup linker nm ret args))
            cstr   (fn [^String t]
                     (let [b (.getBytes t "UTF-8")
                           s ^MemorySegment (.allocate arena (long (inc (alength b))))]
                       (MemorySegment/copy ^bytes b 0 s I8 0 (alength b))
                       (.set s I8 (long (alength ^bytes b)) (byte 0))
                       s))
            slurp-wasm (fn [f] (.readAllBytes (io/input-stream (io/file f))))
            to-seg (fn [^bytes b]
                     (let [s ^MemorySegment (.allocate arena (long (alength ^bytes b)))]
                       (MemorySegment/copy b 0 s I8 0 (alength b))
                       s))]
        (case path
          "trivial"
          (let [abs-p ^IntToInt (MethodHandleProxies/asInterfaceInstance
                                 IntToInt (handle (.defaultLookup linker) linker "abs" I32 I32))]
            (report "trivial native call (proxy)"
                    (fn [^long k]
                      (loop [i 0 acc 0]
                        (if (< i k)
                          (recur (inc i) (unchecked-add acc (long (.call abs-p (int (- i))))))
                          acc)))
                    2000000 21 5))

          ("core" "core-raw" "component")
          (let [engine (setup-call (fx "wasm_engine_new" ADDR))
                store  (setup-call (fx "wasmtime_store_new" ADDR ADDR ADDR ADDR)
                                   engine MemorySegment/NULL MemorySegment/NULL)
                ctx    (setup-call (fx "wasmtime_store_context" ADDR ADDR) store)]
            (if (#{"core" "core-raw"} path)
              (let [b     (slurp-wasm "dev/resources/add.core.wasm")
                    mout  (.allocate arena ^java.lang.foreign.MemoryLayout ADDR)
                    _     (ok! "module_new" (setup-call (fx "wasmtime_module_new" ADDR ADDR ADDR I64 ADDR)
                                                        engine (to-seg b) (long (alength ^bytes b)) mout))
                    modul (.get ^MemorySegment mout ADDR (long 0))
                    inst  (.allocate arena (long 16))
                    trap  (.allocate arena ^java.lang.foreign.MemoryLayout ADDR)
                    _     (ok! "instance_new" (setup-call (fx "wasmtime_instance_new" ADDR ADDR ADDR ADDR I64 ADDR ADDR)
                                                          ctx modul MemorySegment/NULL (long 0) inst trap))
                    extrn ^MemorySegment (.allocate arena (long 32))
                    found (setup-call (fx "wasmtime_instance_export_get" ValueLayout/JAVA_BOOLEAN ADDR ADDR ADDR I64 ADDR)
                                      ctx inst (cstr "add") (long 3) extrn)
                    _     (when-not found (throw (ex-info "export add not found" {})))
                    ;; wasmtime_extern_t is {kind:u8, union at 8}; a func is the
                    ;; first union member and is 16 bytes.
                    f     ^MemorySegment (.asSlice extrn (long 8) (long 16))
                    args  ^MemorySegment (.allocate arena (long 64))
                    res   ^MemorySegment (.allocate arena (long 32))
                    _     (doseq [[i v] (map-indexed vector [17 25])]
                            (let [base (long (* i 32))]
                              (.set args I8 base (byte 0))     ; WASMTIME_I32
                              (.set args I32 (+ base 8) (int v))))
                    ;; val_raw is a bare 16-byte union; arguments start at index
                    ;; 0 and the results overwrite them, so the array is
                    ;; max(nargs, nresults) elements.
                    ;; 16-byte aligned on purpose. Arena.allocate(long) gives
                    ;; alignment 1, and wasmtime_val_raw_t is a union that
                    ;; includes v128 — so an unaligned buffer is the obvious
                    ;; suspect for the unchecked path measuring slower than the
                    ;; checked one, which the header says is impossible.
                    raw   ^MemorySegment (.allocate arena (long 32) (long 16))
                    p     ^CoreCall (MethodHandleProxies/asInterfaceInstance
                                     CoreCall (fx "wasmtime_func_call" ADDR ADDR ADDR ADDR I64 ADDR I64 ADDR))
                    pr    ^CoreRawCall (MethodHandleProxies/asInterfaceInstance
                                        CoreRawCall (fx "wasmtime_func_call_unchecked" ADDR ADDR ADDR ADDR I64 ADDR))]
                (if (= path "core")
                  (report "core module call, checked (proxy)"
                          (fn [^long k]
                            (loop [i 0 acc 0]
                              (if (< i k)
                                (do (ok! "func_call" (.call p ctx f args 2 res 1 trap))
                                    (recur (inc i) (unchecked-add acc (long (.get res I32 (long 8))))))
                                acc)))
                          200000 21 5)
                  (report "core module call, unchecked (proxy)"
                          (fn [^long k]
                            (loop [i 0 acc 0]
                              (if (< i k)
                                (do (.set raw I32 (long 0) (int 17))
                                    (.set raw I32 (long 16) (int 25))
                                    (ok! "func_call_unchecked" (.call pr ctx f raw 2 trap))
                                    (recur (inc i) (unchecked-add acc (long (.get raw I32 (long 0))))))
                                acc)))
                          200000 21 5)))

              (let [b     (slurp-wasm "dev/resources/add.component.wasm")
                    cout  (.allocate arena ^java.lang.foreign.MemoryLayout ADDR)
                    _     (ok! "component_new" (setup-call (fx "wasmtime_component_new" ADDR ADDR ADDR I64 ADDR)
                                                           engine (to-seg b) (long (alength ^bytes b)) cout))
                    comp  (.get ^MemorySegment cout ADDR (long 0))
                    clink (setup-call (fx "wasmtime_component_linker_new" ADDR ADDR) engine)
                    inst  (.allocate arena (long 16))
                    _     (ok! "linker_instantiate"
                               (setup-call (fx "wasmtime_component_linker_instantiate" ADDR ADDR ADDR ADDR ADDR)
                                           clink ctx comp inst))
                    eidx  (setup-call (fx "wasmtime_component_instance_get_export_index" ADDR ADDR ADDR ADDR ADDR I64)
                                      inst ctx MemorySegment/NULL (cstr "add") (long 3))
                    f     (.allocate arena (long 24))
                    found (setup-call (fx "wasmtime_component_instance_get_func" ValueLayout/JAVA_BOOLEAN ADDR ADDR ADDR ADDR)
                                      inst ctx eidx f)
                    _     (when-not found (throw (ex-info "component export add not found" {})))
                    args  ^MemorySegment (.allocate arena (long (* 2 VAL-SIZE)))
                    res   ^MemorySegment (.allocate arena (long VAL-SIZE))
                    _     (doseq [[i v] (map-indexed vector [17 25])]
                            (let [base (long (* i VAL-SIZE))]
                              (.set args I8 base (byte KIND-S32))
                              (.set args I32 (long (+ base UNION)) (int v))))
                    p ^ComponentCall (MethodHandleProxies/asInterfaceInstance
                                      ComponentCall (fx "wasmtime_component_func_call" ADDR ADDR ADDR ADDR I64 ADDR I64))]
                (report "component call (proxy)"
                        (fn [^long k]
                          (loop [i 0 acc 0]
                            (if (< i k)
                              (do (ok! "component_func_call" (.call p f ctx args 2 res 1))
                                  (recur (inc i) (unchecked-add acc (long (.get res I32 (long UNION))))))
                              acc)))
                        200000 21 5))))

          (println "usage: -m cljwit.spike.call-cost <trivial|core|component>"))))))

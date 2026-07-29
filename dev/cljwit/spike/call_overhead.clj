(ns cljwit.spike.call-overhead
  "What a downcall costs from Clojure, three ways, so `cljwit.host`'s calling
   convention is chosen on a number rather than on taste.

   `doc/design/0011` records the prediction; do not read it before forming your
   own. Run: `bb spike-overhead` (inside `nix develop`).

   The three:

     invokeWithArguments   what the spike uses. Boxes every argument and
                           return, and is the only thing Clojure's reflective
                           interop can reach.
     interface proxy       MethodHandleProxies/asInterfaceInstance behind a
                           definterface. The interface method is a *static*
                           call site, which is the thing Clojure cannot write
                           by hand — so if this is fast, pure Clojure has a
                           fast path.
     component call        a real scalar call across a component boundary, for
                           scale. The others only matter relative to this.
     core module, 2 ways   the same add(i32,i32) as a plain core module, called
                           dynamically and raw. Same host, same library, same
                           machine — so the component figure can be split into
                           binding cost, dynamic-calling cost, and what the
                           Component Model itself adds.

   `abs` stands in for a trivial native callee: one int in, one int out, no
   allocation, so what is measured is the call mechanism rather than the work.
   The argument varies with the loop index — with a constant, a sufficiently
   clever JIT could fold the whole call away and the fast path would look
   free for the wrong reason."
  (:require [clojure.java.io :as io])
  (:import [java.lang.foreign Arena FunctionDescriptor Linker MemorySegment
            SymbolLookup ValueLayout]
           [java.lang.invoke MethodHandle MethodHandleProxies]))

(definterface IntToInt (^int call [^int x]))

(def ^:private ADDR ValueLayout/ADDRESS)
(def ^:private I32 ValueLayout/JAVA_INT)
(def ^:private I64 ValueLayout/JAVA_LONG)
(def ^:private I8 ValueLayout/JAVA_BYTE)
(def ^:private VAL-SIZE 32)
(def ^:private VAL-UNION-OFFSET 8)
(def ^:private KIND-S32 5)

(defn- downcall [^SymbolLookup lookup ^Linker linker nm ret & args]
  (let [seg ^MemorySegment (.orElseThrow (.find lookup (name nm)))
        fd  ^FunctionDescriptor
        (if ret
          (FunctionDescriptor/of ret (into-array java.lang.foreign.MemoryLayout args))
          (FunctionDescriptor/ofVoid (into-array java.lang.foreign.MemoryLayout args)))]
    (.downcallHandle linker seg fd (into-array java.lang.foreign.Linker$Option []))))

(defn- timed
  "Median ns per call over `reps` runs of `n` calls, after `warmup` runs.
   `f` takes the iteration count and returns something the caller consumes, so
   the JIT cannot delete the loop."
  [f ^long n ^long reps ^long warmup]
  (dotimes [_ warmup] (f n))
  (let [samples (vec (repeatedly reps #(let [t0 (System/nanoTime)
                                             v  (f n)
                                             t1 (System/nanoTime)]
                                         [(- t1 t0) v])))
        ns (sort (map first samples))]
    {:ns-per-call (/ (double (nth ns (quot reps 2))) n)
     :checksum (reduce + (map second samples))}))

(defn -main [& _]
  (let [linker (Linker/nativeLinker)]
    (with-open [arena0 (Arena/ofConfined)]
      (let [^Arena arena arena0
            libc  (.defaultLookup linker)
            abs-h ^MethodHandle (downcall libc linker "abs" I32 I32)
            abs-p ^IntToInt (MethodHandleProxies/asInterfaceInstance IntToInt abs-h)

            lib    (or (System/getenv "CLJWIT_WASMTIME_LIB")
                       (throw (ex-info "CLJWIT_WASMTIME_LIB unset — run in `nix develop`" {})))
            lookup (SymbolLookup/libraryLookup ^String lib arena)
            fx     (fn [nm ret & args] (apply downcall lookup linker nm ret args))
            call   (fn [^MethodHandle mh & args] (.invokeWithArguments mh ^java.util.List (vec args)))

            engine (call (fx "wasm_engine_new" ADDR))
            store  (call (fx "wasmtime_store_new" ADDR ADDR ADDR ADDR)
                         engine MemorySegment/NULL MemorySegment/NULL)
            ctx    (call (fx "wasmtime_store_context" ADDR ADDR) store)
            wasm   (.readAllBytes (io/input-stream (io/file "dev/resources/add.component.wasm")))
            buf    (.allocate arena (long (count wasm)))
            _      (MemorySegment/copy ^bytes wasm 0 ^MemorySegment buf I8 0 (int (count wasm)))
            cout   (.allocate arena ^java.lang.foreign.MemoryLayout ADDR)
            _      (call (fx "wasmtime_component_new" ADDR ADDR ADDR I64 ADDR)
                         engine buf (long (count wasm)) cout)
            comp   (.get ^MemorySegment cout ADDR (long 0))
            clink  (call (fx "wasmtime_component_linker_new" ADDR ADDR) engine)
            inst   (.allocate arena (long 16))
            _      (call (fx "wasmtime_component_linker_instantiate" ADDR ADDR ADDR ADDR ADDR)
                         clink ctx comp inst)
            nm     (let [b (.getBytes "add" "UTF-8")
                         s ^MemorySegment (.allocate arena (long (inc (alength b))))]
                     (MemorySegment/copy ^bytes b 0 s I8 0 (alength b))
                     (.set s I8 (long (alength b)) (byte 0))
                     s)
            eidx   (call (fx "wasmtime_component_instance_get_export_index" ADDR ADDR ADDR ADDR ADDR I64)
                         inst ctx MemorySegment/NULL nm (long 3))
            func   (.allocate arena (long 24))
            _      (call (fx "wasmtime_component_instance_get_func" ValueLayout/JAVA_BOOLEAN ADDR ADDR ADDR ADDR)
                         inst ctx eidx func)
            fcall  ^MethodHandle (fx "wasmtime_component_func_call" ADDR ADDR ADDR ADDR I64 ADDR I64)
            ;; wasmtime requires post_return after a call before the next one on
            ;; the same func. Omitting it is the obvious way a 2.5us per-call
            ;; number could be the spike's fault rather than the boundary's.
            fpost  ^MethodHandle (fx "wasmtime_component_func_post_return" ADDR ADDR ADDR)
            args   ^MemorySegment (.allocate arena (long (* 2 VAL-SIZE)))
            res    ^MemorySegment (.allocate arena (long VAL-SIZE))
            _      (doseq [[i v] (map-indexed vector [17 25])]
                     (let [base (long (* i VAL-SIZE))]
                       (.set args I8 base (byte KIND-S32))
                       (.set args I32 (+ base VAL-UNION-OFFSET) (int v))))

            ;; The same add as a bare core module, so the component number has
            ;; something to be measured against.
            core-bytes (.readAllBytes (io/input-stream (io/file "dev/resources/add.core.wasm")))
            cbuf   (.allocate arena (long (count core-bytes)))
            _      (MemorySegment/copy ^bytes core-bytes 0 ^MemorySegment cbuf I8 0 (int (count core-bytes)))
            mout   (.allocate arena ^java.lang.foreign.MemoryLayout ADDR)
            _      (call (fx "wasmtime_module_new" ADDR ADDR ADDR I64 ADDR)
                         engine cbuf (long (count core-bytes)) mout)
            module (.get ^MemorySegment mout ADDR (long 0))
            cinst  (.allocate arena (long 16))
            trap   (.allocate arena ^java.lang.foreign.MemoryLayout ADDR)
            _      (call (fx "wasmtime_instance_new" ADDR ADDR ADDR ADDR I64 ADDR ADDR)
                         ctx module MemorySegment/NULL (long 0) cinst trap)
            cnm    (let [b (.getBytes "add" "UTF-8")
                         sg ^MemorySegment (.allocate arena (long (inc (alength b))))]
                     (MemorySegment/copy ^bytes b 0 sg I8 0 (alength b))
                     (.set sg I8 (long (alength b)) (byte 0))
                     sg)
            extrn  ^MemorySegment (.allocate arena (long 32))
            _      (call (fx "wasmtime_instance_export_get" ValueLayout/JAVA_BOOLEAN ADDR ADDR ADDR I64 ADDR)
                         ctx cinst cnm (long 3) extrn)
            ;; wasmtime_extern_t is {kind:u8, of:union} with the union at 8, and
            ;; a func is the first union member — so the wasmtime_func_t sits at
            ;; offset 8 and is 16 bytes.
            cfunc  ^MemorySegment (.asSlice extrn (long 8) (long 16))
            cargs  ^MemorySegment (.allocate arena (long 64))
            cres   ^MemorySegment (.allocate arena (long 32))
            _      (doseq [[i v] (map-indexed vector [17 25])]
                     (let [base (long (* i 32))]
                       (.set cargs I8 base (byte 0))          ; WASMTIME_I32
                       (.set cargs I32 (+ base 8) (int v))))
            ccall  ^MethodHandle (fx "wasmtime_func_call" ADDR ADDR ADDR ADDR I64 ADDR I64 ADDR)
            ;; val_raw is a bare 16-byte union: args and results share one array.
            raw    ^MemorySegment (.allocate arena (long 32))
            _      (do (.set raw I32 (long 0) (int 17)) (.set raw I32 (long 16) (int 25)))
            rcall  ^MethodHandle (fx "wasmtime_func_call_unchecked" ADDR ADDR ADDR ADDR I64 ADDR)

            n 2000000
            r {"core module, call_unchecked (raw)"
               (timed (fn [^long k]
                        (loop [i 0 acc 0]
                          (if (< i k)
                            (do (.set raw I32 (long 0) (int 17))
                                (.set raw I32 (long 16) (int 25))
                                (.invokeWithArguments rcall ^java.util.List [ctx cfunc raw (long 2) trap])
                                (recur (inc i) (unchecked-add acc (long (.get raw I32 (long 0))))))
                            acc)))
                      (quot n 10) 21 5)

               "core module, func_call (dynamic Val)"
               (timed (fn [^long k]
                        (loop [i 0 acc 0]
                          (if (< i k)
                            (do (.invokeWithArguments ccall ^java.util.List
                                                      [ctx cfunc cargs (long 2) cres (long 1) trap])
                                (recur (inc i) (unchecked-add acc (long (.get cres I32 (long 8))))))
                            acc)))
                      (quot n 10) 21 5)

               "invokeWithArguments (trivial native call)"
               (timed (fn [^long k]
                        (loop [i 0 acc 0]
                          (if (< i k)
                            (recur (inc i) (unchecked-add acc (long (.invokeWithArguments abs-h ^java.util.List [(int (- i))]))))
                            acc)))
                      n 21 5)

               "interface proxy (trivial native call)"
               (timed (fn [^long k]
                        (loop [i 0 acc 0]
                          (if (< i k)
                            (recur (inc i) (unchecked-add acc (long (.call abs-p (int (- i))))))
                            acc)))
                      n 21 5)

               "component call + post_return, add(s32,s32)"
               (timed (fn [^long k]
                        (loop [i 0 acc 0]
                          (if (< i k)
                            (do (.invokeWithArguments fcall ^java.util.List
                                                      [func ctx args (long 2) res (long 1)])
                                (.invokeWithArguments fpost ^java.util.List [func ctx])
                                (recur (inc i) (unchecked-add acc (long (.get res I32 (long VAL-UNION-OFFSET))))))
                            acc)))
                      (quot n 20) 21 5)}]
        (doseq [[k {:keys [ns-per-call checksum]}] r]
          (println (format "  %-44s %8.1f ns/call   (checksum %d)" k ns-per-call checksum)))))))

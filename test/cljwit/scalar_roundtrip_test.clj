(ns cljwit.scalar-roundtrip-test
  "Turns `doc/design/0012`'s scalar rows from a table into a check.

   Every WIT type whose canonical ABI needs no linear memory can be echoed by a
   hand-written WAT guest, so the mapping is testable today without the Rust or
   C toolchain the aggregate types will need. A value leaves Clojure, crosses
   the canonical ABI twice, and comes back.

   The lossy mappings `0012` letters — L2 `u64` above 2^63, L3 `f32`
   narrowing, L4 NaN canonicalisation, L5 the `char` surrogate hole — were
   *predicted* from the spec and never measured. These assert what actually
   happens, so a prediction that is wrong shows up as a failing test rather
   than as prose nobody rechecks.

   Skips when wasmtime is not present, the same way `bb lint` skips without
   clj-kondo: the gate stays runnable on a machine that cannot run it, and CI
   has the full toolchain."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io File]
           [java.lang.foreign Arena FunctionDescriptor Linker MemorySegment
            SymbolLookup ValueLayout]
           [java.lang.invoke MethodHandle]))

(def ^:private ADDR ValueLayout/ADDRESS)
(def ^:private I8 ValueLayout/JAVA_BYTE)
(def ^:private I32 ValueLayout/JAVA_INT)
(def ^:private I64 ValueLayout/JAVA_LONG)
(def ^:private F32 ValueLayout/JAVA_FLOAT)
(def ^:private F64 ValueLayout/JAVA_DOUBLE)

;; Measured against the pinned wasmtime 47.0.1 headers with a C program, not
;; inferred: wasmtime_component_val_t is 32 bytes, kind:u8 at 0, union at 8.
(def ^:private VAL 32)
(def ^:private UNION 8)
(def ^:private KIND {:bool 0 :s32 5 :u64 8 :f32 9 :f64 10 :char 11})

(def ^:private lib (System/getenv "CLJWIT_WASMTIME_LIB"))

(defn- unicode-scalar-value?
  "WIT `char` is a Unicode scalar value: 0..D7FF or E000..10FFFF. A lone
   surrogate is not one, and `cljwit.host` must reject it *before* the C API
   sees it — see the note on `char-boundaries` below for why."
  [cp]
  (and (integer? cp)
       (or (<= 0 cp 0xD7FF)
           (<= 0xE000 cp 0x10FFFF))))

(defn- build-component!
  "Builds the echo component from the committed .wat and .wit. Building rather
   than committing the artifact also checks the toolchain still produces what
   this test expects."
  []
  (let [core (File/createTempFile "cljwit-echo" ".core.wasm")
        emb  (File/createTempFile "cljwit-echo" ".embed.wasm")
        out  (File/createTempFile "cljwit-echo" ".component.wasm")
        run! (fn [& args]
               (let [{:keys [exit err]} (apply shell/sh args)]
                 (when-not (zero? exit) (throw (ex-info (str "failed: " (pr-str args)) {:err err})))))]
    (run! "wasm-tools" "parse" "dev/resources/echo.wat" "-o" (str core))
    (run! "wasm-tools" "component" "embed" "dev/resources/echo.wit" (str core) "-o" (str emb))
    (run! "wasm-tools" "component" "new" (str emb) "-o" (str out))
    out))

(defn- open-echo
  "Instantiates the component and returns {export-name (fn [kind v] -> v')}.
   Hand-rolled through wasmtime's component C API — see `doc/design/0011` for
   why this is FFM and why the hot path would use an interface proxy."
  [^Arena arena ^File component]
  (let [linker (Linker/nativeLinker)
        lookup (SymbolLookup/libraryLookup ^String lib arena)
        fx     (fn [nm ret & args]
                 (let [seg ^MemorySegment (.orElseThrow (.find lookup ^String nm))
                       fd  ^FunctionDescriptor
                       (FunctionDescriptor/of ret (into-array java.lang.foreign.MemoryLayout args))]
                   (.downcallHandle linker seg fd (into-array java.lang.foreign.Linker$Option []))))
        call   (fn [^MethodHandle mh & args] (.invokeWithArguments mh ^java.util.List (vec args)))
        ok!    (fn [what v] (let [^MemorySegment e v]
                              (when-not (.equals MemorySegment/NULL e)
                                (throw (ex-info (str what " failed") {})))
                              v))
        cstr   (fn [^String t]
                 (let [b (.getBytes t "UTF-8")
                       s ^MemorySegment (.allocate arena (long (inc (alength b))))]
                   (MemorySegment/copy ^bytes b 0 s I8 0 (alength b))
                   (.set s I8 (long (alength b)) (byte 0))
                   s))
        bytes* (.readAllBytes (io/input-stream component))
        buf    ^MemorySegment (.allocate arena (long (alength bytes*)))
        _      (MemorySegment/copy ^bytes bytes* 0 buf I8 0 (alength bytes*))
        engine (call (fx "wasm_engine_new" ADDR))
        store  (call (fx "wasmtime_store_new" ADDR ADDR ADDR ADDR) engine
                     MemorySegment/NULL MemorySegment/NULL)
        ctx    (call (fx "wasmtime_store_context" ADDR ADDR) store)
        cout   (.allocate arena ^java.lang.foreign.MemoryLayout ADDR)
        _      (ok! "component_new" (call (fx "wasmtime_component_new" ADDR ADDR ADDR I64 ADDR)
                                          engine buf (long (alength bytes*)) cout))
        comp   (.get ^MemorySegment cout ADDR (long 0))
        clink  (call (fx "wasmtime_component_linker_new" ADDR ADDR) engine)
        inst   (.allocate arena (long 16))
        _      (ok! "linker_instantiate"
                    (call (fx "wasmtime_component_linker_instantiate" ADDR ADDR ADDR ADDR ADDR)
                          clink ctx comp inst))
        eidx   (fx "wasmtime_component_instance_get_export_index" ADDR ADDR ADDR ADDR ADDR I64)
        gfunc  (fx "wasmtime_component_instance_get_func" ValueLayout/JAVA_BOOLEAN ADDR ADDR ADDR ADDR)
        fcall  (fx "wasmtime_component_func_call" ADDR ADDR ADDR ADDR I64 ADDR I64)
        ;; Required after any call that returns, before the next one. Omitting
        ;; it does not return an error — wasmtime panics in a function that
        ;; cannot unwind and the process aborts, taking the JVM with it.
        fpost  (fx "wasmtime_component_func_post_return" ADDR ADDR ADDR)]
    (fn [^String export kind v]
      (let [nm   (cstr export)
            idx  (call eidx inst ctx MemorySegment/NULL nm (long (count export)))
            _    (when (.equals MemorySegment/NULL ^MemorySegment idx)
                   (throw (ex-info (str "no export " export) {})))
            f    (.allocate arena (long 24))
            _    (when-not (call gfunc inst ctx idx f)
                   (throw (ex-info (str "get_func " export) {})))
            args ^MemorySegment (.allocate arena (long VAL))
            res  ^MemorySegment (.allocate arena (long VAL))
            k    (KIND kind)]
        (.set args I8 (long 0) (byte k))
        (case kind
          ;; One byte, not four: the union member is a C `bool`. Writing or
          ;; reading an i32 here picks up whatever sits in the next three bytes,
          ;; which read `false` back as `true`.
          :bool (.set args I8 (long UNION) (byte (if v 1 0)))
          :s32  (.set args I32 (long UNION) (int v))
          :char (do (when-not (unicode-scalar-value? v)
                      (throw (ex-info "not a Unicode scalar value" {:code-point v})))
                    (.set args I32 (long UNION) (int v)))
          :u64  (.set args I64 (long UNION) (long v))
          :f32  (.set args F32 (long UNION) (float v))
          :f64  (.set args F64 (long UNION) (double v)))
        (ok! (str "call " export) (call fcall f ctx args (long 1) res (long 1)))
        (ok! (str "post_return " export) (call fpost f ctx))
        (case kind
          :bool (not= 0 (.get res I8 (long UNION)))
          :s32  (.get res I32 (long UNION))
          :char (.get res I32 (long UNION))
          :u64  (.get res I64 (long UNION))
          :f32  (.get res F32 (long UNION))
          :f64  (.get res F64 (long UNION)))))))

(defn- with-echo
  "Builds the component, opens it, and calls `f` with the echo function. A
   function rather than a macro so that the symbol it binds is visible to
   clj-kondo — a macro-introduced binding lints as unresolved, and the push
   hook is what caught that."
  [f]
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping scalar round-trip test")
    (let [c (build-component!)]
      (try
        (with-open [a (Arena/ofConfined)]
          (f (open-echo a c)))
        (finally (.delete c))))))

(deftest scalars-round-trip
  (with-echo
    (fn [echo]
      (testing "bool, s32 — the rows 0012 treats as uncontroversial"
        (is (true? (echo "echo-bool" :bool true)))
        (is (false? (echo "echo-bool" :bool false)))
        (doseq [v [0 1 -1 Integer/MAX_VALUE Integer/MIN_VALUE]]
          (is (= v (echo "echo-s32" :s32 v)) (str "s32 " v))))

      (testing "L2 — u64 above 2^63 survives the boundary as a bit pattern"
      ;; The lossiness 0012 letters is on the *Clojure* side: a long holds the
      ;; bits but reads as negative, so lifting has to widen to BigInt to show
      ;; the value. The boundary itself does not lose anything, which is what
      ;; this asserts and what 0012 assumed without checking.
        (doseq [v [0 1 Long/MAX_VALUE -1 Long/MIN_VALUE]]
          (is (= v (echo "echo-u64" :u64 v)) (str "u64 bits " v)))
        (is (= (biginteger 18446744073709551615N)
               (biginteger (Long/toUnsignedString (echo "echo-u64" :u64 -1))))
            "all-ones round-trips and reads as 2^64-1 unsigned")))))

(deftest f32-narrowing-is-real
  (with-echo
    (fn [echo]
      (testing "L3 — 0012 predicts no f32 round-trips exactly"
        (is (not= 0.1 (double (echo "echo-f32" :f32 0.1)))
            "0.1 does not survive a double->f32->double trip")
        (is (= (float 0.1) (echo "echo-f32" :f32 0.1))
            "but it is exactly the f32 nearest 0.1 — the loss is narrowing, not the boundary")
        (doseq [v [0.0 1.0 -1.0 0.5]]
          (is (= (float v) (echo "echo-f32" :f32 v)) (str "f32-exact " v)))))))

(deftest nan-and-signed-zero
  (with-echo
    (fn [echo]
      (testing "L4 — NaN is canonicalised, so compare bits rather than ="
        (let [out (echo "echo-f64" :f64 Double/NaN)]
          (is (Double/isNaN out) "NaN comes back NaN")
        ;; 0012 predicts the payload may be discarded or scrambled. Asserting
        ;; the *canonical* pattern would over-claim: the spec permits scrambling.
          (is (Double/isNaN (Double/longBitsToDouble (Double/doubleToRawLongBits out)))
              "whatever pattern comes back is still a NaN")))
      (testing "signed zero — the case where = passes spuriously"
        (let [out (echo "echo-f64" :f64 -0.0)]
          (is (= 0.0 out) "= cannot tell -0.0 from 0.0, which is why this row needs bits")
          (is (= (Double/doubleToRawLongBits -0.0) (Double/doubleToRawLongBits out))
              "the sign of zero does survive"))))))

(deftest char-boundaries
  (with-echo
    (fn [echo]
      (testing "L5 — every Unicode scalar value round-trips, including above the BMP"
        (doseq [cp [0 0x41 0xD7FF 0xE000 0x1F600 0x10FFFF]]
          (is (= cp (echo "echo-char" :char cp)) (str "code point " (format "U+%04X" cp)))))

      (testing "L5 — a lone surrogate must be rejected before the C API sees it"
      ;; 0012 predicted this would *trap*. It does worse. wasmtime's C API
      ;; converts with `char::from_u32(x).unwrap()`
      ;; (crates/c-api/src/component/val.rs:303 at v47.0.1), so a surrogate is a
      ;; non-unwinding panic that aborts the process — no trap, no error
      ;; return, nothing to catch, and the JVM goes with it. Verified by doing
      ;; it once; this asserts the guard rather than the abort, because a test
      ;; that aborts the runner cannot report anything.
        (is (thrown? clojure.lang.ExceptionInfo (echo "echo-char" :char 0xD800)))
        (is (thrown? clojure.lang.ExceptionInfo (echo "echo-char" :char 0xDFFF)))
        (is (thrown? clojure.lang.ExceptionInfo (echo "echo-char" :char 0x110000)))
        (is (not (unicode-scalar-value? -1)))))))

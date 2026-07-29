(ns cljwit.roundtrip-test
  "Turns `doc/design/0012`'s mapping from a table into a check.

;; A `MemorySegment.get` whose layout argument is untyped resolves reflectively,
;; and a reflective 4-byte read costs ~1.5 us. That is not a style preference
;; here: it silently became the headline number of two design notes.
(set! *warn-on-reflection* true)

   A value leaves Clojure, crosses the canonical ABI twice, and comes back. The
   guest is hand-written WAT — including for `string`, which needs an exported
   memory and `cabi_realloc` but, it turns out, no Rust toolchain: an echo hands
   back the pointer the host already lowered into its memory, so the whole guest
   is a bump allocator and a stored pair.

   The lossy mappings `0012` letters — L2 `u64` above 2^63, L3 `f32`
   narrowing, L4 NaN canonicalisation, L5 the `char` surrogate hole — were
   *predicted* from the spec and never measured. These assert what actually
   happens, so a prediction that is wrong shows up as a failing test rather
   than as prose nobody rechecks.

   Skips when wasmtime is not present, the same way `bb lint` skips without
   clj-kondo: the gate stays runnable on a machine that cannot run it, and CI
   has the full toolchain.

   Still missing: `record`, `list`, `variant`, `option`, `result`. Each needs
   its own ABI shape in the guest; none needs a toolchain this repo lacks."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io File]
           [java.lang.foreign Arena FunctionDescriptor Linker MemorySegment
            SymbolLookup ValueLayout]
           [java.lang.invoke MethodHandle]))

(def ^:private ^java.lang.foreign.AddressLayout ADDR ValueLayout/ADDRESS)
(def ^:private ^java.lang.foreign.ValueLayout$OfByte I8 ValueLayout/JAVA_BYTE)
(def ^:private ^java.lang.foreign.ValueLayout$OfInt I32 ValueLayout/JAVA_INT)
(def ^:private ^java.lang.foreign.ValueLayout$OfLong I64 ValueLayout/JAVA_LONG)
(def ^:private ^java.lang.foreign.ValueLayout$OfFloat F32 ValueLayout/JAVA_FLOAT)
(def ^:private ^java.lang.foreign.ValueLayout$OfDouble F64 ValueLayout/JAVA_DOUBLE)

;; Measured against the pinned wasmtime 47.0.1 headers with a C program, not
;; inferred: wasmtime_component_val_t is 32 bytes, kind:u8 at 0, union at 8.
(def ^:private VAL 32)
(def ^:private UNION 8)
(def ^:private KIND {:bool 0 :u32 6 :u64 8 :s32 5 :f32 9 :f64 10 :char 11
                     :string 12 :enum 17 :option-u32 18 :option-option-u32 18 :result 19 :variant 16
                     :list-u32 13 :record-pair 14})

;; Both vector types are {size_t size; T *data;} — 16 bytes at the union
;; offset, measured, and the same shape as wasm_name_t. A record's element is
;; {wasm_name_t name; val val;}, 48 bytes, so the val is *inline* rather than
;; behind a pointer: unlike every sum type, a record does not indirect.
(def ^:private VEC-SIZE 0)
(def ^:private VEC-DATA 8)
(def ^:private ENTRY 48)
(def ^:private ENTRY-NAME 0)
(def ^:private ENTRY-VAL 16)

;; wasmtime_component_valvariant_t is {wasm_name_t discriminant; val *val;} —
;; the name occupies the first sixteen bytes of the union, the payload pointer
;; the eight after.
(def ^:private VAR-NAME 8)
(def ^:private VAR-VAL 24)

;; wasmtime_component_valresult_t is {bool is_ok; val *val;} — measured, so
;; is_ok is one byte at the union offset and the pointer is eight past it.
(def ^:private RES-OK 8)
(def ^:private RES-VAL 16)

;; wasm_name_t is {size_t size; char *data;} — 16 bytes, size at 0, data at 8 —
;; measured against the pinned headers, and it sits at the union offset.
(def ^:private STR-SIZE 0)
(def ^:private STR-DATA 8)

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
        ;; No post_return: deprecated and a no-op in 47.0.1, which
        ;; wasmtime_component_func_call now handles itself (0013).
        ]
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
          :f64  (.set args F64 (long UNION) (double v))
          ;; enum's host representation is the *case name*, not an ordinal —
          ;; which is why 0012 maps it to a keyword.
          :enum (let [b   (.getBytes ^String (name v) "UTF-8")
                      buf ^MemorySegment (.allocate arena (long (alength b)))]
                  (MemorySegment/copy ^bytes b 0 buf I8 0 (alength b))
                  (.set args I64 (long (+ UNION STR-SIZE)) (long (alength b)))
                  (.set args ADDR (long (+ UNION STR-DATA)) buf))
          ;; option is a pointer: NULL is none, otherwise it points at a val.
          :option-u32 (.set args ADDR (long UNION)
                            (if (nil? v)
                              MemorySegment/NULL
                              (let [inner ^MemorySegment (.allocate arena (long VAL))]
                                (.set inner I8 (long 0) (byte (KIND :u32)))
                                ;; unchecked-int, not int: the slot is 32 bits
                                ;; and a valid u32 above 2^31-1 does not fit a
                                ;; Java int, so the checked cast throws on a
                                ;; value the ABI accepts.
                                (.set inner I32 (long UNION) (unchecked-int v))
                                inner)))
          ;; Lowered from and lifted to a *faithful* tagged form — :none,
          ;; [:some :none], [:some [:some n]] — so the boundary can be checked
          ;; without 0012's mapping in the way. The mapping's own loss is
          ;; asserted separately, in pure Clojure.
          ;; Lowered from and lifted to a *faithful* tagged form — :none,
          ;; [:some :none], [:some [:some n]] — so the boundary can be checked
          ;; without 0012's mapping in the way. The mapping's own loss is
          ;; asserted separately, in pure Clojure.
          ;;
          ;; Written out per level rather than recursively: the type is exactly
          ;; two options deep, and a recursive lowering wrapped a third.
          :option-option-u32
          (letfn [(val-of [kind write!]
                    (let [m ^MemorySegment (.allocate arena (long VAL))]
                      (.set m I8 (long 0) (byte kind))
                      (write! m)
                      m))
                  (u32val [n] (val-of (KIND :u32)
                                      (fn [^MemorySegment m]
                                        (.set m I32 (long UNION) (unchecked-int n)))))
                  ;; one `option<u32>`
                  (inner [x] (val-of 18
                                     (fn [^MemorySegment m]
                                       (let [^MemorySegment q (if (= :none x)
                                                                MemorySegment/NULL
                                                                (u32val (second x)))]
                                         (.set m ADDR (long UNION) q)))))]
            (let [^MemorySegment q (if (= :none v) MemorySegment/NULL (inner (second v)))]
              (.set args ADDR (long UNION) q)))
          ;; [:ok v] / [:err e] — the *type* mapping. The throwing form is
          ;; sugar defined on top of this, and is exercised below.
          :result
          (let [[tag payload] v
                inner ^MemorySegment (.allocate arena (long VAL))]
            (if (= :ok tag)
              (do (.set inner I8 (long 0) (byte (KIND :u32)))
                  (.set inner I32 (long UNION) (unchecked-int payload)))
              (let [b   (.getBytes ^String payload "UTF-8")
                    buf ^MemorySegment (.allocate arena (long (max 1 (alength b))))]
                (MemorySegment/copy ^bytes b 0 buf I8 0 (alength b))
                (.set inner I8 (long 0) (byte (KIND :string)))
                (.set inner I64 (long (+ UNION STR-SIZE)) (long (alength b)))
                (.set inner ADDR (long (+ UNION STR-DATA)) buf)))
            (.set args I8 (long RES-OK) (byte (if (= :ok tag) 1 0)))
            (.set args ADDR (long RES-VAL) inner))
          ;; [:case-name payload] / [:case-name] — the host hands back a case
          ;; *name*, so a keyword is the natural reading, exactly as for enum.
          :variant
          (let [[tag payload] v
                b   (.getBytes ^String (name tag) "UTF-8")
                buf ^MemorySegment (.allocate arena (long (alength b)))]
            (MemorySegment/copy ^bytes b 0 buf I8 0 (alength b))
            (.set args I64 (long (+ VAR-NAME STR-SIZE)) (long (alength b)))
            (.set args ADDR (long (+ VAR-NAME STR-DATA)) buf)
            (.set args ADDR (long VAR-VAL)
                  (if (nil? payload)
                    MemorySegment/NULL
                    (let [iv ^MemorySegment (.allocate arena (long VAL))]
                      (if (= :circle tag)
                        (do (.set iv I8 (long 0) (byte (KIND :f64)))
                            (.set iv F64 (long UNION) (double payload)))
                        (do (.set iv I8 (long 0) (byte (KIND :u32)))
                            (.set iv I32 (long UNION) (unchecked-int payload))))
                      iv))))
          ;; A vector of vals laid end to end. The list itself is the only
          ;; part that is a vector on the host side; each element is an
          ;; ordinary val, tagged with its own kind.
          :list-u32
          (let [n   (count v)
                buf ^MemorySegment (.allocate arena (long (max 1 (* VAL n))))]
            (dotimes [i n]
              (let [e (.asSlice buf (long (* VAL i)) (long VAL))]
                (.set e I8 (long 0) (byte (KIND :u32)))
                (.set e I32 (long UNION) (unchecked-int (nth v i)))))
            (.set args I64 (long (+ UNION VEC-SIZE)) (long n))
            (.set args ADDR (long (+ UNION VEC-DATA)) buf))
          ;; Fields go out in declaration order, each carrying its own name.
          :record-pair
          (let [fields [["n" (KIND :u32) (:n v)] ["label" (KIND :string) (:label v)]]
                buf ^MemorySegment (.allocate arena (long (* ENTRY (count fields))))]
            (dotimes [i (count fields)]
              (let [[fname k fv] (nth fields i)
                    e  (.asSlice buf (long (* ENTRY i)) (long ENTRY))
                    nb (.getBytes ^String fname "UTF-8")
                    ns ^MemorySegment (.allocate arena (long (alength nb)))]
                (MemorySegment/copy ^bytes nb 0 ns I8 0 (alength nb))
                (.set e I64 (long (+ ENTRY-NAME STR-SIZE)) (long (alength nb)))
                (.set e ADDR (long (+ ENTRY-NAME STR-DATA)) ns)
                (.set e I8 (long ENTRY-VAL) (byte k))
                (if (= k (KIND :u32))
                  (.set e I32 (long (+ ENTRY-VAL UNION)) (unchecked-int fv))
                  (let [b   (.getBytes ^String fv "UTF-8")
                        sb ^MemorySegment (.allocate arena (long (max 1 (alength b))))]
                    (MemorySegment/copy ^bytes b 0 sb I8 0 (alength b))
                    (.set e I64 (long (+ ENTRY-VAL UNION STR-SIZE)) (long (alength b)))
                    (.set e ADDR (long (+ ENTRY-VAL UNION STR-DATA)) sb)))))
            (.set args I64 (long (+ UNION VEC-SIZE)) (long (count fields)))
            (.set args ADDR (long (+ UNION VEC-DATA)) buf))
          :string (let [b   (.getBytes ^String v "UTF-8")
                        buf ^MemorySegment (.allocate arena (long (max 1 (alength b))))]
                    (MemorySegment/copy ^bytes b 0 buf I8 0 (alength b))
                    (.set args I64 (long (+ UNION STR-SIZE)) (long (alength b)))
                    (.set args ADDR (long (+ UNION STR-DATA)) buf)))
        (ok! (str "call " export) (call fcall f ctx args (long 1) res (long 1)))
        (case kind
          :bool (not= 0 (.get res I8 (long UNION)))
          :s32  (.get res I32 (long UNION))
          :char (.get res I32 (long UNION))
          :u64  (.get res I64 (long UNION))
          :f32  (.get res F32 (long UNION))
          :f64  (.get res F64 (long UNION))
          :enum (let [n (.get res I64 (long (+ UNION STR-SIZE)))
                      p ^MemorySegment (.get res ADDR (long (+ UNION STR-DATA)))
                      b (byte-array n)]
                  (MemorySegment/copy (.reinterpret p n) I8 0 b 0 (int n))
                  (keyword (String. b "UTF-8")))
          :option-u32 (let [p ^MemorySegment (.get res ADDR (long UNION))]
                        (when-not (.equals MemorySegment/NULL p)
                          ;; and widen unsigned on the way back, or 2^32-1 lifts
                          ;; as -1.
                          (bit-and (long (.get (.reinterpret p VAL) I32 (long UNION)))
                                   0xFFFFFFFF)))
          ;; Per level, like the lowering. The outer pointer already means
          ;; "the outer option is some", so recursing on it drops a level.
          :option-option-u32
          (let [outer ^MemorySegment (.get res ADDR (long UNION))]
            (if (.equals MemorySegment/NULL outer)
              :none
              (let [ov  (.reinterpret outer VAL)
                    innr ^MemorySegment (.get ov ADDR (long UNION))]
                [:some (if (.equals MemorySegment/NULL innr)
                         :none
                         [:some (bit-and (long (.get (.reinterpret innr VAL) I32 (long UNION)))
                                         0xFFFFFFFF)])])))
          :result
          (let [ok? (not= 0 (.get res I8 (long RES-OK)))
                p   ^MemorySegment (.reinterpret (.get res ADDR (long RES-VAL)) VAL)]
            (if ok?
              [:ok (bit-and (long (.get p I32 (long UNION))) 0xFFFFFFFF)]
              (let [n (.get p I64 (long (+ UNION STR-SIZE)))
                    d ^MemorySegment (.get p ADDR (long (+ UNION STR-DATA)))
                    b (byte-array n)]
                (MemorySegment/copy (.reinterpret d n) I8 0 b 0 (int n))
                [:err (String. b "UTF-8")])))
          :variant
          (let [n   (.get res I64 (long (+ VAR-NAME STR-SIZE)))
                d   ^MemorySegment (.get res ADDR (long (+ VAR-NAME STR-DATA)))
                b   (byte-array n)
                _   (MemorySegment/copy (.reinterpret d n) I8 0 b 0 (int n))
                tag (keyword (String. b "UTF-8"))
                p   ^MemorySegment (.get res ADDR (long VAR-VAL))]
            (if (.equals MemorySegment/NULL p)
              [tag]
              (let [pv (.reinterpret p VAL)]
                ;; the payload carries its own kind, so the case does not have
                ;; to be known here
                [tag (if (= (KIND :f64) (.get pv I8 (long 0)))
                       (.get pv F64 (long UNION))
                       (bit-and (long (.get pv I32 (long UNION))) 0xFFFFFFFF))])))
          :list-u32
          (let [n (.get res I64 (long (+ UNION VEC-SIZE)))
                p ^MemorySegment (.get res ADDR (long (+ UNION VEC-DATA)))
                b (.reinterpret p (long (* VAL n)))]
            (mapv (fn [i]
                    (bit-and (long (.get b I32 (long (+ (* VAL i) UNION)))) 0xFFFFFFFF))
                  (range n)))
          ;; A record comes back as name/value pairs, so a Clojure map with
          ;; keyword keys is the host shape read straight — the field order the
          ;; ABI fixes is information the map does not need to carry.
          :record-pair
          (let [n (.get res I64 (long (+ UNION VEC-SIZE)))
                p ^MemorySegment (.get res ADDR (long (+ UNION VEC-DATA)))
                b (.reinterpret p (long (* ENTRY n)))]
            (into {}
                  (map (fn [i]
                         (let [e   (.asSlice b (long (* ENTRY i)) (long ENTRY))
                               nl  (.get e I64 (long (+ ENTRY-NAME STR-SIZE)))
                               nd ^MemorySegment (.get e ADDR (long (+ ENTRY-NAME STR-DATA)))
                               nb  (byte-array nl)
                               _   (MemorySegment/copy (.reinterpret nd nl) I8 0 nb 0 (int nl))
                               k   (keyword (String. nb "UTF-8"))]
                           [k (if (= (KIND :string) (.get e I8 (long ENTRY-VAL)))
                                (let [sl (.get e I64 (long (+ ENTRY-VAL UNION STR-SIZE)))
                                      sd ^MemorySegment (.get e ADDR (long (+ ENTRY-VAL UNION STR-DATA)))
                                      sb (byte-array sl)]
                                  (MemorySegment/copy (.reinterpret sd sl) I8 0 sb 0 (int sl))
                                  (String. sb "UTF-8"))
                                (bit-and (long (.get e I32 (long (+ ENTRY-VAL UNION)))) 0xFFFFFFFF))])))
                  (range n)))
          :string (let [n (.get res I64 (long (+ UNION STR-SIZE)))
                        p ^MemorySegment (.get res ADDR (long (+ UNION STR-DATA)))
                        b (byte-array n)]
                    (MemorySegment/copy (.reinterpret p n) I8 0 b 0 (int n))
                    (String. b "UTF-8")))))))

(defn- with-echo
  "Builds the component, opens it, and calls `f` with the echo function. A
   function rather than a macro so that the symbol it binds is visible to
   clj-kondo — a macro-introduced binding lints as unresolved, and the push
   hook is what caught that."
  [f]
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping round-trip test")
    (let [c (build-component!)]
      (try
        (with-open [a (Arena/ofConfined)]
          (f (open-echo a c)))
        (finally (.delete ^java.io.File c))))))

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

(deftest strings-round-trip
  (with-echo
    (fn [echo]
      (testing "the first aggregate row — memory and cabi_realloc, no Rust toolchain"
        (doseq [v ["" "hello" "日本語" "a\u0000b" (apply str (repeat 1000 "x"))]]
          (is (= v (echo "echo-string" :string v)) (str "string " (pr-str v)))))
      (testing "L6 — a string carrying an unpaired surrogate is not a WIT string"
        ;; Java lets one exist; WIT's string is a sequence of scalar values.
        ;; UTF-8 encoding replaces it rather than failing, so what comes back is
        ;; not what went in — recorded because 0012 predicted it could not be
        ;; lowered at all.
        (let [lone (str (char 0xD800))]
          (is (not= lone (echo "echo-string" :string lone))
              "an unpaired surrogate does not survive the boundary"))))))

(deftest enums-and-options-round-trip
  (with-echo
    (fn [echo]
      (testing "enum lifts as a case name, so a keyword is the natural mapping"
        (doseq [c [:red :green :blue]]
          (is (= c (echo "echo-colour" :enum c)) (str "colour " c))))

      (testing "option<u32> — a value or nil, per 0012"
        (doseq [v [0 1 42 4294967295]]
          (is (= v (echo "echo-option-u32" :option-u32 v)) (str "some " v)))
        (is (nil? (echo "echo-option-u32" :option-u32 nil)) "none is nil"))

      (testing "L1's premise — the boundary keeps a distinction Clojure loses"
        ;; `none` is a NULL pointer and `some(v)` points at a val, so the two
        ;; are distinct on the wire. 0012's L1 is a loss in *our* mapping, not
        ;; in the ABI: nesting is what collapses, because nil is the only thing
        ;; `none` can become. This asserts the half that is checkable here.
        (is (not= (echo "echo-option-u32" :option-u32 nil)
                  (echo "echo-option-u32" :option-u32 0))
            "none and some(0) stay distinct across the boundary")))))

(defn- as-0012
  "doc/design/0012's rule applied to the faithful form: `none` becomes nil, and
   `some(v)` becomes whatever v becomes."
  [x]
  (cond (= :none x) nil
        (vector? x) (as-0012 (second x))
        :else x))

(deftest nested-option-is-where-the-mapping-loses
  (with-echo
    (fn [echo]
      (testing "the boundary keeps all three apart"
        (doseq [v [:none [:some :none] [:some [:some 9]]]]
          (is (= v (echo "echo-option-option-u32" :option-option-u32 v))
              (str "faithful " (pr-str v)))))

      (testing "L1 — and 0012's mapping collapses two of them into nil"
        ;; This is the letter made concrete. Nothing is lost crossing the wire;
        ;; the loss is that `nil` is the only thing `none` can become, so a
        ;; second level has nowhere to go. Asserted rather than described, so
        ;; that changing the mapping breaks a test rather than a paragraph.
        (is (nil? (as-0012 :none)))
        (is (nil? (as-0012 [:some :none])))
        (is (= (as-0012 :none) (as-0012 [:some :none]))
            "none and some(none) are indistinguishable under 0012's rule")
        (is (= 9 (as-0012 [:some [:some 9]]))
            "a nested some still carries its value")))))

(defn- unwrap
  "0012's return-position sugar, defined on the tagged form: `(f ...)` returns
   the ok payload and throws on error, carrying the lifted E under :wit/error.
   `(f* ...)` would return the tagged value unchanged."
  [[tag payload]]
  (if (= :ok tag)
    payload
    (throw (ex-info "component call returned err" {:wit/error payload}))))

(deftest results-round-trip-and-the-sugar-is-defined-on-them
  (with-echo
    (fn [echo]
      (testing "the tagged form is the type mapping, and it round-trips"
        (doseq [v [[:ok 0] [:ok 7] [:ok 4294967295] [:err "boom"] [:err ""]]]
          (is (= v (echo "echo-result" :result v)) (str "result " (pr-str v)))))

      (testing "the throwing wrapper is sugar over it, not a second mapping"
        (is (= 7 (unwrap (echo "echo-result" :result [:ok 7]))))
        (let [e (try (unwrap (echo "echo-result" :result [:err "boom"]))
                     nil
                     (catch clojure.lang.ExceptionInfo ex ex))]
          (is (some? e) "err throws")
          (is (= "boom" (:wit/error (ex-data e)))
              "and the lifted E survives in ex-data, so nothing is lost")))

      (testing "which is why result cannot only be the throw"
        ;; A result nested inside another type never reaches return position,
        ;; so a mapping defined only as "it throws" has nothing to say about it.
        ;; The tagged form is a value and composes; the sugar is applied at one
        ;; place and cannot.
        (is (= [:err "boom"] (echo "echo-result" :result [:err "boom"]))
            "an err is an ordinary value here, not an exception")))))

(deftest variants-round-trip
  (with-echo
    (fn [echo]
      (testing "0012 maps a variant to [:case payload], or [:case] with none"
        (doseq [v [[:circle 1.5] [:circle 0.0] [:square 9] [:square 4294967295] [:point]]]
          (is (= v (echo "echo-shape" :variant v)) (str "shape " (pr-str v)))))
      (testing "the case comes back as a name, not an ordinal"
        ;; Same as enum: the host union holds a wasm_name_t, so the keyword is
        ;; the representation read straight rather than a Clojure-side choice.
        (is (= :point (first (echo "echo-shape" :variant [:point]))))))))

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

(deftest lists-and-records-round-trip
  (with-echo
    (fn [echo]
      (testing "list<u32> — a vector, and the empty list is not nil"
        (doseq [v [[] [0] [1 2 3] [4294967295] (vec (range 100))]]
          (is (= v (echo "echo-list-u32" :list-u32 v)) (str "list " (pr-str v))))
        (is (vector? (echo "echo-list-u32" :list-u32 []))
            "empty list lifts as [], which `some?` can tell from option's nil"))

      (testing "record — a map keyed by field name, per 0012"
        (doseq [v [{:n 0 :label ""} {:n 7 :label "hi"} {:n 4294967295 :label "日本語"}]]
          (is (= v (echo "echo-pair" :record-pair v)) (str "pair " (pr-str v)))))

      (testing "the record's field order is the ABI's, not the map's"
        ;; A Clojure map has no order, so lowering has to supply the
        ;; declaration order itself. This passes only because it does: the
        ;; input is built with the fields the other way round.
        (is (= {:n 5 :label "x"}
               (echo "echo-pair" :record-pair (array-map :label "x" :n 5)))
            "a map whose seq order is reversed still lowers correctly")))))

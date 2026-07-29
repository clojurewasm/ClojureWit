(ns cljwit.host
  "Call a Wasm component from JVM Clojure.

   Implements `doc/design/0014`. Three lifetimes, because they differ by three
   orders of magnitude in cost:

       (with-open [e (host/engine)]                 ; process
         (with-open [a (host/compile e \"c.wasm\")]   ; ~2-20 ms, Cranelift
           (with-open [i (host/instantiate a)]      ; ~0.02 ms
             ((:some-export i) 42))))

   Exports are discovered from the component itself — there is no WIT file and
   no code generation at run time. Names are the exact WIT strings; a keyword
   alias exists where the name survives a round trip through the reader."
  (:refer-clojure :exclude [compile])
  (:require [clojure.java.io :as io])
  (:import [java.lang.foreign Arena Linker SymbolLookup MemorySegment
            FunctionDescriptor ValueLayout]
           [java.lang.invoke MethodHandle]
           [java.util.concurrent.atomic AtomicBoolean]))

(set! *warn-on-reflection* true)

(def ^:private ^java.lang.foreign.AddressLayout ADDR ValueLayout/ADDRESS)
(def ^:private ^java.lang.foreign.ValueLayout$OfByte I8 ValueLayout/JAVA_BYTE)
(def ^:private ^java.lang.foreign.ValueLayout$OfInt I32 ValueLayout/JAVA_INT)
(def ^:private ^java.lang.foreign.ValueLayout$OfLong I64 ValueLayout/JAVA_LONG)
(def ^:private ^java.lang.foreign.ValueLayout$OfFloat F32 ValueLayout/JAVA_FLOAT)
(def ^:private ^java.lang.foreign.ValueLayout$OfDouble F64 ValueLayout/JAVA_DOUBLE)

;; Measured against the pinned wasmtime 47.0.1 headers, not inferred.
(def ^:private VAL 32)        ; wasmtime_component_val_t, kind:u8 at 0
(def ^:private UNION 8)       ; ...its union
(def ^:private STR-SIZE 0)    ; wasm_name_t {size_t size; char *data;}
(def ^:private STR-DATA 8)
(def ^:private VALTYPE 16)    ; wasmtime_component_valtype_t, kind at 0
(def ^:private ITEM 24)       ; wasmtime_component_item_t, kind at 0, union at 8
(def ^:private ITEM-OF 8)
(def ^:private ITEM-FUNC 3)   ; WASMTIME_COMPONENT_ITEM_COMPONENT_FUNC
(def ^:private FUNC 24)       ; wasmtime_component_func_t
(def ^:private INSTANCE 16)

;; `wasmtime_component_valtype_t`'s kinds. **Not** `wasmtime_component_val_t`'s
;; — valtype groups the signed integers before the unsigned, val interleaves
;; them, and they diverge again above 20. Pinned by valtype_enum_test.
(def ^:private VALTYPE-KIND
  [:bool :s8 :s16 :s32 :s64 :u8 :u16 :u32 :u64 :f32 :f64 :char :string :list
   :record :tuple :variant :enum :option :result :flags :own :borrow :future
   :stream :error-context :map])

;; The translation the two enums force on anything driven by reflection.
(def ^:private VAL-KIND
  {:bool 0 :s8 1 :u8 2 :s16 3 :u16 4 :s32 5 :u32 6 :s64 7 :u64 8
   :f32 9 :f64 10 :char 11 :string 12})

(defn- lib-path []
  (or (System/getenv "CLJWIT_WASMTIME_LIB")
      (throw (ex-info "CLJWIT_WASMTIME_LIB is unset — see doc/design/0005"
                      {:cljwit/error :no-library}))))

(defn- ffm
  "Binds one libwasmtime symbol. `ret` nil means void."
  [^SymbolLookup lookup ^Linker linker ^String nm ret args]
  (let [seg ^MemorySegment (.orElseThrow (.find lookup nm))
        ls  (into-array java.lang.foreign.MemoryLayout args)
        fd  ^FunctionDescriptor (if ret (FunctionDescriptor/of ret ls)
                                    (FunctionDescriptor/ofVoid ls))]
    (.downcallHandle linker seg fd (into-array java.lang.foreign.Linker$Option []))))

(defn- invoke [^MethodHandle mh & args]
  (.invokeWithArguments mh ^java.util.List (vec args)))

(defn- ok!
  "wasmtime returns a null error pointer on success."
  [what v]
  (let [^MemorySegment e v]
    (when-not (.equals MemorySegment/NULL e)
      (throw (ex-info (str what " failed") {:cljwit/error :wasmtime})))
    v))

(defn- api
  "Every entry point this namespace uses, bound once per engine."
  [^Arena arena]
  (let [linker (Linker/nativeLinker)
        lookup (SymbolLookup/libraryLookup ^String (lib-path) arena)
        b      (fn [nm ret & args] (ffm lookup linker nm ret args))
        BOOL   ValueLayout/JAVA_BOOLEAN]
    {:engine-new    (b "wasm_engine_new" ADDR)
     :engine-delete (b "wasm_engine_delete" nil ADDR)
     :store-new     (b "wasmtime_store_new" ADDR ADDR ADDR ADDR)
     :store-delete  (b "wasmtime_store_delete" nil ADDR)
     :store-context (b "wasmtime_store_context" ADDR ADDR)
     :comp-new      (b "wasmtime_component_new" ADDR ADDR ADDR I64 ADDR)
     :comp-delete   (b "wasmtime_component_delete" nil ADDR)
     :comp-type     (b "wasmtime_component_type" ADDR ADDR)
     :type-delete   (b "wasmtime_component_type_delete" nil ADDR)
     :export-count  (b "wasmtime_component_type_export_count" I64 ADDR ADDR)
     :export-nth    (b "wasmtime_component_type_export_nth" BOOL ADDR ADDR I64 ADDR ADDR ADDR)
     :item-delete   (b "wasmtime_component_item_delete" nil ADDR)
     :ft-params     (b "wasmtime_component_func_type_param_count" I64 ADDR)
     :ft-param-nth  (b "wasmtime_component_func_type_param_nth" BOOL ADDR I64 ADDR ADDR ADDR)
     :ft-result     (b "wasmtime_component_func_type_result" BOOL ADDR ADDR)
     :vt-delete     (b "wasmtime_component_valtype_delete" nil ADDR)
     :linker-new    (b "wasmtime_component_linker_new" ADDR ADDR)
     :linker-delete (b "wasmtime_component_linker_delete" nil ADDR)
     :instantiate   (b "wasmtime_component_linker_instantiate" ADDR ADDR ADDR ADDR ADDR)
     :export-index  (b "wasmtime_component_instance_get_export_index" ADDR ADDR ADDR ADDR ADDR I64)
     :get-func      (b "wasmtime_component_instance_get_func" BOOL ADDR ADDR ADDR ADDR)
     :func-call     (b "wasmtime_component_func_call" ADDR ADDR ADDR ADDR I64 ADDR I64)
     :val-delete    (b "wasmtime_component_val_delete" nil ADDR)}))

(defn- cstr [^Arena arena ^String t]
  (let [b (.getBytes t "UTF-8")
        s ^MemorySegment (.allocate arena (long (inc (alength b))))]
    (MemorySegment/copy ^bytes b 0 s I8 0 (alength b))
    (.set s I8 (long (alength b)) (byte 0))
    s))

(defn- read-name
  "A (const char*, size_t) pair written into two out-params."
  [^MemorySegment pp ^MemorySegment lp]
  (let [n (.get lp I64 (long 0))
        p ^MemorySegment (.get pp ADDR (long 0))
        b (byte-array n)]
    (MemorySegment/copy (.reinterpret p n) I8 0 b 0 (int n))
    (String. b "UTF-8")))

;; --- marshalling ------------------------------------------------------------
;; 0012's rows, as far as this implementation goes. An unsupported kind fails
;; at instantiation with the kind named, not at the call with a wrong answer.

(def ^:private SUPPORTED (set (keys VAL-KIND)))

(defn- lower!
  [^MemorySegment seg kind v]
  (.set seg I8 (long 0) (byte (VAL-KIND kind)))
  (case kind
    ;; One byte, not four: the union member is a C `bool`.
    :bool   (.set seg I8 (long UNION) (byte (if v 1 0)))
    :s8     (.set seg I8 (long UNION) (byte v))
    :u8     (.set seg I8 (long UNION) (unchecked-byte v))
    (:s16 :u16 :s32 :u32 :char) (.set seg I32 (long UNION) (unchecked-int v))
    (:s64 :u64) (.set seg I64 (long UNION) (long v))
    :f32    (.set seg F32 (long UNION) (float v))
    :f64    (.set seg F64 (long UNION) (double v))
    :string (throw (ex-info "string lowering needs an arena" {}))))

(defn- lower-string! [^Arena arena ^MemorySegment seg ^String v]
  (let [b   (.getBytes v "UTF-8")
        buf ^MemorySegment (.allocate arena (long (max 1 (alength b))))]
    (MemorySegment/copy ^bytes b 0 buf I8 0 (alength b))
    (.set seg I8 (long 0) (byte (VAL-KIND :string)))
    (.set seg I64 (long (+ UNION STR-SIZE)) (long (alength b)))
    (.set seg ADDR (long (+ UNION STR-DATA)) buf)))

(defn- lift
  "Lifts eagerly into JVM-owned values. 0014 E: the payload is invalid after
   the next call on this function, so nothing backed by `seg` may escape."
  [^MemorySegment seg kind]
  (case kind
    :bool   (not= 0 (.get seg I8 (long UNION)))
    :s8     (.get seg I8 (long UNION))
    :u8     (bit-and (long (.get seg I8 (long UNION))) 0xFF)
    :s16    (unchecked-short (.get seg I32 (long UNION)))
    :u16    (bit-and (long (.get seg I32 (long UNION))) 0xFFFF)
    :s32    (.get seg I32 (long UNION))
    :char   (.get seg I32 (long UNION))
    :u32    (bit-and (long (.get seg I32 (long UNION))) 0xFFFFFFFF)
    (:s64 :u64) (.get seg I64 (long UNION))
    :f32    (.get seg F32 (long UNION))
    :f64    (.get seg F64 (long UNION))
    :string (let [n (.get seg I64 (long (+ UNION STR-SIZE)))
                  p ^MemorySegment (.get seg ADDR (long (+ UNION STR-DATA)))
                  b (byte-array n)]
              (MemorySegment/copy (.reinterpret p n) I8 0 b 0 (int n))
              (String. b "UTF-8"))))

;; --- handles ----------------------------------------------------------------

(defn- near-miss
  "The closest legal export name, by a cheap edit distance on the common typo
   shapes — a dropped, doubled or transposed character."
  [exports k]
  (let [s (if (keyword? k) (name k) (str k))]
    (->> (keys exports)
         (filter (fn [^String e]
                   (let [d (Math/abs (- (count e) (count s)))]
                     (and (<= d 1)
                          (<= (count (remove true? (map = e s)))
                              (max 1 (quot (count e) 4)))))))
         first)))

(defn- alias-map
  "Keyword aliases for the export names that survive a round trip through the
   reader. `wasi:cli/run@0.2.0` does not — it reads back as a *different valid
   keyword* — and `[constructor]f` throws, so those names are string-only
   (`0014` B)."
  [fns]
  (into {} (keep (fn [[nm f]]
                   (let [k (keyword nm)]
                     (when (try (= k (read-string (pr-str k)))
                                (catch Exception _ false))
                       [k f]))))
        fns))

(defn- closed! [^AtomicBoolean flag what]
  (when (.get flag)
    (throw (ex-info (str what " is closed") {:cljwit/error :closed}))))

(deftype Engine [^Arena arena api ^MemorySegment ptr ^AtomicBoolean closed]
  java.lang.AutoCloseable
  (close [_]
    (when (.compareAndSet closed false true)
      (invoke (:engine-delete api) ptr)
      (.close arena))
    nil))

(deftype Artifact [^Engine engine ^Arena arena ^MemorySegment ptr
                   ^AtomicBoolean closed]
  java.lang.AutoCloseable
  (close [_]
    (when (.compareAndSet closed false true)
      (invoke (:comp-delete (.-api engine)) ptr)
      (.close arena))
    nil))

(deftype Instance [^Artifact artifact ^Arena arena exports aliases sigs
                   ^MemorySegment store ^AtomicBoolean closed ^AtomicBoolean in-call]
  java.lang.AutoCloseable
  (close [_]
    (when (.compareAndSet closed false true)
      ;; 0014 D: close is an entry into the store like any other.
      (when-not (.compareAndSet in-call false true)
        (throw (ex-info "cannot close while a call is in flight"
                        {:cljwit/error :concurrent-use})))
      (invoke (:store-delete (.-api ^Engine (.-engine artifact))) store)
      (.close arena))
    nil)
  clojure.lang.ILookup
  (valAt [this k] (.valAt ^clojure.lang.ILookup this k ::none))
  (valAt [_ k default]
    (or (get aliases k)
        (get exports (if (keyword? k) (name k) k))
        (if (= ::none default)
          (throw (ex-info (str "no export " (pr-str k))
                          (cond-> {:cljwit/error :no-such-export
                                   :cljwit/exports (set (keys exports))}
                            (near-miss exports k) (assoc :cljwit/did-you-mean
                                                         (near-miss exports k)))))
          default)))
  clojure.lang.IFn
  (invoke [this k] (.valAt this k ::none)))

;; --- public -----------------------------------------------------------------

(defn engine
  "A wasmtime engine. Process-lifetime: share one across every component, or
   they can never be linked (a compiled artifact is engine-scoped)."
  ^Engine []
  (let [arena (Arena/ofShared)
        a     (api arena)]
    (->Engine arena a (invoke (:engine-new a)) (AtomicBoolean. false))))

(defn compile
  "Compiles a component. This is the expensive step — milliseconds, scaling
   with module size — and is why it has a lifetime of its own (`0014` C)."
  ^Artifact [^Engine e source]
  (closed! (.-closed e) "engine")
  (let [a     (.-api e)
        arena (Arena/ofShared)
        bs    (if (bytes? source) source (.readAllBytes (io/input-stream source)))
        buf   ^MemorySegment (.allocate arena (long (alength ^bytes bs)))
        _     (MemorySegment/copy ^bytes bs 0 buf I8 0 (alength ^bytes bs))
        out   ^MemorySegment (.allocate arena ^java.lang.foreign.MemoryLayout ADDR)]
    (ok! "component_new"
         (invoke (:comp-new a) (.-ptr e) buf (long (alength ^bytes bs)) out))
    (->Artifact e arena (.get out ADDR (long 0)) (AtomicBoolean. false))))

(defn- reflect-func
  "The declared shape of one exported function: parameter names and types, and
   the result type, read from the component itself."
  [api ^Arena arena ^MemorySegment ft]
  (let [np  (invoke (:ft-params api) ft)
        pp  ^MemorySegment (.allocate arena ^java.lang.foreign.MemoryLayout ADDR)
        lp  ^MemorySegment (.allocate arena ^java.lang.foreign.MemoryLayout I64)
        vt  ^MemorySegment (.allocate arena (long VALTYPE))
        kind (fn [] (get VALTYPE-KIND (bit-and (long (.get vt I8 (long 0))) 0xFF)))]
    {:params (mapv (fn [i]
                     (invoke (:ft-param-nth api) ft (long i) pp lp vt)
                     (let [n (read-name pp lp) k (kind)]
                       (invoke (:vt-delete api) vt)
                       [n k]))
                   (range np))
     :result (when (invoke (:ft-result api) ft vt)
               (let [k (kind)] (invoke (:vt-delete api) vt) k))}))

(defn- export-fn
  "Builds the callable for one export. The argument buffer is allocated once
   and reused; the result is lifted eagerly and nothing backed by it escapes
   (`0014` E)."
  [api ^Arena arena ^MemorySegment ctx ^MemorySegment f sig
   ^AtomicBoolean closed ^AtomicBoolean in-call nm]
  (let [ptypes  (mapv second (:params sig))
        n       (count ptypes)
        rtype   (:result sig)
        args    ^MemorySegment (.allocate arena (long (max 1 (* VAL n))))
        res     ^MemorySegment (.allocate arena (long VAL))
        ;; A confined arena per call would be safer for strings, but nothing
        ;; here outlives the call: the bytes are copied into wasm before it
        ;; returns. A shared arena that grows per call would leak, so string
        ;; arguments get a scratch arena of their own, closed on the way out.
        call!   (:func-call api)]
    (fn [& vs]
      (closed! closed (str "instance (calling " nm ")"))
      (when-not (= n (count vs))
        (throw (ex-info (str nm " takes " n " argument(s)")
                        {:cljwit/error :wrong-arity :cljwit/export nm})))
      (when-not (.compareAndSet in-call false true)
        (throw (ex-info "the store is already in use by another call"
                        {:cljwit/error :concurrent-use :cljwit/export nm})))
      (try
        (with-open [scratch (Arena/ofConfined)]
          (dotimes [i n]
            (let [seg (.asSlice args (long (* VAL i)) (long VAL))
                  k   (nth ptypes i)
                  v   (nth vs i)]
              (if (= :string k)
                (lower-string! scratch seg v)
                (lower! seg k v))))
          (ok! (str "call " nm) (invoke call! f ctx args (long n) res (long 1)))
          (let [out (when rtype (lift res rtype))]
            (invoke (:val-delete api) res)
            out))
        (finally (.set in-call false))))))

(defn instantiate
  "Instantiates a compiled component. Cheap — tens of microseconds — so a
   store per request is the intended shape."
  ^Instance [^Artifact art]
  (closed! (.-closed art) "artifact")
  (let [^Engine e (.-engine art)
        _     (closed! (.-closed e) "engine")
        api   (.-api e)
        arena (Arena/ofShared)
        store (invoke (:store-new api) (.-ptr e) MemorySegment/NULL MemorySegment/NULL)
        ctx   (invoke (:store-context api) store)
        clink (invoke (:linker-new api) (.-ptr e))
        inst  ^MemorySegment (.allocate arena (long INSTANCE))
        _     (ok! "linker_instantiate"
                   (invoke (:instantiate api) clink ctx (.-ptr art) inst))
        ct    (invoke (:comp-type api) (.-ptr art))
        cnt   (invoke (:export-count api) ct (.-ptr e))
        pp    ^MemorySegment (.allocate arena ^java.lang.foreign.MemoryLayout ADDR)
        lp    ^MemorySegment (.allocate arena ^java.lang.foreign.MemoryLayout I64)
        item  ^MemorySegment (.allocate arena (long ITEM))
        closed  (AtomicBoolean. false)
        in-call (AtomicBoolean. false)
        sigs  (into {}
                    (keep (fn [i]
                            (when (invoke (:export-nth api) ct (.-ptr e) (long i) pp lp item)
                              (let [nm (read-name pp lp)]
                                (when (= ITEM-FUNC (bit-and (long (.get item I8 (long 0))) 0xFF))
                                  (let [ft (.get item ADDR (long ITEM-OF))]
                                    [nm (reflect-func api arena ft)]))))))
                    (range cnt))
        fns   (into {}
                    (map (fn [[nm sig]]
                           (let [unsupported (remove SUPPORTED
                                                     (cons (:result sig)
                                                           (map second (:params sig))))]
                             [nm (if (seq (remove nil? unsupported))
                                   (fn [& _]
                                     (throw (ex-info
                                             (str nm " uses a type cljwit.host cannot marshal yet")
                                             {:cljwit/error :unsupported-type
                                              :cljwit/export nm
                                              :cljwit/kinds (vec (remove nil? unsupported))})))
                                   (let [eidx (invoke (:export-index api) inst ctx
                                                      MemorySegment/NULL
                                                      (cstr arena nm) (long (count (.getBytes ^String nm "UTF-8"))))
                                         f ^MemorySegment (.allocate arena (long FUNC))]
                                     (when-not (invoke (:get-func api) inst ctx eidx f)
                                       (throw (ex-info (str "export " nm " vanished between type and instance")
                                                       {:cljwit/error :wasmtime})))
                                     (export-fn api arena ctx f sig closed in-call nm)))])))
                    sigs)]
    (->Instance art arena fns (alias-map fns) sigs store closed in-call)))

(defn exports
  "Every exported function, by its exact WIT name."
  [^Instance i]
  (keys (.-exports i)))

(defn signature
  "The declared shape of one export, as read from the component."
  [^Instance i nm]
  (get (.-sigs i) nm))

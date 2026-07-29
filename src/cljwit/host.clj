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
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.lang.foreign Arena Linker SymbolLookup MemorySegment
            FunctionDescriptor ValueLayout]
           [java.lang.invoke MethodHandle MethodHandles MethodType]
           [java.util.concurrent.atomic AtomicBoolean AtomicReference]))

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
(def ^:private ITEM-IFACE 1)  ; ...COMPONENT_INSTANCE — a WIT interface
(def ^:private VT-OF 8)       ; ...its union: one pointer to the specific type
(def ^:private RES-OK 8)      ; wasmtime_component_valresult_t {bool; val *}
(def ^:private RES-VAL 16)
(def ^:private VAR-NAME 8)    ; wasmtime_component_valvariant_t {wasm_name_t; val *}
(def ^:private VAR-VAL 24)
(def ^:private VEC-SIZE 0)    ; vallist / valrecord {size_t size; T *data;}
(def ^:private VEC-DATA 8)
(def ^:private ENTRY 48)      ; valrecord_entry {wasm_name_t name; val val;}
(def ^:private ENTRY-NAME 0)
(def ^:private ENTRY-VAL 16)
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
   :f32 9 :f64 10 :char 11 :string 12
   :flags 20 :tuple 15 :own 21 :borrow 21})

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
  "wasmtime returns a null error pointer on success. On failure the message is
   the only thing that says *why*, and throwing without it turned a two-minute
   debug into an afternoon once already."
  [api what v]
  (let [^MemorySegment e v]
    (when-not (.equals MemorySegment/NULL e)
      (let [msg (with-open [a (Arena/ofConfined)]
                  (let [nm ^MemorySegment (.allocate a (long 16))]
                    (invoke (:err-message api) e nm)
                    (let [n (.get nm I64 (long 0))
                          p ^MemorySegment (.get nm ADDR (long 8))
                          b (byte-array n)]
                      (MemorySegment/copy (.reinterpret p n) I8 0 b 0 (int n))
                      (String. b "UTF-8"))))]
        (invoke (:err-delete api) e)
        (throw (ex-info (str what ": " msg) {:cljwit/error :wasmtime}))))
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
     :import-count  (b "wasmtime_component_type_import_count" I64 ADDR ADDR)
     :import-nth    (b "wasmtime_component_type_import_nth" BOOL ADDR ADDR I64 ADDR ADDR ADDR)
     :iface-count   (b "wasmtime_component_instance_type_export_count" I64 ADDR ADDR)
     :iface-nth     (b "wasmtime_component_instance_type_export_nth" BOOL ADDR ADDR I64 ADDR ADDR ADDR)
     :ft-params     (b "wasmtime_component_func_type_param_count" I64 ADDR)
     :ft-param-nth  (b "wasmtime_component_func_type_param_nth" BOOL ADDR I64 ADDR ADDR ADDR)
     :ft-result     (b "wasmtime_component_func_type_result" BOOL ADDR ADDR)
     :vt-delete     (b "wasmtime_component_valtype_delete" nil ADDR)
     :list-el       (b "wasmtime_component_list_type_element" nil ADDR ADDR)
     :option-ty     (b "wasmtime_component_option_type_ty" nil ADDR ADDR)
     :result-ok     (b "wasmtime_component_result_type_ok" BOOL ADDR ADDR)
     :result-err    (b "wasmtime_component_result_type_err" BOOL ADDR ADDR)
     :enum-count    (b "wasmtime_component_enum_type_names_count" I64 ADDR)
     :enum-nth      (b "wasmtime_component_enum_type_names_nth" BOOL ADDR I64 ADDR ADDR)
     :rec-count     (b "wasmtime_component_record_type_field_count" I64 ADDR)
     :rec-nth       (b "wasmtime_component_record_type_field_nth" BOOL ADDR I64 ADDR ADDR ADDR)
     :flags-count   (b "wasmtime_component_flags_type_names_count" I64 ADDR)
     :flags-nth     (b "wasmtime_component_flags_type_names_nth" BOOL ADDR I64 ADDR ADDR)
     :tup-count     (b "wasmtime_component_tuple_type_types_count" I64 ADDR)
     :tup-nth       (b "wasmtime_component_tuple_type_types_nth" BOOL ADDR I64 ADDR)
     :var-count     (b "wasmtime_component_variant_type_case_count" I64 ADDR)
     :var-nth       (b "wasmtime_component_variant_type_case_nth" BOOL ADDR I64 ADDR ADDR ADDR ADDR)
     :linker-new    (b "wasmtime_component_linker_new" ADDR ADDR)
     :linker-delete (b "wasmtime_component_linker_delete" nil ADDR)
     :instantiate   (b "wasmtime_component_linker_instantiate" ADDR ADDR ADDR ADDR ADDR)
     :export-index  (b "wasmtime_component_instance_get_export_index" ADDR ADDR ADDR ADDR ADDR I64)
     :get-func      (b "wasmtime_component_instance_get_func" BOOL ADDR ADDR ADDR ADDR)
     :func-call     (b "wasmtime_component_func_call" ADDR ADDR ADDR ADDR I64 ADDR I64)
     :res-clone     (b "wasmtime_component_resource_any_clone" ADDR ADDR)
     :res-drop      (b "wasmtime_component_resource_any_drop" ADDR ADDR ADDR)
     :res-delete    (b "wasmtime_component_resource_any_delete" nil ADDR)
     :err-message   (b "wasmtime_error_message" nil ADDR ADDR)
     :err-delete    (b "wasmtime_error_delete" nil ADDR)
     :err-new       (b "wasmtime_error_new" ADDR ADDR)
     ;; 0017 C: wasmtime frees what an import callback writes, so a string
     ;; result cannot come from an Arena. libc's malloc is the allocator it
     ;; expects, and this is the only place in the library that needs one.
     :malloc        (let [seg ^MemorySegment (.orElseThrow (.find (.defaultLookup linker) "malloc"))]
                      (.downcallHandle linker seg
                                       (FunctionDescriptor/of ADDR (into-array java.lang.foreign.MemoryLayout [I64]))
                                       (into-array java.lang.foreign.Linker$Option [])))
     :linker-root   (b "wasmtime_component_linker_root" ADDR ADDR)
     :li-add-inst   (b "wasmtime_component_linker_instance_add_instance" ADDR ADDR ADDR I64 ADDR)
     :li-add-func   (b "wasmtime_component_linker_instance_add_func" ADDR ADDR ADDR I64 ADDR ADDR ADDR)}))

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

;; --- reflection -------------------------------------------------------------

(declare valtype->tree)

(defn- name-pair [^Arena arena]
  [^MemorySegment (.allocate arena ^java.lang.foreign.MemoryLayout ADDR)
   ^MemorySegment (.allocate arena ^java.lang.foreign.MemoryLayout I64)])

(defn- vt-kind [^MemorySegment vt]
  (get VALTYPE-KIND (bit-and (long (.get vt I8 (long 0))) 0xFF)))

(defn- sub-valtype
  "Reads a nested valtype through `f`, into a fresh segment, and returns its
   tree. The segment is deleted afterwards: wasmtime clones on the way out."
  [api ^Arena arena f & args]
  (let [vt ^MemorySegment (.allocate arena (long VALTYPE))]
    (when (apply invoke f (concat args [vt]))
      (let [t (valtype->tree api arena vt)]
        (invoke (:vt-delete api) vt)
        t))))

(defn- valtype->tree
  "A WIT type as Clojure data. Scalars are bare keywords; everything with
   structure is a map carrying the structure, because a marshaller cannot be
   built from the kind alone."
  [api ^Arena arena ^MemorySegment vt]
  (let [k  (vt-kind vt)
        of (.get vt ADDR (long VT-OF))]
    (case k
      :list   {:kind :list
               :element (let [e ^MemorySegment (.allocate arena (long VALTYPE))]
                          (invoke (:list-el api) of e)
                          (let [t (valtype->tree api arena e)]
                            (invoke (:vt-delete api) e) t))}
      :option {:kind :option
               :ty (let [e ^MemorySegment (.allocate arena (long VALTYPE))]
                     (invoke (:option-ty api) of e)
                     (let [t (valtype->tree api arena e)]
                       (invoke (:vt-delete api) e) t))}
      :result {:kind :result
               :ok  (sub-valtype api arena (:result-ok api) of)
               :err (sub-valtype api arena (:result-err api) of)}
      :flags  (let [[pp lp] (name-pair arena)]
                {:kind :flags
                 :names (mapv (fn [i]
                                (invoke (:flags-nth api) of (long i) pp lp)
                                (read-name pp lp))
                              (range (invoke (:flags-count api) of)))})
      :tuple  (let [ft ^MemorySegment (.allocate arena (long VALTYPE))]
                {:kind :tuple
                 :types (mapv (fn [i]
                                (invoke (:tup-nth api) of (long i) ft)
                                (let [t (valtype->tree api arena ft)]
                                  (invoke (:vt-delete api) ft) t))
                              (range (invoke (:tup-count api) of)))})
      :enum   (let [[pp lp] (name-pair arena)]
                {:kind :enum
                 :cases (mapv (fn [i]
                                (invoke (:enum-nth api) of (long i) pp lp)
                                (read-name pp lp))
                              (range (invoke (:enum-count api) of)))})
      :record (let [[pp lp] (name-pair arena)
                    ft ^MemorySegment (.allocate arena (long VALTYPE))]
                {:kind :record
                 :fields (mapv (fn [i]
                                 (invoke (:rec-nth api) of (long i) pp lp ft)
                                 (let [n (read-name pp lp)
                                       t (valtype->tree api arena ft)]
                                   (invoke (:vt-delete api) ft)
                                   [n t]))
                               (range (invoke (:rec-count api) of)))})
      :variant (let [[pp lp] (name-pair arena)
                     hp ^MemorySegment (.allocate arena (long 1))
                     ft ^MemorySegment (.allocate arena (long VALTYPE))]
                 {:kind :variant
                  :cases (mapv (fn [i]
                                 (invoke (:var-nth api) of (long i) pp lp hp ft)
                                 (let [n (read-name pp lp)
                                       has? (not= 0 (.get hp I8 (long 0)))
                                       t (when has? (valtype->tree api arena ft))]
                                   (when has? (invoke (:vt-delete api) ft))
                                   [n t]))
                               (range (invoke (:var-count api) of)))})
      k)))

(def ^:private RESOURCE 21)   ; wasmtime_component_val_t's kind for a handle

(deftype Handle [^MemorySegment ptr ^MemorySegment ctx api registry pending
                 ^AtomicBoolean closed]
  java.lang.AutoCloseable
  (close [this]
    ;; 0016 B: `delete` is required per handle whatever `drop` did, so it goes
    ;; in a finally. Marking closed first would leak the host memory when
    ;; `drop` throws; marking last would re-drop on a retry.
    (when (.compareAndSet closed false true)
      (swap! registry disj this)
      (try
        (ok! api "resource drop" (invoke (:res-drop api) ctx ptr))
        (finally (invoke (:res-delete api) ptr))))
    nil)
  Object
  (toString [_] (str "#cljwit/resource[" (if (.get closed) "closed" "open") "]")))

(defn- transfer!
  "0016 C: lowering an `own` gives the resource to the guest. The host's handle
   is stale immediately — dropping it afterwards is `unknown handle index` —
   so it is deleted, never dropped.

   **Deleted after the call, not during lowering.** `func_call` reads the
   pointer the argument val holds, so freeing it while lowering hands wasmtime
   a dangling `resource_any_t *` — which surfaces as `mismatched resource
   types`, an error about the wrong thing entirely."
  [^Handle h]
  (when (.compareAndSet ^AtomicBoolean (.-closed h) false true)
    (swap! (.-registry h) disj h)
    (swap! (.-pending h) conj (.-ptr h))))

(defn- live-ptr [^Handle h what]
  (when (.get ^AtomicBoolean (.-closed h))
    (throw (ex-info (str "resource handle is closed (" what ")")
                    {:cljwit/error :closed})))
  (.-ptr h))

;; --- marshalling ------------------------------------------------------------
;; Lowering and lifting are *compiled* from the reflected tree once per export,
;; not interpreted per call. An unsupported type fails at instantiation naming
;; the kind, rather than at the call with a wrong answer.

(declare lower-fn lift-fn)

(defn- write-name! [^Arena arena ^MemorySegment seg off ^String t]
  (let [b   (.getBytes t "UTF-8")
        buf ^MemorySegment (.allocate arena (long (max 1 (alength b))))]
    (MemorySegment/copy ^bytes b 0 buf I8 0 (alength b))
    (.set seg I64 (long (+ off STR-SIZE)) (long (alength b)))
    (.set seg ADDR (long (+ off STR-DATA)) buf)))

(defn- read-str [^MemorySegment seg off]
  (let [n (.get seg I64 (long (+ off STR-SIZE)))
        p ^MemorySegment (.get seg ADDR (long (+ off STR-DATA)))
        b (byte-array n)]
    (MemorySegment/copy (.reinterpret p n) I8 0 b 0 (int n))
    (String. b "UTF-8")))

(defn- scalar-lower [kind]
  (fn [_ ^MemorySegment seg v]
    (.set seg I8 (long 0) (byte (VAL-KIND kind)))
    (case kind
      ;; One byte, not four: the union member is a C `bool`.
      :bool (.set seg I8 (long UNION) (byte (if v 1 0)))
      :s8   (.set seg I8 (long UNION) (byte v))
      :u8   (.set seg I8 (long UNION) (unchecked-byte v))
      (:s16 :u16 :s32 :u32 :char) (.set seg I32 (long UNION) (unchecked-int v))
      (:s64 :u64) (.set seg I64 (long UNION) (long v))
      :f32  (.set seg F32 (long UNION) (float v))
      :f64  (.set seg F64 (long UNION) (double v)))))

(defn- scalar-lift [kind]
  (fn [^MemorySegment seg]
    (case kind
      :bool (not= 0 (.get seg I8 (long UNION)))
      :s8   (.get seg I8 (long UNION))
      :u8   (bit-and (long (.get seg I8 (long UNION))) 0xFF)
      :s16  (unchecked-short (.get seg I32 (long UNION)))
      :u16  (bit-and (long (.get seg I32 (long UNION))) 0xFFFF)
      (:s32 :char) (.get seg I32 (long UNION))
      :u32  (bit-and (long (.get seg I32 (long UNION))) 0xFFFFFFFF)
      (:s64 :u64) (.get seg I64 (long UNION))
      :f32  (.get seg F32 (long UNION))
      :f64  (.get seg F64 (long UNION)))))

(defn- val-of
  "Allocates one wasmtime_component_val_t in `arena` and lowers `v` into it."
  [lower ^Arena arena v]
  (let [m ^MemorySegment (.allocate arena (long VAL))]
    (lower arena m v)
    m))

(defn- lower-fn [tree]
  (if (keyword? tree)
    (case tree
      :string (fn [^Arena arena ^MemorySegment seg v]
                (.set seg I8 (long 0) (byte (VAL-KIND :string)))
                (write-name! arena seg UNION v))
      (:own :borrow)
      (let [own? (= :own tree)]
        (fn [_ ^MemorySegment seg v]
          (let [^Handle h v
                ^MemorySegment p (live-ptr h (if own? "transferring" "borrowing"))]
            (.set seg I8 (long 0) (byte RESOURCE))
            (.set seg ADDR (long UNION) p)
            ;; A borrow leaves the handle live; an own is stale the moment the
            ;; guest takes it (0016 C).
            (when own? (transfer! h)))))
      (scalar-lower tree))
    (case (:kind tree)
      :enum (fn [^Arena arena ^MemorySegment seg v]
              (.set seg I8 (long 0) (byte 17))
              (write-name! arena seg UNION (name v)))
      :option (let [inner (lower-fn (:ty tree))]
                (fn [^Arena arena ^MemorySegment seg v]
                  (.set seg I8 (long 0) (byte 18))
                  (let [^MemorySegment q (if (nil? v)
                                           MemorySegment/NULL
                                           (val-of inner arena v))]
                    (.set seg ADDR (long UNION) q))))
      :result (let [lo (some-> (:ok tree) lower-fn)
                    le (some-> (:err tree) lower-fn)]
                (fn [^Arena arena ^MemorySegment seg v]
                  (let [[tag payload] v
                        ok? (= :ok tag)
                        f   (if ok? lo le)]
                    (.set seg I8 (long 0) (byte 19))
                    (.set seg I8 (long RES-OK) (byte (if ok? 1 0)))
                    (let [^MemorySegment q (if (nil? f)
                                             MemorySegment/NULL
                                             (val-of f arena payload))]
                      (.set seg ADDR (long RES-VAL) q)))))
      :variant (let [lowers (into {} (map (fn [[n t]] [n (some-> t lower-fn)]))
                                  (:cases tree))]
                 (fn [^Arena arena ^MemorySegment seg v]
                   (let [[tag payload] v
                         nm (name tag)
                         f  (get lowers nm)]
                     (.set seg I8 (long 0) (byte 16))
                     (write-name! arena seg VAR-NAME nm)
                     (let [^MemorySegment q (if (nil? f)
                                              MemorySegment/NULL
                                              (val-of f arena payload))]
                       (.set seg ADDR (long VAR-VAL) q)))))
      ;; A set of keywords on this side, the names that are on over there.
      ;; Written in declaration order so the wire form is canonical.
      :flags (let [names (:names tree)]
               (fn [^Arena arena ^MemorySegment seg v]
                 (let [on  (filterv (fn [n] (contains? v (keyword n))) names)
                       buf ^MemorySegment (.allocate arena (long (max 1 (* 16 (count on)))))]
                   (dotimes [i (count on)]
                     (write-name! arena buf (* 16 i) (nth on i)))
                   (.set seg I8 (long 0) (byte 20))
                   (.set seg I64 (long (+ UNION VEC-SIZE)) (long (count on)))
                   (.set seg ADDR (long (+ UNION VEC-DATA)) buf))))
      :tuple (let [els (mapv lower-fn (:types tree))]
               (fn [^Arena arena ^MemorySegment seg v]
                 (let [n   (count els)
                       buf ^MemorySegment (.allocate arena (long (max 1 (* VAL n))))]
                   (dotimes [i n]
                     ((nth els i) arena (.asSlice buf (long (* VAL i)) (long VAL)) (nth v i)))
                   (.set seg I8 (long 0) (byte 15))
                   (.set seg I64 (long (+ UNION VEC-SIZE)) (long n))
                   (.set seg ADDR (long (+ UNION VEC-DATA)) buf))))
      :list (let [el (lower-fn (:element tree))]
              (fn [^Arena arena ^MemorySegment seg v]
                (let [n   (count v)
                      buf ^MemorySegment (.allocate arena (long (max 1 (* VAL n))))]
                  (dotimes [i n]
                    (el arena (.asSlice buf (long (* VAL i)) (long VAL)) (nth v i)))
                  (.set seg I8 (long 0) (byte 13))
                  (.set seg I64 (long (+ UNION VEC-SIZE)) (long n))
                  (.set seg ADDR (long (+ UNION VEC-DATA)) buf))))
      :record (let [fs (mapv (fn [[n t]] [n (lower-fn t)]) (:fields tree))]
                (fn [^Arena arena ^MemorySegment seg v]
                  ;; Fields go out in declaration order: a Clojure map has none.
                  (let [buf ^MemorySegment (.allocate arena (long (* ENTRY (count fs))))]
                    (dotimes [i (count fs)]
                      (let [[nm f] (nth fs i)
                            e (.asSlice buf (long (* ENTRY i)) (long ENTRY))]
                        (write-name! arena e ENTRY-NAME nm)
                        (f arena (.asSlice e (long ENTRY-VAL) (long VAL))
                           (get v (keyword nm)))))
                    (.set seg I8 (long 0) (byte 14))
                    (.set seg I64 (long (+ UNION VEC-SIZE)) (long (count fs)))
                    (.set seg ADDR (long (+ UNION VEC-DATA)) buf)))))))

(defn- lift-fn [tree rt]
  (if (keyword? tree)
    (case tree
      :string (fn [^MemorySegment seg] (read-str seg UNION))
      ;; 0016: clone on lift. With one reused result val, the next call deletes
      ;; the previous handle -- and deletes without dropping, so the resource
      ;; leaks and its destructor never runs.
      (:own :borrow)
      (fn [^MemorySegment seg]
        (let [p ^MemorySegment (.get seg ADDR (long UNION))
              c ^MemorySegment (invoke (:res-clone (:api rt)) p)
              h (->Handle c (:ctx rt) (:api rt) (:registry rt) (:pending rt)
                          (AtomicBoolean. false))]
          (swap! (:registry rt) conj h)
          h))
      (scalar-lift tree))
    (case (:kind tree)
      :enum (fn [^MemorySegment seg] (keyword (read-str seg UNION)))
      :option (let [inner (lift-fn (:ty tree) rt)]
                (fn [^MemorySegment seg]
                  (let [p ^MemorySegment (.get seg ADDR (long UNION))]
                    (when-not (.equals MemorySegment/NULL p)
                      (inner (.reinterpret p (long VAL)))))))
      :result (let [lo (when (:ok tree) (lift-fn (:ok tree) rt))
                    le (when (:err tree) (lift-fn (:err tree) rt))]
                (fn [^MemorySegment seg]
                  (let [ok? (not= 0 (.get seg I8 (long RES-OK)))
                        f   (if ok? lo le)
                        p ^MemorySegment (.get seg ADDR (long RES-VAL))]
                    [(if ok? :ok :err)
                     (when (and f (not (.equals MemorySegment/NULL p)))
                       (f (.reinterpret p (long VAL))))])))
      :variant (let [lifts (into {} (map (fn [[n t]] [n (when t (lift-fn t rt))]))
                                 (:cases tree))]
                 (fn [^MemorySegment seg]
                   (let [nm (read-str seg VAR-NAME)
                         f  (get lifts nm)
                         p ^MemorySegment (.get seg ADDR (long VAR-VAL))]
                     (if (and f (not (.equals MemorySegment/NULL p)))
                       [(keyword nm) (f (.reinterpret p (long VAL)))]
                       [(keyword nm)]))))
      :flags (fn [^MemorySegment seg]
               (let [n (.get seg I64 (long (+ UNION VEC-SIZE)))
                     p ^MemorySegment (.get seg ADDR (long (+ UNION VEC-DATA)))
                     b (.reinterpret p (long (* 16 n)))]
                 (into #{} (map (fn [i] (keyword (read-str b (* 16 i))))) (range n))))
      :tuple (let [els (mapv (fn [t] (lift-fn t rt)) (:types tree))]
               (fn [^MemorySegment seg]
                 (let [p ^MemorySegment (.get seg ADDR (long (+ UNION VEC-DATA)))
                       b (.reinterpret p (long (* VAL (count els))))]
                   (mapv (fn [i] ((nth els i) (.asSlice b (long (* VAL i)) (long VAL))))
                         (range (count els))))))
      :list (let [el (lift-fn (:element tree) rt)]
              (fn [^MemorySegment seg]
                (let [n (.get seg I64 (long (+ UNION VEC-SIZE)))
                      p ^MemorySegment (.get seg ADDR (long (+ UNION VEC-DATA)))
                      b (.reinterpret p (long (* VAL n)))]
                  (mapv (fn [i] (el (.asSlice b (long (* VAL i)) (long VAL))))
                        (range n)))))
      :record (let [fs (mapv (fn [[n t]] [(keyword n) (lift-fn t rt)]) (:fields tree))]
                (fn [^MemorySegment seg]
                  (let [n (.get seg I64 (long (+ UNION VEC-SIZE)))
                        p ^MemorySegment (.get seg ADDR (long (+ UNION VEC-DATA)))
                        b (.reinterpret p (long (* ENTRY n)))]
                    ;; Keyed by the *reflected* field order, which the ABI fixes.
                    (into {} (map (fn [i]
                                    (let [[k f] (nth fs i)
                                          e (.asSlice b (long (* ENTRY i)) (long ENTRY))]
                                      [k (f (.asSlice e (long ENTRY-VAL) (long VAL)))]))
                                  (range n)))))))))

(defn- unsupported
  "Kinds `0012` has no accepted mapping for yet, found anywhere in a tree."
  [tree]
  (cond
    (nil? tree) nil
    (keyword? tree) (when-not (contains? VAL-KIND tree) [tree])
    :else (case (:kind tree)
            :list (unsupported (:element tree))
            :option (unsupported (:ty tree))
            :result (concat (unsupported (:ok tree)) (unsupported (:err tree)))
            :enum nil
            :flags nil
            :tuple (mapcat unsupported (:types tree))
            :record (mapcat (fn [[_ t]] (unsupported t)) (:fields tree))
            :variant (mapcat (fn [[_ t]] (unsupported t)) (:cases tree))
            [(:kind tree)])))

(definterface ImportCallback
  (^java.lang.foreign.MemorySegment
   call [^java.lang.foreign.MemorySegment data
         ^java.lang.foreign.MemorySegment ctx
         ^java.lang.foreign.MemorySegment ty
         ^java.lang.foreign.MemorySegment args ^long nargs
         ^java.lang.foreign.MemorySegment res ^long nres]))

;; Slice one of 0017: scalars only. Anything wasmtime would have to *free* --
;; a string, a list, a boxed option -- needs `lower-fn` to allocate with
;; `malloc` rather than from an Arena (0017 C), which is not built yet. Refusing
;; at instantiate with the kind named beats a wrong answer or a corrupt heap.
(def ^:private IMPORTABLE
  #{:bool :s8 :u8 :s16 :u16 :s32 :u32 :s64 :u64 :f32 :f64 :char :string})

(defn- import-stub
  "One FFM upcall stub for one host import. Lifts the arguments, calls `f`,
   lowers the result, and converts anything thrown into a wasmtime error --
   a JVM exception unwinding through native frames exits the VM (0017 D)."
  [api ^Arena arena sig f nm dead]
  (let [lifts  (mapv (fn [[_ t]] (if (= :string t)
                                   (fn [^MemorySegment seg] (read-str seg UNION))
                                   (scalar-lift t)))
                     (:params sig))
        rt     (:result sig)
        lower  (cond
                 (nil? rt) nil
                 (= :string rt)
                 (fn [_ ^MemorySegment seg ^String v]
                   ;; malloc, not the arena: wasmtime takes ownership and frees.
                   (let [b   (.getBytes v "UTF-8")
                         n   (max 1 (alength b))
                         buf ^MemorySegment (.reinterpret
                                             ^MemorySegment (invoke (:malloc api) (long n))
                                             (long n))]
                     (MemorySegment/copy ^bytes b 0 buf I8 0 (alength b))
                     (.set seg I8 (long 0) (byte (VAL-KIND :string)))
                     (.set seg I64 (long (+ UNION STR-SIZE)) (long (alength b)))
                     (.set seg ADDR (long (+ UNION STR-DATA)) buf)))
                 :else (scalar-lower rt))
        cb (reify ImportCallback
             (call [_ _data _ctx _ty args _n res _nres]
               (try
                 (let [ab ^MemorySegment (.reinterpret ^MemorySegment args
                                                       (long (* VAL (max 1 (count lifts)))))
                       vs (mapv (fn [i] ((nth lifts i) (.asSlice ab (long (* VAL i)) (long VAL))))
                                (range (count lifts)))
                       out (apply f vs)]
                   (when lower
                     (lower nil (.reinterpret ^MemorySegment res (long VAL)) out))
                   MemorySegment/NULL)
                 (catch Throwable t
                   ;; This handler must not throw: ex-message can be nil, and
                   ;; the C string needs an arena alive inside the callback.
                   (reset! dead (str nm ": " (or (ex-message t) (str t))))
                   (with-open [a (Arena/ofConfined)]
                     (let [msg (str nm ": " (or (ex-message t) (str t)))
                           b   (.getBytes msg "UTF-8")
                           cs  ^MemorySegment (.allocate a (long (inc (alength b))))]
                       (MemorySegment/copy ^bytes b 0 cs I8 0 (alength b))
                       (.set cs I8 (long (alength b)) (byte 0))
                       ^MemorySegment (invoke (:err-new api) cs)))))))
        mt (MethodType/methodType MemorySegment
                                  ^"[Ljava.lang.Class;"
                                  (into-array Class [MemorySegment MemorySegment MemorySegment
                                                     MemorySegment Long/TYPE MemorySegment Long/TYPE]))
        mh (.bindTo (.findVirtual (MethodHandles/lookup) ImportCallback "call" mt) cb)]
    (.upcallStub (Linker/nativeLinker) mh
                 (FunctionDescriptor/of ADDR (into-array java.lang.foreign.MemoryLayout
                                                         [ADDR ADDR ADDR ADDR I64 ADDR I64]))
                 arena
                 (into-array java.lang.foreign.Linker$Option []))))

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
                   ^MemorySegment store ^AtomicBoolean closed ^AtomicReference in-call
                   registry dead]
  java.lang.AutoCloseable
  (close [_]
    ;; 0014 D: close is an entry into the store like any other. Take `in-call`
    ;; *before* flipping `closed` — the other order marks the instance closed,
    ;; then throws, leaving the store never deleted and the arena never closed,
    ;; with every retry a silent no-op because the CAS already won.
    (when-not (.get closed)
      (when (some? (.compareAndExchange ^AtomicReference in-call nil (Thread/currentThread)))
        (throw (ex-info "cannot close while a call is in flight"
                        {:cljwit/error :concurrent-use})))
      (when (.compareAndSet closed false true)
        ;; Before the store goes: a handle that outlives it holds a dangling
        ;; context, which is the one real use-after-free in this row (0016 D).
        ;;
        ;; A handle's `drop` can throw — a guest that trapped leaves the
        ;; instance unable to be entered, so every drop then fails. Letting
        ;; that escape here would skip `store_delete` and `arena.close`
        ;; entirely, and the retry would be a silent no-op because `closed` is
        ;; already set: the same shape as the bug this ordering was written to
        ;; fix, one level down. Teardown always completes; the first failure is
        ;; rethrown after it.
        (let [errs (reduce (fn [acc ^java.lang.AutoCloseable h]
                             (try (.close h) acc (catch Throwable t (conj acc t))))
                           [] @registry)]
          (invoke (:store-delete (.-api ^Engine (.-engine artifact))) store)
          (.close arena)
          (when-let [t (first errs)] (throw t)))))
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
    (ok! a "component_new"
         (invoke (:comp-new a) (.-ptr e) buf (long (alength ^bytes bs)) out))
    (->Artifact e arena (.get out ADDR (long 0)) (AtomicBoolean. false))))

(defn- reflect-func
  "The declared shape of one exported function: parameter names and full types,
   and the result type, read from the component itself. Nested structure comes
   with it — a marshaller cannot be built from the kind alone."
  [api ^Arena arena ^MemorySegment ft]
  (let [np  (invoke (:ft-params api) ft)
        [pp lp] (name-pair arena)
        vt  ^MemorySegment (.allocate arena (long VALTYPE))]
    {:params (mapv (fn [i]
                     (invoke (:ft-param-nth api) ft (long i) pp lp vt)
                     (let [n (read-name pp lp)
                           t (valtype->tree api arena vt)]
                       (invoke (:vt-delete api) vt)
                       [n t]))
                   (range np))
     :result (when (invoke (:ft-result api) ft vt)
               (let [t (valtype->tree api arena vt)]
                 (invoke (:vt-delete api) vt)
                 t))}))

(defn- export-fn
  "Builds the callable for one export. The argument buffer is allocated once
   and reused; the result is lifted eagerly and nothing backed by it escapes
   (`0014` E)."
  [api ^Arena arena ^MemorySegment ctx ^MemorySegment f sig
   ^AtomicBoolean closed ^AtomicReference in-call nm rt dead]
  (let [ptypes  (mapv (comp lower-fn second) (:params sig))
        n       (count ptypes)
        rlift   (when (:result sig) (lift-fn (:result sig) rt))
        args    ^MemorySegment (.allocate arena (long (max 1 (* VAL n))))
        res     ^MemorySegment (.allocate arena (long VAL))
        ;; A confined arena per call would be safer for strings, but nothing
        ;; here outlives the call: the bytes are copied into wasm before it
        ;; returns. A shared arena that grows per call would leak, so string
        ;; arguments get a scratch arena of their own, closed on the way out.
        call!   (:func-call api)]
    (fn [& vs]
      (closed! closed (str "instance (calling " nm ")"))
      (when-let [why @dead]
        (throw (ex-info (str "instance is dead: a host import threw (" why ")")
                        {:cljwit/error :instance-dead :cljwit/export nm})))
      (when-not (= n (count vs))
        (throw (ex-info (str nm " takes " n " argument(s)")
                        {:cljwit/error :wrong-arity :cljwit/export nm})))
      ;; 0014 D is one flag doing two jobs. Which one it is depends on whether
      ;; *this* thread already holds it: a host import calling back into the
      ;; instance that is executing is nesting, which the component model also
      ;; forbids ("wasm trap: cannot enter component instance"), and saying
      ;; "another call" about it sends the reader looking for a second thread.
      (let [me (Thread/currentThread)
            holder (.compareAndExchange ^AtomicReference in-call nil me)]
        (when (some? holder)
          (throw (ex-info (if (identical? holder me)
                            (str "re-entered the store while " nm
                                 " was running — a component instance cannot be"
                                 " re-entered, including from a host import")
                            (str "the store is in use by " (.getName ^Thread holder)))
                          {:cljwit/error (if (identical? holder me)
                                           :reentrant :concurrent-use)
                           :cljwit/export nm}))))
      (try
        (with-open [scratch (Arena/ofConfined)]
          (dotimes [i n]
            ((nth ptypes i) scratch (.asSlice args (long (* VAL i)) (long VAL)) (nth vs i)))
          (ok! api (str "call " nm) (invoke call! f ctx args (long n) res (long 1)))
          ;; No val_delete on `res`. Its header says it "will deallocate the
          ;; contents of the value but not the value pointer itself", and on a
          ;; result or variant — the two kinds carrying a payload pointer — the
          ;; *second* call then aborts the JVM in a wasmtime panic that cannot
          ;; unwind. wasmtime recycles its own result allocation: 300k calls
          ;; grow RSS by less than the same number of scalar calls do.
          (let [out (when rlift (rlift res))]
            out))
        (finally
          (let [ps @(:pending rt)]
            (when (seq ps)
              (reset! (:pending rt) [])
              (run! (fn [p] (invoke (:res-delete api) p)) ps)))
          (.set ^AtomicReference in-call nil))))))

(defn- reflect-imports
  "What the component needs, keyed exactly as exports are (`0017` A)."
  [api ^Arena arena ^Engine e ^MemorySegment ct]
  (let [[pp lp] (name-pair arena)
        item ^MemorySegment (.allocate arena (long ITEM))
        kind (fn [] (bit-and (long (.get item I8 (long 0))) 0xFF))]
    (into {}
          (comp
           (keep (fn [i]
                   (when (invoke (:import-nth api) ct (.-ptr e) (long i) pp lp item)
                     (let [nm (read-name pp lp)
                           k  (kind)
                           of (.get item ADDR (long ITEM-OF))]
                       (cond
                         (= ITEM-FUNC k) [[nm (reflect-func api arena of)]]
                         (= ITEM-IFACE k)
                         (let [[ip il] (name-pair arena)
                               sub ^MemorySegment (.allocate arena (long ITEM))]
                           (into []
                                 (keep (fn [j]
                                         (when (invoke (:iface-nth api) of (.-ptr e) (long j) ip il sub)
                                           (when (= ITEM-FUNC (bit-and (long (.get sub I8 (long 0))) 0xFF))
                                             [(str nm "#" (read-name ip il))
                                              (reflect-func api arena (.get sub ADDR (long ITEM-OF)))]))))
                                 (range (invoke (:iface-count api) of (.-ptr e)))))
                         :else nil)))))
           cat)
          (range (invoke (:import-count api) ct (.-ptr e))))))

(defn- define-imports!
  "Registers each supplied Clojure function with the linker. Missing imports
   are wasmtime's to report at instantiate, in a message that names both the
   interface and the function (`0017` B); what the host checks is the other
   direction, because a key nobody needs is a typo."
  [api ^Arena arena ^Engine e _ctx clink ^MemorySegment ct imports dead]
  (when (seq imports)
    (let [needed (reflect-imports api arena e ct)
          extra  (remove (set (keys needed)) (keys imports))]
      (when (seq extra)
        (throw (ex-info (str "no such import: " (pr-str (vec extra)))
                        {:cljwit/error :no-such-import
                         :cljwit/extra (vec extra)
                         :cljwit/imports (set (keys needed))})))
      (let [root (invoke (:linker-root api) clink)
            ifaces (atom {})]
        (doseq [[nm f] imports]
          (let [sig (get needed nm)
                bad (remove IMPORTABLE (cons (:result sig) (map second (:params sig))))
                bad (remove nil? bad)]
            (when (seq bad)
              (throw (ex-info (str nm " uses a type cljwit.host cannot marshal in this direction yet")
                              {:cljwit/error :unsupported-type
                               :cljwit/import nm :cljwit/kinds (vec bad)})))
            (let [[iface fname] (if (str/includes? nm "#") (str/split nm #"#" 2) [nil nm])
                  li (if (nil? iface)
                       root
                       (or (get @ifaces iface)
                           (let [out ^MemorySegment (.allocate arena ^java.lang.foreign.MemoryLayout ADDR)]
                             (ok! api "linker_instance_add_instance"
                                  (invoke (:li-add-inst api) root (cstr arena iface)
                                          (long (count (.getBytes ^String iface "UTF-8"))) out))
                             (let [v (.get out ADDR (long 0))]
                               (swap! ifaces assoc iface v)
                               v))))
                  stub (import-stub api arena sig f nm dead)]
              (ok! api "linker_instance_add_func"
                   (invoke (:li-add-func api) li (cstr arena fname)
                           (long (count (.getBytes ^String fname "UTF-8")))
                           stub MemorySegment/NULL MemorySegment/NULL)))))))))

(defn instantiate
  "Instantiates a compiled component. Cheap — tens of microseconds — so a
   store per request is the intended shape."
  (^Instance [^Artifact art] (instantiate art {}))
  (^Instance [^Artifact art {:keys [imports]}]
   (closed! (.-closed art) "artifact")
   (let [^Engine e (.-engine art)
         _     (closed! (.-closed e) "engine")
         api   (.-api e)
         arena (Arena/ofShared)
         store (invoke (:store-new api) (.-ptr e) MemorySegment/NULL MemorySegment/NULL)
         ctx   (invoke (:store-context api) store)
         clink (invoke (:linker-new api) (.-ptr e))
         ct    (invoke (:comp-type api) (.-ptr art))
        ;; 0017 D: an import that throws poisons the whole store, so the
        ;; instance has to know it is dead rather than let every later call
        ;; report a trap with the cause gone.
         dead  (atom nil)
         _     (define-imports! api arena e ctx clink ct imports dead)
         inst  ^MemorySegment (.allocate arena (long INSTANCE))
         _     (ok! api "linker_instantiate"
                    (invoke (:instantiate api) clink ctx (.-ptr art) inst))
         closed  (AtomicBoolean. false)
         in-call (AtomicReference. nil)
        ;; 0016 D: one call can yield handles the caller never destructured, so
        ;; the instance keeps the list and closes what is left before deleting
        ;; the store a handle's context points into.
         registry (atom #{})
        ;; Handles transferred by a call, deleted once the call has read them.
        ;; One atom per instance is enough because 0014 D forbids concurrent
        ;; calls into a store.
         pending  (atom [])
         rt      {:ctx ctx :api api :registry registry :pending pending}
         item-kind (fn [^MemorySegment it] (bit-and (long (.get it I8 (long 0))) 0xFF))
        ;; Every export, flattened. A function inside an interface is keyed the
        ;; way WIT spells it — "pkg:name/iface@ver#func" — and remembers the
        ;; path it needs to resolve its export index (0014 B).
        ;; Fresh out-parameters per level: a recursive walk that shares them
        ;; has the inner call overwrite the outer's name mid-iteration, which
        ;; is a JVM crash rather than a wrong answer.
         walk  (fn walk [ty nth-f count-f prefix]
                ;; Fresh out-parameters per level: a recursive walk that shares
                ;; them has the inner call overwrite the outer's name
                ;; mid-iteration, which is a JVM crash, not a wrong answer.
                ;; And `cat` here, not only at the top — a recursive producer
                ;; has to flatten its own layer.
                 (let [[pp lp] (name-pair arena)
                       item ^MemorySegment (.allocate arena (long ITEM))
                       one  (fn [i]
                              (when (invoke nth-f ty (.-ptr e) (long i) pp lp item)
                                (let [nm (read-name pp lp)
                                      k  (item-kind item)
                                      of (.get item ADDR (long ITEM-OF))]
                                  (cond
                                    (= ITEM-FUNC k)
                                    [{:key  (if prefix (str prefix "#" nm) nm)
                                      :path (if prefix [prefix nm] [nm])
                                      :sig  (reflect-func api arena of)}]

                                   ;; One level: WIT does not nest interfaces.
                                    (and (= ITEM-IFACE k) (nil? prefix))
                                    (walk of (:iface-nth api) (:iface-count api) nm)))))]
                   (into [] (comp (keep one) cat)
                         (range (invoke count-f ty (.-ptr e))))))
         found (walk ct (:export-nth api) (:export-count api) nil)
         sigs  (into {} (map (fn [x] [(:key x) (:sig x)])) found)
         index-of (fn [path]
                    (when (empty? path)
                      (throw (ex-info "an export with no path" {:cljwit/error :internal})))
                    (reduce (fn [parent seg]
                              (let [ix ^MemorySegment
                                    (invoke (:export-index api) inst ctx parent
                                            (cstr arena seg)
                                            (long (count (.getBytes ^String seg "UTF-8"))))]
                               ;; A null index passed to get_func is a segfault,
                               ;; not an error return.
                                (when (.equals MemorySegment/NULL ix)
                                  (throw (ex-info (str "no export index for " (pr-str seg)
                                                       " in " (pr-str path))
                                                  {:cljwit/error :no-such-export
                                                   :cljwit/path path})))
                                ix))
                            MemorySegment/NULL
                            path))
         fns   (into {}
                     (map (fn [{:keys [key path sig]}]
                            (let [bad (distinct (mapcat unsupported
                                                        (cons (:result sig)
                                                              (map second (:params sig)))))]
                              [key (if (seq bad)
                                     (fn [& _]
                                       (throw (ex-info
                                               (str key " uses a type cljwit.host cannot marshal yet")
                                               {:cljwit/error :unsupported-type
                                                :cljwit/export key
                                                :cljwit/kinds (vec bad)})))
                                     (let [eidx (index-of path)
                                           f ^MemorySegment (.allocate arena (long FUNC))]
                                       (when-not (invoke (:get-func api) inst ctx eidx f)
                                         (throw (ex-info (str "export " key " vanished between type and instance")
                                                         {:cljwit/error :wasmtime})))
                                       (export-fn api arena ctx f sig closed in-call key rt dead)))])))
                     found)]
     (->Instance art arena fns (alias-map fns) sigs store closed in-call registry dead))))

(defn exports
  "Every exported function, by its exact WIT name."
  [^Instance i]
  (keys (.-exports i)))

(defn signature
  "The declared shape of one export, as read from the component."
  [^Instance i nm]
  (get (.-sigs i) nm))

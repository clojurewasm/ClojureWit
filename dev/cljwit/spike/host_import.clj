(ns cljwit.spike.host-import
  "Can a Wasm guest call back into Clojure?

   `0014` names host imports as its most-likely-to-fire falsifier: they are
   what WASI needs, and they break two of its decisions — the in-call flag
   (`0014` D) sees a guest callback as concurrent use, and the reused argument
   buffer (`0014` E) is no longer safe once a call can nest.

   This is the premise check, not the feature. It answers one question: does
   `java.lang.foreign`'s upcall stub reach Clojure from inside a component
   call at all?"
  (:require [clojure.java.io :as io])
  (:import [java.lang.foreign Arena Linker SymbolLookup MemorySegment
            FunctionDescriptor ValueLayout]
           [java.lang.invoke MethodHandle MethodHandles MethodType]))

(set! *warn-on-reflection* true)

(def ^:private ^java.lang.foreign.AddressLayout ADDR ValueLayout/ADDRESS)
(def ^:private ^java.lang.foreign.ValueLayout$OfByte I8 ValueLayout/JAVA_BYTE)
(def ^:private ^java.lang.foreign.ValueLayout$OfInt I32 ValueLayout/JAVA_INT)
(def ^:private ^java.lang.foreign.ValueLayout$OfLong I64 ValueLayout/JAVA_LONG)

(def ^:private VAL 32)
(def ^:private UNION 8)
(def ^:private KIND-U32 6)

(definterface Callback
  (^java.lang.foreign.MemorySegment
   call [^java.lang.foreign.MemorySegment data
         ^java.lang.foreign.MemorySegment ctx
         ^java.lang.foreign.MemorySegment ty
         ^java.lang.foreign.MemorySegment args ^long nargs
         ^java.lang.foreign.MemorySegment res ^long nres]))

(defn -main [& _args]
  (let [linker (Linker/nativeLinker)
        lib    (or (System/getenv "CLJWIT_WASMTIME_LIB")
                   (throw (ex-info "CLJWIT_WASMTIME_LIB unset" {})))]
    (with-open [arena (Arena/ofShared)]
      (let [lookup (SymbolLookup/libraryLookup ^String lib arena)
            fx  (fn [nm ret & args]
                  (let [seg ^MemorySegment (.orElseThrow (.find lookup ^String nm))
                        ls  (into-array java.lang.foreign.MemoryLayout args)]
                    (.downcallHandle linker seg
                                     ^FunctionDescriptor
                                     (if ret (FunctionDescriptor/of ret ls)
                                         (FunctionDescriptor/ofVoid ls))
                                     (into-array java.lang.foreign.Linker$Option []))))
            call (fn [^MethodHandle mh & as] (.invokeWithArguments mh ^java.util.List (vec as)))
            emsg (fx "wasmtime_error_message" nil ADDR ADDR)
            edel (fx "wasmtime_error_delete" nil ADDR)
            ;; Reading wasmtime's own message is the difference between a
            ;; bounded question and a guess — see 0016.
            ok!  (fn [what v]
                   (when-not (.equals MemorySegment/NULL ^MemorySegment v)
                     (let [nm ^MemorySegment (.allocate arena (long 16))]
                       (call emsg v nm)
                       (let [n (.get nm I64 (long 0))
                             p ^MemorySegment (.get nm ADDR (long 8))
                             b (byte-array n)]
                         (MemorySegment/copy (.reinterpret p n) I8 0 b 0 (int n))
                         (call edel v)
                         (throw (ex-info (str what ": " (String. b "UTF-8")) {})))))
                   v)
            cstr (fn [^String t]
                   (let [b (.getBytes t "UTF-8")
                         s ^MemorySegment (.allocate arena (long (inc (alength b))))]
                     (MemorySegment/copy ^bytes b 0 s I8 0 (alength b))
                     (.set s I8 (long (alength b)) (byte 0))
                     s))
            seen (atom [])
            reenter (atom nil)   ; set once `run` is resolvable
            reentry (atom :not-tried)
            ;; The host function the guest imports. Reads one u32 out of the
            ;; argument val and writes one back — the same layout every other
            ;; lane in this repo uses.
            cb   (reify Callback
                   (call [_ _data _ctx _ty args _n res _nres]
                     (let [v (.get ^MemorySegment (.reinterpret ^MemorySegment args (long VAL))
                                   I32 (long UNION))
                           out ^MemorySegment (.reinterpret ^MemorySegment res (long VAL))]
                       (swap! seen conj v)
                       ;; Does the component model let a host callback call
                       ;; back into the instance that is currently executing?
                       (when-let [f @reenter]
                         (reset! reentry (try (f 1) (catch Exception ex (ex-message ex)))))
                       (.set out I8 (long 0) (byte KIND-U32))
                       (.set out I32 (long UNION) (int (* 2 v)))
                       MemorySegment/NULL)))
            mh   (.bindTo (.findVirtual (MethodHandles/lookup) Callback "call"
                                        (MethodType/methodType
                                         MemorySegment
                                         ^"[Ljava.lang.Class;"
                                         (into-array Class [MemorySegment MemorySegment MemorySegment
                                                            MemorySegment Long/TYPE MemorySegment Long/TYPE])))
                          cb)
            stub (.upcallStub linker mh
                              (FunctionDescriptor/of
                               ADDR (into-array java.lang.foreign.MemoryLayout
                                                [ADDR ADDR ADDR ADDR I64 ADDR I64]))
                              arena
                              (into-array java.lang.foreign.Linker$Option []))

            engine (call (fx "wasm_engine_new" ADDR))
            store  (call (fx "wasmtime_store_new" ADDR ADDR ADDR ADDR)
                         engine MemorySegment/NULL MemorySegment/NULL)
            ctx    (call (fx "wasmtime_store_context" ADDR ADDR) store)
            bs     (.readAllBytes (io/input-stream (or (first *command-line-args*) "target/imp.component.wasm")))
            buf    ^MemorySegment (.allocate arena (long (alength ^bytes bs)))
            _      (MemorySegment/copy ^bytes bs 0 buf I8 0 (alength ^bytes bs))
            cout   ^MemorySegment (.allocate arena ^java.lang.foreign.MemoryLayout ADDR)
            _      (ok! "component_new"
                        (call (fx "wasmtime_component_new" ADDR ADDR ADDR I64 ADDR)
                              engine buf (long (alength ^bytes bs)) cout))
            comp   (.get cout ADDR (long 0))
            clink  (call (fx "wasmtime_component_linker_new" ADDR ADDR) engine)
            root   (call (fx "wasmtime_component_linker_root" ADDR ADDR) clink)
            iout   ^MemorySegment (.allocate arena ^java.lang.foreign.MemoryLayout ADDR)
            iname  "local:imp/host@0.1.0"
            _      (ok! "add_instance"
                        (call (fx "wasmtime_component_linker_instance_add_instance" ADDR ADDR ADDR I64 ADDR)
                              root (cstr iname) (long (count (.getBytes iname "UTF-8"))) iout))
            li     (.get iout ADDR (long 0))
            _      (ok! "add_func"
                        (call (fx "wasmtime_component_linker_instance_add_func" ADDR ADDR ADDR I64 ADDR ADDR ADDR)
                              li (cstr "twice") (long 5) stub MemorySegment/NULL MemorySegment/NULL))
            inst   ^MemorySegment (.allocate arena (long 16))
            _      (ok! "instantiate"
                        (call (fx "wasmtime_component_linker_instantiate" ADDR ADDR ADDR ADDR ADDR)
                              clink ctx comp inst))
            eidx   (call (fx "wasmtime_component_instance_get_export_index" ADDR ADDR ADDR ADDR ADDR I64)
                         inst ctx MemorySegment/NULL (cstr "run") (long 3))
            f      ^MemorySegment (.allocate arena (long 24))
            _      (when-not (call (fx "wasmtime_component_instance_get_func" ValueLayout/JAVA_BOOLEAN ADDR ADDR ADDR ADDR)
                                   inst ctx eidx f)
                     (throw (ex-info "no export run" {})))
            args   ^MemorySegment (.allocate arena (long VAL))
            res    ^MemorySegment (.allocate arena (long VAL))]
        (reset! reenter
                (fn [n]
                  (let [a2 ^MemorySegment (.allocate arena (long VAL))
                        r2 ^MemorySegment (.allocate arena (long VAL))]
                    (.set a2 I8 (long 0) (byte KIND-U32))
                    (.set a2 I32 (long UNION) (int n))
                    (ok! "re-entrant func_call"
                         (call (fx "wasmtime_component_func_call" ADDR ADDR ADDR ADDR I64 ADDR I64)
                               f ctx a2 (long 1) r2 (long 1)))
                    (.get r2 I32 (long UNION)))))
        (.set args I8 (long 0) (byte KIND-U32))
        (.set args I32 (long UNION) (int 20))
        (ok! "func_call" (call (fx "wasmtime_component_func_call" ADDR ADDR ADDR ADDR I64 ADDR I64)
                               f ctx args (long 1) res (long 1)))
        (println "run(20) =" (.get res I32 (long UNION))
                 "  host saw" @seen
                 "  (expected 41 and [20])")
        (println "re-entry from the host callback:" (pr-str @reentry))))))

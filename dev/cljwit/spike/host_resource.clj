(ns cljwit.spike.host-resource
  "Can the host *define* a resource the guest imports?

   Every `wasi:io` interface hands out a handle, so this is the shape `0017` F
   says is inside the host-import unit rather than after it. A map keyed
   `iface#func` cannot express a resource, and minting one needs a second
   upcall shape — a destructor `void (*)(void *, wasmtime_context_t *, u32)`.

   The premise check, not the feature:

       bb spike-hres

   The one thing that is not guessable: **the destructor returns
   `wasmtime_error_t *`, not `void`.** Declaring it void makes wasmtime read a
   garbage register as an error pointer and crash walking its source chain,
   after the destructor has already run correctly. A null destructor is not
   allowed either -- wasmtime calls it."
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
(def ^:private KIND-RESOURCE 21)
(def ^:private TY 7)          ; the u32 tag that distinguishes this host type

(definterface Import
  (^java.lang.foreign.MemorySegment
   call [^java.lang.foreign.MemorySegment data ^java.lang.foreign.MemorySegment ctx
         ^java.lang.foreign.MemorySegment ty
         ^java.lang.foreign.MemorySegment args ^long nargs
         ^java.lang.foreign.MemorySegment res ^long nres]))

(definterface Dtor
  ;; Returns wasmtime_error_t*, not void. Declaring it void makes wasmtime read
  ;; a garbage register as an error pointer and crash walking it.
  (^java.lang.foreign.MemorySegment
   call [^java.lang.foreign.MemorySegment data ^java.lang.foreign.MemorySegment ctx
         ^int rep]))

(defn -main [& _args]
  (let [linker (Linker/nativeLinker)
        lib (or (System/getenv "CLJWIT_WASMTIME_LIB")
                (throw (ex-info "CLJWIT_WASMTIME_LIB unset" {})))]
    (with-open [arena (Arena/ofShared)]
      (let [lookup (SymbolLookup/libraryLookup ^String lib arena)
            fx (fn [nm ret & args]
                 (let [seg ^MemorySegment (.orElseThrow (.find lookup ^String nm))
                       ls (into-array java.lang.foreign.MemoryLayout args)]
                   (.downcallHandle linker seg
                                    ^FunctionDescriptor
                                    (if ret (FunctionDescriptor/of ret ls)
                                        (FunctionDescriptor/ofVoid ls))
                                    (into-array java.lang.foreign.Linker$Option []))))
            call (fn [^MethodHandle mh & as] (.invokeWithArguments mh ^java.util.List (vec as)))
            emsg (fx "wasmtime_error_message" nil ADDR ADDR)
            ok! (fn [what v]
                  (when-not (.equals MemorySegment/NULL ^MemorySegment v)
                    (let [nm ^MemorySegment (.allocate arena (long 16))]
                      (call emsg v nm)
                      (let [n (.get nm I64 (long 0))
                            p ^MemorySegment (.get nm ADDR (long 8))
                            b (byte-array n)]
                        (MemorySegment/copy (.reinterpret p n) I8 0 b 0 (int n))
                        (throw (ex-info (str what ": " (String. b "UTF-8")) {})))))
                  v)
            cstr (fn [^String t]
                   (let [b (.getBytes t "UTF-8")
                         s ^MemorySegment (.allocate arena (long (inc (alength b))))]
                     (MemorySegment/copy ^bytes b 0 s I8 0 (alength b))
                     (.set s I8 (long (alength b)) (byte 0))
                     s))
            upcall (fn [obj iface mt desc]
                     (.upcallStub linker
                                  (.bindTo (.findVirtual (MethodHandles/lookup) iface "call" mt) obj)
                                  desc arena (into-array java.lang.foreign.Linker$Option [])))
            IMPORT-MT (MethodType/methodType MemorySegment ^"[Ljava.lang.Class;"
                                             (into-array Class [MemorySegment MemorySegment MemorySegment
                                                                MemorySegment Long/TYPE MemorySegment Long/TYPE]))
            IMPORT-FD (FunctionDescriptor/of ADDR (into-array java.lang.foreign.MemoryLayout
                                                              [ADDR ADDR ADDR ADDR I64 ADDR I64]))
            ;; reps the host has hidden behind handles, and what happened to them
            table (atom {})
            dropped (atom [])
            next-rep (atom 0)

            hnew (fx "wasmtime_component_resource_host_new" ADDR ValueLayout/JAVA_BOOLEAN I32 I32)
            hrep (fx "wasmtime_component_resource_host_rep" I32 ADDR)
            hdel (fx "wasmtime_component_resource_host_delete" nil ADDR)
            h2a (fx "wasmtime_component_resource_host_to_any" ADDR ADDR ADDR ADDR)
            a2h (fx "wasmtime_component_resource_any_to_host" ADDR ADDR ADDR ADDR)

            engine (call (fx "wasm_engine_new" ADDR))
            store (call (fx "wasmtime_store_new" ADDR ADDR ADDR ADDR)
                        engine MemorySegment/NULL MemorySegment/NULL)
            ctx (call (fx "wasmtime_store_context" ADDR ADDR) store)

            mint (reify Import
                   (call [_ _d cx _t args _n res _nr]
                     (let [v (.get ^MemorySegment (.reinterpret ^MemorySegment args (long VAL))
                                   I32 (long UNION))
                           rep (swap! next-rep inc)
                           _ (swap! table assoc rep v)
                           hr ^MemorySegment (call hnew true (int rep) (int TY))
                           out ^MemorySegment (.allocate arena ^java.lang.foreign.MemoryLayout ADDR)]
                       (ok! "host_to_any" (call h2a cx hr out))
                       (call hdel hr)
                       (let [o ^MemorySegment (.reinterpret ^MemorySegment res (long VAL))]
                         (.set o I8 (long 0) (byte KIND-RESOURCE))
                         (.set o ADDR (long UNION) ^MemorySegment (.get out ADDR (long 0))))
                       MemorySegment/NULL)))
            peek (reify Import
                   (call [_ _d cx _t args _n res _nr]
                     (let [a ^MemorySegment (.reinterpret ^MemorySegment args (long VAL))
                           any ^MemorySegment (.get a ADDR (long UNION))
                           out ^MemorySegment (.allocate arena ^java.lang.foreign.MemoryLayout ADDR)]
                       (ok! "any_to_host" (call a2h cx any out))
                       (let [hr ^MemorySegment (.get out ADDR (long 0))
                             rep (call hrep hr)
                             o ^MemorySegment (.reinterpret ^MemorySegment res (long VAL))]
                         (call hdel hr)
                         (.set o I8 (long 0) (byte KIND-U32))
                         (.set o I32 (long UNION) (int (get @table rep -1))))
                       MemorySegment/NULL)))
            dtor (reify Dtor
                   (call [_ _d _cx rep]
                     (swap! dropped conj [rep (get @table rep)])
                     (swap! table dissoc rep)
                     MemorySegment/NULL))

            bs (.readAllBytes (io/input-stream (or (first *command-line-args*)
                                                   "target/hres.component.wasm")))
            buf ^MemorySegment (.allocate arena (long (alength ^bytes bs)))
            _ (MemorySegment/copy ^bytes bs 0 buf I8 0 (alength ^bytes bs))
            cout ^MemorySegment (.allocate arena ^java.lang.foreign.MemoryLayout ADDR)
            _ (ok! "component_new" (call (fx "wasmtime_component_new" ADDR ADDR ADDR I64 ADDR)
                                         engine buf (long (alength ^bytes bs)) cout))
            comp (.get cout ADDR (long 0))
            clink (call (fx "wasmtime_component_linker_new" ADDR ADDR) engine)
            root (call (fx "wasmtime_component_linker_root" ADDR ADDR) clink)
            iout ^MemorySegment (.allocate arena ^java.lang.foreign.MemoryLayout ADDR)
            iname "local:hres/host@0.1.0"
            _ (ok! "add_instance" (call (fx "wasmtime_component_linker_instance_add_instance" ADDR ADDR ADDR I64 ADDR)
                                        root (cstr iname) (long (count (.getBytes iname "UTF-8"))) iout))
            li (.get iout ADDR (long 0))
            rty (call (fx "wasmtime_component_resource_type_new_host" ADDR I32) (int TY))
            _ (ok! "add_resource"
                   (call (fx "wasmtime_component_linker_instance_add_resource" ADDR ADDR ADDR I64 ADDR ADDR ADDR ADDR)
                         li (cstr "token") (long 5) rty
                         (upcall dtor Dtor
                                 (MethodType/methodType MemorySegment ^"[Ljava.lang.Class;"
                                                        (into-array Class [MemorySegment MemorySegment Integer/TYPE]))
                                 (FunctionDescriptor/of ADDR (into-array java.lang.foreign.MemoryLayout [ADDR ADDR I32])))
                         MemorySegment/NULL MemorySegment/NULL))
            addf (fx "wasmtime_component_linker_instance_add_func" ADDR ADDR ADDR I64 ADDR ADDR ADDR)
            _ (ok! "add mint" (call addf li (cstr "mint") (long 4)
                                    (upcall mint Import IMPORT-MT IMPORT-FD)
                                    MemorySegment/NULL MemorySegment/NULL))
            _ (ok! "add peek" (call addf li (cstr "peek") (long 4)
                                    (upcall peek Import IMPORT-MT IMPORT-FD)
                                    MemorySegment/NULL MemorySegment/NULL))
            inst ^MemorySegment (.allocate arena (long 16))
            _ (ok! "instantiate" (call (fx "wasmtime_component_linker_instantiate" ADDR ADDR ADDR ADDR ADDR)
                                       clink ctx comp inst))
            eidx (call (fx "wasmtime_component_instance_get_export_index" ADDR ADDR ADDR ADDR ADDR I64)
                       inst ctx MemorySegment/NULL (cstr "run") (long 3))
            f ^MemorySegment (.allocate arena (long 24))
            _ (when-not (call (fx "wasmtime_component_instance_get_func" ValueLayout/JAVA_BOOLEAN ADDR ADDR ADDR ADDR)
                              inst ctx eidx f)
                (throw (ex-info "no export run" {})))
            args ^MemorySegment (.allocate arena (long VAL))
            res ^MemorySegment (.allocate arena (long VAL))]
        (.set args I8 (long 0) (byte KIND-U32))
        (.set args I32 (long UNION) (int 42))
        (ok! "func_call" (call (fx "wasmtime_component_func_call" ADDR ADDR ADDR ADDR I64 ADDR I64)
                               f ctx args (long 1) res (long 1)))
        (println "run(42) =" (.get res I32 (long UNION))
                 " dropped:" (pr-str @dropped)
                 " table now:" (pr-str @table))))))

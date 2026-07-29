(ns cljwit.spike.component-call
  "S1's first question, answered by running it: can the JVM get a value across a
   Wasm component boundary?

   This is a spike, not `cljwit.host`. It hand-rolls the smallest path through
   wasmtime's component C API — engine, store, component, linker, instantiate,
   look up an export, call it — with no abstraction, so that what the real
   library has to wrap is visible rather than guessed at. Everything it learns
   belongs in a design note before any of it becomes an API.

   Run: `nix develop --command clojure -M:dev -m cljwit.spike.component-call`

   The struct layouts below were measured with a C program against the pinned
   wasmtime 47.0.1 headers, not inferred:

     wasmtime_component_val_t   32 bytes, kind:u8 at 0, union at 8
     wasmtime_component_instance_t  16 bytes
     wasmtime_component_func_t      24 bytes
     WASMTIME_COMPONENT_S32     = 5

   One finding worth carrying into the library's design: **Clojure cannot call
   `MethodHandle.invokeExact`.** It is signature-polymorphic, so the JVM
   requires the call site to state the exact type statically, which Clojure's
   reflective interop cannot do — it fails with `No matching field found:
   invokeExact`. `invokeWithArguments` works and is what this uses, at the cost
   of boxing every argument and return. For a host making many small calls that
   is a real cost, and the way out is emitting bytecode rather than reflecting."
  (:require [clojure.java.io :as io])
  (:import [java.lang.foreign Arena FunctionDescriptor Linker MemorySegment
            SymbolLookup ValueLayout]
           [java.lang.invoke MethodHandle]))

(def ^:private ADDR ValueLayout/ADDRESS)
(def ^:private I32 ValueLayout/JAVA_INT)
(def ^:private I64 ValueLayout/JAVA_LONG)
(def ^:private I8 ValueLayout/JAVA_BYTE)

(def ^:private VAL-SIZE 32)
(def ^:private VAL-UNION-OFFSET 8)
(def ^:private KIND-S32 5)

(defn- lib-path
  "The flake exports this. `.claude/CLAUDE.md` forbids a machine-specific path
   in a committed file, so the path is computed by nix rather than written here
   — and how a *shipped* cljwit.host finds the library is still open."
  []
  (or (System/getenv "CLJWIT_WASMTIME_LIB")
      (throw (ex-info "CLJWIT_WASMTIME_LIB is unset — run inside `nix develop`" {}))))

(defn- downcall
  "Bind one C function. Every signature here is transcribed from the pinned
   headers; getting one wrong is a segfault, not an exception, which is the
   main reason this spike exists before the library does."
  [^SymbolLookup lookup ^Linker linker nm ret & args]
  (let [seg ^MemorySegment (.orElseThrow (.find lookup (name nm)))
        fd  ^FunctionDescriptor
        (if ret
          (FunctionDescriptor/of ret (into-array java.lang.foreign.MemoryLayout args))
          (FunctionDescriptor/ofVoid (into-array java.lang.foreign.MemoryLayout args)))]
    ;; The options varargs must be passed explicitly, or Clojure resolves to
    ;; downcallHandle(FunctionDescriptor, Option...) and casts the address to a
    ;; descriptor. It fails as a ClassCastException here; in a less lucky
    ;; signature it would be a segfault.
    (.downcallHandle linker seg fd
                     (into-array java.lang.foreign.Linker$Option []))))

(defn- err-message
  "wasmtime returns a non-null wasmtime_error_t* on failure. Rendering it needs
   more of the C API than this spike binds, so it reports the pointer and stops
   — a spike that hides a failure is worse than one that dies."
  [what err]
  (let [^MemorySegment err err]
    (when-not (.equals MemorySegment/NULL err)
      (throw (ex-info (str what " failed") {:error-ptr (.address err)})))))

(defn- call
  "MethodHandle.invokeExact is signature-polymorphic and unreachable from
   Clojure; invokeWithArguments is not, and boxes."
  [^MethodHandle mh & args]
  (.invokeWithArguments mh ^java.util.List (vec args)))

(defn -main [& _]
  (let [linker (Linker/nativeLinker)]
    (with-open [arena0 (Arena/ofConfined)]
      ;; Hinted, or every allocate/allocateFrom below resolves reflectively
      ;; against the private implementation class instead of the Arena
      ;; interface and fails to find the method at all.
      (let [^Arena arena arena0
            lookup (SymbolLookup/libraryLookup ^String (lib-path) arena)
            fx     (fn [nm ret & args] (apply downcall lookup linker nm ret args))

            engine-new     (fx "wasm_engine_new" ADDR)
            store-new      (fx "wasmtime_store_new" ADDR ADDR ADDR ADDR)
            store-context  (fx "wasmtime_store_context" ADDR ADDR)
            component-new  (fx "wasmtime_component_new" ADDR ADDR ADDR I64 ADDR)
            linker-new     (fx "wasmtime_component_linker_new" ADDR ADDR)
            instantiate    (fx "wasmtime_component_linker_instantiate" ADDR ADDR ADDR ADDR ADDR)
            get-export-idx (fx "wasmtime_component_instance_get_export_index" ADDR ADDR ADDR ADDR ADDR I64)
            get-func       (fx "wasmtime_component_instance_get_func" ValueLayout/JAVA_BOOLEAN ADDR ADDR ADDR ADDR)
            func-call      (fx "wasmtime_component_func_call" ADDR ADDR ADDR ADDR I64 ADDR I64)

            ;; A WasmGC core module whose exported signature is scalars only, so
            ;; per doc/design/0007 it becomes a component with no linear memory.
            wasm (.readAllBytes (io/input-stream (io/file "dev/resources/add.component.wasm")))
            buf  (.allocate arena (long (count wasm)))
            _    (MemorySegment/copy ^bytes wasm 0 ^MemorySegment buf I8 0 (int (count wasm)))

            engine (call engine-new)
            store  (call store-new engine MemorySegment/NULL MemorySegment/NULL)
            ctx    (call store-context store)

            comp-out  (.allocate arena ^java.lang.foreign.MemoryLayout ADDR)
            _         (err-message "component_new"
                                   (call component-new engine buf (long (count wasm)) comp-out))
            component (.get ^MemorySegment comp-out ADDR (long 0))

            clinker  (call linker-new engine)
            instance (.allocate arena (long 16))
            _        (err-message "linker_instantiate"
                                  (call instantiate clinker ctx component instance))

            ;; Built by hand rather than with allocateFrom: that method is
            ;; declared on SegmentAllocator, and Clojure resolves it against the
            ;; non-exported Arena implementation class where it cannot see it.
            nm      (let [b   (.getBytes "add" "UTF-8")
                          seg ^MemorySegment (.allocate arena (long (inc (alength b))))]
                      (MemorySegment/copy ^bytes b 0 seg I8 0 (alength b))
                      (.set seg I8 (long (alength b)) (byte 0))
                      seg)
            exp-idx (call get-export-idx instance ctx MemorySegment/NULL nm (long 3))
            _       (when (.equals MemorySegment/NULL exp-idx)
                      (throw (ex-info "export \"add\" not found" {})))

            func  (.allocate arena (long 24))
            found (call get-func instance ctx exp-idx func)
            _     (when-not found (throw (ex-info "get_func returned false" {})))

            args ^MemorySegment (.allocate arena (long (* 2 VAL-SIZE)))
            res  ^MemorySegment (.allocate arena (long VAL-SIZE))]
        (doseq [[i v] (map-indexed vector [17 25])]
          (let [base (long (* i VAL-SIZE))]
            (.set args I8 base (byte KIND-S32))
            (.set args I32 (+ base VAL-UNION-OFFSET) (int v))))
        (err-message "func_call" (call func-call func ctx args (long 2) res (long 1)))
        (let [kind (.get res I8 (long 0))
              out  (.get res I32 (long VAL-UNION-OFFSET))]
          (println (format "add(17, 25) across the component boundary = %d (kind %d)" out kind))
          (when-not (= 42 out)
            (throw (ex-info "wrong answer across the boundary" {:got out})))
          (println "A value crossed a Wasm component boundary from the JVM."))))))

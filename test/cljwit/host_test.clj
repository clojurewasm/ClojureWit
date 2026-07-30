(ns cljwit.host-test
  "The contract `0014` specifies, written before the implementation.

   Everything here is about the *shape* of the API — lifetimes, naming,
   failure modes. That values survive the boundary is `roundtrip_test`'s job
   and is not repeated; this asserts only that `cljwit.host` reaches the same
   answers through the public interface."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [cljwit.host :as host])
  (:import [java.io File]))

(set! *warn-on-reflection* true)

(def ^:private lib (System/getenv "CLJWIT_WASMTIME_LIB"))

(defn- build-component!
  ([] (build-component! "echo"))
  ([base]
   (let [core (File/createTempFile "cljwit-host" ".core.wasm")
         emb  (File/createTempFile "cljwit-host" ".embed.wasm")
         out  (File/createTempFile "cljwit-host" ".component.wasm")
         run! (fn [& args]
                (let [{:keys [exit err]} (apply shell/sh args)]
                  (when-not (zero? exit)
                    (throw (ex-info (str "failed: " (pr-str args)) {:err err})))))]
     (run! "wasm-tools" "parse" (str "dev/resources/" base ".wat") "-o" (str core))
     ;; A directory when the world needs a `deps/` tree, a file otherwise.
     (let [d (io/file (str "dev/resources/" base))
           w (if (.isDirectory d) (str d) (str "dev/resources/" base ".wit"))]
       (run! "wasm-tools" "component" "embed" w (str core) "-o" (str emb)))
     (run! "wasm-tools" "component" "new" (str emb) "-o" (str out))
     out)))

(defn- build-dummy!
  "A component with no guest at all — `--dummy` synthesises the core module
   from the WIT. The point is a shape this repo did not author."
  [base]
  (let [emb (File/createTempFile "cljwit-dummy" ".embed.wasm")
        out (File/createTempFile "cljwit-dummy" ".component.wasm")
        run! (fn [& args]
               (let [{:keys [exit err]} (apply shell/sh args)]
                 (when-not (zero? exit)
                   (throw (ex-info (str "failed: " (pr-str args)) {:err err})))))]
    (run! "wasm-tools" "component" "embed" "--dummy"
          (str "dev/resources/" base ".wit") "-o" (str emb))
    (run! "wasm-tools" "component" "new" (str emb) "-o" (str out))
    out))

(defn- with-echo
  "Opens the three lifetimes `0014` separates and calls `f` with the instance."
  [f]
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping host test")
    (let [c (build-component!)]
      (try
        (with-open [e (host/engine)]
          (with-open [a (host/compile e (io/file c))]
            (with-open [i (host/instantiate a)]
              (f i))))
        (finally (.delete ^File c))))))

(deftest finding-libwasmtime
  ;; `0019`. An explicit source that fails to load is an error naming it, not
  ;; a fallthrough — a typo that silently probed onward could load a different
  ;; wasmtime than the one named.
  (testing "a :lib that does not load stops there, naming the path"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (host/engine {:lib "/nonexistent/libwasmtime.dylib"})))]
      (is (= :no-library (:cljwit/error (ex-data ex))))
      (is (re-find #"/nonexistent/libwasmtime\.dylib" (ex-message ex)))))
  (testing "a library that loads but is not wasmtime names the missing symbol"
    ;; libjava ships with every JVM and dlopens fine, standing in for a
    ;; pre-43 libwasmtime: loaded, then missing the component API.
    (let [jvm-lib (io/file (System/getProperty "java.home") "lib"
                           (System/mapLibraryName "java"))]
      (if-not (.exists jvm-lib)
        (println "no libjava at" (str jvm-lib) "— skipping symbol-missing case")
        (let [ex (is (thrown? clojure.lang.ExceptionInfo
                              (host/engine {:lib (str jvm-lib)})))]
          (is (= :symbol-missing (:cljwit/error (ex-data ex))))
          (is (re-find #">= 43" (ex-message ex)))))))
  (when lib
    (testing "the system property is honored — and beats the environment"
      (try
        (System/setProperty "cljwit.wasmtime.lib" "/also/not/here.dylib")
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (host/engine)))]
          (is (= "/also/not/here.dylib" (:cljwit/tried (ex-data ex)))
              "CLJWIT_WASMTIME_LIB points at a real library, and lost"))
        (finally (System/clearProperty "cljwit.wasmtime.lib"))))
    (testing "an explicit :lib beats the property"
      (try
        (System/setProperty "cljwit.wasmtime.lib" "/also/not/here.dylib")
        (with-open [e (host/engine {:lib lib})]
          (is (some? e)))
        (finally (System/clearProperty "cljwit.wasmtime.lib"))))))

(deftest reflects-its-own-exports
  (with-echo
    (fn [i]
      (testing "every export the component declares, by its exact WIT name"
        (is (= #{"echo-bool" "echo-s32" "echo-u64" "echo-f32" "echo-f64"
                 "echo-char" "echo-string" "echo-colour" "echo-option-u32"
                 "echo-option-option-u32" "echo-result" "echo-shape"
                 "echo-list-u32" "echo-pair" "echo-perms" "echo-tuple"}
               (set (host/exports i)))))
      (testing "the reflected signature, which is what marshalling is built from"
        (is (= {:params [["v" :string]] :result :string}
               (host/signature i "echo-string")))
        (is (= {:params [["v" :s32]] :result :s32}
               (host/signature i "echo-s32"))
            "s32, not s16 — the valtype enum is not the val enum")))))

(deftest names-are-wit-strings-with-keyword-aliases
  (with-echo
    (fn [i]
      (testing "the exact WIT name always works"
        (is (fn? (i "echo-string"))))
      (testing "a keyword alias exists when the name round-trips through the reader"
        (is (fn? (:echo-string i)))
        (is (identical? (i "echo-string") (:echo-string i))
            "the alias is the same function, not a second one"))
      (testing "an unknown name throws and names the near miss"
        (let [e (is (thrown? clojure.lang.ExceptionInfo (i "echo-strng")))]
          (is (= "echo-string" (:cljwit/did-you-mean (ex-data e))))))
      (testing "a keyword miss throws too, rather than yielding nil"
        (is (thrown? clojure.lang.ExceptionInfo (:echo-strng i)))))))

(deftest values-survive-through-the-public-api
  (with-echo
    (fn [i]
      (is (true? ((i "echo-bool") true)))
      (is (false? ((i "echo-bool") false)))
      (is (= 42 ((:echo-s32 i) 42)))
      (is (= -1 ((:echo-s32 i) -1)))
      (is (= "日本語" ((:echo-string i) "日本語")))
      (is (= "" ((:echo-string i) "")))
      (is (= (float 0.5) ((:echo-f32 i) 0.5)))
      (is (= 0.25 ((:echo-f64 i) 0.25)))
      (testing "many sequential calls — the in-call flag must be released each time"
        (is (= 100 (count (distinct (map (fn [n] ((:echo-s32 i) n)) (range 100))))))))))

(deftest describe-reads-the-artifact
  ;; `0020` E: the generator's single source, and a REPL user's first
  ;; question about an alien .wasm — no instance needed.
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping describe test")
    (let [c (build-component!)]
      (try
        (with-open [e (host/engine)]
          (with-open [a (host/compile e (io/file c))]
            (let [d (host/describe a)]
              (is (= {:params [["v" :string]] :result :string}
                     (get d "echo-string")))
              (testing "identical to what instantiate finds"
                (with-open [i (host/instantiate a)]
                  (is (= d (into {}
                                 (map (fn [k] [k (host/signature i k)]))
                                 (host/exports i)))))))))
        (finally (.delete ^File c))))))

(deftest a-handle-belongs-to-its-instance
  ;; `0020` B: a handle from instance A passed to instance B used to surface
  ;; as wasmtime's `mismatched resource types` — an error about the wrong
  ;; thing. The handle knows its store, so the mismatch is named.
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping wrong-instance test")
    (let [f (build-component! "res")]
      (try
        (with-open [e  (host/engine)
                    a  (host/compile e (io/file f))
                    i1 (host/instantiate a)
                    i2 (host/instantiate a)]
          (let [h  ((i1 "local:res/bag@0.1.0#[constructor]counter") 5)
                ex (is (thrown? clojure.lang.ExceptionInfo
                                ((i2 "local:res/bag@0.1.0#[method]counter.bump") h 1)))]
            (is (= :wrong-instance (:cljwit/error (ex-data ex))))
            (is (int? ((i1 "local:res/bag@0.1.0#[method]counter.bump") h 1))
                "and the instance it belongs to still takes it")))
        (finally (.delete ^File f))))))

(deftest one-resource-under-two-names
  ;; `0018` A: a `use`d resource reflects once per interface. Both names must
  ;; bind to one type — and the clustering merge deletes the duplicate clone,
  ;; which only this multi-name shape exercises.
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping multi-name resource test")
    (let [f (build-component! "mres")]
      (try
        (with-open [e (host/engine)
                    a (host/compile e (io/file f))]
          (let [dropped (atom [])
                opts {:resources {"local:mres/host@0.1.0#token"
                                  {:drop (fn [v] (swap! dropped conj v))}}
                      :imports {"local:mres/host@0.1.0#mint" (fn [v] {:n v})
                                "local:mres/more@0.1.0#peek" (fn [t] (:n t))}}]
            (with-open [i (host/instantiate a opts)]
              (is (= 9 ((i "run") 9))
                  "minted through one interface, peeked through the other")
              (is (= [{:n 9}] @dropped)))
            (dotimes [_ 20]
              (.close ^java.lang.AutoCloseable (host/instantiate a opts)))
            (is true "20 multi-name instantiates exercised the merge-branch delete")))
        (finally (.delete ^File f))))))

(deftest reflection-cleanup-survives-repetition
  ;; type_delete, item_delete and resource_type_delete are required by the
  ;; pinned headers and fire on every reflection. The val_delete incident
  ;; (0014 E) surfaced on the *second* call, so this loops well past it.
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping cleanup-repetition test")
    (let [c (build-component!)
          f (build-component! "hres")]
      (try
        (with-open [e (host/engine)]
          (with-open [a (host/compile e (io/file c))]
            (dotimes [_ 50] (host/describe a))
            (dotimes [_ 50] (.close ^java.lang.AutoCloseable (host/instantiate a)))
            (is (= {:params [["v" :string]] :result :string}
                   (get (host/describe a) "echo-string"))
                "reflection still answers after the churn"))
          (with-open [a2 (host/compile e (io/file f))]
            (dotimes [_ 20]
              (.close ^java.lang.AutoCloseable
               (host/instantiate
                a2 {:resources {"local:hres/host@0.1.0#token" {:drop identity}}
                    :imports {"local:hres/host@0.1.0#mint" (fn [v] {:n v})
                              "local:hres/host@0.1.0#peek" (fn [t] (:n t))
                              "local:hres/host@0.1.0#eat" (fn [t] (:n @t))}})))
            (is true "20 resource-defining instantiates deleted their types")))
        (finally (.delete ^File c) (.delete ^File f))))))

(deftest lifetimes-are-explicit
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping host lifetime test")
    (let [c (build-component!)]
      (try
        (testing "a closed instance refuses to be called rather than faulting"
          (let [f (with-open [e (host/engine)]
                    (with-open [a (host/compile e (io/file c))]
                      (let [i (host/instantiate a)
                            g (:echo-s32 i)]
                        (.close i)
                        g)))]
            (is (thrown? clojure.lang.ExceptionInfo (f 1)))))
        (testing "close is idempotent"
          (let [e (host/engine)]
            (.close e)
            (is (nil? (.close e)))))
        (finally (.delete ^File c))))))

(deftest aggregates-round-trip
  (with-echo
    (fn [i]
      (testing "the nested type has to come from reflection, not a hard-coded kind"
        (is (= {:params [["v" {:kind :list :element :u32}]]
                :result {:kind :list :element :u32}}
               (host/signature i "echo-list-u32")))
        (is (= {:params [["v" {:kind :record
                               :fields [["n" :u32] ["label" :string]]}]]
                :result {:kind :record :fields [["n" :u32] ["label" :string]]}}
               (host/signature i "echo-pair")))
        (is (= [["circle" :f64] ["square" :u32] ["point" nil]]
               (:cases (:result (host/signature i "echo-shape"))))))

      (testing "0012's rows, through the public API"
        (is (= :red ((:echo-colour i) :red)))
        (is (= :blue ((:echo-colour i) :blue)))
        (is (= 42 ((:echo-option-u32 i) 42)))
        (is (nil? ((:echo-option-u32 i) nil)))
        (is (= [:ok 7] ((:echo-result i) [:ok 7])))
        (is (= [:err "boom"] ((:echo-result i) [:err "boom"])))
        (is (= [:circle 1.5] ((:echo-shape i) [:circle 1.5])))
        (is (= [:square 9] ((:echo-shape i) [:square 9])))
        (is (= [:point] ((:echo-shape i) [:point])))
        (is (= [1 2 3] ((:echo-list-u32 i) [1 2 3])))
        (is (= [] ((:echo-list-u32 i) [])))
        (is (= (vec (range 100)) ((:echo-list-u32 i) (vec (range 100)))))
        (is (= {:n 7 :label "hi"} ((:echo-pair i) {:n 7 :label "hi"})))
        (is (= {:n 4294967295 :label "日本語"}
               ((:echo-pair i) {:n 4294967295 :label "日本語"}))))

      (testing "flags is a set of keywords, tuple a vector — 0012's last two value rows"
        (is (= {:params [["v" {:kind :flags :names ["read" "write" "exec"]}]]
                :result {:kind :flags :names ["read" "write" "exec"]}}
               (host/signature i "echo-perms")))
        (is (= {:params [["v" {:kind :tuple :types [:u32 :string]}]]
                :result {:kind :tuple :types [:u32 :string]}}
               (host/signature i "echo-tuple")))
        (doseq [v [#{} #{:read} #{:read :exec} #{:read :write :exec}]]
          (is (= v ((:echo-perms i) v)) (str "perms " (pr-str v))))
        (doseq [v [[0 ""] [7 "hi"] [4294967295 "日本語"]]]
          (is (= v ((:echo-tuple i) v)) (str "tuple " (pr-str v))))
        (is (vector? ((:echo-tuple i) [1 "a"]))
            "a tuple lifts as a vector, which only its type tells apart from a list"))

      (testing "L1 — nesting collapses, as 0012 says it must"
        ;; option<option<u32>> has three inhabitants and nil/value has two, so
        ;; `some(none)` cannot be expressed. Asserted rather than hidden.
        (is (nil? ((:echo-option-option-u32 i) nil)))
        (is (= 5 ((:echo-option-option-u32 i) 5)))))))

(deftest interfaces-are-exports-too
  ;; Every real WASI world puts its functions inside interfaces, which arrive
  ;; as nested component instances. The echo component has none, so nothing
  ;; here was exercised until this test existed — the failure `0014` predicted
  ;; of itself.
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping interface test")
    (let [c (build-component! "iface")]
      (try
        (with-open [e (host/engine)]
          (with-open [a (host/compile e (io/file c))]
            (with-open [i (host/instantiate a)]
              (testing "a function inside an interface is named the way WIT spells it"
                (is (= #{"top-level" "local:iface/math@1.2.3#add"}
                       (set (host/exports i)))))
              (testing "and is callable"
                (is (= 7 ((i "local:iface/math@1.2.3#add") 3 4))))
              (testing "its signature is reflected like any other"
                (is (= {:params [["a" :u32] ["b" :u32]] :result :u32}
                       (host/signature i "local:iface/math@1.2.3#add"))))
              (testing "no keyword alias — the version and the colon both bar it"
                (is (thrown? clojure.lang.ExceptionInfo
                             (get i (keyword "local:iface/math@1.2.3#add"))))
                (is (fn? (:top-level i)) "the plain label still gets one")))))
        (finally (.delete ^File c))))))

(deftest survives-a-component-it-did-not-author
  ;; Three times now a claim held for the artifact in this repo and failed for
  ;; the artifact the design is for (`0007`, `0013`, and the interface walk).
  ;; dev/resources/zoo.wit is the counterweight: resources, flags, tuples and
  ;; types nested several deep, with no guest written to match.
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping zoo test")
    (let [c (build-dummy! "zoo")]
      (try
        (with-open [e (host/engine)]
          (with-open [a (host/compile e (io/file c))]
            (with-open [i (host/instantiate a)]
              (testing "resource methods are exports too, under their annotated names"
                (is (= #{"plain"
                         "local:zoo/shapes@0.3.0#[constructor]counter"
                         "local:zoo/shapes@0.3.0#[method]counter.bump"
                         "local:zoo/shapes@0.3.0#[method]counter.peek"
                         "local:zoo/shapes@0.3.0#[static]counter.reset"
                         "local:zoo/shapes@0.3.0#take-tuple"
                         "local:zoo/shapes@0.3.0#take-flags"
                         "local:zoo/shapes@0.3.0#take-nested"}
                       (set (host/exports i)))))

              (testing "arbitrary nesting reflects exactly"
                (is (= {:params [["v" {:kind :list
                                       :element {:kind :record
                                                 :fields [["rows" {:kind :list
                                                                   :element {:kind :list :element :s32}}]
                                                          ["tag" {:kind :variant
                                                                  :cases [["leaf" :u32]
                                                                          ["branch" {:kind :list :element :u32}]
                                                                          ["nil" nil]]}]]}}]]
                        :result {:kind :option
                                 :ty {:kind :result
                                      :ok {:kind :record :fields [["x" :f64] ["y" :f64]]}
                                      :err :string}}}
                       (host/signature i "local:zoo/shapes@0.3.0#take-nested"))))

              (testing "every export now gets past marshalling and reaches the guest"
                ;; --dummy traps, so a wasmtime error rather than an
                ;; :unsupported-type one is the evidence that lowering worked.
                ;; When this repo learns a new type, this block is what notices.
                (doseq [[nm & args] [["take-flags" #{:read}]
                                     ["take-tuple" [1 "x"]]
                                     ["[static]counter.reset"]]]
                  (let [f (i (str "local:zoo/shapes@0.3.0#" nm))
                        e (is (thrown? clojure.lang.ExceptionInfo (apply f args)))]
                    (is (= :wasmtime (:cljwit/error (ex-data e)))
                        (str nm " should reach the guest and trap"))))))))
        (finally (.delete ^File c))))))

(deftest a-guest-that-really-implements-a-resource
  ;; `0016` named this artifact as its own first falsifier: everything it
  ;; decided rested on headers and on a --dummy component that traps when
  ;; called. This is the component that answers. Marshalling `own<T>` is not
  ;; implemented yet, so what is asserted here is that the guest builds, that
  ;; reflection reads it, and that the unsupported path names the right kind.
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping resource guest test")
    (let [c (build-component! "res")]
      (try
        (with-open [e (host/engine)]
          (with-open [a (host/compile e (io/file c))]
            (with-open [i (host/instantiate a)]
              (testing "the resource's methods, its constructor, and both shapes 0016 decides"
                (is (= #{"local:res/bag@0.1.0#[constructor]counter"
                         "local:res/bag@0.1.0#[method]counter.bump"
                         "local:res/bag@0.1.0#consume"
                         "local:res/bag@0.1.0#make-two"
                         "local:res/bag@0.1.0#boom"}
                       (set (host/exports i)))))

              (testing "own and borrow are distinct in the reflected type, not a runtime check"
                (is (= {:params [["start" :u32]] :result :own}
                       (host/signature i "local:res/bag@0.1.0#[constructor]counter")))
                (is (= {:params [["self" :borrow] ["by" :u32]] :result :u32}
                       (host/signature i "local:res/bag@0.1.0#[method]counter.bump")))
                (is (= {:params [["c" :own]] :result :u32}
                       (host/signature i "local:res/bag@0.1.0#consume"))
                    "an own in parameter position — ownership transfers")
                (is (= {:params [["start" :u32]]
                        :result {:kind :list :element :own}}
                       (host/signature i "local:res/bag@0.1.0#make-two"))
                    "handles nested in a list, which with-open has no shape for"))

              (let [make    (i "local:res/bag@0.1.0#[constructor]counter")
                    bump    (i "local:res/bag@0.1.0#[method]counter.bump")
                    consume (i "local:res/bag@0.1.0#consume")
                    two     (i "local:res/bag@0.1.0#make-two")]

                (testing "0016 A — a handle is opaque, and a borrow leaves it live"
                  (with-open [^java.lang.AutoCloseable c (make 10)]
                    (is (instance? java.lang.AutoCloseable c))
                    (is (not (map? c)))
                    (is (not (coll? c)))
                    (is (= 15 (bump c 5)))
                    (is (= 20 (bump c 5)))))

                (testing "0016 B — closing twice is a no-op, using a closed handle throws"
                  (let [^java.lang.AutoCloseable c (make 1)]
                    (.close c)
                    (is (nil? (.close c)))
                    (is (thrown? clojure.lang.ExceptionInfo (bump c 1)))))

                (testing "0016 D — handles the caller never destructured"
                  (let [cs (two 100)]
                    (is (= 2 (count cs)))
                    (is (= 101 (bump (first cs) 1)))
                    (is (= 102 (bump (second cs) 1)))
                    (run! (fn [^java.lang.AutoCloseable c] (.close c)) cs)))

                (testing "0016 C — lowering an own transfers, and with-open still exits cleanly"
                  (is (= 7 (with-open [^java.lang.AutoCloseable c (make 7)]
                             (consume c)))
                      "close after a transfer must not throw over the body's value")
                  (let [^java.lang.AutoCloseable c (make 3)]
                    (is (= 3 (consume c)))
                    (is (thrown? clojure.lang.ExceptionInfo (bump c 1))
                        "the handle is stale the moment ownership moves")))))))
        (finally (.delete ^File c))))))

(deftest an-instance-closes-handles-it-still-holds
  ;; `0016` D: a handle outliving its store holds a dangling context, which is
  ;; the one real use-after-free in this row. The instance owns the fix.
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping handle-lifetime test")
    (let [f (build-component! "res")]
      (try
        (with-open [e (host/engine)]
          (with-open [a (host/compile e (io/file f))]
            (let [leaked (with-open [i (host/instantiate a)]
                           ;; Never closed by the caller, and two more the
                           ;; caller never even named.
                           ((i "local:res/bag@0.1.0#make-two") 1)
                           ((i "local:res/bag@0.1.0#[constructor]counter") 2))]
              ;; Closing twice is a no-op (`0016` B), so the evidence that the
              ;; instance got there first is the handle's own state.
              (is (= "#cljwit/resource[closed]" (str leaked))
                  "the instance closed it on the way out"))))
        (finally (.delete ^File f))))))

(deftest teardown-completes-even-when-a-handle-refuses-to-close
  ;; A trapped instance cannot be entered again, so every [resource-drop] then
  ;; fails. If that escapes `Instance/close`, `store_delete` and `arena.close`
  ;; never run and the retry is a silent no-op, because `closed` is already
  ;; set — the same shape as the bug the close ordering was written to fix, one
  ;; level down. Found by an adversarial review of `0017`, not by this suite.
  ;;
  ;; **This test does not distinguish the fix.** Fault injection: reverting to
  ;; the plain `run!` leaves it green, because both versions throw and both
  ;; leave `closed` set. What differs is whether `store_delete` and
  ;; `arena.close` ran, and that is not observable from Clojure without adding
  ;; API for a test. What it does pin is the contract — close reports the drop
  ;; rather than swallowing it, and the instance ends closed rather than stuck
  ;; half-torn-down.
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping teardown test")
    (let [f (build-component! "res")]
      (try
        (with-open [e (host/engine)]
          (with-open [a (host/compile e (io/file f))]
            (let [i (host/instantiate a)]
              ((i "local:res/bag@0.1.0#make-two") 1)
              (is (thrown? clojure.lang.ExceptionInfo ((i "local:res/bag@0.1.0#boom")))
                  "the guest traps, which poisons the instance")
              (let [t (is (thrown? clojure.lang.ExceptionInfo (.close ^java.lang.AutoCloseable i)))]
                (is (re-find #"cannot enter component instance" (ex-message t))
                    "close reports the drop that failed rather than swallowing it"))
              (is (nil? (.close ^java.lang.AutoCloseable i))
                  "and the instance is closed afterwards, not stuck half-torn-down"))))
        (finally (.delete ^File f))))))

(deftest host-imports
  ;; `0017`. The mechanism is `bb spike-import`; this is the API over it.
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping host-import test")
    (let [f (build-component! "imp")]
      (try
        (with-open [e (host/engine)]
          (with-open [a (host/compile e (io/file f))]

            (testing "a component with an unsatisfied import names it, and both halves"
              (let [ex (is (thrown? clojure.lang.ExceptionInfo (host/instantiate a)))]
                (is (re-find #"local:imp/host@0\.1\.0" (ex-message ex)))
                (is (re-find #"twice" (ex-message ex))
                    "wasmtime names the function too — 0017 B's first draft said it did not")))

            (testing "0017 A — an import is an ordinary Clojure function"
              (with-open [i (host/instantiate
                             a {:imports {"local:imp/host@0.1.0#twice" (fn [v] (* 2 v))}})]
                (is (= 41 ((i "run") 20)) "the guest called back and added one")
                (is (= 3 ((i "run") 1)))))

            (testing "0017 B — an import that is not needed is a typo, not a courtesy"
              (let [ex (is (thrown? clojure.lang.ExceptionInfo
                                    (host/instantiate
                                     a {:imports {"local:imp/host@0.1.0#twice" identity
                                                  "local:imp/host@0.1.0#thrice" identity}})))]
                (is (= :no-such-import (:cljwit/error (ex-data ex))))
                (is (some #{"local:imp/host@0.1.0#thrice"} (:cljwit/extra (ex-data ex))))))

            (testing "0017 D — a Clojure exception surfaces, and the instance is dead after it"
              (with-open [i (host/instantiate
                             a {:imports {"local:imp/host@0.1.0#twice"
                                          (fn [_] (throw (ex-info "clojure blew up" {})))}})]
                (let [ex (is (thrown? clojure.lang.ExceptionInfo ((i "run") 1)))]
                  (is (re-find #"clojure blew up" (ex-message ex))
                      "the host's own message reaches the caller"))
                (let [ex (is (thrown? clojure.lang.ExceptionInfo ((i "run") 1)))]
                  (is (= :instance-dead (:cljwit/error (ex-data ex)))
                      "and the next call says the instance is dead rather than reporting a trap"))))))
        (finally (.delete ^File f))))))

(deftest host-imports-returning-a-string
  ;; `0017` C: the two directions are not mirror images. wasmtime takes
  ;; ownership of what an import callback writes and frees it, so a string
  ;; result must come from `malloc`, not from an `Arena`.
  ;;
  ;; Measured, and the shape of the measurement is the point: with an Arena
  ;; pointer **one call succeeds** and 2000 abort the process (rc=134). A test
  ;; that called once would have licensed the corruption, which is why this one
  ;; loops.
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping string-import test")
    (let [f (build-component! "imps")]
      (try
        (with-open [e (host/engine)]
          (with-open [a (host/compile e (io/file f))]
            (with-open [i (host/instantiate
                           a {:imports {"local:imps/host@0.1.0#mk"
                                        (fn [n] (apply str (repeat n "ab")))
                                        "local:imps/host@0.1.0#mklist"
                                        (fn [n] (vec (range n)))
                                        "local:imps/host@0.1.0#mkopt"
                                        (fn [n] (when (pos? n) (* 10 n)))}})]
              (testing "a string crosses in both directions within one call"
                (is (= "ababab" ((i "run") 3)))
                (is (= "" ((i "run") 0)))
                (is (= "ab" ((i "run") 1))))
              (testing "and keeps working, which is what an Arena pointer would not"
                (is (every? (fn [n] (= (apply str (repeat n "ab")) ((i "run") n)))
                            (map (fn [k] (inc (mod k 9))) (range 500)))
                    "500 calls, each result freed by wasmtime"))
              (testing "an aggregate result goes through the same lowering"
                ;; The allocator is a parameter now, so list, tuple, record,
                ;; enum and flags all reach the import direction unchanged.
                (is (= [0 1 2 3 4] ((i "run-list") 5)))
                (is (= [] ((i "run-list") 0)))
                (is (every? (fn [n] (= (vec (range n)) ((i "run-list") n)))
                            (map (fn [k] (mod k 9)) (range 500)))
                    "and the element buffer is freed by wasmtime too"))
              (testing "a boxed payload — 0017 C said this needed val_new; it does not"
                ;; option, result and variant store a pointer to a val. Once the
                ;; byte allocator is malloc, that val is already on the heap
                ;; wasmtime frees, so wasmtime_component_val_new would allocate
                ;; a second one and leak the first.
                (is (= 30 ((i "run-opt") 3)))
                (is (nil? ((i "run-opt") 0)))
                (is (every? (fn [n] (= (when (pos? n) (* 10 n)) ((i "run-opt") n)))
                            (map (fn [k] (mod k 4)) (range 2000)))
                    "2000 calls, the threshold that caught the Arena pointer")))))
        (finally (.delete ^File f))))))

(deftest wasi
  ;; `0017` E. `add_wasip2` on its own is not enough: without a WASI context on
  ;; the store, the first WASI call panics inside the C API and **aborts the
  ;; process** (rc=134) rather than returning an error. That failure cannot be
  ;; asserted from inside a JVM test — it takes the JVM with it — so what is
  ;; pinned here is that the wired-up path works and the unwired one is refused
  ;; at instantiate, where it is still an exception.
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping wasi test")
    (let [f (build-component! "rnd")]
      (try
        (with-open [e (host/engine)]
          (with-open [a (host/compile e (io/file f))]
            (testing "without :wasi the import is simply missing, and is named"
              (let [ex (is (thrown? clojure.lang.ExceptionInfo (host/instantiate a)))]
                (is (re-find #"wasi:random/random" (ex-message ex)))))
            (testing "with :wasi {} — deny-by-default — WASI is satisfied"
              (with-open [i (host/instantiate a {:wasi {}})]
                (let [rolls (repeatedly 20 (i "roll"))]
                  (is (every? integer? rolls))
                  (is (< 15 (count (distinct rolls)))
                      "wasi:random returns random numbers, so they differ"))))
            (testing "a capability is inherited only when named"
              (with-open [i (host/instantiate a {:wasi {:inherit-stdout true}})]
                (is (integer? ((i "roll")))
                    "naming one capability does not break the rest")))))
        (finally (.delete ^File f))))))

(deftest wasi-arguments
  ;; `0017` E's remaining surface. `:args` and `:env` are the two that need an
  ;; array of C strings rather than a single call, so they are the ones where
  ;; the wiring can be wrong without the boolean capabilities noticing.
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping wasi-args test")
    (let [f (build-component! "args")]
      (try
        (with-open [e (host/engine)]
          (with-open [a (host/compile e (io/file f))]
            (testing "the guest sees exactly the arguments it was given"
              (doseq [as [[] ["one"] ["a" "b" "c"] ["x" "y" "z" "w" "v"]]]
                (with-open [i (host/instantiate a {:wasi {:args as}})]
                  (is (= (count as) ((i "argc"))) (pr-str as)))))
            (testing "and none when none are named — deny-by-default"
              (with-open [i (host/instantiate a {:wasi {}})]
                (is (= 0 ((i "argc")))
                    "no :args means no arguments, not the host's")))))
        (finally (.delete ^File f))))))

(deftest host-defined-resources
  ;; `0018`. The mechanism is `bb spike-hres`; this is the API over it.
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping host-resource test")
    (let [f (build-component! "hres")]
      (try
        (with-open [e (host/engine)]
          (with-open [a (host/compile e (io/file f))]

            (testing "0018 A/B — a resource the host defines, minted and read back"
              (let [dropped (atom [])]
                (with-open [i (host/instantiate
                               a {:resources {"local:hres/host@0.1.0#token"
                                              {:drop (fn [v] (swap! dropped conj v))}}
                                  :imports {"local:hres/host@0.1.0#mint"
                                            (fn [v] {:n v})
                                            "local:hres/host@0.1.0#peek"
                                            (fn [t] (:n t))
                                            "local:hres/host@0.1.0#eat" (fn [t] (:n @t))}})]
                  (is (= 42 ((i "run") 42))
                      "the guest minted, borrowed and dropped a host resource")
                  (is (= [{:n 42}] @dropped)
                      "and :drop saw the value the import returned, not a rep"))))

            (testing "a borrow hands over the value, and identity is the rep"
              ;; Two mints of the same object are two resources, so two drops.
              (let [dropped (atom [])
                    same {:n 1}]
                (with-open [i (host/instantiate
                               a {:resources {"local:hres/host@0.1.0#token"
                                              {:drop (fn [v] (swap! dropped conj v))}}
                                  :imports {"local:hres/host@0.1.0#mint" (fn [_] same)
                                            "local:hres/host@0.1.0#peek" (fn [t] (:n t))
                                            "local:hres/host@0.1.0#eat" (fn [t] (:n @t))}})]
                  ((i "run") 7)
                  ((i "run") 7)
                  (is (= [same same] @dropped)
                      ":drop is per handle, not per value"))))

            (testing "0018 D — close drops what the guest still holds"
              ;; store_delete runs no host destructors, so clearing the table
              ;; would drop every surviving file on the floor.
              (let [dropped (atom [])
                    i (host/instantiate
                       a {:resources {"local:hres/host@0.1.0#token"
                                      {:drop (fn [v] (swap! dropped conj v))}}
                          :imports {"local:hres/host@0.1.0#mint" (fn [v] {:kept v})
                                    "local:hres/host@0.1.0#peek" (fn [t] (:kept t))
                                    "local:hres/host@0.1.0#eat" (fn [t] (:kept @t))}})]
                (is (= 9 ((i "leak") 9)) "the guest mints and keeps the handle")
                (is (= [] @dropped) "nothing dropped while it is held")
                (.close ^java.lang.AutoCloseable i)
                (is (= [{:kept 9}] @dropped)
                    "close dropped it, rather than clearing the table")))

            (testing "0018 E — a :drop that throws is readable, not silent"
              (let [i (host/instantiate
                       a {:resources {"local:hres/host@0.1.0#token"
                                      {:drop (fn [_] (throw (ex-info "close! failed" {})))}}
                          :imports {"local:hres/host@0.1.0#mint" (fn [v] {:kept v})
                                    "local:hres/host@0.1.0#peek" (fn [t] (:kept t))
                                    "local:hres/host@0.1.0#eat" (fn [t] (:kept @t))}})]
                (is (= 5 ((i "run") 5))
                    "the guest's drop still succeeds — 0017 D would have killed the instance")
                (is (= 1 (count (host/drop-failures i)))
                    "and the failure is readable")
                (is (empty? (host/drop-failures i))
                    "reading them clears them, so close has nothing to rethrow")
                (.close ^java.lang.AutoCloseable i)))

            (testing "0018 C — an own parameter hands the caller the obligation"
              (let [dropped (atom [])
                    got (atom nil)]
                (with-open [i (host/instantiate
                               a {:resources {"local:hres/host@0.1.0#token"
                                              {:drop (fn [v] (swap! dropped conj v))}}
                                  :imports {"local:hres/host@0.1.0#mint" (fn [v] {:n v})
                                            "local:hres/host@0.1.0#peek" (fn [t] (:n t))
                                            "local:hres/host@0.1.0#eat"
                                            (fn [t]
                                              (reset! got t)
                                              (is (instance? java.lang.AutoCloseable t)
                                                  "an own arrives closeable, not bare")
                                              (:n @t))}})]
                  (is (= 4 ((i "give-back") 4)))
                  (is (= [] @dropped)
                      "the guest will never drop it, so nothing has yet")
                  (.close ^java.lang.AutoCloseable @got)
                  (is (= [{:n 4}] @dropped) "closing it runs :drop"))
                (is (= [{:n 4}] @dropped)
                    "and the instance's own close does not drop it again")))

            (testing "a resource the component does not declare is a typo"
              (let [ex (is (thrown? clojure.lang.ExceptionInfo
                                    (host/instantiate
                                     a {:resources {"local:hres/host@0.1.0#nope" {:drop identity}}
                                        :imports {"local:hres/host@0.1.0#mint" (fn [_] nil)
                                                  "local:hres/host@0.1.0#peek" (fn [_] 0)}})))]
                (is (= :no-such-resource (:cljwit/error (ex-data ex))))))))
        (finally (.delete ^File f))))))

(deftest nested-resource-import-is-refused
  ;; `import-stub` routes a *whole* resource parameter or result through the
  ;; rep table; one nested in a container would fall through to the
  ;; guest-handle path and NPE at call time. So `list<own<token>>` in an
  ;; import's signature must be refused at instantiate, with the kind named.
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping nested-resource test")
    (let [f (build-component! "nres")]
      (try
        (with-open [e (host/engine)]
          (with-open [a (host/compile e (io/file f))]
            (let [ex (is (thrown? clojure.lang.ExceptionInfo
                                  (host/instantiate
                                   a {:imports {"local:nres/host@0.1.0#poof"
                                                (fn [ts] (count ts))}})))]
              (is (= :unsupported-type (:cljwit/error (ex-data ex))))
              (is (= [:nested-resource] (:cljwit/kinds (ex-data ex)))))))
        (finally (.delete ^File f))))))

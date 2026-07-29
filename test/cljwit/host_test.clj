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
     (run! "wasm-tools" "component" "embed" (str "dev/resources/" base ".wit") (str core) "-o" (str emb))
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

              (testing "only the resource handles are still unmarshallable"
                (doseq [[nm kind] [["[constructor]counter" :own]
                                   ["[method]counter.bump" :borrow]]]
                  (let [f (i (str "local:zoo/shapes@0.3.0#" nm))
                        e (is (thrown? clojure.lang.ExceptionInfo (f nil)))]
                    (is (= :unsupported-type (:cljwit/error (ex-data e))) nm)
                    (is (some #{kind} (:cljwit/kinds (ex-data e)))
                        (str nm " should name " kind)))))

              (testing "flags and tuple now marshal, and reach the guest"
                ;; --dummy traps, so getting a wasmtime error rather than an
                ;; :unsupported-type one is the evidence that lowering worked.
                (doseq [[nm arg] [["take-flags" #{:read}]
                                  ["take-tuple" [1 "x"]]]]
                  (let [f (i (str "local:zoo/shapes@0.3.0#" nm))
                        e (is (thrown? clojure.lang.ExceptionInfo (f arg)))]
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
                         "local:res/bag@0.1.0#make-two"}
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

              (testing "until 0016 is implemented, every one of them says so"
                (doseq [nm ["[constructor]counter" "consume" "make-two"]]
                  (let [f (i (str "local:res/bag@0.1.0#" nm))
                        e (is (thrown? clojure.lang.ExceptionInfo (f 1)))]
                    (is (= :unsupported-type (:cljwit/error (ex-data e))) nm)
                    (is (some #{:own} (:cljwit/kinds (ex-data e))) nm)))))))
        (finally (.delete ^File c))))))

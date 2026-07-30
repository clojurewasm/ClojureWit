(ns cljwit.gen-test
  "`0020`'s contract. The planning and emission rules are pure and tested
   without a component; one end-to-end test writes a namespace from the echo
   fixture, loads it, and calls through it."
  (:require [cljwit.host :as host]
            [cljwit.host.gen :as gen]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io File]))

(set! *warn-on-reflection* true)

(def ^:private lib (System/getenv "CLJWIT_WASMTIME_LIB"))

(def ^:private u32-sig {:params [["v" :u32]] :result :u32})

(deftest names-take-the-last-segment
  (let [vn #'gen/var-name]
    (is (= 'plain (vn "plain")))
    (is (= 'take-flags (vn "local:zoo/shapes@0.3.0#take-flags")))
    (is (= 'counter (vn "local:zoo/shapes@0.3.0#[constructor]counter")))
    (is (= 'counter-bump (vn "local:zoo/shapes@0.3.0#[method]counter.bump")))
    (is (= 'counter-reset (vn "local:zoo/shapes@0.3.0#[static]counter.reset")))))

(deftest collisions-fail-with-two-exits
  (let [two-versions {"acme:api/svc@1.0.0#run" u32-sig
                      "acme:api/svc@2.0.0#run" u32-sig}]
    (testing "the two-version world collides pairwise, and says so"
      (let [ex (is (thrown? clojure.lang.ExceptionInfo
                            (gen/source-for two-versions {:ns 'acme.api})))]
        (is (= :name-collision (:cljwit/error (ex-data ex))))
        (is (= {'run ["acme:api/svc@1.0.0#run" "acme:api/svc@2.0.0#run"]}
               (:cljwit/collisions (ex-data ex))))))
    (testing ":interface is the O(interfaces) exit"
      (let [src (gen/source-for two-versions {:ns 'acme.api.v1
                                              :interface "acme:api/svc@1.0.0"})]
        (is (str/includes? src "(defn run"))
        (is (str/includes? src "acme:api/svc@1.0.0#run"))
        (is (not (str/includes? src "@2.0.0")))))
    (testing ":rename is the leaf exit"
      (let [src (gen/source-for two-versions
                                {:ns 'acme.api
                                 :rename {"acme:api/svc@2.0.0#run" 'run-v2}})]
        (is (str/includes? src "(defn run\n"))
        (is (str/includes? src "(defn run-v2\n"))))
    (testing "a :rename for an export that does not exist is a typo"
      (is (= :no-such-export
             (:cljwit/error (ex-data (is (thrown? clojure.lang.ExceptionInfo
                                                  (gen/source-for two-versions
                                                                  {:ns 'acme.api
                                                                   :rename {"acme:api/svc@3.0.0#run" 'x}}))))))))
    (testing "the method/plain-func collision 0014 B predicted is caught too"
      (is (thrown? clojure.lang.ExceptionInfo
                   (gen/source-for {"t:c/i@1.0.0#[method]counter.bump" u32-sig
                                    "t:c/i@1.0.0#counter-bump" u32-sig}
                                   {:ns 't.c}))))))

(deftest clojure-core-shadows-are-excluded
  ;; count, map, get are all legal WIT identifiers; without the exclude the
  ;; file loads with a warning per consumer and clj-kondo exits 2.
  (let [src (gen/source-for {"count" u32-sig "helper" u32-sig} {:ns 'acme.stats})]
    (is (str/includes? src "(:refer-clojure :exclude [count])"))
    (testing "and the source is loadable as written"
      (is (some? (load-string src)))
      (is (= [1 2] ((resolve 'acme.stats/count) (fn [_] identity) [1 2]))
          "the generated body is ((i \"count\") v) — a fake instance shows the shape")
      (remove-ns 'acme.stats))))

(deftest docstrings-render-structural-wit
  (let [wt #'gen/wit-type]
    (is (= "list<u8>" (wt {:kind :list :element :u8})))
    (is (= "option<result<u32, string>>"
           (wt {:kind :option :ty {:kind :result :ok :u32 :err :string}})))
    (is (= "result" (wt {:kind :result :ok nil :err nil})))
    (is (= "result<_, string>" (wt {:kind :result :ok nil :err :string})))
    (is (= "flags{read, write, exec}" (wt {:kind :flags :names ["read" "write" "exec"]})))
    (is (= "variant{leaf(u32), nil}" (wt {:kind :variant :cases [["leaf" :u32] ["nil" nil]]})))
    (is (= "record{x: f64, y: f64}" (wt {:kind :record :fields [["x" :f64] ["y" :f64]]})))))

(deftest the-hash-is-of-the-api
  (testing "same signatures, same hash — regardless of option noise"
    (is (= (#'gen/api-hash {"a" u32-sig}) (#'gen/api-hash {"a" u32-sig}))))
  (testing "a signature change is a hash change"
    (is (not= (#'gen/api-hash {"a" u32-sig})
              (#'gen/api-hash {"a" {:params [["v" :u64]] :result :u32}})))))

(defn- build-echo! []
  (let [core (File/createTempFile "cljwit-gen" ".core.wasm")
        emb  (File/createTempFile "cljwit-gen" ".embed.wasm")
        out  (File/createTempFile "cljwit-gen" ".component.wasm")
        run! (fn [& args]
               (let [{:keys [exit err]} (apply shell/sh args)]
                 (when-not (zero? exit)
                   (throw (ex-info (str "failed: " (pr-str args)) {:err err})))))]
    (run! "wasm-tools" "parse" "dev/resources/echo.wat" "-o" (str core))
    (run! "wasm-tools" "component" "embed" "dev/resources/echo.wit" (str core) "-o" (str emb))
    (run! "wasm-tools" "component" "new" (str emb) "-o" (str out))
    out))

(deftest a-generated-namespace-works-end-to-end
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping gen end-to-end test")
    (let [c   (build-echo!)
          dir (str (File/createTempFile "cljwit-gen-ns" ""))
          _   (.delete (io/file dir))]
      (try
        (let [path (gen/write-ns! (str c) {:ns 'gen.echo :dir dir})]
          (testing "the file loads with no .wasm and no instance in sight"
            (is (.exists (io/file path)))
            (load-file path))
          (with-open [e (host/engine)
                      a (host/compile e (io/file c))
                      i (host/instantiate a)]
            (testing "vars call through the instance-first convention"
              (is (= "hi" ((resolve 'gen.echo/echo-string) i "hi")))
              (is (= 41 ((resolve 'gen.echo/echo-s32) i 41))))
            (testing "result stays tagged, and unwrap is the sugar (0020 D)"
              (is (= [:ok 7] ((resolve 'gen.echo/echo-result) i [:ok 7])))
              (is (= 7 (host/unwrap ((resolve 'gen.echo/echo-result) i [:ok 7]))))
              (let [ex (is (thrown? clojure.lang.ExceptionInfo
                                    (host/unwrap ((resolve 'gen.echo/echo-result)
                                                  i [:err "nope"]))))]
                (is (= "nope" (:wit/error (ex-data ex))))))
            (testing "regeneration is byte-identical — the hash is of the API"
              (let [before (slurp path)]
                (gen/write-ns! (str c) {:ns 'gen.echo :dir dir :engine e})
                (is (= before (slurp path)))))))
        (finally
          (.delete ^File c)
          (remove-ns 'gen.echo))))))

(deftest unwrap-is-lenient-outside-results
  (is (= 3 (host/unwrap [:ok 3])))
  (is (nil? (host/unwrap [:ok nil])) "an ok with no payload is nil, as 0012 maps it")
  (is (= "s" (host/unwrap "s")))
  (is (nil? (host/unwrap nil)))
  (is (= [:circle 1.0] (host/unwrap [:circle 1.0])) "a variant passes through")
  (is (= [1 2 3] (host/unwrap [1 2 3])) "so does an ordinary vector"))

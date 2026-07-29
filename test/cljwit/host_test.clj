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

(defn- build-component! []
  (let [core (File/createTempFile "cljwit-host" ".core.wasm")
        emb  (File/createTempFile "cljwit-host" ".embed.wasm")
        out  (File/createTempFile "cljwit-host" ".component.wasm")
        run! (fn [& args]
               (let [{:keys [exit err]} (apply shell/sh args)]
                 (when-not (zero? exit)
                   (throw (ex-info (str "failed: " (pr-str args)) {:err err})))))]
    (run! "wasm-tools" "parse" "dev/resources/echo.wat" "-o" (str core))
    (run! "wasm-tools" "component" "embed" "dev/resources/echo.wit" (str core) "-o" (str emb))
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
                 "echo-list-u32" "echo-pair"}
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

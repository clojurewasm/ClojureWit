(ns cljwit.project-test
  "`0021`'s contract. The file-shape rules run without a component; the
   end-to-end test builds a temp project around the echo fixture and drives
   every verdict `status` can return."
  (:require [cljwit.project :as project]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io File]))

(set! *warn-on-reflection* true)

(def ^:private lib (System/getenv "CLJWIT_WASMTIME_LIB"))

(defn- tmp-project!
  "A directory holding cljwit.edn with `cfg`. Returns its File."
  [cfg]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "cljwit-proj-" (System/nanoTime)))]
    (.mkdirs d)
    (spit (io/file d "cljwit.edn") (pr-str cfg))
    d))

(defn- opts [^File d] {:file (str (io/file d "cljwit.edn"))})

(deftest the-file-shape-is-validated-with-provenance
  (testing "a missing file says where it looked and what to pass"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (project/status {:file "/nonexistent/cljwit.edn"})))]
      (is (= :no-project-file (:cljwit/error (ex-data ex))))))
  (testing "an unknown top-level key is an error naming it"
    (let [d (tmp-project! {:components {} :watcher true})]
      (is (= :bad-project
             (:cljwit/error (ex-data (is (thrown? clojure.lang.ExceptionInfo
                                                  (project/status (opts d))))))))))
  (testing "entry failures are collected, each naming its namespace"
    (let [d (tmp-project! {:components {'acme.a {:wasm "a.wasm" :renmae {}}
                                        'acme.b {:wasm 42}}})
          ex (is (thrown? clojure.lang.ExceptionInfo (project/status (opts d))))]
      (is (= :bad-project (:cljwit/error (ex-data ex))))
      (is (str/includes? (ex-message ex) "acme.a"))
      (is (str/includes? (ex-message ex) ":renmae"))
      (is (str/includes? (ex-message ex) "acme.b"))))
  (testing "two namespaces that munge to one file are refused"
    ;; x.wasm not existing is a failure too; both are collected before any
    ;; engine exists, so this runs without libwasmtime.
    (let [d  (tmp-project! {:components {'acme.foo-bar {:wasm "x.wasm"}
                                         'acme.foo_bar {:wasm "x.wasm"}}})
          ex (is (thrown? clojure.lang.ExceptionInfo (project/sync! (opts d))))]
      (is (str/includes? (ex-message ex) "share a file"))))
  (testing "a missing wasm is named before anything is written"
    (let [d (tmp-project! {:components {'acme.a {:wasm "nowhere.wasm"}}})
          ex (is (thrown? clojure.lang.ExceptionInfo (project/sync! (opts d))))]
      (is (str/includes? (ex-message ex) "nowhere.wasm"))))
  (testing "a non-map :components gets the written message, not an nth trace"
    ;; The vector is the rejected first-draft shape — the likeliest mistake.
    (let [d (tmp-project! {:components [{:ns 'acme.a :wasm "a.wasm"}]})
          ex (is (thrown? clojure.lang.ExceptionInfo (project/status (opts d))))]
      (is (= :bad-project (:cljwit/error (ex-data ex))))))
  (testing "an absolute path is refused with its entry named"
    (let [d (tmp-project! {:components {'acme.a {:wasm "/abs/a.wasm"}}})
          ex (is (thrown? clojure.lang.ExceptionInfo (project/status (opts d))))]
      (is (str/includes? (ex-message ex) "acme.a"))
      (is (str/includes? (ex-message ex) "relative to cljwit.edn")))))

(defn- build-echo-into! [^File d]
  (let [core (File/createTempFile "cljwit-proj" ".core.wasm")
        emb  (File/createTempFile "cljwit-proj" ".embed.wasm")
        out  (io/file d "echo.component.wasm")
        run! (fn [& args]
               (let [{:keys [exit err]} (apply shell/sh args)]
                 (when-not (zero? exit)
                   (throw (ex-info (str "failed: " (pr-str args)) {:err err})))))]
    (run! "wasm-tools" "parse" "dev/resources/echo.wat" "-o" (str core))
    (run! "wasm-tools" "component" "embed" "dev/resources/echo.wit" (str core) "-o" (str emb))
    (run! "wasm-tools" "component" "new" (str emb) "-o" (str out))
    out))

(deftest sync-status-check-end-to-end
  (if-not lib
    (println "CLJWIT_WASMTIME_LIB unset — skipping project end-to-end test")
    (let [d (tmp-project! {:components {'gen.proj {:wasm "echo.component.wasm"
                                                   :dir "src/gen"}}})
          o (opts d)]
      (build-echo-into! d)

      (testing "sync! writes on the first run, and is idempotent"
        (is (= 1 (count (:wrote (project/sync! o)))))
        (let [r (project/sync! o)]
          (is (empty? (:wrote r)))
          (is (= 1 (count (:unchanged r))))))

      (let [f (io/file d "src/gen/gen/proj.clj")]
        (is (.exists f) "the file landed under the entry's :dir")
        (is (str/includes? (slurp f) ";; source: echo.component.wasm")
            "the committed header carries the edn-relative path, not a machine's")
        (is (str/includes? (slurp f) "(cljwit.project/sync!)")
            "a sync!-managed file's regenerate line points here")

        (testing "everything in sync — check passes"
          (is (= 1 (count (:ok (project/check o))))))

        (testing "a hand edit is :modified — the API hash still matches"
          (let [pristine (slurp f)]
            (spit f (str pristine "\n;; tweak\n"))
            (is (= [(str (.getCanonicalFile f))] (:modified (project/status o))))
            (let [ex (is (thrown? clojure.lang.ExceptionInfo (project/check o)))]
              (is (str/includes? (ex-message ex) "modified:")
                  "the drift is in the message, where -X callers can see it"))
            (spit f pristine)))

        (testing "a differing exports-hash is :stale — the component changed"
          (let [pristine (slurp f)]
            (spit f (str/replace pristine #";; exports-hash: sha256:[0-9a-f]+"
                                 ";; exports-hash: sha256:0000"))
            (is (= [(str (.getCanonicalFile f))] (:stale (project/status o))))
            (spit f pristine)))

        (testing "deleting the hash line altogether is :modified — an edit, not an API change"
          (let [pristine (slurp f)]
            (spit f (str/replace pristine #";; exports-hash: sha256:[0-9a-f]+\n" ""))
            (is (= [(str (.getCanonicalFile f))] (:modified (project/status o))))
            (spit f pristine)))

        (testing "a deleted file is :missing"
          (let [pristine (slurp f)]
            (.delete f)
            (is (= [(str (.getCanonicalFile f))] (:missing (project/status o))))
            (io/make-parents f)
            (spit f pristine)))

        (testing "a generated file no entry claims is an :orphan"
          (let [stray (io/file d "src/gen/gen/stray.clj")]
            (spit stray ";; Generated by cljwit.host.gen (doc/design/0020). Do not edit by hand.\n(ns gen.stray)\n")
            (is (= [(str (.getCanonicalFile stray))] (:orphans (project/status o))))
            (.delete stray)))

        (testing "clean again"
          (is (= {:stale [] :modified [] :missing [] :orphans []}
                 (dissoc (project/status o) :ok))))))))

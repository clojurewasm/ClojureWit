(ns cljwit.corpus-test
  "The differential oracle (doc/design/0022 B, harness shape in 0023):
   every corpus entry runs through real `clojure` in this JVM — a fresh,
   discarded namespace per entry, the isolation rule — and through
   analyze -> emit -> wasm-tools -> both engines in both modes, and the
   outcomes must agree: structurally (via edn) for values, through
   corpus/trap_table.edn for errors.

   The wasmtime lane skips with a printed line when the CLI is not on
   PATH — the same trade the FFM tests make with CLJWIT_WASMTIME_LIB.
   CI runs inside the flake, where nothing skips."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cljwit.analyze :as analyze]
            [cljwit.emit :as emit]))

(set! *warn-on-reflection* true)

(def ^:private entries (edn/read-string (slurp "corpus/s3.edn")))
(def ^:private trap-table (edn/read-string (slurp "corpus/trap_table.edn")))

(def ^:private target-dir "target/corpus")

(defn- sh [& args]
  (let [p   (apply process/start {:out :pipe :err :pipe} args)
        out (future (slurp (process/stdout p)))
        err (future (slurp (process/stderr p)))]
    {:exit @(process/exit-ref p) :out @out :err @err}))

(def ^:private wasmtime-on-path?
  (some (fn [dir]
          (let [f (io/file dir "wasmtime")]
            (and (.isFile f) (.canExecute f))))
        (str/split (or (System/getenv "PATH") "")
                   (re-pattern (java.util.regex.Pattern/quote
                                java.io.File/pathSeparator)))))

;; --- the three lanes --------------------------------------------------------

(defn- oracle-eval
  "Evaluates one entry's forms in order in a fresh namespace.
   Returns {:value v} or {:throw class-name :message m}."
  [forms]
  (let [ns-sym (gensym "cljwit.corpus.oracle")]
    (binding [*ns* (create-ns ns-sym)]
      (try
        (refer-clojure)
        {:value (reduce (fn [_ f] (eval f)) nil forms)}
        (catch Throwable t
          ;; `eval` wraps a throw during a top-level form (a def init, say)
          ;; in Compiler$CompilerException; what the program observably
          ;; throws under `clojure` is the cause — clojure.main's own
          ;; ex-triage reports the cause's class — so the oracle records it.
          (let [t (if (and (instance? clojure.lang.Compiler$CompilerException t)
                           (.getCause t))
                    (.getCause t)
                    t)]
            {:throw (.getName (class t)) :message (.getMessage t)}))
        (finally (remove-ns ns-sym))))))

(defn- assemble-at!
  "WAT text -> .wasm at the given basename (dir + name, no extension),
   via wasm-tools. Returns the .wasm path."
  [^java.io.File dir base wat-text]
  (.mkdirs dir)
  (let [wat  (io/file dir (str base ".wat"))
        wasm (io/file dir (str base ".wasm"))]
    (spit wat wat-text)
    (let [{:keys [exit err]} (sh "wasm-tools" "parse" (str wat) "-o" (str wasm))]
      (when-not (zero? exit)
        (throw (ex-info (str "wasm-tools parse failed for " base) {:err err}))))
    (str wasm)))

(defn- assemble!
  "WAT text -> .wasm on disk, via wasm-tools. Returns the .wasm path."
  [id mode wat-text]
  (assemble-at! (io/file target-dir) (str id "." (name mode)) wat-text))

(defn- wasmtime-lane [wasm]
  (let [{:keys [exit out err]} (sh "wasmtime" "run" "--invoke" "entry" wasm)]
    (if (zero? exit)
      {:value (str/trim (last (str/split-lines out)))}
      {:trap err})))

(defn- node-outcome
  "Parses the corpus vocabulary (result/trap/exn) a node runner prints."
  [{:keys [exit out err]}]
  (let [line (str/trim out)]
    (cond
      (not (zero? exit))
      (throw (ex-info "the node corpus lane itself failed" {:err err}))
      (str/starts-with? line "result ") {:value (subs line 7)}
      (str/starts-with? line "trap ")   {:trap (subs line 5)}
      ;; An uncaught Clojure throw, class-precise on this lane (0027).
      (str/starts-with? line "exn ")    {:exn (parse-long (subs line 4))}
      :else (throw (ex-info (str "unrecognized corpus lane output: " (pr-str line)) {})))))

(defn- v8-lane [wasm]
  (node-outcome (sh "node" "corpus/run.mjs" wasm)))

(defn- linked-lane
  "The linked dev-mode session (0028): one runtime instance, one module
   per form, the var ledger threaded by corpus/session.mjs."
  [id asts]
  (let [{:keys [runtime forms]} (emit/emit-linked-session asts)
        dir   (io/file target-dir "linked" id)
        rt    (assemble-at! dir "rt" runtime)
        paths (into [rt]
                    (map-indexed (fn [i wat] (assemble-at! dir (str "form" i) wat)) forms))]
    (node-outcome (apply sh "node" "corpus/session.mjs" paths))))

;; --- comparison -------------------------------------------------------------

(defn- trap-matches?
  "True when a trap-table row licenses comparing this oracle throw equal
   to this engine trap (0022 B.5). A row without a pattern for this
   engine licenses nothing on it."
  [engine {:keys [message] :as oracle} trap]
  (boolean (some (fn [row]
                   (and (= (:class row) (:throw oracle))
                        (str/includes? (or message "") (:jvm-message row))
                        (when-let [pat (get row engine)]
                          (str/includes? trap pat))))
                 trap-table)))

(defn- exn-matches?
  "The class-precise half (0027): a lane that read the thrown payload's
   class enum compares through a row's :exn-class."
  [{:keys [message] :as oracle} exn-class]
  (boolean (some (fn [row]
                   (and (= (:class row) (:throw oracle))
                        (str/includes? (or message "") (:jvm-message row))
                        (= (:exn-class row) exn-class)))
                 trap-table)))

(defn- out-of-contract?
  "An `unreachable` or cast trap means the entry broke the authoring
   rules (i31 overflow, non-scalar result) — the entry is wrong, not the
   compiler (0023)."
  [trap]
  (boolean (some #(str/includes? trap %) ["unreachable" "cast" "null"])))

(defn- check-lane [id mode engine oracle lane]
  (let [label (str id " [" (name mode) " " (name engine) "]")]
    (cond
      (and (contains? oracle :value) (contains? lane :value))
      (is (= (:value oracle) (edn/read-string (:value lane)))
          (str label ": value diverged from the oracle"))

      (and (contains? oracle :throw) (contains? lane :trap))
      (is (trap-matches? engine oracle (:trap lane))
          (str label ": oracle threw " (:throw oracle) " (" (:message oracle)
               ") but no corpus/trap_table.edn row matches the trap: "
               (str/trim (:trap lane))))

      (and (contains? oracle :throw) (contains? lane :exn))
      (is (exn-matches? oracle (:exn lane))
          (str label ": oracle threw " (:throw oracle) " (" (:message oracle)
               ") but no trap-table row carries :exn-class " (:exn lane)))

      (and (contains? oracle :value) (contains? lane :exn))
      (is false
          (str label ": oracle returned " (pr-str (:value oracle))
               " but the lane threw exn class " (:exn lane)))

      (and (contains? oracle :value) (contains? lane :trap))
      (is false
          (str label ": oracle returned " (pr-str (:value oracle))
               " but the lane trapped"
               (when (out-of-contract? (:trap lane))
                 " — this looks out-of-contract (i31 overflow or non-scalar result): fix the entry, not the compiler")
               ": " (str/trim (:trap lane))))

      :else
      (is false (str label ": oracle threw " (:throw oracle)
                     " but the lane returned " (pr-str (:value lane)))))))

;; --- the harness -------------------------------------------------------------

(deftest corpus-differential
  (when-not wasmtime-on-path?
    (println "wasmtime not on PATH — skipping the wasmtime corpus lane"))
  (doseq [{:keys [id forms modes]} entries]
    (let [oracle (oracle-eval forms)
          asts   (analyze/analyze-forms forms)]
      ;; The corpus lint (0022 B.4): a value the oracle cannot round-trip
      ;; through edn (fn values, unreadable prints) may not be compared.
      (when (contains? oracle :value)
        (is (= (:value oracle) (edn/read-string (pr-str (:value oracle))))
            (str id ": oracle value does not round-trip through edn — corpus lint")))
      ;; :modes restricts an entry to lanes that can express it — the
      ;; dev-only fn-redef case, 0024 §10. Default is both.
      (doseq [mode (or modes [:dev :prod])]
        (let [wasm (assemble! id mode (emit/emit-module asts mode))]
          (check-lane id mode :v8 oracle (v8-lane wasm))
          (when wasmtime-on-path?
            (check-lane id mode :wasmtime oracle (wasmtime-lane wasm)))))
      ;; The linked dev session (0028): every entry's :forms vector is a
      ;; session — each form its own module over one shared runtime.
      (when (contains? (set (or modes [:dev :prod])) :dev)
        (check-lane id :linked :v8 oracle (linked-lane id asts))))))

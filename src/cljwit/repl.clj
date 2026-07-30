(ns cljwit.repl
  "The ClojureWit nREPL server (`doc/design/0029`): the default nrepl
   middleware stack with `\"eval\"` intercepted ahead of
   interruptible-eval — piggieback's shape — so session bookkeeping,
   describe and unknown-op semantics stay protocol-correct and free.
   An eval analyzes in the persistent session (`cljwit.analyze`), emits
   a linked form module (`0028`), and runs it in the long-lived node
   engine child (`dev/repl_engine.mjs`), which owns the runtime
   instance and the var ledger. Values are scalars v0; a def prints
   #'user/name as a presentation of analysis state."
  (:require [cljwit.analyze :as analyze]
            [cljwit.emit :as emit]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.process :as process]
            [nrepl.middleware :as middleware]
            [nrepl.misc :refer [response-for]]
            [nrepl.server :as server]
            [nrepl.transport :as transport])
  (:import [java.io BufferedReader PushbackReader StringReader Writer]))

(set! *warn-on-reflection* true)

;; --- exn class -> JVM class name, from the numbered table -------------------

(def ^:private exn-classes
  (delay (into {} (keep (fn [{:keys [exn-class class]}]
                          (when exn-class [exn-class class])))
               (edn/read-string (slurp "corpus/trap_table.edn")))))

;; --- the engine child (0029 §2) ----------------------------------------------

(defonce ^:private engine (atom nil))

(defn- start-engine! []
  (let [p   (process/start {:in :pipe :out :pipe :err :inherit}
                           "node" "dev/repl_engine.mjs")
        in  (io/writer (process/stdin p))
        out (io/reader (process/stdout p))]
    {:process p :in in :out out}))

(defn- engine-call!
  "One request, one response line — the protocol's strict alternation."
  [{:keys [^Writer in ^BufferedReader out]} msg]
  (locking in
    (.write in (str (json/write-str msg) "\n"))
    (.flush in)
    (let [line (.readLine out)]
      (when-not line
        (throw (ex-info "the engine child closed its stdout" {})))
      (json/read-str line :key-fn keyword))))

(defn- engine! []
  (or @engine
      (let [e (start-engine!)
            r (engine-call! e {:op "init" :runtime emit/runtime-module})]
        (when-not (= "ok" (:tag r))
          (throw (ex-info "engine init failed" r)))
        (reset! engine e)
        e)))

;; --- the compile session ------------------------------------------------------

(defonce ^:private session (atom nil))

(defn- session! []
  (or @session
      (reset! session {:analysis (analyze/open-session)
                       :known    (atom #{})})))

(defn- eval-form!
  "form -> printed string for the editor, or throws with a message."
  [form]
  (let [{:keys [analysis known]} (session!)
        ast  (analyze/analyze-in-session! analysis form)
        def? (= :def (:op ast))
        wat  (emit/emit-form-module ast @known)
        _    (when def? (swap! known conj (str (symbol (:var ast)))))
        resp (engine-call! (engine!) {:op "eval" :wat wat :statement def?})]
    (case (:tag resp)
      "result" (:value resp)
      ;; The var's printed form is a presentation of analysis state —
      ;; the internal namespace is gensym'd and never shown (0029 §4).
      "done"   (str "#'user/" (name (:name ast)))
      "exn"    (throw (ex-info (str "; error: "
                                    (get @exn-classes (:class resp)
                                         (str "exn class " (:class resp))))
                               {:cljwit/outcome resp}))
      "trap"   (throw (ex-info (str "; trap: " (:message resp))
                               {:cljwit/outcome resp}))
      (throw (ex-info (str "engine error: " (:message resp)) resp)))))

(defn- read-forms [^String code]
  (let [rdr (PushbackReader. (StringReader. code))
        eof (Object.)]
    (loop [forms []]
      (let [f (read {:eof eof} rdr)]
        (if (identical? f eof) forms (recur (conj forms f)))))))

;; --- the middleware (0029 §1) --------------------------------------------------

(defn wrap-cljwit-eval
  "Intercepts \"eval\" ahead of interruptible-eval; everything else
   passes through the default stack."
  [handler]
  (fn [{:keys [op code transport] :as msg}]
    (if (and (= "eval" op) code)
      (do (try
            (doseq [form (read-forms code)]
              (transport/send transport
                              (response-for msg {:value (eval-form! form)})))
            (catch Exception e
              (transport/send transport
                              (response-for msg {:err (str (ex-message e) "\n")
                                                 :status #{"eval-error"}
                                                 :ex (str (class e))}))))
          (transport/send transport (response-for msg {:status #{"done"}})))
      (handler msg))))

(middleware/set-descriptor! #'wrap-cljwit-eval
                            {:requires #{"clone"}
                             :expects #{"eval"}
                             :handles {}})

(defn handler
  "The default nrepl stack plus the eval interceptor — `default-handler`
   already includes the defaults (0029 §1)."
  []
  (server/default-handler #'wrap-cljwit-eval))

(defn -main [& _]
  (let [srv (server/start-server :handler (handler))]
    (println "cljwit nREPL server on port" (:port srv))
    (spit ".nrepl-port" (str (:port srv)))
    @(promise)))

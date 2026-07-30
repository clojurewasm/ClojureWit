(ns cljwit.analyze
  "The S3 front end: tools.analyzer core plus this host layer
   (`doc/design/0022` E decided it; `0023` grew the spike into the
   pipeline).

   The one semantic decision embedded here: `macroexpand-1` applies only
   `:macro` vars, never `:inline` — with inlining on, `(+ x 1)` becomes a
   JVM interop form and `+`-vs-`+'` semantics leave cljwit's hands
   (`0022` C). Without it, `+` stays an `:invoke` of `#'clojure.core/+`.

   Analysis state is isolated per call: each `analyze-forms` run gets its
   own discarded namespace and `create-var` interns only there, so a
   `def` in one corpus entry can never resolve during another entry's
   analysis — the oracle lane's isolation rule (`0022` B), one lane over."
  (:refer-clojure :exclude [macroexpand-1])
  (:require [clojure.tools.analyzer :as ana]
            [clojure.tools.analyzer.env :refer [with-env]]
            [clojure.tools.analyzer.passes :refer [schedule]]
            [clojure.tools.analyzer.passes.uniquify :refer [uniquify-locals]]
            [clojure.tools.analyzer.utils :refer [resolve-sym]]))

(set! *warn-on-reflection* true)

;; Uniquify is the one scheduled pass: emission computes free variables
;; and lexical scope by name, which partial shadowing would corrupt —
;; `(let [x 1] (fn [] (+ x (let [x 2] x))))` has two locals both named x.
(def ^:private run-passes (schedule #{#'uniquify-locals}))

(defn- macroexpand-1
  "Only `:macro` vars expand; specials and locals shield; no `:inline`."
  [form env]
  (if (seq? form)
    (let [op (first form)]
      (if (ana/specials op)
        form
        (let [v (resolve-sym op env)]
          (if (and (not (-> env :locals (get op)))
                   (:macro (meta v)))
            (apply v form env (rest form))
            form))))
    form))

(defn- fresh-genv [ns-sym]
  (atom {:namespaces
         {ns-sym        {:mappings (ns-map 'clojure.core)
                         :aliases {} :ns ns-sym}
          'clojure.core {:mappings (ns-map 'clojure.core)
                         :aliases {} :ns 'clojure.core}}}))

(defmacro ^:private with-host [& body]
  `(binding [ana/macroexpand-1 macroexpand-1
             ana/create-var    (fn [sym# var-env#]
                                 (doto (intern (:ns var-env#) sym#)
                                   (reset-meta! (meta sym#))))
             ana/parse         ana/-parse
             ana/var?          var?]
     ~@body))

(defn open-session
  "A persistent analysis session (`0029`): one kept namespace — internal
   and gensym'd, presented to the user as `user` — so a later form's
   analysis resolves an earlier form's vars. The corpus lanes must NOT
   use this: the oracle's isolation rule (`0022` B.2) depends on
   `analyze-forms`' per-call discard, which is unchanged."
  []
  (let [ns-sym (gensym "cljwit.session")]
    (create-ns ns-sym)
    {:ns ns-sym :genv (fresh-genv ns-sym)}))

(defn analyze-in-session!
  "Analyzes one form in the session's kept environment; returns its AST."
  [{:keys [ns genv]} form]
  (with-host
    (with-env genv
      (run-passes (ana/analyze form {:context :ctx/expr :locals {} :ns ns})))))

(defn close-session!
  "Discards the session's analysis namespace."
  [{:keys [ns]}]
  (remove-ns ns))

(defn analyze-forms
  "Analyzes top-level `forms` in order as one program — later forms see
   earlier `def`s. Returns a vector of ASTs. The namespace is created
   and discarded per call — the corpus oracle's isolation rule
   (`0022` B.2); sessions use `open-session` instead."
  [forms]
  (let [ns-sym (gensym "cljwit.analysis")]
    (create-ns ns-sym)
    (try
      (let [env  {:context :ctx/expr :locals {} :ns ns-sym}
            genv (fresh-genv ns-sym)]
        (with-host
          (with-env genv
            (mapv #(run-passes (ana/analyze % env)) forms))))
      (finally (remove-ns ns-sym)))))

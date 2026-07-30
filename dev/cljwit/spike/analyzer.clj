(ns cljwit.spike.analyzer
  "Can bare tools.analyzer, with a minimal host layer, analyze S3's forms
   without leaking JVM host ops? (`0022` E's survey ran this and the answer
   decided the front end.)

   The host layer is four dynamic vars, modeled on tools.analyzer's own
   core-test. The one decision embedded here: `macroexpand-1` applies only
   `:macro` vars, never `:inline` — with inlining on, `(+ x 1)` becomes
   `(. clojure.lang.Numbers (add x 1))` and `+`-vs-`+'` semantics leave
   cljwit's hands. Without it, `+` stays an `:invoke` of `#'clojure.core/+`,
   which the emitter maps to the overflow-checking runtime (`0022` C).

   Success is a `:def` AST for fib whose distinct op set contains no
   `:host-call`/`:host-field`/`:host-interop`/`:maybe-host-form`/`:new` —
   the host ops double as leak detectors."
  (:refer-clojure :exclude [macroexpand-1])
  (:require [clojure.pprint :as pp]
            [clojure.tools.analyzer :as ana]
            [clojure.tools.analyzer.ast :as ast]
            [clojure.tools.analyzer.env :refer [with-env]]
            [clojure.tools.analyzer.utils :refer [resolve-sym]]))

(set! *warn-on-reflection* true)

(defn macroexpand-1
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

(def ^:private HOST-OPS #{:host-call :host-field :host-interop :maybe-host-form :new})

(def ^:private fib-form
  '(defn fib [n]
     (if (< n 2)
       n
       (+ (fib (- n 1)) (fib (- n 2))))))

(defn -main [& _]
  (let [env  {:context :ctx/expr :locals {} :ns 'user}
        genv (atom {:namespaces
                    {'user         {:mappings (ns-map 'clojure.core)
                                    :aliases {} :ns 'user}
                     'clojure.core {:mappings (ns-map 'clojure.core)
                                    :aliases {} :ns 'clojure.core}}})
        result (binding [ana/macroexpand-1 macroexpand-1
                         ana/create-var    (fn [sym env]
                                             (doto (intern (:ns env) sym)
                                               (reset-meta! (meta sym))))
                         ana/parse         ana/-parse
                         ana/var?          var?]
                 (with-env genv
                   (ana/analyze fib-form env)))
        ops    (sort (distinct (map :op (ast/nodes result))))
        leaks  (filter HOST-OPS ops)]
    (println "top :op      =>" (:op result))
    (println "via :raw-forms =>" (pr-str (map first (:raw-forms result))))
    (println "distinct ops =>" (pr-str ops))
    (println "host-op leaks =>" (if (seq leaks) (pr-str leaks) "none"))
    (println "recursive fib resolved =>"
             (-> result :init :expr :methods first :body :ret :else
                 :args first :fn :var pr-str))
    (when (seq leaks)
      (pp/pprint result)
      (System/exit 1))))

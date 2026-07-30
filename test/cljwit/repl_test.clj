(ns cljwit.repl-test
  "The whole REPL pipe (0029): a real nREPL client against the real
   handler — analysis session, linked emission, the node engine child.
   Skips with a printed line if the dev assembler is absent (bb check's
   ensure-npm materializes it, so this skips only off the sanctioned
   entry points)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cljwit.repl :as repl]
            [nrepl.core :as nrepl]
            [nrepl.server :as server]))

(set! *warn-on-reflection* true)

(def ^:private assembler? (.exists (io/file "node_modules/binaryen")))

(deftest repl-round-trip
  (if-not assembler?
    (println "node_modules/binaryen absent — skipping the REPL pipe test (bb ensure-npm)")
    (let [srv (server/start-server :handler (repl/handler))]
      (try
        (with-open [^java.io.Closeable conn (nrepl/connect :port (:port srv))]
          (let [client (nrepl/client conn 30000)
                evalc  (fn [code]
                         (let [resps (doall (nrepl/message client {:op "eval" :code code}))]
                           {:values (vec (keep :value resps))
                            :err    (first (keep :err resps))}))]
            (is (= ["3"] (:values (evalc "(+ 1 2)"))))
            ;; A def prints the user-facing presentation; the var then
            ;; resolves in a LATER eval — the session, not one message.
            (is (= ["#'user/x"] (:values (evalc "(def x 5)"))))
            (is (= ["42"] (:values (evalc "(+ x 37)"))))
            (is (= ["#'user/fib" "6765"]
                   (:values (evalc "(defn fib [n] (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2))))) (fib 20)"))))
            ;; Redefinition takes effect across evals — the open world.
            (is (= ["#'user/f" "#'user/f" "2"]
                   (:values (evalc "(def f (fn [] 1)) (def f (fn [] 2)) (f)")))
                "fn redefinition must take effect in dev")
            ;; Classified outcomes arrive as errors, not hangs.
            (is (str/includes? (str (:err (evalc "(+ 9223372036854775807 1)")))
                               "ArithmeticException"))
            (is (str/includes? (str (:err (evalc "(quot 1 0)"))) "divide by zero"))
            ;; Out-of-slice stays a loud compile error.
            (is (str/includes? (str (:err (evalc "\"strings later\""))) "out of the S3 slice"))
            ;; An unknown op answers instead of hanging (0029 §1's whole
            ;; argument for the default stack).
            (let [resp (first (nrepl/message client {:op "lookup" :sym "x"}))]
              (is (some? resp)))))
        (finally (server/stop-server srv))))))

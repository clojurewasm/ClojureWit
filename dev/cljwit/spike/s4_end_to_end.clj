(ns cljwit.spike.s4-end-to-end
  "The S4 entry spike: the project's two deliverables meet. `cljwit`
   (the S3 emitter) compiles `(defn fib …)` to a prod module,
   `wasm-tools` wraps it as a component (`0007`: scalar-only exports
   need no Canonical ABI lowering at all), and `cljwit.host` (the S1
   library) calls it from the JVM. Success is 6765 through the
   component boundary — the smallest thing that would fail if S4's
   premise were wrong. Needs `nix develop` (wasm-tools and
   CLJWIT_WASMTIME_LIB)."
  (:require [cljwit.analyze :as analyze]
            [cljwit.emit :as emit]
            [cljwit.host :as host]
            [clojure.java.io :as io]
            [clojure.java.process :as process]))

(set! *warn-on-reflection* true)

(def ^:private program
  ['(defn fib [n] (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
   '(fib 20)])

(defn- sh! [& args]
  (let [p    (apply process/start {:out :pipe :err :pipe} args)
        err  (future (slurp (process/stderr p)))
        exit @(process/exit-ref p)]
    (when-not (zero? exit)
      (throw (ex-info (str "failed: " (pr-str args)) {:err @err})))))

(defn -main [& _]
  (let [dir  (doto (io/file "target/spike-s4") (.mkdirs))
        wat  (io/file dir "fib.wat")
        core (io/file dir "fib.core.wasm")
        emb  (io/file dir "fib.embed.wasm")
        comp (io/file dir "fib.component.wasm")]
    (spit wat (emit/emit-module (analyze/analyze-forms program) :prod))
    (sh! "wasm-tools" "parse" (str wat) "-o" (str core))
    (sh! "wasm-tools" "component" "embed" "dev/resources/s4_fib.wit" (str core) "-o" (str emb))
    (sh! "wasm-tools" "component" "new" (str emb) "-o" (str comp))
    (println "component:" (str comp) (str "(" (.length comp) " bytes)"))
    (with-open [e (host/engine)
                a (host/compile e comp)
                i (host/instantiate a)]
      (println "exports:  " (host/exports i))
      (println "signature:" (host/signature i "entry"))
      (let [v ((i "entry"))]
        (println "(fib 20) through the component boundary =" v)
        (when-not (= 6765 v)
          (println "✗ expected 6765")
          (System/exit 1))))))

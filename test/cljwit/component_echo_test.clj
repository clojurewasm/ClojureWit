(ns cljwit.component-echo-test
  "S4's first slice, round-tripped (0030 §5): the guest-side echo
   component — memory, bump-arena cabi_realloc, the pinned trampoline
   shape — built by wasm-tools and called through cljwit.host, whose
   host-side string lanes already pass. Skips like the other FFM tests
   when libwasmtime is not resolvable."
  (:require [clojure.java.io :as io]
            [clojure.java.process :as process]
            [clojure.test :refer [deftest is]]
            [cljwit.component :as component]
            [cljwit.host :as host]))

(set! *warn-on-reflection* true)

(def ^:private lib? (some? (System/getenv "CLJWIT_WASMTIME_LIB")))

(defn- sh! [& args]
  (let [p    (apply process/start {:out :pipe :err :pipe} args)
        err  (future (slurp (process/stderr p)))
        exit @(process/exit-ref p)]
    (when-not (zero? exit)
      (throw (ex-info (str "failed: " (pr-str args)) {:err @err})))))

(defn- build-echo-component ^java.io.File []
  (let [dir  (doto (io/file "target/component-echo") (.mkdirs))
        wat  (io/file dir "echo.wat")
        wit  (io/file dir "echo.wit")
        core (io/file dir "echo.core.wasm")
        emb  (io/file dir "echo.embed.wasm")
        comp (io/file dir "echo.component.wasm")]
    (spit wat component/echo-wat)
    (spit wit (component/wit))
    (sh! "wasm-tools" "parse" (str wat) "-o" (str core))
    (sh! "wasm-tools" "component" "embed" (str wit) (str core) "-o" (str emb))
    (sh! "wasm-tools" "component" "new" (str emb) "-o" (str comp))
    comp))

(deftest echo-round-trip
  (if-not lib?
    (println "CLJWIT_WASMTIME_LIB unset — skipping the component echo test")
    (let [comp (build-echo-component)]
      (with-open [e (host/engine)
                  a (host/compile e comp)
                  i (host/instantiate a)]
        (let [echo (i "echo")]
          (is (= {:params [["s" :string]] :result :string} (host/signature i "echo")))
          (is (= "hello, boundary" (echo "hello, boundary")))
          ;; Multi-byte utf8 crosses intact — the encoding decision's
          ;; first executable check (0030 §1).
          (is (= "こんにちは、component境界" (echo "こんにちは、component境界")))
          (is (= "" (echo "")))
          ;; Long enough to force memory.grow past the initial page.
          (let [big (apply str (repeat 100000 "abcДЖ日"))]
            (is (= big (echo big))))
          ;; Two calls in a row: the second entry's reset must not
          ;; corrupt the first's already-lifted result (0030 §3).
          (let [r1 (echo "first") r2 (echo "second")]
            (is (= ["first" "second"] [r1 r2]))))))))

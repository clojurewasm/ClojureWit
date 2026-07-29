(ns cljwit.valtype-enum-test
  "wasmtime hands a component's declared types back as
   `wasmtime_component_valtype_t` kinds, and takes values as
   `wasmtime_component_val_t` kinds. **They are different enums.** A marshaller
   driven by runtime reflection reads one and writes the other, with no
   compiler between them, and the two agree on twelve of the kinds in the
   middle — so confusing them passes most of a test suite and corrupts exactly
   the integer types.

   This pins both against the pinned headers, so a wasmtime upgrade that
   renumbers either one fails the gate rather than silently mis-marshalling."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(set! *warn-on-reflection* true)

(def ^:private inc-dir (System/getenv "CLJWIT_WASMTIME_INCLUDE"))

(defn- defines
  "Every `#define <prefix><NAME> <n>` in a header, as {name n}."
  [path prefix]
  (let [re (re-pattern (str "#define " prefix "([A-Z0-9_]+) (\\d+)"))]
    (into {} (for [line (str/split-lines (slurp (io/file inc-dir path)))
                   :let [m (re-find re line)]
                   :when m]
               [(str/lower-case (nth m 1)) (parse-long (nth m 2))]))))

;; What cljwit.host will assume. Written out rather than derived, because the
;; point is to fail when the header stops matching.
(def ^:private VAL
  {"bool" 0 "s8" 1 "u8" 2 "s16" 3 "u16" 4 "s32" 5 "u32" 6 "s64" 7 "u64" 8
   "f32" 9 "f64" 10 "char" 11 "string" 12 "list" 13 "record" 14 "tuple" 15
   "variant" 16 "enum" 17 "option" 18 "result" 19 "flags" 20 "resource" 21
   "map" 22})

(def ^:private VALTYPE
  {"bool" 0 "s8" 1 "s16" 2 "s32" 3 "s64" 4 "u8" 5 "u16" 6 "u32" 7 "u64" 8
   "f32" 9 "f64" 10 "char" 11 "string" 12 "list" 13 "record" 14 "tuple" 15
   "variant" 16 "enum" 17 "option" 18 "result" 19 "flags" 20 "own" 21
   "borrow" 22 "future" 23 "stream" 24 "error_context" 25 "map" 26})

(deftest enums-are-what-we-think
  (if-not inc-dir
    (println "CLJWIT_WASMTIME_INCLUDE unset — skipping valtype enum test")
    (do
      (testing "wasmtime_component_val_t kinds"
        (is (= VAL (defines "wasmtime/component/val.h" "WASMTIME_COMPONENT_"))))
      (testing "wasmtime_component_valtype_t kinds"
        (is (= VALTYPE (defines "wasmtime/component/types/val.h"
                         "WASMTIME_COMPONENT_VALTYPE_")))))))

(deftest the-two-enums-disagree
  ;; Not a wasmtime bug, and not something to work around — something to know.
  ;; Asserted so that a future reader cannot conclude the tables are the same
  ;; from a spot check of the middle.
  (testing "the integer kinds are numbered differently"
    (is (not= (VAL "s32") (VALTYPE "s32")) "s32 is 5 as a val, 3 as a valtype")
    (is (not= (VAL "u32") (VALTYPE "u32")) "u32 is 6 as a val, 7 as a valtype")
    (is (= 8 (VAL "u64") (VALTYPE "u64")) "u64 collides at 8, which is the trap"))
  (testing "twelve kinds coincide, which is why the mistake survives testing"
    (let [same (filter (fn [k] (= (VAL k) (VALTYPE k))) (keys VAL))]
      (is (= #{"f32" "f64" "char" "string" "list" "record" "tuple" "variant"
               "enum" "option" "result" "flags" "u64" "bool" "s8"}
             (set same))
          "everything from f32 to flags agrees, plus bool, s8 and u64")))
  (testing "the resource kinds diverge again"
    (is (= 21 (VAL "resource")) "val has one `resource` kind")
    (is (and (= 21 (VALTYPE "own")) (= 22 (VALTYPE "borrow")))
        "valtype splits it into own and borrow, so 21 means different things")))

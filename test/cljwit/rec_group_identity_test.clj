(ns cljwit.rec-group-identity-test
  "Pins the WasmGC type-identity rule that doc/design/0009 rests on.

   Two independently compiled units share a heap iff their rec groups
   canonicalise to the same thing. That is what lets a REPL compile new code
   against a running program's objects — and what makes any change to the
   shared prelude a breaking change for every unit ever compiled.

   Each case builds a component holding two core modules: a producer that
   declares a prelude and returns one of its objects, and a consumer that
   declares its own prelude *independently* and imports the producer's
   function. If the two preludes canonicalise alike the import type-checks;
   if not, `wasm-tools validate` rejects it. That makes the rule executable
   with only `wasm-tools`, which tools.json already requires, so this runs in
   the gate on any machine.

   The runtime half — that the objects really are shared, not copied — was
   measured on V8 and wasmtime and is recorded in doc/design/0009. It is not
   asserted here because it needs `node`, which the gate does not require."
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io File]))

;; The mutually recursive core of doc/design/0004: the group every compilation
;; unit has to re-declare identically. Everything the tests vary is a variation
;; on this.
(def ^:private prelude
  "(rec
     (type $fn1 (func (param (ref null $obj)) (result (ref null $obj))))
     (type $vt1 (array (ref null $fn1)))
     (type $vtables (struct (field $a1 (ref $vt1))))
     (type $obj (sub (struct (field $hash (mut i32)) (field $vt (ref $vtables))))))")

(defn- component
  "A producer declaring `made`, and a consumer declaring `seen` and importing
   the producer's function. `made`/`seen` are the two units' own spellings of
   the type crossing between them."
  [made seen]
  (format "(component
             (core module $producer
               %s
               (func (export \"make\") (result (ref null %s))
                 (ref.null %s)))
             (core module $consumer
               %s
               (import \"p\" \"make\" (func $make (result (ref null %s))))
               (func (export \"run\") (result i32)
                 (ref.is_null (call $make))))
             (core instance $pi (instantiate $producer))
             (core instance $ci (instantiate $consumer (with \"p\" (instance $pi)))))"
          (:decl made) (:type made) (:type made)
          (:decl seen) (:type seen)))

(defn- shares-heap?
  "True when the two units' types canonicalise alike. `wasm-tools validate`
   reads .wat directly, so no build step is involved."
  [made seen]
  (let [f (File/createTempFile "cljwit-recgroup" ".wat")]
    (try
      (spit f (component made seen))
      (zero? (:exit (shell/sh "wasm-tools" "validate" (str f))))
      (finally (.delete f)))))

(def ^:private core
  {:decl prelude :type "$obj"})

(deftest identical-rec-groups-unify
  (testing "a unit that re-declares the prelude verbatim shares the heap"
    (is (shares-heap? core core)
        "this is the property a REPL depends on")))

(deftest a-user-type-in-its-own-group-is-safe
  (testing "deftype goes in a new group referring to the frozen one"
    ;; doc/design/0009's mechanism for adding types without invalidating
    ;; anything: never append to the shared group.
    (let [user {:decl (str prelude "
                       (rec (type $user (sub $obj (struct
                         (field $hash (mut i32))
                         (field $vt (ref $vtables))
                         (field $x i32)))))")
                :type "$user"}]
      (is (shares-heap? user user)
          "a separate group referring to the frozen one still unifies")
      (is (shares-heap? user (update user :decl #(str % "
                       (rec (type $later (sub $obj (struct
                         (field $hash (mut i32))
                         (field $vt (ref $vtables))
                         (field $y i64)))))")))
          "and a unit that later defines more types still sees the earlier ones"))))

(deftest changing-the-shared-group-breaks-every-unit
  ;; Each of these is a way the prelude can drift between two builds. All of
  ;; them invalidate every object ever allocated by the other build, which is
  ;; why doc/design/0009 requires the shared group to be frozen and versioned.
  (testing "a changed field type"
    (is (not (shares-heap? core {:decl (.replace prelude "field $hash (mut i32)"
                                                 "field $hash (mut i64)")
                                 :type "$obj"}))))
  (testing "field mutability"
    (is (not (shares-heap? core {:decl (.replace prelude "field $hash (mut i32)"
                                                 "field $hash i32")
                                 :type "$obj"}))))
  (testing "final instead of open to subtyping"
    (is (not (shares-heap? core {:decl (.replace prelude "(type $obj (sub (struct"
                                                 "(type $obj ((struct")
                                 :type "$obj"}))))
  (testing "one more type added to the shared group — the sharpest edge"
    ;; Adding an arity to doc/design/0004's $vtables means a new $fnN/$vtN pair
    ;; inside this group, so this is not hypothetical: it is what supporting one
    ;; more arity costs.
    (is (not (shares-heap? core {:decl (.replace prelude "(type $obj (sub (struct"
                                                 "(type $spacer (struct (field $z i32)))
                                                  (type $obj (sub (struct")
                                 :type "$obj"})))))

(ns cljwit.emit
  "AST -> WAT text, the batch emitter (`doc/design/0022` A; the
   representation is pinned in `0023`).

   Every value is `(ref null eq)`; fixnums are i31; nil is the null
   reference; false and true are singleton struct globals distinct by
   identity. Arithmetic computes in i64 and re-boxes through one guard
   whose failure arm is `unreachable` — the boxed-i64 lane is open
   (`0022` C) and no corpus entry may reach it."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def ^:private i31-min -1073741824)
(def ^:private i31-max 1073741823)

(defn- out-of-slice! [what ast]
  (throw (ex-info (str "out of the S3 slice: " what)
                  {:cljwit/error :out-of-slice
                   :op (:op ast) :form (:form ast)})))

(defn- fresh-local!
  "Allocates a WAT local in the enclosing function and returns its name."
  [{:keys [counter local-decls]}]
  (let [nm (str "$l" (swap! counter inc))]
    (swap! local-decls conj (format "(local %s (ref null eq))" nm))
    nm))

(defn- global-for!
  "The module global backing a def'd var — one per var, declared null and
   assigned at eval (`0022` D: declare at analysis, assign at eval)."
  [{:keys [counter globals global-decls]} v]
  (or (get @globals v)
      (let [nm (str "$g" (swap! counter inc))]
        (swap! global-decls conj (format "(global %s (mut (ref null eq)) (ref.null eq))" nm))
        (swap! globals assoc v nm)
        nm)))

(declare emit-expr)

(defn- emit-const [{:keys [type val] :as ast}]
  (case type
    :number (cond
              (not (integer? val))
              (out-of-slice! (str "non-integer literal " (pr-str val)) ast)
              (or (< val i31-min) (> val i31-max))
              (out-of-slice! (str "literal " val " outside i31 — the boxed lane is open (0022 C)") ast)
              :else (format "(ref.i31 (i32.const %d))" val))
    :bool   (if val "(global.get $true)" "(global.get $false)")
    :nil    "(ref.null eq)"
    (out-of-slice! (str "literal of type " type) ast)))

(defn- emit-loop
  "`0022` D: a block and a br. The loop's locals are set before entry;
   `recur` re-enters via `br` to the loop label."
  [ctx {:keys [bindings body loop-id]}]
  (let [[ctx' sets]
        (reduce (fn [[c acc] {:keys [name init]}]
                  (let [wl (fresh-local! c)]
                    [(assoc-in c [:locals name] wl)
                     (conj acc (format "(local.set %s %s)" wl (emit-expr c init)))]))
                [ctx []] bindings)
        label (str "$loop" (swap! (:counter ctx) inc))
        ctx'' (assoc-in ctx' [:loops loop-id]
                        {:label label
                         :locals (mapv #(get-in ctx' [:locals (:name %)]) bindings)})]
    (format "(block (result (ref null eq)) %s (loop %s (result (ref null eq)) %s))"
            (str/join " " sets) label (emit-expr ctx'' body))))

(defn- emit-recur
  "Rebinding is simultaneous: every argument is evaluated onto the value
   stack under the *current* bindings, then popped into the loop locals in
   reverse — a sequential `local.set` would let later arguments observe
   earlier rebinds."
  [ctx {:keys [exprs loop-id] :as ast}]
  (let [{:keys [label locals]} (get-in ctx [:loops loop-id])]
    (when-not label
      (out-of-slice! "recur outside an enclosing loop (fn recur is a later unit)" ast))
    (format "(block (result (ref null eq)) %s %s (br %s))"
            (str/join " " (map #(emit-expr ctx %) exprs))
            (str/join " " (map #(format "(local.set %s)" %) (reverse locals)))
            label)))

(defn- emit-let [ctx {:keys [bindings body]}]
  (let [[ctx' sets]
        (reduce (fn [[c acc] {:keys [name init]}]
                  ;; Sequential semantics: the init is emitted under the
                  ;; bindings before it, then the local joins the scope for
                  ;; the ones after it and the body.
                  (let [wl (fresh-local! c)]
                    [(assoc-in c [:locals name] wl)
                     (conj acc (format "(local.set %s %s)" wl (emit-expr c init)))]))
                [ctx []] bindings)]
    (format "(block (result (ref null eq)) %s %s)"
            (str/join " " sets) (emit-expr ctx' body))))

(defn- fold-binary
  "Left-folds an n-ary arithmetic call to the binary runtime function,
   matching Clojure's own reduce order — overflow is observed at the same
   partial sum it would be on the JVM."
  [rt-fn identity-wat emitted]
  (case (count emitted)
    0 identity-wat
    1 (first emitted)
    (reduce (fn [a b] (format "(call %s %s %s)" rt-fn a b)) emitted)))

(defn- emit-invoke [ctx {:keys [args] :as ast}]
  (let [f (:fn ast)]
    (when-not (= :var (:op f))
      (out-of-slice! "call target is not a var" ast))
    (let [v       (:var f)
          emitted (mapv #(emit-expr ctx %) args)]
      (condp = v
        #'clojure.core/+    (fold-binary "$add" "(ref.i31 (i32.const 0))" emitted)
        #'clojure.core/*    (fold-binary "$mul" "(ref.i31 (i32.const 1))" emitted)
        #'clojure.core/-    (case (count emitted)
                              0 (out-of-slice! "(-) needs at least one argument" ast)
                              1 (format "(call $sub (ref.i31 (i32.const 0)) %s)" (first emitted))
                              (reduce (fn [a b] (format "(call $sub %s %s)" a b)) emitted))
        #'clojure.core/quot (if (= 2 (count emitted))
                              (format "(call $quot %s %s)" (first emitted) (second emitted))
                              (out-of-slice! "quot takes exactly two arguments" ast))
        #'clojure.core/<    (if (= 2 (count emitted))
                              (format "(call $lt %s %s)" (first emitted) (second emitted))
                              (out-of-slice! "< beyond two arguments" ast))
        (out-of-slice! (str "no intrinsic for " v) ast)))))

(defn- emit-expr
  "Emits one folded WAT expression leaving one `(ref null eq)` value."
  [ctx ast]
  (case (:op ast)
    :const  (emit-const ast)
    :do     (let [stmts (:statements ast)]
              (if (empty? stmts)
                (emit-expr ctx (:ret ast))
                (format "(block (result (ref null eq)) %s %s)"
                        (str/join " " (map #(format "(drop %s)" (emit-expr ctx %)) stmts))
                        (emit-expr ctx (:ret ast)))))
    :if     (format "(if (result (ref null eq)) (call $truthy %s) (then %s) (else %s))"
                    (emit-expr ctx (:test ast))
                    (emit-expr ctx (:then ast))
                    (emit-expr ctx (:else ast)))
    :let    (emit-let ctx ast)
    :loop   (emit-loop ctx ast)
    :recur  (emit-recur ctx ast)
    :local  (if-let [wl (get-in ctx [:locals (:name ast)])]
              (format "(local.get %s)" wl)
              (out-of-slice! (str "unresolved local " (:name ast)) ast))
    :var    (if-let [g (get @(:globals ctx) (:var ast))]
              (format "(global.get %s)" g)
              (out-of-slice! (str "var " (:var ast) " has no global — only def'd vars are referenceable") ast))
    :def    (out-of-slice! "def in expression position — its value is a var, which has no representation yet" ast)
    :invoke (emit-invoke ctx ast)
    (out-of-slice! (str "op " (:op ast)) ast)))

(defn- emit-top-level
  "A top-level statement: a def becomes a `global.set`; anything else is
   an expression whose value is dropped."
  [ctx ast]
  (if (= :def (:op ast))
    (if-let [init (:init ast)]
      ;; The init is emitted before the var's global registers: a
      ;; self-referential `(def y y)` must hit the loud unresolved-var
      ;; error, not silently read the null the global was declared with.
      (let [init-wat (emit-expr ctx init)]
        (format "(global.set %s %s)" (global-for! ctx (:var ast)) init-wat))
      (out-of-slice! "def without init — an unbound var has no edn representation" ast))
    (format "(drop %s)" (emit-expr ctx ast))))

(def ^:private runtime
  "  (type $Unit (struct))
  (global $false (ref $Unit) (struct.new $Unit))
  (global $true (ref $Unit) (struct.new $Unit))
  (func $truthy (param $v (ref null eq)) (result i32)
    (i32.eqz (i32.or (ref.is_null (local.get $v))
                     (ref.eq (local.get $v) (global.get $false)))))
  (func $unbox (param $v (ref null eq)) (result i64)
    (i64.extend_i32_s (i31.get_s (ref.cast (ref i31) (local.get $v)))))
  (func $box (param $v i64) (result (ref eq))
    ;; 31-bit signed range check; the failure arm is the boxed-i64 lane,
    ;; open and unmeasured (0022 C) — no corpus entry may reach it.
    (if (i64.ne (local.get $v)
                (i64.shr_s (i64.shl (local.get $v) (i64.const 33)) (i64.const 33)))
      (then (unreachable)))
    (ref.i31 (i32.wrap_i64 (local.get $v))))
  (func $add (param $a (ref null eq)) (param $b (ref null eq)) (result (ref eq))
    (call $box (i64.add (call $unbox (local.get $a)) (call $unbox (local.get $b)))))
  (func $sub (param $a (ref null eq)) (param $b (ref null eq)) (result (ref eq))
    (call $box (i64.sub (call $unbox (local.get $a)) (call $unbox (local.get $b)))))
  (func $mul (param $a (ref null eq)) (param $b (ref null eq)) (result (ref eq))
    (call $box (i64.mul (call $unbox (local.get $a)) (call $unbox (local.get $b)))))
  (func $quot (param $a (ref null eq)) (param $b (ref null eq)) (result (ref eq))
    (call $box (i64.div_s (call $unbox (local.get $a)) (call $unbox (local.get $b)))))
  (func $lt (param $a (ref null eq)) (param $b (ref null eq)) (result (ref eq))
    (if (result (ref eq))
        (i64.lt_s (call $unbox (local.get $a)) (call $unbox (local.get $b)))
      (then (global.get $true))
      (else (global.get $false))))
")

(defn emit-module
  "One self-contained module for `asts` (one program): forms evaluate in
   order, the last one's value is `entry`'s i64 result — a non-fixnum
   result traps the unwrap cast, the mechanical form of `0022` B.3's
   scalar-entries-first rule. `mode` is :dev or :prod; both thread the
   pipeline from day one and nothing in the slice diverges by mode yet
   (`0023`)."
  [asts mode]
  {:pre [(#{:dev :prod} mode) (seq asts)]}
  (let [ctx   {:locals {} :counter (atom 0) :local-decls (atom [])
               :globals (atom {}) :global-decls (atom [])}
        stmts (mapv #(emit-top-level ctx %) (butlast asts))
        ;; The last form is the entry's result, so it cannot be a def —
        ;; a def's value is the var itself, which is not a scalar.
        ret   (if (= :def (:op (last asts)))
                (out-of-slice! "a def cannot be an entry's last form — its value is a var" (last asts))
                (emit-expr ctx (last asts)))]
    (str "(module\n"
         (format "  ;; mode %s — no divergence in the slice yet (0023)\n" (name mode))
         runtime
         (str/join (map #(str "  " % "\n") @(:global-decls ctx)))
         "  (func $entry (export \"entry\") (result i64)\n"
         (str/join (map #(str "    " % "\n") @(:local-decls ctx)))
         (str/join (map #(str "    " % "\n") stmts))
         (format "    (i64.extend_i32_s (i31.get_s (ref.cast (ref i31) %s)))))\n" ret))))

(ns cljwit.emit
  "AST -> WAT text, the batch emitter (`doc/design/0022` A; the value
   representation is pinned in `0023`, fn's in `0024`).

   Every value is `(ref null eq)`; fixnums are i31; nil is the null
   reference; false and true are singleton struct globals distinct by
   identity. Arithmetic computes in i64 and re-boxes through one guard
   whose failure arm is `unreachable` — the boxed-i64 lane is open
   (`0022` C) and no corpus entry may reach it.

   fn (`0024`): closures are subtypes of a `$Fn` base struct carrying one
   nullable typed-function-ref slot per arity; each capture signature is
   its own subtype in its own rec group (`0009`); calls are cast + slot
   load + `call_ref`, except `:prod` calls through a direct-linked var,
   which are plain `call` — the first place the two modes emit different
   bytes. Method funcs declare `(type $sigN)` explicitly: a bare func
   type would canonicalize as its own singleton rec group and lose
   identity with the slot's field type (`0009`'s rule, again)."
  (:require [clojure.string :as str]
            [clojure.tools.analyzer.ast :as ast]))

(set! *warn-on-reflection* true)

(def ^:private i31-min -1073741824)
(def ^:private i31-max 1073741823)

(defn- out-of-slice! [what ast]
  (throw (ex-info (str "out of the S3 slice: " what)
                  {:cljwit/error :out-of-slice
                   :op (:op ast) :form (:form ast)})))

(defn- fresh-local!
  "Allocates a WAT local in the enclosing function and returns its name."
  ([ctx] (fresh-local! ctx "(ref null eq)"))
  ([{:keys [counter local-decls]} type]
   (let [nm (str "$l" (swap! counter inc))]
     (swap! local-decls conj (format "(local %s %s)" nm type))
     nm)))

(defn- global-for!
  "The module global backing a def'd var — one per var, declared null and
   assigned at eval (`0022` D: declare at analysis, assign at eval)."
  [{:keys [counter globals global-decls]} v]
  (or (get @globals v)
      (let [nm (str "$g" (swap! counter inc))]
        (swap! global-decls conj (format "(global %s (mut (ref null eq)) (ref.null eq))" nm))
        (swap! globals assoc v nm)
        nm)))

(defn- local-read
  "Reads a lexical binding: a WAT local, or a capture field off the
   closure the method prologue cast into `$me` (`0024`)."
  [entry]
  (if (string? entry)
    (format "(local.get %s)" entry)
    (format "(struct.get %s $c%d (local.get $me))" (:type entry) (:idx entry))))

(declare emit-expr emit-fn)

;; --- scalars and control -----------------------------------------------------

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
   earlier rebinds. A fn method's params register under its `:loop-id`,
   so `recur` to the method head comes through here too (`0024`)."
  [ctx {:keys [exprs loop-id] :as ast}]
  (let [{:keys [label locals]} (get-in ctx [:loops loop-id])]
    (when-not label
      (out-of-slice! "recur outside an enclosing loop or fn method" ast))
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

;; --- calls -------------------------------------------------------------------

(defn- fold-binary
  "Left-folds an n-ary arithmetic call to the binary runtime function,
   matching Clojure's own reduce order — overflow is observed at the same
   partial sum it would be on the JVM."
  [rt-fn identity-wat emitted]
  (case (count emitted)
    0 identity-wat
    1 (first emitted)
    (reduce (fn [a b] (format "(call %s %s %s)" rt-fn a b)) emitted)))

(def ^:private intrinsic-vars
  #{#'clojure.core/+ #'clojure.core/- #'clojure.core/*
    #'clojure.core/quot #'clojure.core/<})

(defn- emit-intrinsic [v emitted ast]
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
                          (out-of-slice! "< beyond two arguments" ast))))

(defn- emit-generic-call
  "`0004`'s measured generic shape: cast to `$Fn`, load the arity slot,
   `call_ref`. The target is evaluated once into a non-nullable local."
  [ctx f-ast args]
  (let [n  (count args)
        t  (fresh-local! ctx "(ref $Fn)")]
    (format "(block (result (ref null eq)) (local.set %s (ref.cast (ref $Fn) %s)) (call_ref $sig%d (local.get %s) %s (struct.get $Fn $a%d (local.get %s))))"
            t (emit-expr ctx f-ast) n t
            (str/join " " (map #(emit-expr ctx %) args)) n t)))

(defn- emit-invoke [ctx {:keys [args] :as ast}]
  (let [f (:fn ast)
        v (when (= :var (:op f)) (:var f))]
    (cond
      (and v (intrinsic-vars v))
      (emit-intrinsic v (mapv #(emit-expr ctx %) args) ast)

      ;; :prod, calling through a direct-linked var: a plain call — the
      ;; first divergence between the modes (`0024`).
      (and v (get @(:direct ctx) v))
      (let [{:keys [global methods]} (get @(:direct ctx) v)]
        (if-let [fname (get methods (count args))]
          (format "(call %s (global.get %s) %s)"
                  fname global (str/join " " (map #(emit-expr ctx %) args)))
          (out-of-slice! (str "no arity " (count args) " on direct-linked " v) ast)))

      :else (emit-generic-call ctx f args))))

;; --- fn ----------------------------------------------------------------------

(defn- fn-field-list
  "The `$Fn` slot fields, repeated verbatim in every capture subtype —
   WasmGC subtypes redeclare their parent's fields."
  [k]
  (str/join "" (for [n (range (inc k))]
                 (format " (field $a%d (ref null $sig%d))" n n))))

(defn- free-names
  "Captured bindings: locals referenced under `fn-ast` that resolve in the
   enclosing scope. Names are uniquified at analysis (`0024`), so name
   membership is exact even under partial shadowing."
  [ctx fn-ast]
  (let [bound (set (keys (:locals ctx)))]
    (->> (ast/nodes fn-ast)
         (filter #(= :local (:op %)))
         (map :name)
         distinct
         (filter bound)
         vec)))

(defn- emit-method!
  "Compiles one fn method to a module-level func typed `(type $sigN)` —
   the explicit type keeps identity with the slot field (`0009`)."
  [ctx tname caps {:keys [params body loop-id fixed-arity]} fname self-binding]
  (let [pnames (mapv #(str "$p" (inc %)) (range fixed-arity))
        mctx   (assoc ctx
                      :locals (merge (into {} (map-indexed
                                               (fn [j nm] [nm {:type tname :idx j}]) caps))
                                     (zipmap (map :name params) pnames)
                                     (when self-binding {(:name self-binding) "$self"}))
                      :local-decls (atom [])
                      :loops {})
        label  (str "$m" (swap! (:counter ctx) inc))
        mctx   (assoc-in mctx [:loops loop-id] {:label label :locals pnames})
        body-w (emit-expr mctx body)]
    (swap! (:module-funcs ctx) conj
           (str (format "  (func %s (type $sig%d) (param $self (ref eq))%s (result (ref null eq))\n"
                        fname fixed-arity
                        (str/join "" (map #(format " (param %s (ref null eq))" %) pnames)))
                (when (seq caps) (format "    (local $me (ref %s))\n" tname))
                (str/join (map #(str "    " % "\n") @(:local-decls mctx)))
                (when (seq caps)
                  (format "    (local.set $me (ref.cast (ref %s) (local.get $self)))\n" tname))
                (format "    (loop %s (result (ref null eq)) %s))\n" label body-w)))))

(defn- fn-parts!
  "Compiles a fn's type and methods; returns what a creation site or a
   direct-linked global needs: the type name, the arity->func map, and
   the slot expressions. `register!` (optional) runs after the arity map
   exists but before the method bodies emit, so a direct-linked fn's own
   recursion can already resolve to a plain call (`0024`)."
  [ctx {:keys [methods] :as ast} & [register!]]
  (when (some :variadic? methods)
    (out-of-slice! "varargs need a seq representation (0022 D's rest-mode, marked untested)" ast))
  (when (empty? methods)
    (out-of-slice! "fn with no methods" ast))
  (let [caps  (free-names ctx ast)
        k     @(:max-arity ctx)
        idx   (swap! (:counter ctx) inc)
        tname (if (seq caps) (str "$FnC" idx) "$Fn")
        m-map (into {} (for [m methods] [(:fixed-arity m) (str "$f" idx "_a" (:fixed-arity m))]))]
    (when register! (register! tname m-map))
    (when (seq caps)
      (swap! (:fn-types ctx) conj
             (format "  (rec (type %s (sub $Fn (struct%s%s))))\n"
                     tname (fn-field-list k)
                     (str/join "" (map #(format " (field $c%d (ref null eq))" %)
                                       (range (count caps)))))))
    (doseq [m methods]
      (emit-method! ctx tname caps m (m-map (:fixed-arity m)) (:local ast)))
    (swap! (:declared-funcs ctx) into (vals m-map))
    {:tname tname :methods m-map :caps caps
     :slots (for [n (range (inc k))]
              (if-let [f (m-map n)] (format "(ref.func %s)" f) "(ref.null nofunc)"))}))

(defn- emit-fn
  "A fn value: `struct.new` of its capture subtype — slots, then the
   captured values read in the enclosing scope."
  [ctx ast]
  (let [{:keys [tname slots caps]} (fn-parts! ctx ast)]
    (format "(struct.new %s %s)" tname
            (str/join " " (concat slots
                                  (map #(local-read (get-in ctx [:locals %])) caps))))))

;; --- expressions ---------------------------------------------------------------

(defn- emit-expr
  "Emits one folded WAT expression leaving one `(ref null eq)` value."
  [ctx ast]
  (case (:op ast)
    :const     (emit-const ast)
    :with-meta (emit-expr ctx (:expr ast))
    :do        (let [stmts (:statements ast)]
                 (if (empty? stmts)
                   (emit-expr ctx (:ret ast))
                   (format "(block (result (ref null eq)) %s %s)"
                           (str/join " " (map #(format "(drop %s)" (emit-expr ctx %)) stmts))
                           (emit-expr ctx (:ret ast)))))
    :if        (format "(if (result (ref null eq)) (call $truthy %s) (then %s) (else %s))"
                       (emit-expr ctx (:test ast))
                       (emit-expr ctx (:then ast))
                       (emit-expr ctx (:else ast)))
    :let       (emit-let ctx ast)
    :loop      (emit-loop ctx ast)
    :recur     (emit-recur ctx ast)
    :fn        (emit-fn ctx ast)
    :local     (if-let [entry (get-in ctx [:locals (:name ast)])]
                 (local-read entry)
                 (out-of-slice! (str "unresolved local " (:name ast)) ast))
    :var       (cond
                 (get @(:direct ctx) (:var ast))
                 (format "(global.get %s)" (:global (get @(:direct ctx) (:var ast))))
                 (get @(:globals ctx) (:var ast))
                 (format "(global.get %s)" (get @(:globals ctx) (:var ast)))
                 :else
                 (out-of-slice! (str "var " (:var ast) " has no global — only def'd vars are referenceable") ast))
    :def       (out-of-slice! "def in expression position — its value is a var, which has no representation yet" ast)
    :invoke    (emit-invoke ctx ast)
    (out-of-slice! (str "op " (:op ast)) ast)))

;; --- top level -----------------------------------------------------------------

(defn- unwrap-meta [ast]
  (if (= :with-meta (:op ast)) (:expr ast) ast))

(defn- emit-def
  "`0022` D: declare at analysis, assign at eval — one mutable global per
   var. In `:prod`, a fn-valued def whose var is neither ^:dynamic nor
   ^:redef becomes an immutable global holding a constant `struct.new`,
   and its var registers for direct calls (`0024`). Registration happens
   before the methods emit, so self-recursion direct-links too."
  [ctx {:keys [init] :as ast}]
  (when-not init
    (out-of-slice! "def without init — an unbound var has no edn representation" ast))
  (let [v     (:var ast)
        init' (unwrap-meta init)
        direct? (and (= :prod (:mode ctx)) (= :fn (:op init'))
                     (not (:dynamic (meta v))) (not (:redef (meta v))))]
    (cond
      (get @(:direct ctx) v)
      (out-of-slice! (str "re-def of direct-linked " v " in :prod — mark it ^:redef") ast)

      (and direct? (get @(:globals ctx) v))
      (out-of-slice! (str v " changes from value to direct-linked fn in :prod") ast)

      direct?
      (let [gname (str "$g" (swap! (:counter ctx) inc))
            {:keys [slots caps]}
            (fn-parts! ctx init'
                       ;; Register before the bodies emit: the method
                       ;; bodies may call this very var.
                       (fn [tname m-map]
                         (swap! (:direct ctx) assoc v
                                {:global gname :methods m-map :type tname})))]
        (when (seq caps)
          ;; Top-level defs have no enclosing locals; a capture here means
          ;; the emitter's scope tracking broke — fail, don't mis-emit.
          (out-of-slice! (str "direct-linked fn captured " (vec caps)) ast))
        (swap! (:global-decls ctx) conj
               (format "(global %s (ref $Fn) (struct.new $Fn %s))" gname (str/join " " slots)))
        nil)

      ;; A fn init's var references run at call time, after the
      ;; assignment below — so a recursive `(defn fib …)` needs its
      ;; global registered before the method bodies emit.
      (= :fn (:op init'))
      (let [g (global-for! ctx v)]
        (format "(global.set %s %s)" g (emit-expr ctx init)))

      :else
      ;; A value init evaluates eagerly, so it emits before the var's
      ;; global registers: a self-referential `(def y y)` must hit the
      ;; loud unresolved-var error, not silently read the declared null.
      (let [init-wat (emit-expr ctx init)]
        (format "(global.set %s %s)" (global-for! ctx v) init-wat)))))

(defn- emit-top-level
  "A top-level statement: a def becomes a `global.set` (or, direct-linked
   in :prod, just its declarations); anything else is an expression whose
   value is dropped."
  [ctx ast]
  (if (= :def (:op ast))
    (emit-def ctx ast)
    (format "(drop %s)" (emit-expr ctx ast))))

;; --- module assembly -------------------------------------------------------------

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

(defn- max-arity-used
  "K for the module: the largest fn-method arity or generic call argc.
   nil when the program has no fns and no fn-value calls, in which case
   none of the fn machinery is emitted."
  [asts]
  (let [nodes (mapcat ast/nodes asts)
        arities (concat
                 (keep #(when (= :fn-method (:op %)) (:fixed-arity %)) nodes)
                 (keep #(when (and (= :invoke (:op %))
                                   (not (and (= :var (:op (:fn %)))
                                             (intrinsic-vars (:var (:fn %))))))
                          (count (:args %)))
                       nodes))]
    (when (seq arities) (apply max arities))))

(defn- fn-base-types
  "$sig0..$sigK and $Fn share one rec group — they are the module's shared
   fn substrate; capture subtypes get their own groups (`0009`, `0024`)."
  [k]
  (str "  (rec\n"
       (str/join (for [n (range (inc k))]
                   (format "    (type $sig%d (func (param (ref eq))%s (result (ref null eq))))\n"
                           n (str/join "" (repeat n " (param (ref null eq))")))))
       "    (type $Fn (sub (struct" (fn-field-list k) ")))\n"
       "  )\n"))

(defn emit-module
  "One self-contained module for `asts` (one program): forms evaluate in
   order, the last one's value is `entry`'s i64 result — a non-fixnum
   result traps the unwrap cast, the mechanical form of `0022` B.3's
   scalar-entries-first rule. `mode` is :dev or :prod; they diverge only
   at defs of fns and calls through them (`0024`)."
  [asts mode]
  {:pre [(#{:dev :prod} mode) (seq asts)]}
  (let [k     (max-arity-used asts)
        ctx   {:locals {} :counter (atom 0) :local-decls (atom [])
               :globals (atom {}) :global-decls (atom [])
               :direct (atom {}) :module-funcs (atom []) :fn-types (atom [])
               :declared-funcs (atom []) :max-arity (atom k) :mode mode}
        stmts (into [] (keep #(emit-top-level ctx %)) (butlast asts))
        ;; The last form is the entry's result, so it cannot be a def —
        ;; a def's value is the var itself, which is not a scalar.
        ret   (if (= :def (:op (last asts)))
                (out-of-slice! "a def cannot be an entry's last form — its value is a var" (last asts))
                (emit-expr ctx (last asts)))]
    (str "(module\n"
         (format "  ;; mode %s (0023, 0024)\n" (name mode))
         (when k (fn-base-types k))
         runtime
         (str/join @(:fn-types ctx))
         (str/join (map #(str "  " % "\n") @(:global-decls ctx)))
         (str/join @(:module-funcs ctx))
         (when-let [fs (seq @(:declared-funcs ctx))]
           (format "  (elem declare func %s)\n" (str/join " " fs)))
         "  (func $entry (export \"entry\") (result i64)\n"
         (str/join (map #(str "    " % "\n") @(:local-decls ctx)))
         (str/join (map #(str "    " % "\n") stmts))
         (format "    (i64.extend_i32_s (i31.get_s (ref.cast (ref i31) %s)))))\n" ret))))

(ns cljwit.host.gen
  "Generates a checked-in .clj namespace over a component's exports
   (`doc/design/0020`).

   Dev-time only: generation reads the component through libwasmtime, but the
   file it writes needs neither the .wasm nor the library to *load* — it is
   ordinary source, one var per export, with the instance as the explicit
   first argument. `cljwit.host/unwrap` is the opt-in result sugar."
  (:require [cljwit.host :as host]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

(set! *warn-on-reflection* true)

(defn- var-name
  "The last WIT segment as a symbol: annotations dropped, dots to dashes.
   `…#[method]counter.bump` → counter-bump."
  [wit-name]
  (let [n (peek (str/split wit-name #"#" 2))
        n (str/replace n #"^\[(constructor|method|static)\]" "")
        n (str/replace n "." "-")]
    (when-not (re-matches #"[a-zA-Z][a-zA-Z0-9-]*" n)
      (throw (ex-info (str (pr-str wit-name) " does not munge to a readable var name"
                           " — use :rename (0020 C)")
                      {:cljwit/error :unnameable :cljwit/export wit-name})))
    (symbol n)))

(defn- plan
  "Emission order for a described component: [{:wit :var :sig} …], sorted so
   regeneration is deterministic. A collision after munging is an error with
   two exits — :interface at interface granularity, :rename at the leaf
   (0020 C)."
  [described {:keys [interface rename]}]
  (let [described (if interface
                    (into {} (filter (fn [[k _]] (str/starts-with? k (str interface "#"))))
                          described)
                    described)]
    (when (and interface (empty? described))
      (throw (ex-info (str "no exports under interface " (pr-str interface))
                      {:cljwit/error :no-such-interface :cljwit/interface interface})))
    (when-let [extra (seq (remove described (keys rename)))]
      (throw (ex-info (str ":rename names exports the component does not have: "
                           (pr-str (vec extra)))
                      {:cljwit/error :no-such-export :cljwit/extra (vec extra)})))
    ;; A string value would slip past collision detection (group-by treats
    ;; "run" and 'run as distinct) and then emit two same-named defns.
    (when-let [bad (seq (remove simple-symbol? (vals rename)))]
      (throw (ex-info (str ":rename values must be simple symbols: " (pr-str (vec bad)))
                      {:cljwit/error :bad-options :cljwit/values (vec bad)})))
    (let [rows  (mapv (fn [[k sig]]
                        {:wit k :var (or (get rename k) (var-name k)) :sig sig})
                      described)
          dupes (->> (group-by :var rows) (filter (fn [[_ rs]] (< 1 (count rs)))))]
      (when (seq dupes)
        (throw (ex-info (str "export names collide after munging: "
                             (str/join "; " (map (fn [[v rs]]
                                                   (str v " <= " (str/join ", " (sort (map :wit rs)))))
                                                 dupes))
                             " — split with :interface, or disambiguate with :rename (0020 C)")
                        {:cljwit/error :name-collision
                         :cljwit/collisions (into {} (map (fn [[v rs]] [v (vec (sort (map :wit rs)))]))
                                                  dupes)})))
      (sort-by (comp str :var) rows))))

(defn- wit-type
  "A reflected type tree in WIT syntax — structural only: reflection has no
   declared type names (`0012`), so `perms` renders as its flags, never as
   `perms`."
  [t]
  (cond
    (nil? t) "_"
    (keyword? t) (name t)
    :else
    (case (:kind t)
      :list    (str "list<" (wit-type (:element t)) ">")
      :option  (str "option<" (wit-type (:ty t)) ">")
      :result  (let [{:keys [ok err]} t]
                 (cond
                   (and (nil? ok) (nil? err)) "result"
                   (nil? err) (str "result<" (wit-type ok) ">")
                   :else      (str "result<" (wit-type ok) ", " (wit-type err) ">")))
      :tuple   (str "tuple<" (str/join ", " (map wit-type (:types t))) ">")
      :enum    (str "enum{" (str/join ", " (:cases t)) "}")
      :flags   (str "flags{" (str/join ", " (:names t)) "}")
      :record  (str "record{" (str/join ", " (map (fn [[n ft]] (str n ": " (wit-type ft)))
                                                  (:fields t))) "}")
      :variant (str "variant{" (str/join ", " (map (fn [[n ft]]
                                                     (if ft (str n "(" (wit-type ft) ")") n))
                                                   (:cases t))) "}")
      (name (:kind t)))))

(defn- sig-line [wit {:keys [params result]}]
  (str wit ": func("
       (str/join ", " (map (fn [[n t]] (str n ": " (wit-type t))) params))
       ")"
       (when result (str " -> " (wit-type result)))))

(defn- emit-fn [{:keys [wit var sig]}]
  (let [pnames (mapv (fn [[n _]] (symbol n)) (:params sig))
        ;; Total and deterministic: a gensym here would break byte-identical
        ;; regeneration, and nil would emit silently broken source.
        isym   (loop [s "i"]
                 (if (contains? (set (map str pnames)) s)
                   (recur (str s "_"))
                   (symbol s)))
        handle? (some (fn [[_ t]] (#{:own :borrow} t)) (:params sig))
        doc    (cond-> (str "WIT: " (sig-line wit sig))
                 handle? (str "\n  The handle argument must come from this same instance."))]
    (str "(defn " var "\n"
         "  " (pr-str doc) "\n"
         "  [" (str/join " " (cons isym pnames)) "]\n"
         "  ((" isym " " (pr-str wit) ")"
         (apply str (map #(str " " %) pnames)) "))\n")))

(defn- api-hash
  "sha-256 of the described API — the signatures, not the component bytes, so
   a toolchain bump that changes @producers without changing a signature does
   not churn the generated header (0020 A)."
  [described]
  (let [md (MessageDigest/getInstance "SHA-256")
        bs (.getBytes (pr-str (into (sorted-map) described)) "UTF-8")]
    (str/join (map (fn [b] (format "%02x" b)) (.digest md bs)))))

(defn source-for
  "The complete source text for namespace `ns` over `described` — pure, so
   the collision rules and the emitted shape are testable without a
   component. `write-ns!` is the wrapper that reads one."
  [described {:keys [ns source] :as opts}]
  (when-not (symbol? ns)
    (throw (ex-info ":ns must be a symbol" {:cljwit/error :bad-options :cljwit/ns ns})))
  (let [rows     (plan described opts)
        core     (into #{} (keys (ns-publics 'clojure.core)))
        shadows  (into [] (comp (map :var) (filter core)) rows)
        regen    (pr-str (into {:ns ns} (filter val (select-keys opts [:interface :rename :dir]))))]
    (str ";; Generated by cljwit.host.gen (doc/design/0020). Do not edit by hand.\n"
         (when source (str ";; source: " source "\n"))
         ";; exports-hash: sha256:" (api-hash (into {} (map (juxt :wit :sig)) rows)) "\n"
         ";; regenerate: (cljwit.host.gen/write-ns! \"" (or source "<component.wasm>") "\" '" regen ")\n"
         "(ns " ns "\n"
         "  \"Exports of a Wasm component, one var per export; the instance is the\n"
         "   first argument (doc/design/0020). cljwit.host/unwrap is the result sugar.\""
         (when (seq shadows)
           (str "\n  (:refer-clojure :exclude [" (str/join " " (sort-by str shadows)) "])"))
         ")\n\n"
         (str/join "\n" (map emit-fn rows)))))

(defn write-ns!
  "Reads `wasm` (a path), generates the namespace `(:ns opts)` and writes it
   under `(:dir opts)` (default \"src\"). Returns the written path. Pass
   :engine to reuse one; otherwise a private engine lives for this call."
  [wasm {:keys [ns dir engine] :or {dir "src"} :as opts}]
  (let [gen (fn [e]
              (with-open [a (host/compile e (io/file (str wasm)))]
                (source-for (host/describe a) (assoc opts :source (str wasm)))))
        src (if engine
              (gen engine)
              (with-open [e (host/engine)] (gen e)))
        f   (io/file dir (str (-> (str ns)
                                  (str/replace "-" "_")
                                  (str/replace "." java.io.File/separator))
                              ".clj"))]
    (io/make-parents f)
    (spit f src)
    (str f)))

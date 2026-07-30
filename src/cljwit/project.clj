(ns cljwit.project
  "Reads `cljwit.edn` — the project file (`doc/design/0021`) — and reconciles
   the generated namespaces it declares.

       {:components {acme.resize {:wasm \"components/resize.wasm\"}}}

   `sync!` plans everything and then writes; `status` touches nothing and
   reports; `check` is the CI verb — it prints and throws with the drift in
   its message. The `-X` coordinate is provisional until the S2 server
   process is designed."
  (:require [cljwit.host :as host]
            [cljwit.host.gen :as gen]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

(set! *warn-on-reflection* true)

(def ^:private ENTRY-KEYS #{:wasm :dir :interface :rename})

(defn- read-project
  "The parsed file plus its root directory, every shape error collected and
   thrown at once with its provenance — a config file owes the reader which
   entry, not just which mistake."
  [{:keys [file] :or {file "cljwit.edn"}}]
  (let [f (io/file (str file))]
    (when-not (.exists f)
      (throw (ex-info (str "no project file at " (pr-str (str f))
                           " — pass {:file \"path/to/cljwit.edn\"}, or run from the project root")
                      {:cljwit/error :no-project-file :cljwit/file (str f)})))
    (let [cfg (edn/read-string (slurp f))]
      (when-not (map? cfg)
        (throw (ex-info (str f " must hold a map") {:cljwit/error :bad-project})))
      (when-let [bad (seq (remove #{:components} (keys cfg)))]
        (throw (ex-info (str "unknown key(s) in " f ": " (pr-str (vec bad)))
                        {:cljwit/error :bad-project :cljwit/keys (vec bad)})))
      (let [comps (:components cfg)
            ;; This guard must run before anything walks comps: a vector here
            ;; — the rejected first-draft shape, and the likeliest mistake —
            ;; would otherwise surface as a raw nth exception.
            _     (when-not (map? comps)
                    (throw (ex-info ":components must be a map of namespace symbol -> options"
                                    {:cljwit/error :bad-project})))
            errs  (into []
                        (mapcat (fn [[nss opts]]
                                  (cond
                                    (not (simple-symbol? nss))
                                    [(str (pr-str nss) " — a component key is the namespace, a simple symbol")]
                                    (not (map? opts))
                                    [(str nss " — its options must be a map")]
                                    :else
                                    (concat
                                     (when-let [bad (seq (remove ENTRY-KEYS (keys opts)))]
                                       [(str nss " — unknown key(s): " (pr-str (vec bad)))])
                                     (when-not (string? (:wasm opts))
                                       [(str nss " — :wasm must be a path string")])
                                     ;; io/file throws on an absolute child, so
                                     ;; an absolute path here would crash later
                                     ;; without naming its entry. Everything in
                                     ;; this file is root-relative by design
                                     ;; (0021 A) — a committed absolute path is
                                     ;; a machine-specific assumption anyway.
                                     (for [k [:wasm :dir]
                                           :let [v (get opts k)]
                                           :when (and (string? v)
                                                      (.isAbsolute (io/file v)))]
                                       (str nss " — " k " must be relative to cljwit.edn: "
                                            (pr-str v)))))))
                        comps)]
        (when (seq errs)
          (throw (ex-info (str "cljwit.edn: " (str/join "; " errs))
                          {:cljwit/error :bad-project :cljwit/errors errs})))
        {:root (or (.getParentFile (.getAbsoluteFile f)) (io/file "."))
         :file (str f)
         :components comps}))))

(defn- out-file ^File [root dir nss]
  (.getCanonicalFile
   (io/file root (or dir "src")
            (str (-> (str nss) (str/replace "-" "_") (str/replace "." File/separator))
                 ".clj"))))

(defn- plan
  "Every entry realised to {:ns :file :dir :src}, nothing written. Pure
   failures (paths, duplicate outputs) are collected before an engine
   exists; generation failures are collected across entries after. Either
   way the throw carries every failure, each naming its entry."
  [{:keys [root components]}]
  (let [entries (mapv (fn [[nss opts]]
                        {:ns nss :opts opts
                         :file (out-file root (:dir opts) nss)
                         :dir (or (:dir opts) "src")})
                      (sort-by (comp str key) components))
        by-file (group-by (comp str :file) entries)
        dupes   (filter (fn [[_ es]] (< 1 (count es))) by-file)
        missing (remove (fn [{:keys [opts]}] (.exists (io/file root (str (:wasm opts))))) entries)
        pure-errs (concat
                   (map (fn [[f es]]
                          (str (str/join " and " (map :ns es)) " both generate " f
                               " — namespaces that differ only in -/_ share a file"))
                        dupes)
                   (map (fn [{:keys [ns opts]}]
                          (str ns " — no such wasm: " (pr-str (str (:wasm opts)))))
                        missing))]
    (when (seq pure-errs)
      (throw (ex-info (str "cljwit.edn: " (str/join "; " pure-errs))
                      {:cljwit/error :bad-project :cljwit/errors (vec pure-errs)})))
    (with-open [e (host/engine)]
      (let [realised
            (mapv (fn [{:keys [ns opts] :as entry}]
                    (try
                      (assoc entry :src
                             (with-open [a (host/compile e (io/file root (str (:wasm opts))))]
                               (gen/source-for (host/describe a)
                                               (-> (select-keys opts [:interface :rename])
                                                   (assoc :ns ns
                                                          :source (str (:wasm opts))
                                                          :regenerate "(cljwit.project/sync!)")))))
                      (catch Exception ex
                        (assoc entry :error ex))))
                  entries)
            errs (filter :error realised)]
        (when (seq errs)
          (throw (ex-info (str "cljwit.edn: "
                               (str/join "; " (map (fn [{:keys [ns ^Exception error]}]
                                                     (str ns " — " (ex-message error)))
                                                   errs)))
                          {:cljwit/error :project-errors
                           :cljwit/failures (mapv (fn [{:keys [ns error]}]
                                                    {:ns ns :error (ex-data error)})
                                                  errs)})))
        realised))))

(defn sync!
  "Regenerates every declared namespace — planned in full first, so a bad
   entry aborts before the first byte is written. Returns
   {:wrote [...] :unchanged [...]}."
  ([] (sync! nil))
  ([opts]
   (let [entries (plan (read-project (or opts {})))]
     (reduce (fn [acc {:keys [^File file src]}]
               (if (and (.exists file) (= src (slurp file)))
                 (update acc :unchanged conj (str file))
                 (do (io/make-parents file)
                     (spit file src)
                     (update acc :wrote conj (str file)))))
             {:wrote [] :unchanged []}
             entries))))

(defn- hash-line [s]
  (first (filter #(str/starts-with? % ";; exports-hash:") (str/split-lines s))))

(defn- generated? [^File f]
  (with-open [r (io/reader f)]
    (str/starts-with? (or (first (line-seq r)) "")
                      ";; Generated by cljwit.host.gen")))

(defn status
  "Touches nothing. {:ok [...] :stale [...] :modified [...] :missing [...]
   :orphans [...]} — :stale means the component's API changed (the
   exports-hash line differs: regenerate); :modified means the API did not
   (a hand edit, a changed option, a newer generator, or a direct
   write-ns! over a sync!-managed file — the generator: and regenerate:
   lines say which); :orphans are generated files no entry claims, found
   under the :dirs current entries configure plus the default \"src\" —
   so removing the *last* entry of a custom :dir also removes that dir
   from view, a recorded limit (0021 B). Nothing is deleted."
  ([] (status nil))
  ([opts]
   (let [proj    (read-project (or opts {}))
         entries (plan proj)
         claimed (into #{} (map (comp str :file)) entries)
         base    (reduce (fn [acc {:keys [^File file src]}]
                           (cond
                             (not (.exists file))
                             (update acc :missing conj (str file))
                             (= src (slurp file))
                             (update acc :ok conj (str file))
                             ;; No hash line at all is an edit, not a changed
                             ;; component — :stale must only ever mean "the
                             ;; API moved".
                             (and (some? (hash-line (slurp file)))
                                  (not= (hash-line src) (hash-line (slurp file))))
                             (update acc :stale conj (str file))
                             :else
                             (update acc :modified conj (str file))))
                         {:ok [] :stale [] :modified [] :missing [] :orphans []}
                         entries)
        ;; distinct twice over: "src" is always scanned, so a custom :dir
        ;; under it would visit the same file through both roots.
         orphans (distinct
                  (for [d     (distinct (cons "src" (map :dir entries)))
                        ^File f (file-seq (io/file (:root proj) (str d)))
                        :when (and (.isFile f)
                                   (str/ends-with? (.getName f) ".clj")
                                   (not (claimed (str (.getCanonicalFile f))))
                                   (generated? f))]
                    (str (.getCanonicalFile f))))]
     (update base :orphans into orphans))))

(defn check
  "The CI verb: `status`, printed, thrown if anything is not :ok. The drift
   is enumerated in the exception message because `-X` shows a caller only
   ex-message — ex-data lands in a temp-file report CI discards (0021 B)."
  ([] (check nil))
  ([opts]
   (let [st (status opts)
         problems (mapcat (fn [k] (map (fn [f] (str (name k) ": " f)) (get st k)))
                          [:stale :modified :missing :orphans])]
     (run! println problems)
     (when (seq problems)
       (throw (ex-info (str "cljwit.edn drift — " (str/join "; " problems))
                       {:cljwit/error :drift :cljwit/status st})))
     (println (str (count (:ok st)) " component namespace(s) in sync"))
     st)))

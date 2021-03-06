(ns cider.nrepl.middleware.info
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.javadoc :as javadoc]
            [orchard.classloader :refer [class-loader]]
            [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
            [orchard.java :as java]
            [orchard.misc :as u]
            [orchard.meta :as m]
            [cljs-tooling.info :as cljs-info]
            [orchard.spec :as spec]))

(defn- resource-full-path [relative-path]
  (io/resource relative-path (class-loader)))

(def see-also-data
  (edn/read-string (slurp (io/resource "see-also.edn"))))

(defn info-clj
  [ns sym]
  (or
   ;; it's a special (special-symbol?)
   (m/special-sym-meta sym)
   ;; it's a var
   (m/var-meta (m/resolve-var ns sym))
   ;; sym is an alias for another ns
   (m/ns-meta (get (m/resolve-aliases ns) sym))
   ;; it's simply a full ns
   (m/ns-meta (find-ns sym))
   ;; it's a Java class/member symbol...or nil
   (java/resolve-symbol ns sym)))

(defn info-cljs
  [env symbol ns]
  (some-> (cljs-info/info env symbol ns)
          (select-keys [:file :line :ns :doc :column :name :arglists])
          (update
           :file
           (fn [f]
             (if (u/boot-project?)
               ;; Boot stores files in a temporary directory & ClojureScript
               ;; stores the :file metadata location absolutely instead of
               ;; relatively to the classpath. This means when doing jump to
               ;; source in Boot & ClojureScript, you end up at the temp file.
               ;; This code attempts to find the classpath-relative location
               ;; of the file, so that it can be opened correctly.
               (let [path (java.nio.file.Paths/get f (into-array String []))
                     path-count (.getNameCount path)]
                 (or
                  (first
                   (sequence
                    (comp (map #(.subpath path % path-count))
                          (map str)
                          (filter io/resource))
                    (range path-count)))
                  f))
               f)))))

(defn info-java
  [class member]
  (java/member-info class member))

(defn info
  [{:keys [ns symbol class member] :as msg}]
  (let [[ns symbol class member] (map u/as-sym [ns symbol class member])]
    (if-let [cljs-env (cljs/grab-cljs-env msg)]
      (info-cljs cljs-env symbol ns)
      (let [var-info (cond (and ns symbol) (info-clj ns symbol)
                           (and class member) (info-java class member)
                           :else (throw (Exception.
                                         "Either \"symbol\", or (\"class\", \"member\") must be supplied")))
            var-key  (str (:ns var-info) "/" (:name var-info))
            see-also (->> (get see-also-data var-key)
                          (filter (comp resolve u/as-sym)))]
        (if (seq see-also)
          (merge {:see-also see-also} var-info)
          var-info)))))

(defn resource-path
  "If it's a resource, return a tuple of the relative path and the full resource path."
  [x]
  (or (if-let [full (resource-full-path x)]
        [x full])
      (if-let [[_ relative] (re-find #".*jar!/(.*)" x)]
        (if-let [full (resource-full-path relative)]
          [relative full]))
      ;; handles load-file on jar resources from a cider buffer
      (if-let [[_ relative] (re-find #".*jar:(.*)" x)]
        (if-let [full (resource-full-path relative)]
          [relative full]))))

(defn file-path
  "For a file path, return a URL to the file if it exists and does not
  represent a form evaluated at the REPL."
  [x]
  (when (seq x)
    (let [f (io/file x)]
      (when (and (.exists f)
                 (not (-> f .getName (.startsWith "form-init"))))
        (io/as-url f)))))

(defn file-info
  [path]
  (let [[resource-relative resource-full] (resource-path path)]
    (merge {:file (or (file-path path) resource-full path)}
           ;; Classpath-relative path if possible
           (if resource-relative
             {:resource resource-relative}))))

(defn javadoc-info
  "Resolve a relative javadoc path to a URL and return as a map. Prefer javadoc
  resources on the classpath; then use online javadoc content for core API
  classes. If no source is available, return the relative path as is."
  [path]
  {:javadoc
   (or (resource-full-path path)
       ;; [bug#308] `*remote-javadocs*` is outdated WRT Java
       ;; 8, so we try our own thing first.
       (when (re-find #"^(java|javax|org.omg|org.w3c.dom|org.xml.sax)/" path)
         (format "http://docs.oracle.com/javase/%s/docs/api/%s"
                 u/java-api-version path))
       ;; If that didn't work, _then_ we fallback on `*remote-javadocs*`.
       (some (let [classname (.replaceAll path "/" ".")]
               (fn [[prefix url]]
                 (when (.startsWith classname prefix)
                   (str url path))))
             @javadoc/*remote-javadocs*)
       path)})

(javadoc/add-remote-javadoc "com.amazonaws." "http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/")
(javadoc/add-remote-javadoc "org.apache.kafka." "https://kafka.apache.org/090/javadoc/index.html?")

(declare format-response)

(defn format-nested
  "Apply response formatting to nested `:candidates` info for Java members."
  [info]
  (if-let [candidates (:candidates info)]
    (assoc info :candidates
           (zipmap (keys candidates)
                   (->> (vals candidates) (map format-response))))
    info))

(defn blacklist
  "Remove anything that might contain arbitrary EDN, metadata can hold anything"
  [info]
  (let [blacklisted #{:arglists :forms}]
    (apply dissoc info blacklisted)))

(defn format-response
  [info]
  (letfn [(forms-join [forms]
            (->> (map pr-str forms)
                 (str/join \newline)))]
    (when info
      (-> info
          (merge (when-let [ns (:ns info)]
                   {:ns (str ns)})
                 (when-let [args (:arglists info)]
                   {:arglists-str (forms-join args)})
                 (when-let [forms (:forms info)]
                   {:forms-str (forms-join forms)})
                 (when-let [file (:file info)]
                   (file-info file))
                 (when-let [path (:javadoc info)]
                   (javadoc-info path)))
          format-nested
          blacklist
          u/transform-value))))

(defn info-reply
  [msg]
  (if-let [var-info (format-response (info msg))]
    var-info
    {:status :no-info}))

(defn extract-arglists
  [info]
  (cond
    (:special-form info) (->> (:forms info)
                              ;; :forms contains a vector of sequences or symbols
                              ;; which we have to convert the format employed by :arglists
                              (map #(if (coll? %) (vec %) (vector %))))
    (:candidates info) (->> (:candidates info)
                            vals
                            (mapcat :arglists)
                            distinct
                            (sort-by count))
    :else (:arglists info)))

(defn format-arglists [raw-arglists]
  (map #(mapv str %) raw-arglists))

(defn extract-ns-or-class
  [{:keys [ns class candidates] :as info}]
  (cond
    ns {:ns (str ns)}
    class {:class [(str class)]}
    candidates {:class (map key candidates)}))

(defn extract-name-or-member
  [{:keys [name member candidates]}]
  (cond
    name {:name (str name)}
    member {:member (str member)}
    candidates {:member (->> candidates vals (map :member) first str)}))

(defn extract-eldoc
  [info]
  (if-let [arglists (seq (-> info extract-arglists format-arglists))]
    {:eldoc arglists :type "function"}
    {:type "variable"}))

(defn eldoc-reply
  [msg]
  (if-let [info (info msg)]
    (merge (extract-ns-or-class info)
           (extract-name-or-member info)
           (extract-eldoc info)
           {:docstring (:doc info)})
    {:status :no-eldoc}))

(defn eldoc-datomic-query-reply
  [msg]
  (try
    (let [ns (read-string (:ns msg))
          sym (read-string (:symbol msg))
          query (if (symbol? sym)
                  (deref (ns-resolve ns sym))
                  (eval sym))
          inputs (if (map? query)
                   ;; query as map
                   (or (:in query) "$")
                   ;; query as vector
                   (let [partitioned (partition-by keyword? query)
                         index (.indexOf partitioned '(:in))]
                     (if (= index -1)
                       "$"
                       (nth partitioned (+ 1 index)))))]
      {:inputs (format-arglists [inputs])})
    (catch Throwable _ {:status :no-eldoc})))

(defn handle-info [handler msg]
  (with-safe-transport handler msg
    "info" info-reply
    "eldoc" eldoc-reply
    "eldoc-datomic-query" eldoc-datomic-query-reply))

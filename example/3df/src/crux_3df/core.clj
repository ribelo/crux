(ns crux-3df.core
  (:require
   [clj-3df.core :as df :use [exec!]]
   [clj-3df.attribute :as attribute]
   [crux.api :as api]
   [crux.db :as crux-db]
   [crux.codec :as crux-codec]
   [crux.memory :as crux-memory]
   [crux.decorators.core :as crux-decorators]))

(def index-dir "data/db-dir")
(def log-dir "data/eventlog")

(def crux-options
  {:kv-backend "crux.kv.rocksdb.RocksKv"
   :bootstrap-servers "kafka-cluster-kafka-brokers.crux.svc.cluster.local:9092"
   :event-log-dir log-dir
   :db-dir index-dir})

(extend-protocol crux-codec/IdToBuffer
  Long
  (id->buffer [^Long this to] (java.nio.ByteBuffer/allocate Long/BYTES)
    (crux-codec/id-function
      to (.array (doto (java.nio.ByteBuffer/allocate Long/BYTES)
                   (.putLong this))))))

(defn validate-schema!
  [crux schema tx-ops]
  (doseq [[tx-op a b] tx-ops]
    (case tx-op
      :crux.tx/put (do (assert (instance? Long a))
                       (assert (map? b))
                       (doseq [[k v] (dissoc b :crux.db/id)
                               :let [value-type (-> schema k :db/valueType)]]
                         (assert (contains? schema k))
                         (case value-type
                           :String (assert (string? v))))))))

(defn crux-3df-decorator
  [conn schema]
  (crux-decorators/system-decorator
    {#'api/submit-tx (fn [crux tx-ops]
                       (validate-schema! crux schema tx-ops)
                       (api/submit-tx crux tx-ops))}))

(defn index-to-3df
  [conn db crux tx-ops tx-time tx-id]
  (let [crux-db (api/db crux)]
    (with-open [snapshot (api/new-snapshot crux-db)]
      (let [new-transaction
            (reduce
              (fn [acc [op-key a b]]
                (case op-key
                  :crux.tx/put (do
                                 ;; TODO load whatever the previus document is
                                 (let [new-doc (api/document crux b)
                                       eid (:crux.db/id new-doc)
                                       old-doc (some-> (api/history-descending crux-db snapshot (:crux.db/id new-doc))
                                                       second :crux.db/doc)]
                                   (into
                                     acc
                                     (for [k (set (concat
                                                    (keys new-doc)
                                                    (keys old-doc)))
                                           :when (not= k :crux.db/id)]
                                       (if (contains? new-doc k)
                                         [:db/add eid k (get new-doc k)]
                                         [:db/retract eid k (get old-doc k)])))))))
              []
              tx-ops)]
        (println "3DF: " new-transaction)
        @(exec! conn (df/transact db new-transaction))))))

(defn crux-3df-system
  [conn db schema bootstrap-fn options with-system-fn]
  (let [crux-ref (atom nil)]
    (with-open [^java.io.Closeable crux
                (bootstrap-fn
                  (assoc-in options
                            [:crux/hooks :crux.tx/post-index-hook]
                            (fn [tx-ops tx-time tx-id]
                              (try
                                (println "HOOK CALLED?")
                                (index-to-3df conn db @crux-ref tx-ops tx-time tx-id)
                                (catch Throwable t
                                  (println "ERROR " t))))))]
      (println "DOES NOT GET HERE??? 1")
      (reset! crux-ref ((crux-3df-decorator conn schema) crux))

      (println "DOES NOT GET HERE??? 2")
      (with-system-fn @crux-ref))))

(def schema
  {:user/name (merge
                (attribute/of-type :String)
                (attribute/input-semantics :db.semantics.cardinality/one)
                (attribute/tx-time))

   :user/email (merge
                 (attribute/of-type :String)
                 (attribute/input-semantics :db.semantics.cardinality/one)
                 (attribute/tx-time))})

(def conn (df/create-debug-conn! "ws://127.0.0.1:6262"))
(def db (df/create-db schema))

(def system
  (future
    (crux-3df-system
      conn
      db
      schema
      api/start-standalone-system
      crux-options
      (fn [c]
        (def crux c)
        (Thread/sleep Long/MAX_VALUE)))))

(comment

  @system

  (future-cancel system)

  (api/submit-tx
    crux
    [[:crux.tx/put
      1
      {:crux.db/id 1
       :user/name "Patrik"
       :user/email "p@p.com"}]])

  (api/submit-tx
    crux
    [[:crux.tx/put
      1
      {:crux.db/id 1
       :user/name "Patrik"}]])


  (exec! conn (df/create-db-inputs db))

  (exec! conn
         (df/query
           db "patrik-email"
           '[:find ?email
             :where
             [?patrik :user/name "Patrik"]
             [?patrik :user/email ?email]]))

  (df/listen-query!
    conn
    "patrik-email"
    (fn [& message]
      (println "QUERY BACK: " message)))


  )
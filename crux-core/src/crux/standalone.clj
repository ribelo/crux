(ns crux.standalone
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [crux.kv :as kv]
            [crux.moberg :as moberg]
            [crux.node :as n]
            [crux.tx.polling :as p])
  (:import java.io.Closeable))

(s/def ::event-log-sync-interval-ms nat-int?)
(s/def ::event-log-dir string?)

(defn- start-event-log-fsync ^java.io.Closeable [{::keys [event-log-kv]}
                                                 {:keys [crux.standalone/event-log-sync-interval-ms]}]
  (log/debug "Using event log fsync interval ms:" event-log-sync-interval-ms)
  (let [running? (atom true)
        fsync-thread (when event-log-sync-interval-ms
                       (doto (Thread. #(while @running?
                                         (try
                                           (Thread/sleep event-log-sync-interval-ms)
                                           (kv/fsync event-log-kv)
                                           (catch Throwable t
                                             (log/error t "Event log fsync threw exception:"))))
                                      "crux.tx.event-log-fsync-thread")
                         (.start)))]
    (reify Closeable
      (close [_]
        (reset! running? false)
        (some-> fsync-thread (.join))))))

(defn- start-event-log-kv [_ {:keys [crux.standalone/event-log-kv
                                     crux.standalone/event-log-sync-interval-ms
                                     crux.standalone/event-log-dir
                                     crux.standalone/event-log-sync?]}]
  (let [event-log-sync? (boolean (or event-log-sync? (not event-log-sync-interval-ms)))
        options {:crux.kv/db-dir event-log-dir
                 :crux.kv/sync? event-log-sync?
                 :crux.kv/check-and-store-index-version false}]
    (n/start-module event-log-kv nil options)))

(defn- start-event-log-consumer [{:keys [crux.standalone/event-log-kv crux.node/indexer]} _]
  (when event-log-kv
    (p/start-event-log-consumer indexer
                                (moberg/map->MobergEventLogConsumer {:event-log-kv event-log-kv
                                                                     :batch-size 100}))))

(defn- start-moberg-event-log [{::keys [event-log-kv]} _]
  (moberg/->MobergTxLog event-log-kv))

(def topology (merge n/base-topology
                     {::event-log-kv [start-event-log-kv
                                      []
                                      (s/keys :req [::event-log-dir
                                                    ::event-log-kv]
                                              :opt [::event-log-sync?
                                                    ::event-log-sync-interval-ms])
                                      {::event-log-kv
                                       {:doc "Key/Value store to use for standalone event-log persistence."
                                        :default 'crux.kv.rocksdb/kv}
                                       ::event-log-dir
                                       {:doc "Directory used to store the event-log and used for backup/restore."}
                                       ::event-log-sync?
                                       {:doc "Sync the event-log backed KV store to disk after every write."
                                        :default false}}]
                      ::event-log-sync [start-event-log-fsync
                                        [::event-log-kv]
                                        (s/keys :opt [::event-log-sync-interval-ms])]
                      ::event-log-consumer [start-event-log-consumer [::event-log-kv :crux.node/indexer]]
                      :crux.node/tx-log [start-moberg-event-log [::event-log-kv]]}))

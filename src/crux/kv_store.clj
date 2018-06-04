(ns crux.kv-store
  (:import [java.io Closeable]))

(defprotocol KvIterator
  (-seek [this k])
  (-next [this]))

(defprotocol KvSnapshot
  (^java.io.Closeable new-iterator [this]))

(defprotocol KvStore
  (open [this])
  (^java.io.Closeable new-snapshot [this])
  (store [this kvs])
  (delete [this ks])
  (backup [this dir]))

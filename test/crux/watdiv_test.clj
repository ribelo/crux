(ns crux.watdiv-test
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk :as w]
            [crux.db :as db]
            [crux.index :as idx]
            [crux.io :as cio]
            [crux.kv :as kv]
            [crux.tx :as tx]
            [crux.lru :as lru]
            [crux.rdf :as rdf]
            [crux.query :as q]
            [crux.sparql :as sparql]
            [crux.kafka :as k]
            [crux.fixtures :as f]
            [datomic.api :as d])
  (:import java.io.StringReader
           org.eclipse.rdf4j.repository.sail.SailRepository
           org.eclipse.rdf4j.repository.RepositoryConnection
           org.eclipse.rdf4j.sail.nativerdf.NativeStore
           org.eclipse.rdf4j.rio.RDFFormat
           org.eclipse.rdf4j.query.Binding))

;; See:
;; https://dsg.uwaterloo.ca/watdiv/
;; https://pdfs.semanticscholar.org/eaed/4750e90109f6efaf01aa9fa914636e9366b5.pdf

;; Needs the following files downloaded and unpacked under test/watdiv
;; in the project root:

;; https://dsg.uwaterloo.ca/watdiv/watdiv.10M.tar.bz2
;; https://dsg.uwaterloo.ca/watdiv/stress-workloads.tar.gz

;; First test run:

;; WatDiv 10M:
;; wc -l test/watdiv/watdiv.10M.nt
;; 10916457 test/watdiv/watdiv.10M.nt
;; du -hs test/watdiv/watdiv.10M.nt
;; 1.5G	test/watdiv/watdiv.10M.nt

;; Ingest:
;; "Elapsed time: 136125.904116 msecs"
;; du -hs /tmp/kafka-log* /tmp/kv-store*
;; 672M	/tmp/kafka-log1198983040100044874
;; 192M	/tmp/kv-store1625659699196661317

;; Query:
;; wc -l test/watdiv/watdiv-stress-100/test.1.sparql
;; 12400 test/watdiv/watdiv-stress-100/test.1.sparql

;; "Elapsed time: 2472368.881591 msecs"
;; Tested 1 namespaces
;; Ran 12401 assertions, in 1 test functions
;; 518 errors

;; Second test run, with -Xmss10M, from lein:
;; "Elapsed time: 3255993.121931 msecs"
;; Ran 1 tests containing 12401 assertions.
;; 0 failures, 186 errors.

;; Thrid test run, with -Xss32M, from lein trampoline:
;; lein test crux.watdiv-test
;; "Elapsed time: 130545.461865 msecs"
;; "Elapsed time: 3729324.4121 msecs"

;; Ran 1 tests containing 12401 assertions.
;; 0 failures, 0 errors.

;; Forth test run, with waiting for indexing to catch up:
;; "Elapsed time: 271361.052728 msecs"
;; du -hs /tmp/kafka-* /tmp/kv-store*
;; 671M    /tmp/kafka-log14363302893017472464
;; 1.4G    /tmp/kv-store3281879845440675012

;; First 4 queries match Sail's counts, the 5 times out in Sail (and
;; takes forever in Crux).

(def ^:const watdiv-triples-resource "watdiv/watdiv.10M.nt")
(def ^:const watdiv-num-queries nil)
(def ^:const watdiv-indexes nil)

(def run-watdiv-tests? (and (boolean (System/getenv "CRUX_WATDIV"))
                            (boolean (io/resource watdiv-triples-resource))))

(def crux-tests? (boolean (System/getenv "CRUX_WATDIV_RUN_CRUX")))
(def datomic-tests? (boolean (System/getenv "CRUX_WATDIV_RUN_DATOMIC")))
(def sail-tests? (boolean (System/getenv "CRUX_WATDIV_RUN_SAIL")))

(def query-timeout-ms 15000)

;; Datomic

(defn entity->datomic [e]
  (let [id (:crux.db/id e)
        tx-op-fn (fn tx-op-fn [k v]
                   (if (set? v)
                     (vec (mapcat #(tx-op-fn k %) v))
                     [[:db/add id k v]]))]
    (->> (for [[k v] (dissoc e :crux.db/id)]
           (tx-op-fn k v))
         (apply concat)
         (vec))))

(defn entity->idents [e]
  (cons
   {:db/ident (:crux.db/id e)}
   (for [[_ v] e
         v (idx/normalize-value v)
         :when (keyword? v)]
     {:db/ident v})))

(def datomic-tx-size 100)

(defn load-rdf-into-datomic [conn resource]
  (with-open [in (io/input-stream (io/resource resource))]
    (->> (rdf/ntriples-seq in)
         (rdf/statements->maps)
         (map #(rdf/use-default-language % rdf/*default-language*))
         (partition-all datomic-tx-size)
         (reduce (fn [^long n entities]
                   (when (zero? (long (mod n rdf/*ntriples-log-size*)))
                     (log/debug "submitted" n))
                   @(d/transact conn (mapcat entity->idents entities))
                   @(d/transact conn (->> (map entity->datomic entities)
                                          (apply concat)
                                          (vec)))
                   (+ n (count entities)))
                 0))))

(declare datomic-watdiv-schema)

(def datomic-uri-base (or (System/getenv "CRUX_WATDIV_DATOMIC_URI") "datomic:mem://"))
(def ^:dynamic *datomic-conn*)

(defn with-datomic [f]
  (let [uri (str datomic-uri-base (d/squuid))]
    (try
      (d/delete-database uri)
      (d/create-database uri)
      (binding [*datomic-conn* (d/connect uri)]
        @(d/transact *datomic-conn* datomic-watdiv-schema)
        (f))
      (finally
        (d/delete-database uri)))))

;; Sail

(def max-sparql-query-time-seconds (quot query-timeout-ms 1000))

(defn execute-sparql [^RepositoryConnection conn q]
  (with-open [tq (.evaluate (doto (.prepareTupleQuery conn q)
                              (.setMaxExecutionTime max-sparql-query-time-seconds)))]
    (set ((fn step []
            (when (.hasNext tq)
              (cons (mapv #(rdf/rdf->clj (.getValue ^Binding %))
                          (.next tq))
                    (lazy-seq (step)))))))))

(defn load-rdf-into-sail [^RepositoryConnection conn resource]
  (with-open [in (io/input-stream (io/resource resource))]
    (->> (partition-all rdf/*ntriples-log-size* (line-seq (io/reader in)))
         (reduce (fn [n chunk]
                   (log/debug "submitted" n)
                   (.add conn (StringReader. (str/join "\n" chunk)) "" RDFFormat/NTRIPLES rdf/empty-resource-array)
                   (+ n (count chunk)))
                 0))))

(def ^:dynamic *sail-conn*)

(defn with-sail-repository [f]
  (let [db-dir (str (cio/create-tmpdir "sail-store"))
        db (SailRepository. (NativeStore. (io/file db-dir)))]
    (try
      (.initialize db)
      (with-open [conn (.getConnection db)]
        (binding [*sail-conn* conn]
          (f)))
      (finally
        (.shutDown db)
        (cio/delete-dir db-dir)))))

;; Crux

;; TODO: This query returns 0 results in Crux, should return 117:
;; {:idx 91
;; :query "SELECT * WHERE {  ?v0 <http://db.uwaterloo.ca/~galuc/wsdbm/gender> <http://db.uwaterloo.ca/~galuc/wsdbm/Gender1> .  ?v0 <http://purl.org/dc/terms/Location> ?v1 .  ?v0 <http://db.uwaterloo.ca/~galuc/wsdbm/follows> ?v0 .  ?v0 <http://db.uwaterloo.ca/~galuc/wsdbm/userId> ?v5 .  ?v1 <http://www.geonames.org/ontology#parentCountry> ?v2 .  ?v3 <http://purl.org/ontology/mo/performed_in> ?v1 .  }"
;; :crux-results 0
;; :crux-time 40078}

(defn load-rdf-into-crux [resource]
  (let [tx-topic "test-can-run-watdiv-tx-queries"
        doc-topic "test-can-run-watdiv-doc-queries"
        tx-log (k/->KafkaTxLog f/*producer* tx-topic doc-topic {})
        object-store (lru/new-cached-object-store f/*kv*)
        indexer (tx/->KvIndexer f/*kv* tx-log object-store)]

    (k/create-topic f/*admin-client* tx-topic 1 1 k/tx-topic-config)
    (k/create-topic f/*admin-client* doc-topic 1 1 k/doc-topic-config)
    (k/subscribe-from-stored-offsets indexer f/*consumer* [tx-topic doc-topic])
    (let [submit-future (future
                          (with-open [in (io/input-stream (io/resource resource))]
                            (rdf/submit-ntriples tx-log in 1000)))
          consume-args {:indexer indexer
                        :consumer f/*consumer*
                        :tx-topic tx-topic
                        :doc-topic doc-topic}]
      (k/consume-and-index-entities consume-args)
      (while (not= {:txs 0 :docs 0}
                   (k/consume-and-index-entities
                    (assoc consume-args :timeout 100))))
      (t/is (= 521585 @submit-future))
      (tx/await-no-consumer-lag indexer {:crux.tx-log/await-tx-timeout 60000}))))

(defn with-watdiv-data [f]
  (if run-watdiv-tests?
    (do (when datomic-tests?
          (println "Loading into Datomic...")
          (time
           (load-rdf-into-datomic *datomic-conn* watdiv-triples-resource)))

        ;; "Elapsed time: 305376.165167 msecs" 767Mb
        (when sail-tests?
          (println "Loading into Sail...")
          (time
           (load-rdf-into-sail *sail-conn* watdiv-triples-resource)))

        (when crux-tests?
          (println "Loading into Crux...")
          (time
           (load-rdf-into-crux watdiv-triples-resource)))

        (f))
    (f)))

(defn lazy-count-with-timeout [kv q timeout-ms]
  (let [query-future (future
                       (with-open [snapshot (kv/new-snapshot kv)]
                         (count (q/q (q/db kv) snapshot q))))]
    (or (deref query-future timeout-ms nil)
        (do (future-cancel query-future)
            (throw (IllegalStateException. "Query timed out."))))))

(t/use-fixtures :once f/with-embedded-kafka-cluster f/with-kafka-client with-sail-repository with-datomic f/with-kv-store with-watdiv-data)

;; TODO: What do the numbers in the .desc file represent? They all
;; add up to the same across test runs, so cannot be query
;; times. Does not seem to be result size either.
(t/deftest watdiv-stress-test-1
  (if run-watdiv-tests?
    (time
     (with-open [desc-in (io/reader (io/resource "watdiv/watdiv-stress-100/test.1.desc"))
                 sparql-in (io/reader (io/resource "watdiv/watdiv-stress-100/test.1.sparql"))
                 out (io/writer (io/file (format "target/watdiv_%s.edn" (System/currentTimeMillis))))]
       (.write out "[\n")
       (doseq [[idx [d q]] (->> (cond->> (map vector (line-seq desc-in) (line-seq sparql-in))
                                  watdiv-num-queries (take watdiv-num-queries))
                                (map-indexed vector))
               :when (or (nil? watdiv-indexes)
                         (contains? watdiv-indexes idx))]
         (.write out "{")
         (.write out (str ":idx " (pr-str idx) "\n"))
         (.write out (str ":query " (pr-str q) "\n"))
         (when crux-tests?
           (let [start-time (System/currentTimeMillis)]
             (t/is (try
                     (.write out (str ":crux-results " (lazy-count-with-timeout f/*kv* (sparql/sparql->datalog q) query-timeout-ms)
                                      "\n"))
                     true
                     (catch Throwable t
                       (.write out (str ":crux-error " (pr-str (str t)) "\n"))
                       (throw t)))
                   idx)
             (.write out (str ":crux-time " (pr-str (-  (System/currentTimeMillis) start-time))))))

         (when sail-tests?
           (let [start-time (System/currentTimeMillis)]
             (t/is (try
                     (.write out (str ":sail-results " (pr-str (count (execute-sparql *sail-conn* q)))
                                      "\n"))
                     true
                     (catch Throwable t
                       (.write out (str ":sail-error " (pr-str (str t)) "\n"))
                       (throw t)))
                   idx)
             (.write out (str ":sail-time " (pr-str (-  (System/currentTimeMillis) start-time))))))

         (when datomic-tests?
           (let [start-time (System/currentTimeMillis)]
             (t/is (try
                     (.write out (str ":datomic-results " (pr-str (count (d/query {:query (sparql/sparql->datalog q)
                                                                                   :timeout query-timeout-ms
                                                                                   :args [(d/db *datomic-conn*)]})))
                                      "\n"))
                     true
                     (catch Throwable t
                       (.write out (str ":datomic-error " (pr-str (str t)) "\n"))
                       (throw t)))
                   idx)
             (.write out (str ":datomic-time " (pr-str (-  (System/currentTimeMillis) start-time))))))

         (.write out "}\n")
         (.flush out))
       (.write out "]")))
    (t/is true "skipping")))

(t/deftest sail-sanity-check
  (if (and run-watdiv-tests? sail-tests?)
    (t/is true "skipping")
    (with-sail-repository
      (fn []
        (load-rdf-into-sail *sail-conn* "crux/example-data-artists.nt")
        (t/is (= 2 (count (execute-sparql *sail-conn* "
PREFIX ex: <http://example.org/>
PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?s ?n
WHERE
{
   ?s a ex:Artist;
     foaf:firstName ?n.
}"))))))))

;; See: https://dsg.uwaterloo.ca/watdiv/watdiv-data-model.txt
;; Some things like dates are strings in the actual data.
(def datomic-watdiv-schema
  [#:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://db.uwaterloo.ca/~galuc/wsdbm/composer")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/many
        :ident (keyword "http://db.uwaterloo.ca/~galuc/wsdbm/follows")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/many
        :ident (keyword "http://db.uwaterloo.ca/~galuc/wsdbm/friendOf")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/one
        :ident (keyword "http://db.uwaterloo.ca/~galuc/wsdbm/gender")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/many
        :ident (keyword "http://db.uwaterloo.ca/~galuc/wsdbm/hasGenre")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://db.uwaterloo.ca/~galuc/wsdbm/hits")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/many
        :ident (keyword "http://db.uwaterloo.ca/~galuc/wsdbm/likes")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/many
        :ident (keyword "http://db.uwaterloo.ca/~galuc/wsdbm/makesPurchase")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://db.uwaterloo.ca/~galuc/wsdbm/purchaseDate")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/one
        :ident (keyword "http://db.uwaterloo.ca/~galuc/wsdbm/purchaseFor")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/many
        :ident (keyword "http://db.uwaterloo.ca/~galuc/wsdbm/subscribes")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://db.uwaterloo.ca/~galuc/wsdbm/userId")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/many
        :ident (keyword "http://ogp.me/ns#tag")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://ogp.me/ns#title")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/dc/terms/Location")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/goodrelations/description")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/goodrelations/includes")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/goodrelations/name")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/many
        :ident (keyword "http://purl.org/goodrelations/offers")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/goodrelations/price")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/goodrelations/serialNumber")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/goodrelations/validFrom")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/goodrelations/validThrough")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/ontology/mo/artist")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/ontology/mo/conductor")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/ontology/mo/movement")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/ontology/mo/opus")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/ontology/mo/performed_in")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/ontology/mo/performer")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/ontology/mo/producer")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/ontology/mo/record_number")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/ontology/mo/release")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/many
        :ident (keyword "http://purl.org/stuff/rev#hasReview")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/stuff/rev#rating")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/stuff/rev#reviewer")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/stuff/rev#text")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/stuff/rev#title")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://purl.org/stuff/rev#totalVotes")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/many
        :ident (keyword "http://schema.org/actor")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/aggregateRating")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/many
        :ident (keyword "http://schema.org/author")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/many
        :ident (keyword "http://schema.org/award")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/birthDate")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/bookEdition")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/caption")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/contactPoint")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/contentRating")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/contentSize")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/datePublished")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/description")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/director")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/duration")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/many
        :ident (keyword "http://schema.org/editor")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/eligibleQuantity")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/many
        :ident (keyword "http://schema.org/eligibleRegion")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/email")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/many
        :ident (keyword "http://schema.org/employee")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/expires")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/faxNumber")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/isbn")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/jobTitle")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/keywords")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/many
        :ident (keyword "http://schema.org/language")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/legalName")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/nationality")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/numberOfPages")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/openingHours")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/paymentAccepted")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/priceValidUntil")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/printColumn")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/printEdition")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/printPage")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/printSection")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/producer")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/publisher")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/telephone")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/text")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/many
        :ident (keyword "http://schema.org/trailer")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/url")}
   #:db{:valueType :db.type/long
        :cardinality :db.cardinality/one
        :ident (keyword "http://schema.org/wordCount")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/one
        :ident (keyword "http://www.geonames.org/ontology#parentCountry")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/many
        :ident (keyword "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/one
        :ident (keyword "http://xmlns.com/foaf/age")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://xmlns.com/foaf/familyName")}
   #:db{:valueType :db.type/string
        :cardinality :db.cardinality/one
        :ident (keyword "http://xmlns.com/foaf/givenName")}
   #:db{:valueType :db.type/ref
        :cardinality :db.cardinality/one
        :ident (keyword "http://xmlns.com/foaf/homepage")}])
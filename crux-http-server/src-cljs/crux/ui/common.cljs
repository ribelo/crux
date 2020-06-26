(ns crux.ui.common
  (:require
   [cljs.reader :as reader]
   [clojure.pprint :as pprint]
   [clojure.string :as string]
   [crux.ui.navigation :as navigation]
   [reitit.frontend :as reitit]
   [reitit.frontend.easy :as rfe]
   [tick.alpha.api :as t]
   [tick.format :as tf]
   [tick.locale-en-us]))

(defn route->url
  "k: page handler i.e. :entity
  params: path-params map
  query: query-params map i.e. {:find '[?eid]'}"
  ([k]
   (route->url k nil nil))
  ([k params]
   (route->url k params nil))
  ([k params query]
   (rfe/href k params query)))

(defn url->route
  "url: abosolute string path i.e. '/_entity?eid=...'"
  [url]
  (reitit/match-by-path (navigation/router) url))

(defn date-time->datetime
  "d: 2020-04-28
  t: 15:45:45.935"
  [d t]
  (when (and (not-empty d) (not-empty t))
    (str (t/date d) "T" (t/time t))))

(defn datetime->date-time
  "dt: 2020-04-28T15:45:45.935"
  [dt]
  (when (not-empty dt)
    {:date (str (t/date dt))
     :time (str (t/time dt))}))

(defn iso-format-datetime
  [dt]
  (when dt
    (t/format (tf/formatter "yyyy-MM-dd'T'HH:mm:ss.SSSXXX") (t/zoned-date-time (t/inst dt)))))

(defn vectorize
  [ks m]
  (map (fn [[k v]]
         (if (and (some #(= k %) ks)
                  (string? v))
           [k (vector v)]
           [k v])) m))

(defn query-params->formatted-edn-string
  [query-params-map]
  (when-let [formatted
             (not-empty
              (try
                (->> (dissoc query-params-map
                             :valid-time :transaction-time)
                     (vectorize [:where :args :order-by])
                     (map (fn [[k v]]
                            (if (vector? v)
                              [k (mapv #(reader/read-string %) v)]
                              [k (reader/read-string v)])))
                     (into {}))
                (catch :default _ {})))]
    (with-out-str
      (pprint/with-pprint-dispatch
        pprint/code-dispatch
        (pprint/pprint formatted)))))

(defn edn->query-params
  [edn]
  (->> edn
       (map
        (fn [[k v]]
          [k (if (or (= :where k) (= :args k) (= :order-by k))
               (mapv str v)
               (str v))]))
       (into {})))

(defn- scroll-top []
  (set! (.. js/document -body -scrollTop) 0)
  (set! (.. js/document -documentElement -scrollTop) 0))

(defn arrow-svg
  [toggled?]
  [:svg
   {:class (if toggled? "arrow-toggle" "arrow-untoggle")
    :height "24"
    :id "arrow"
    :viewBox "0 0 24 24"
    :width "24"}
   [:g {:fill "#111111"}
    [:path {:d "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"}]]])
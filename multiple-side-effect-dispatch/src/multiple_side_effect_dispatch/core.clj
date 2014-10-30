(ns multiple-side-effect-dispatch.core
  (:require [clojure.core.async :refer [chan >!! <!! close!]]
            [onyx.peer.task-lifecycle-extensions :as l-ext]
            [onyx.plugin.core-async]
            [onyx.api]))

(defn my-inc [{:keys [n] :as segment}]
  (assoc segment :n (inc n)))

(def workflow
  [[:input :inc]
   [:inc :output]])

(def capacity 1000)

(def input-chan (chan capacity))

(def output-chan (chan capacity))

(defmethod l-ext/inject-lifecycle-resources :input
  [_ _] {:core-async/in-chan input-chan})

(defmethod l-ext/inject-lifecycle-resources :output
  [_ _] {:core-async/out-chan output-chan})

;; Dispatch by name
(defmethod l-ext/inject-lifecycle-resources :inc
  [_ context]
  (println "inject-lifecycle-resources: Dispatching by name")
  (update-in context [:side-effects] conj :dispatch-by-name))

;; Dispatch by identity
(defmethod l-ext/inject-lifecycle-resources :my/inc
  [_ context]
  (println "inject-lifecycle-resources: Dispatching by identity")
  (update-in context [:side-effects] conj :dispatch-by-ident))

;; Dispatch by type and medium
(defmethod l-ext/inject-lifecycle-resources [:function nil]
  [_ context]
  (println "inject-lifecycle-resources: Dispatching by type and medium")
  (update-in context [:side-effects] conj :dispatch-by-type-and-medium))

;; Dispatch by name to see the value we built up.
(defmethod l-ext/close-lifecycle-resources :inc
  [_ context]
  (println "close-lifecycle-resources: Dispatching by name")
  (println "Conj'ed list: " (:side-effects context)))

(def batch-size 10)

(def catalog
  [{:onyx/name :input
    :onyx/ident :core.async/read-from-chan
    :onyx/type :input
    :onyx/medium :core.async
    :onyx/consumption :concurrent
    :onyx/batch-size batch-size
    :onyx/doc "Reads segments from a core.async channel"}

   {:onyx/name :inc
    :onyx/ident :my/inc
    :onyx/fn :multiple-side-effect-dispatch.core/my-inc
    :onyx/type :function
    :onyx/consumption :concurrent
    :onyx/batch-size batch-size}

   {:onyx/name :output
    :onyx/ident :core.async/write-to-chan
    :onyx/type :output
    :onyx/medium :core.async
    :onyx/consumption :concurrent
    :onyx/batch-size batch-size
    :onyx/doc "Writes segments to a core.async channel"}])

(def input-segments
  [{:n 0}
   {:n 1}
   {:n 2}
   {:n 3}
   {:n 4}
   {:n 5}
   :done])

(doseq [segment input-segments]
  (>!! input-chan segment))

(close! input-chan)

(def id (java.util.UUID/randomUUID))

(def coord-opts
  {:hornetq/mode :vm
   :hornetq/server? true
   :hornetq.server/type :vm
   :zookeeper/address "127.0.0.1:2186"
   :zookeeper/server? true
   :zookeeper.server/port 2186
   :onyx/id id
   :onyx.coordinator/revoke-delay 5000})

(def peer-opts
  {:hornetq/mode :vm
   :zookeeper/address "127.0.0.1:2186"
   :onyx/id id})

(def conn (onyx.api/connect :memory coord-opts))

(def v-peers (onyx.api/start-peers conn 1 peer-opts))

(onyx.api/submit-job conn {:catalog catalog :workflow workflow})

(def results
  (doall
   (map (fn [_] (<!! output-chan))
        (range (count input-segments)))))

(clojure.pprint/pprint results)

(doseq [v-peer v-peers]
  ((:shutdown-fn v-peer)))

(onyx.api/shutdown conn)


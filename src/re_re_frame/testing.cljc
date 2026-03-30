(ns re-re-frame.testing
  (:require [re-frame.core :refer [->interceptor reg-global-interceptor console]]
            [clojure.spec.alpha :as s]))

(def ^:private !db-spec (atom nil))
(def ^:private !thrown-errors (atom []))
(defn validate-db?- [] (some? @!db-spec))

(defn db-valid?
  "Returns `true` if `db` passes the db spec that was set on `init!`, `false` if otherwise.
   If the db spec has not been set, a warning will be shown in the console (development only) and `true` will be returned."
  [db]
  (if @!db-spec
    (not (s/valid? @!db-spec db))
    true))

(defn enable-testing!
  "Starts re-frame handler testing. Registers a global interceptor that validates the app-db.
   `db-spec` should be a clojure.spec.alpha spec"
  [db-spec]
  (reset! !db-spec db-spec)
  (reg-global-interceptor
   (->interceptor
    :id :validate-db
    :after (fn [{{:keys [:event :re-frame.std-interceptors/untrimmed-event]} :coeffects
                 {:keys [:db]} :effects :as context}]
             (when (and db (not (db-valid? db)))
               (let [reason (s/explain-str @!db-spec db)]
                 (if-not (some #{reason} @!thrown-errors)
                   (do
                     (console :group "[re-re-frame] app-db spec check has failed!")
                     (console :error (str "event: " (or untrimmed-event event)))
                     (console :error (str "reason: " reason))
                     (console :error (str "app-db after handler: " db))
                     (console :groupEnd)
                     (swap! !thrown-errors conj reason))
                   (console :error "[re-re-frame] app-db spec check has failed!"))))
             context))))
(ns re-re-frame.testing-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [re-frame.core :as rf]
            [re-frame.db :as db]
            [re-re-frame.core :refer :all]
            [re-re-frame.testing :refer :all]))

(deftest enable-testing-test
  (testing "enable-testing! works"
    (reset! db/app-db {:first-name "Brandon"
                       :last-name "Flowers"})

    (s/def ::db (s/keys :req-un [::first-name ::last-name]))
    (s/def ::first-name string?)
    (s/def ::last-name string?)

    (def !log (atom []))
    (defn on-log
      [& args]
      (swap! !log into args))
    (rf/set-loggers! {:group on-log
                      :error on-log})

    (enable-testing! ::db)

    (reg-event-x
     :first-name
     (fn [db first-name]
       (assoc db :first-name first-name)))
    (dispatch-sync [:first-name "Ronnie"])
    (is (empty? @!log))

    (dispatch-sync [:first-name 420])
    (is (seq @!log))

    (reset! !log [])
    (dispatch-sync [:first-name "Brandon"])
    (is (empty? @!log))))
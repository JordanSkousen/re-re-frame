(ns re-re-frame.core-test
  (:require [clojure.string :as string]
            [clojure.test :refer :all]
            [re-frame.core :as rf]
            [re-frame.db :as db]
            [re-re-frame.core :refer :all]))

(use-fixtures :each 
  (fn [f]
    (reset! db/app-db {:first-name "Brandon"
                       :last-name "Flowers"
                       :address {:address-line-1 "3111 S Valley View Blvd"
                                 :address-line-2 "F-107"
                                 :city "Las Vegas"
                                 :state "Nevada"
                                 :zip-code 89102}})
    (f)
    (rf/clear-cofx)
    (rf/clear-event)
    (rf/clear-fx)
    (rf/clear-global-interceptor)
    (rf/clear-sub)
    (rf/clear-subscription-cache!)))

(deftest test-reg-grab 
  (testing "simple reg-grab works"
    (reg-grab
     :first-name
     (fn [db]
       (get db :first-name)))
    (is (= @(subscribe [:first-name]) "Brandon"))
    (is (= (grab @db/app-db :first-name) "Brandon")))
  
  (testing "reg-grab with arguments works"
    (reg-grab
     :address
     (fn [db key]
       (get-in db [:address key])))
    (is (= @(subscribe [:address :city]) "Las Vegas"))
    (is (= (grab @db/app-db :address :state) "Nevada")))
  
  (testing "reg-grab with 1 :<- signal works"
    (reg-grab
     :address-line-1
     :<- [:address]
     (fn [[address db]]
       (:address-line-1 address)))
    (is (= @(subscribe [:address-line-1]) "3111 S Valley View Blvd"))
    (is (= (grab @db/app-db :address-line-1) "3111 S Valley View Blvd")))
  
  (testing "reg-grab with 2 :<- signals works"
    (reg-grab
     :person
     :<- [:first-name]
     :<- [:address]
     (fn [[first-name address db] include-address?]
       (if include-address?
         (-> (string/join " " [first-name (:last-name db) "lives at" (:address-line-1 address) (:address-line-2 address) (:city address) (:state address) (:zip-code address)]))
         (str first-name " " (:last-name db)))))
    (is (= @(subscribe [:person]) "Brandon Flowers"))
    (is (= (grab @db/app-db :person) "Brandon Flowers"))
    (is (= @(subscribe [:person true]) "Brandon Flowers lives at 3111 S Valley View Blvd F-107 Las Vegas Nevada 89102"))
    (is (= (grab @db/app-db :person true) "Brandon Flowers lives at 3111 S Valley View Blvd F-107 Las Vegas Nevada 89102")))
  
  (testing "reg-grab with signal fn works"
    (reg-grab
     :person*
     (fn []
       (subscribe [:person]))
     (fn [[person] f]
       (apply f person)))
    (is (= @(subscribe [:person* string/upper-case]) "BRANDON FLOWERS LIVES AT 3111 S VALLEY VIEW BLVD F-107 LAS VEGAS NEVADA 89102"))
    (is (= (grab @db/app-db :person* string/upper-case) "BRANDON FLOWERS LIVES AT 3111 S VALLEY VIEW BLVD F-107 LAS VEGAS NEVADA 89102"))))

(deftest test-grab-all 
  (testing "grab-all works"
    (reg-grab
     :first-name
     (fn [db]
       (get db :first-name)))
    (reg-grab
     :address
     (fn [db key]
       (get-in db [:address key])))
    (is (= (grab-all @db/app-db :first-name [:address :city]) {:first-name "Brandon"
                                                               :address "Las Vegas"}))))

(deftest test-reg-event-x
  (testing "simple reg-event-x returning db works"
    (reg-event-x
     :first-name
     (fn [db name]
       (assoc db :first-name name)))
    (dispatch-sync [:first-name "Ronnie"])
    (is (= (:first-name @db/app-db) "Ronnie")))
   
  (testing "simple reg-event-x returning fx map works"
    (reg-event-x
     :address-city
     (fn [db city]
       {:db (assoc-in db [:address :city] city)
        :dispatch [:address-zip-code 90210]}))
    (reg-event-x
     :address-zip-code
     (fn [db zip]
       (assoc-in db [:address :zip-code] zip)))
    (dispatch-sync [:address-city "Beverly Hills"])
    (is (= (-> @db/app-db :address :city) "Beverly Hills"))
    (is (= (-> @db/app-db :address :zip-code) 90210)))
  
  (testing "reg-event-x with interceptors works"
    (reg-event-x
     :full-name
     [(rf/->interceptor
       :id :upper-case-interceptor
       :before (fn [context]
                 (update-in context [:effects :db :first-name] string/upper-case)))]
     (fn [db last-name]
       (assoc db :full-name (str (:first-name db) " " last-name))))
    (dispatch-sync [:first-name "Brandon"])
    (dispatch-sync [:full-name "Flowers"])
    (is (= (:full-name @db/app-db) "BRANDON Flowers"))) 
  
  (testing "reg-event-x with dispatch-sync works"
    (reg-event-x
     :name
     (fn [db first-name last-name]
       {:db (assoc db :last-name last-name)
        :dispatch-sync [:first-name first-name]}))
    (swap! db/app-db merge {:first-name "Brandon"
                            :last-name "Flowers"})
    (dispatch-sync [:name "Ronnie" "Vannucci"])
    (is (= (:first-name @db/app-db) "Ronnie"))
    (is (= (:last-name @db/app-db) "Vannucci")))
  
  (testing "reg-event-x with dispatch-sync-n works"
    (reg-event-x
     :address
     (fn [db address-line-1 address-line-2 city state zip-code]
       {:db (-> db
                (assoc-in [:address :address-line-1] address-line-1)
                (assoc-in [:address :address-line-2] address-line-2)
                (assoc-in [:address :state] state))
        :dispatch-sync-n [[:address-city city]
                          [:address-zip-code zip-code]]}))
    (swap! db/app-db assoc :address {:address-line-1 "3111 S Valley View Blvd"
                                     :address-line-2 "F-107"
                                     :city "Las Vegas"
                                     :state "Nevada"
                                     :zip-code 89102})
    (dispatch-sync [:address "255 W 1100 N" nil "Nephi" "Utah" 84648])
    (is (= (:address @db/app-db) {:address-line-1 "255 W 1100 N"
                                  :address-line-2 nil
                                  :city "Nephi"
                                  :state "Utah"
                                  :zip-code 84648}))))

(deftest test-custom-fx
  (testing "add-custom-fx! works"
    (def !test (atom {:a false
                      :b false}))
    (reg-fx
     :update-atom-a
     (fn []
       (swap! !test assoc :a true)))
    (reg-fx
     :update-atom-b
     (fn []
       (swap! !test assoc :b true)))
    (add-custom-fx! :update-atom-a :update-atom-b)
    (reg-event-x
     :test-custom-fx
     (fn [db]
       {:update-atom-a nil
        :update-atom-b nil}))

    (is (= @db/app-db {:first-name "Brandon"
                       :last-name "Flowers"
                       :address {:address-line-1 "3111 S Valley View Blvd"
                                 :address-line-2 "F-107"
                                 :city "Las Vegas"
                                 :state "Nevada"
                                 :zip-code 89102}}))
    (dispatch-sync [:test-custom-fx])
    (is (= @!test {:a true
                   :b true}))
    (is (= @db/app-db {:first-name "Brandon"
                       :last-name "Flowers"
                       :address {:address-line-1 "3111 S Valley View Blvd"
                                 :address-line-2 "F-107"
                                 :city "Las Vegas"
                                 :state "Nevada"
                                 :zip-code 89102}})))

  (testing "remove-custom-fx! works"
    (remove-custom-fx! :update-atom-a :update-atom-b)
    (dispatch-sync [:test-custom-fx])
    (is (= @db/app-db {:update-atom-a nil
                       :update-atom-b nil}))))
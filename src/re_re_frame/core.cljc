(ns re-re-frame.core
  (:require [re-frame.core :as rf :refer [reg-sub reg-event-fx]]
            [re-re-frame.testing :refer [db-valid? validate-db?-]]))

;; ============================================================================================================================================================
;; Settings

(defonce ^:private RE_FRAME_FXS ;; re-frame built-in/add-on fxs
  [:db :fx :dispatch :dispatch-n :async-flow :http-xhrio])
(def ^:private !custom-fx-identifiers (atom #{}))

(defn add-custom-fx!
  [& keys]
  (swap! !custom-fx-identifiers into keys))

(defn remove-custom-fx!
  [& keys]
  (swap! !custom-fx-identifiers (fn [custom-fx-identifiers]
                                  (->> custom-fx-identifiers
                                       (filter #(not (some #{%} keys)))
                                       (apply hash-set)))))

(defn clear-custom-fx! []
  (reset! !custom-fx-identifiers #{}))

;; ============================================================================================================================================================
;; Subscriptions utils

(defmulti grab
  "Grabs a value from the db. Essentially works like re-frame.core/subscribe, but is instead meant to be used inside re-frame handlers.
   To use grab, you'll need to first register a grab method with reg-grab (see below).
   Args are: 
    1. The re-frame app-db
    2. The key of the reg-grab fn to call
    3. All other arguments to send to the grab method"
  (fn [_ key] key))

(defn grab-all
  "Grabs a bunch of different values from `db`, and returns them as a map.
   Each `keys-with-args` argument should be either:
   - a keyword to send to grab
   - a vector, where the first item is a keyword to send to grab, and the rest are args to send along with grab."
  [db & keys-with-args]
  (->> keys-with-args
       (map (fn [key-or-key-with-args]
              (if (keyword? key-or-key-with-args)
                (grab db key-or-key-with-args)
                (apply grab (concat [db (first key-or-key-with-args)] (drop 1 key-or-key-with-args))))))
       (zipmap (->> keys-with-args
                    (map #(cond-> % 
                            (coll? %) first))))))

(defn- apply-signals
  [signals db args]
  (cond
    ;; using fn as signal
    (-> signals first fn?)
    (let [signals-out (apply (first signals) args)]
      (if (coll? signals-out)
        (conj signals-out db)
        [signals-out db]))

    ;; using :<- syntatic sugar(s)
    (seq signals)
    (conj (->> (range (/ (count signals) 2))
               (mapv (fn [i]
                       (let [signal (get signals (inc (* i 2)))] ;; only get odd indexes (1, 3, 5, 7...) so we skip the :<- syntax thingy
                         (apply grab (into [db] signal))))))
          db)

    ;; no signals
    :else
    db))

(defn reg-grab
  "Defines a `grab` method and registers it as a re-frame subscription, all in one.
   You can call this grab method by either calling re-frame.core/subscribe or grab (see above).
   The args are exactly the same as re-frame.core/reg-sub, with the id keyword as the first arg and fn as the second arg.
    * Note: `f`'s args are first the db, then the args sent with subscribe or grab are 2nd, 3rd, 4th etc.
      Notice the args aren't in their own array, and you don't have to skip the 1st arg! For example, instead of writing the args like usual:
      ```
       (reg-sub
         :example
         (fn [db [_ arg1 arg2]]
           ...))
      ```
      The args to reg-grab are written as:
      ```
       (reg-grab
         :example 
         (fn [db arg1 arg2]
           ...))
      ```"
  [& args]
  (let [key (first args)
        signals (->> args
                     (drop 1)
                     drop-last
                     vec)
        f (last args)]
    (defmethod grab
      key
      [db _ & args]
      (let [db' (apply-signals signals db args)]
        (apply f (into [db'] args))))
    (reg-sub
     key
     (fn [db [_ & args]]
       (apply grab (into [db key] args))))))

;; ============================================================================================================================================================
;; Handlers utils

(defmulti synchronous-event (fn [_ key] key))

(defn- convert-handler-result-to-fx-map-only
  [result]
  (let [result' (cond-> result
                  (nil? (:fx result))
                  (assoc :fx [])
                  :always
                  (update :fx into (->> (dissoc result :db :fx)
                                        (map (fn [[key args]]
                                               [key args])))))]
    (apply dissoc result' (->> result'
                               keys
                               (filter #(not (some #{%} [:db :fx])))))))

(defn reg-event-x
  "Registers a 'smart' re-frame handler, which will detect if the result of `f` is the modified db itself, or is a db/dispatch/fx map.
   Basically eliminates the need to distinguish between `reg-event-db` and `reg-event-fx`.
   Also allows you to use dispatch-sync and dispatch-sync-n.
    * Note: `f`'s args are first the db, then the args sent with dispatch are 2nd, 3rd, 4th etc.
      Notice the args aren't in their own array, and you don't have to skip the 1st arg! For example, instead of writing the args like usual:
      ```
       (reg-event-fx
         :example 
         (fn [{:keys [db]} [_ arg1 arg2]]
           ...))
      ```
      The args to reg-event-x are written as:
      ```
       (reg-event-x
         :example 
         (fn [db arg1 arg2]
           ...))
      ```"
  ([key f]
   (reg-event-x key nil f))
  ([key interceptors f]
   (let [fn-body (fn [{:keys [db]} [_ & args]]
                   (when-let [result (apply f (into [db] args))]
                     (if (or (some #(contains? result %) (into RE_FRAME_FXS @!custom-fx-identifiers))
                             (->> result
                                  keys
                                  (some qualified-keyword?))) ;; if we see a namespaced keyword, assume the key is a fx (and thus we should treat this as a reg-event-fx)
                       ;; special key found, treat this as a reg-event-fx
                       (let [result' (cond-> result
                                       (contains? result :dispatch-sync) (assoc :dispatch-sync-n [(get result :dispatch-sync)])
                                       :always (-> (update :dispatch-sync-n (partial filter some?))
                                                   (dissoc :dispatch-sync)))]
                         (if (contains? result' :dispatch-sync-n)
                           ;; "dispatch" synchronous events (really just calls them with the latest db)
                           (reduce (fn [result'' event-vec]
                                     (let [sync-result (apply synchronous-event (:db result'') (first event-vec) (rest event-vec))
                                           sync-result' (convert-handler-result-to-fx-map-only sync-result)]
                                       {:db (or (:db sync-result') (:db result''))  ;; some event handlers don't return the db key if there's no change. if so, assume the previous db was returned
                                        :fx (into (:fx result'') (:fx sync-result'))}))
                                   (-> result'
                                       (dissoc :dispatch-sync-n)
                                       convert-handler-result-to-fx-map-only
                                       (assoc :db (get result' :db db))) ;; some event handlers don't return the db key if there's no change. if so, assume the current db was returned
                                   (:dispatch-sync-n result'))
                           ;; no synchronous events to take care of, return handler result
                           result'))
                       ;; no special keys found, treat this as a reg-event-db (result is modified db)
                       (do
                         (when (and (validate-db?-) (not (db-valid? result)))
                           (rf/console :error "[re-re-frame] ERROR the event" key "returned an invalid db, is it possible a reg-event-fx is being misinterpreted as a reg-event-db? You can add a custom fx key using `add-custom-fx!`.")
                           (rf/console :error "Keys of the supposed \"db\" that were returned with the result:" (keys result)))
                         {:db result}))))]
     (defmethod synchronous-event
       key
       [db _ & args]
       (fn-body {:db db} (into [key] args)))
     (reg-event-fx
      key
      interceptors
      fn-body))))

;; ============================================================================================================================================================
;; Aliases: these are here so you don't have to require re-re-frame AND re-frame, just re-re-frame.

(defn dispatch [& args] (apply rf/dispatch args))
(defn dispatch-sync [& args] (apply rf/dispatch-sync args))
(defn reg-event-ctx [& args] (apply rf/reg-event-ctx args))
(defn clear-event [& args] (apply rf/clear-event args))
(defn subscribe [& args] (apply rf/subscribe args))
(defn clear-sub [& args] (apply rf/clear-sub args))
(defn clear-subscription-cache! [& args] (apply rf/clear-subscription-cache! args))
(defn reg-fx [& args] (apply rf/reg-fx args))
(defn clear-fx [& args] (apply rf/clear-fx args))
(defn reg-cofx [& args] (apply rf/reg-cofx args))
(defn inject-cofx [& args] (apply rf/inject-cofx args))
(defn clear-cofx [& args] (apply rf/clear-cofx args))
(defn path [& args] (apply rf/path args))
(defn enrich [& args] (apply rf/enrich args))
(defn after [& args] (apply rf/after args))
(defn on-changes [& args] (apply rf/on-changes args))
(defn my-f [& args] (apply rf/my-f args))
(defn reg-global-interceptor [& args] (apply rf/reg-global-interceptor args))
(defn clear-global-interceptor [& args] (apply rf/clear-global-interceptor args))
(defn ->interceptor [& args] (apply rf/->interceptor args))
(defn get-coeffect [& args] (apply rf/get-coeffect args))
(defn assoc-coeffect [& args] (apply rf/assoc-coeffect args))
(defn get-effect [& args] (apply rf/get-effect args))
(defn assoc-effect [& args] (apply rf/assoc-effect args))
(defn enqueue [& args] (apply rf/enqueue args))
(defn set-loggers! [& args] (apply rf/set-loggers! args))
(defn my-logger [& args] (apply rf/my-logger args))
(defn console [& args] (apply rf/console args))
(defn make-restore-fn [& args] (apply rf/make-restore-fn args))
(defn purge-event-queue [& args] (apply rf/purge-event-queue args))
(defn add-post-event-callback [& args] (apply rf/add-post-event-callback args))
(defn remove-post-event-callback [& args] (apply rf/remove-post-event-callback args))
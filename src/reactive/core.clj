(ns reactive.core
  (:require [clojure.string :as string]))


(defprotocol IObservableLookup
  (get-observable [this k] "Gets the observable event stream with the given name"))

(defprotocol IObservable
  (attach [this k sink] "Attaches sink to this event stream with the specified key")
  (detatch [this k] "Detatches the sink associated with k")
  (deref-value [this] "Gets the current value of this value"))

(defprotocol IObserver
  (get-socket [this] [this nm] "Gets a socket with the given name, defaults to :default"))

(defprotocol IEventSocket
  (mark-dirty [this f] "Marks this socket as dirty, instructing it to clear its cache and call f when it needs to
                        update its cache")
  (push-update [this]))


(defprotocol INextEvent
  (next-event [this v]))


; This represents a contstant that has been lifted

(deftype LiftedValue [v]
  IObservable
  (attach [this k sink]
          (mark-dirty sink #(identity v))
          (push-update sink))
  (detatch [this k] nil)
  (deref-value [this] v))

(defn lift [v]
  (if-not (extends? IObservable (type v))
    (LiftedValue. v)
    v))

(defn reactive-cell [init]
  (let [value (atom init)
        listeners (atom {})]
    (reify
      IObservable
      (attach [this k sink]
        (swap! listeners assoc k sink)
        (mark-dirty sink #(deref-value this))
        (push-update sink))
      (detatch [this k]
        (swap! listeners dissoc k))
      (deref-value [this] @value)
      INextEvent
      (next-event [this v]
        (reset! value v)
        (doall
         (for [[_ l] @listeners]
           (mark-dirty l #(deref-value this))))
        (doall
         (for [[_ l] @listeners]
           (push-update l)))))))

(defn unknown? [v]
  (= v ::unknown))

(defn not-unknown? [v]
  (not (= v ::unknown)))

(defn get-vals [state]
  (map :value-fn (vals (:slots @state))))

(defn should-mark-dirty? [state]
  (let [vals (get-vals state)]
    (apply (:mark-dirty? @state) vals)))

(defn get-deref-value [state]
  (let [vals (get-vals state)
        update? (apply (:push-update? @state) vals)]
    (if update?
      (apply (:map-fn @state) vals)
      ::unknown)))



(defn reactive-node [slots mark-dirty? push-update? map-fn]
  (let [state {:slots (into {}
                            (map (fn [s]
                                   [s {:value-fn ::unknown
                                       :is-dirty? true}])
                                 slots))
               :mark-dirty? mark-dirty?
               :push-update? push-update?
               :map-fn map-fn
               :value ::unknown
               :listeners {}}
        state (atom state)]
    (reify
      IObservable
      (attach [this k sink]
        (swap! state assoc-in [:listeners k] sink)
        (mark-dirty sink #(deref-value this)))
      (detatch [this k]
        (swap! state update-in [:listeners] #(dissoc k)))
      (deref-value [this]
        (if (unknown? (:value @state))
          (get-deref-value state)
          (:value @state)))

      clojure.lang.IDeref
      (deref [this]
        (deref-value this))

      IObserver
      (get-socket [this] (get-socket this (first slots)))
      (get-socket [this s]
        (reify
          IEventSocket
          (mark-dirty [sthis value-fn]
            (swap! state assoc-in [:slots s :value-fn] value-fn)
            (when (should-mark-dirty? state)
              (swap! state assoc :value ::unknown)
              (doall (for [[_ v] (:listeners @state)]
                       (mark-dirty v #(deref-value this))))))

          (push-update [sthis]
            (when (apply (:push-update? @state) (get-vals state))
              (doall (for [[_ v] (:listeners @state)]
                       (push-update v)))))
          ))
      )


    ))


(defn reactive-atom []
  (reactive-node
   [:default]
   (fn [default] true)
   (fn [default] true)
   (fn [default]
     (if (unknown? default)
       default
       (default)))))

(defn reactive-action [action]
  (reactive-node
   [:default]
   (fn [default] true)
   (fn [default]
     (when (not-unknown? default)
           (action (default)))
     true)
   (fn [default]
     (when (not-unknown? default)
       (default)))))

(defn reactive-invoke [slots]
  (reactive-node
   (vec (range slots))
   (fn [& slots] true)
   (fn [& slots]
     (every? not-unknown? slots))
   (fn [& slots]
     (if (every? not-unknown? slots)
       (apply ((first slots))
              (map #(%) (next slots)))))))

(def interpret-binding)

(defn interpret-invoke [bindings]
  (let [bindings (vec bindings)
        nd (gensym "invokesym")]
    `(let [~nd (reactive-invoke ~(count bindings))]
       ~@(for [b (range (count bindings))]

           `(connect ~(interpret-binding (bindings b))
                     ~nd
                     ~b))
       ~nd)))


(defn interpret-symbol [s]
  (let [sp (clojure.string/split (name s) #":")]
    (if (= (count sp) 2)
      `(get-observable ~(symbol (first sp)) ~(keyword (second sp)))
       `(lift ~s))))

(defn interpret-binding [bindings]
  (cond (seq? bindings)
        (interpret-invoke bindings)
        (symbol? bindings)
        (interpret-symbol bindings)
        :else
        `(lift ~bindings)))

(defn debug [x]
  (println x)
  x)

(defmacro bind
  ([itm bindings]
      `(connect ~(interpret-binding bindings) ~itm))
  ( [itm slot bindings]
      (debug `(connect ~(interpret-binding bindings) ~itm ~slot))))

(defn connect
   ([src dest]
              (connect src dest :default))
           ( [src dest dk]
               (let [dests (get-socket dest dk)]
                 (attach src dest dests))))

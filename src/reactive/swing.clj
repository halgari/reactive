(ns reactive.swing
  (:require [reactive.core :as core])
  (:import [javax.swing JFrame]
           [java.awt.event MouseMotionListener]))


(defmulti make-observable (fn [this k] k))

(defmethod make-observable :mouse-pos [this k]
  (let [obs (core/reactive-cell nil)
        listener (reify
          MouseMotionListener
          (mouseMoved [this e]
            (core/next-event obs {:x (.getX e)
                                  :y (.getY e)
                                  :state :up}))
          (mouseDragged [this e]
            (core/next-event obs {:x (.getX e)
                                  :y (.getY e)
                                  :state :down})))]
    (.addMouseMotionListener this listener)
    obs))

(defmulti make-socket (fn [this k] k))

(defmethod make-socket :title
  [this k]
  (core/reactive-action
   (fn [s]
     (.setTitle this s))))

(extend-type JFrame
  core/IObservableLookup
  (get-observable [this k] (make-observable this k))
  core/IObserver
  (core/get-socket
    ([this] nil)
    ([this k] (make-socket this k))))

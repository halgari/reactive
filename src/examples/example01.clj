(ns examples.example01
  (:require [reactive.core :as core]
            [reactive.swing :as rswing])
  (:import [javax.swing JFrame]))


(defn -main []
  (let [f (JFrame.)
        a (core/reactive-action #(println %)) ]
    (doto f
      (.setSize 400 400)
      (.setVisible true))
    (core/connect (core/get-observable f :mouse-pos) a)
    ))

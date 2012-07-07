(ns reactive.core-test
  (:use clojure.test
        reactive.core))

(deftest basic-test
  (let [l (lift 1)
        a (reactive-atom)]
    (connect l a)
    (println (deref-value a))
    (is (= @a 1))))

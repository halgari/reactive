(ns reactive.core-test
  (:use clojure.test
        reactive.core))

(deftest basic-test
  (let [l (lift 1)
        a (reactive-atom)]
    (connect l a)
    (println (deref-value a))
    (is (= @a 1))))

(deftest test-push
  (let [l (reactive-cell 1)
        a (reactive-atom)]
    (connect l a)
    (println (deref-value a))
    (is (= @a 1))
    (next-event l 2)
    (is (= @a 2))))

(deftest invoke-test
  (let [fn (lift inc)
        rf (reactive-invoke 2)
        v (reactive-cell 1)
        a (reactive-atom)]
    (connect fn rf 0)
    (connect v rf 1)
    (connect rf a)
    (println @a)
    (is (= @a 2))
    (next-event v 41)
    (is (= @a 42))))

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

(deftest test-action
  (let [l (reactive-cell 1)
        a (atom nil)
        action (reactive-action #(reset! a %))]
    (connect l action)
    (is (= @a 1))
    (next-event l 42)
    (is (= @a 42))))

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

(deftest simple-binding
  (let [a (reactive-atom)]
    (bind a 1)
    (is (= @a 1))))

(deftest simple-binding2
  (let [a (reactive-atom)]
    (bind a (+ 1 2))
    (is (= @a 3))))

(deftest square-test
  (let [a (reactive-atom)
        v (reactive-cell 1)]
    (bind a (* v v))
    (is (= @a 1))
    (next-event v 42)
    (is (= @a 1764))))

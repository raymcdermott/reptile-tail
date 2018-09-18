(ns reptile.tail.core-test
  (:require [clojure.test :refer :all]
            [reptile.tail.server :refer :all]
            [reptile.tail.socket-repl :as repl])
  (:import (clojure.lang Cons)))

(deftest prepl-test
  (testing "Possible PREPL fails"
    (let [prepl (repl/shared-prepl {:host :self :port 0 :server-daemon true})]

      (let [resp        (repl/shared-eval prepl "42")
            eval-result (first resp)]
        (is (= 42 (:val eval-result))))

      (let [resp        (repl/shared-eval prepl "(def x 1)")
            eval-result (first resp)]
        (is (and (= 1 (count resp)) (= :ret (:tag eval-result))))
        (is (= Cons (type (:val eval-result)))))

      (let [resp        (repl/shared-eval prepl "x")
            eval-result (first resp)]
        (is (= 1 (:val eval-result))))

      (let [resp        (repl/shared-eval prepl ":x")
            eval-result (first resp)]
        (is (= :x (:val eval-result))))

      (let [resp        (repl/shared-eval prepl "(inc x)")
            eval-result (first resp)]
        (is (= 2 (:val eval-result))))

      (let [resp (repl/shared-eval prepl "\"foo\"")
            eval-result (first resp)]
        (is (and (= 1 (count resp)) (= :ret (:tag eval-result))))
        (is (= "foo" (:val eval-result))))

      (let [resp        (repl/shared-eval prepl "(range 2)")
            eval-result (first resp)]
        (is (and (= 1 (count resp)) (= :ret (:tag eval-result))))
        (is (= (range 2) (:val eval-result))))

      (let [resp        (repl/shared-eval prepl "(println \"foo\")")
            eval-result (first resp)]
        (is (= 2 (count resp)))
        (is (= "foo\n" (:val eval-result)))
        (is (= :out (:tag eval-result)))
        (is (nil? (get-in (last resp) [:eval-result :val]))))

      (let [resp        (repl/shared-eval prepl "; 42")
            eval-result (first resp)]
        (is (and (= 1 (count resp)) (= :ret (:tag eval-result))))
        (is (nil? (:val eval-result))))

      (let [resp (repl/shared-eval prepl "(comment 42)")
            eval-result (first resp)]
        (is (and (= 1 (count resp)) (= :ret (:tag eval-result))))
        (is (nil? (:val eval-result))))

      (let [resp (repl/shared-eval prepl "#_ xx")
            eval-result (first resp)]
        (is (and (= 1 (count resp)) (= :ret (:tag eval-result))))
        (is (nil? (:val eval-result)))))))

(ns reptile.tail.core-test
  (:require [clojure.test :refer :all]
            [reptile.tail.server :refer :all]
            [reptile.tail.socket-repl :as repl])
  (:import (clojure.lang Cons)))

(deftest prepl-tests
  (testing "Test out a variety of Clojure forms"
    (let [prepl (repl/shared-prepl {:host :self :port 0 :server-daemon true})]

      (let [resp        (repl/shared-eval prepl "42")
            eval-result (first resp)]
        (is (= 42 (:val eval-result))))

      (let [resp        (repl/shared-eval prepl "4.2")
            eval-result (first resp)]
        (is (= 4.2 (:val eval-result))))

      (let [resp        (repl/shared-eval prepl ":x")
            eval-result (first resp)]
        (is (= :x (:val eval-result))))

      (let [resp        (repl/shared-eval prepl "(def x 1)")
            eval-result (first resp)]
        (is (and (= 1 (count resp)) (= :ret (:tag eval-result))))
        (is (= Cons (type (:val eval-result)))))

      (let [resp        (repl/shared-eval prepl "x")
            eval-result (first resp)]
        (is (= 1 (:val eval-result))))

      ; Literals
      (let [resp        (repl/shared-eval prepl "[123 \\newline ##Inf nil true :foo]")
            eval-result (first resp)]
        (is (= [123 \newline ##Inf nil true :foo] (:val eval-result))))

      (let [resp        (repl/shared-eval prepl "#'x")
            eval-result (first resp)]
        (is (and (= 1 (count resp)) (= :ret (:tag eval-result))))
        (is (= Cons (type (:val eval-result)))))

      (let [resp        (repl/shared-eval prepl "(defn x2 [x] (+ x x))")
            eval-result (first resp)]
        (is (and (= 1 (count resp)) (= :ret (:tag eval-result))))
        (is (= Cons (type (:val eval-result)))))

      (let [resp        (repl/shared-eval prepl "(x2 4)")
            eval-result (first resp)]
        (is (= 8 (:val eval-result))))

      (let [resp        (repl/shared-eval prepl "(+ 3 4)")
            eval-result (first resp)]
        (is (= 7 (:val eval-result))))

      (let [resp        (repl/shared-eval prepl "(inc x)")
            eval-result (first resp)]
        (is (= 2 (:val eval-result))))

      (let [resp        (repl/shared-eval prepl "\"foo\"")
            eval-result (first resp)]
        (is (= "foo" (:val eval-result))))

      (let [resp        (repl/shared-eval prepl "(range 2)")
            eval-result (first resp)]
        (is (= (range 2) (:val eval-result))))

      (let [resp        (repl/shared-eval prepl "(println \"foo\")")
            eval-result (first resp)]
        (is (= 2 (count resp)))
        (is (= "foo\n" (:val eval-result)))
        (is (= :out (:tag eval-result)))
        (is (nil? (get-in (last resp) [:eval-result :val]))))

      (let [resp        (repl/shared-eval prepl "(loop [results [1]]
                                                   (if (= [1 2 3] results)
                                                     results
                                                     (recur (conj results (inc (last results))))))")
            eval-result (first resp)]
        (is (= [1 2 3] (:val eval-result))))

      ;; Lambdas
      (let [resp        (repl/shared-eval prepl "(map #(inc %) (range 3))")
            eval-result (first resp)]
        (is (= '(1 2 3) (:val eval-result))))

      (let [resp        (repl/shared-eval prepl "(map (fn [x] (inc x)) (range 3))")
            eval-result (first resp)]
        (is (= '(1 2 3) (:val eval-result))))

      ;; Reader special characters
      (let [resp        (repl/shared-eval prepl "'l33t")
            eval-result (first resp)]
        (is (= 'l33t (:val eval-result))))

      (let [resp        (repl/shared-eval prepl "(quote l33t)")
            eval-result (first resp)]
        (is (= 'l33t (:val eval-result))))

      (let [_           (repl/shared-eval prepl "(def atomic (atom 123))")
            resp        (repl/shared-eval prepl "@atomic")
            eval-result (first resp)]
        (is (= 123 (:val eval-result))))

      (let [_           (repl/shared-eval prepl "(defn type-hints [^String s] (clojure.string/trim s))")
            resp        (repl/shared-eval prepl "(type-hints \"  Hello-World    \")")
            eval-result (first resp)]
        (is (= "Hello-World" (:val eval-result))))

      (let [_           (repl/shared-eval prepl "(def lst '(a b c))")
            resp        (repl/shared-eval prepl "`(fred x ~x lst ~@lst 7 8 :nine)")
            eval-result (first resp)]
        (is (= '(user/fred user/x 1 user/lst a b c 7 8 :nine) (:val eval-result))))

      (let [resp        (repl/shared-eval prepl "#{1}")
            eval-result (first resp)]
        (is (= #{1} (:val eval-result))))

      (let [resp        (repl/shared-eval prepl "(re-find #\"\\s*\\d+\" \"Hello-World42\")")
            eval-result (first resp)]
        (is (= "42" (:val eval-result))))

      ;; Various comment styles
      (let [resp        (repl/shared-eval prepl "; 42")
            eval-result (first resp)]
        (is (empty? (:val eval-result))))

      (let [resp        (repl/shared-eval prepl "(comment 42)")
            eval-result (first resp)]
        (is (nil? (:val eval-result))))

      (let [resp        (repl/shared-eval prepl "#_ xx")
            eval-result (first resp)]
        (is (empty? (:val eval-result))))

      ; Multiple forms
      (let [resp          (repl/shared-eval prepl "(def x 1) x")
            first-result  (first resp)
            second-result (last resp)]
        (is (= 2 (count resp)))

        (is (= :ret (:tag first-result)))
        (is (= Cons (type (:val first-result))))

        (is (= :ret (:tag second-result)))
        (is (= 1 (:val second-result))))))

  (testing "Test spec / add-lib"
    (let [prepl (repl/shared-prepl {:host :self :port 0 :server-daemon true})]

      (let [add-ok (repl/shared-eval prepl "(use 'clojure.tools.deps.alpha.repl)
                                            (add-lib 'org.clojure/test.check {:mvn/version \"0.9.0\"})")]
        (is (= nil (:val (first add-ok))))
        (is (or (true? (:val (last add-ok))) (false? (:val (last add-ok))))))

      (let [spec-ok (repl/shared-eval prepl "(require '[clojure.spec.alpha :as s])
                                            (s/valid? even? 10)")]
        (is (= nil (:val (first spec-ok))))
        (is (true? (:val (last spec-ok)))))

      (let [gen-ok (repl/shared-eval prepl "(require '[clojure.spec.gen.alpha :as gen])
                                            (gen/generate (s/gen int?))")]
        (is (= nil (:val (first gen-ok))))
        (is (int? (:val (last gen-ok))))))))



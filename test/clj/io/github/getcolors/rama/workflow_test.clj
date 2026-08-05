(ns io.github.getcolors.rama.workflow-test
  (:require [clojure.test :refer [deftest is]]
            [io.github.getcolors.rama.workflow :as sut]))
(deftest graph-order
  (is (= :rama/infrastructure (second (sut/wire-fn :rama/start {:green/event :create}))))
  (is (= :rama/ansible (second (sut/wire-fn :rama/start {:green/event :delete}))))
  (is (= :rama/acceptance (second (sut/wire-fn :rama/ansible {:green/event :create})))))
(deftest profile-overlay-refused
  (let [r (sut/start-step {:green/event :build} {"COLORS_PAR_PROFILE" "other"})]
    (is (= 2 (:green/exit r)))))

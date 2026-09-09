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

(require '[green.workflow :as wf]
         '[io.github.getcolors.rama.tools :as tools]
         '[io.github.getcolors.rama.tools-test :as fixtures]
         '[io.github.getcolors.rama.validate :as validate])
(deftest delete-inspection-failure-stops-before-app-cleanup
  (with-redefs [validate/state-errors (constantly []) validate/secret-errors (constantly [])
                tools/load-infrastructure-step (fn [opts] (assoc opts :green/exit 1 :green/err "owned state unavailable"))
                tools/ansible-step (fn [_] (throw (Exception. "must not clean application")))]
    (let [result (wf/run sut/workflow (assoc fixtures/fixture :green/event :delete :compute-prevent-destroy false))]
      (is (not (zero? (:green/exit result)))))))
(deftest cleanup-failure-prevents-compute-and-key-destruction
  (let [calls (atom [])]
    (with-redefs [validate/state-errors (constantly []) validate/secret-errors (constantly [])
                  sut/adopt-existing-state #(assoc % :green/exit 0)
                  tools/ansible-step (fn [opts] (swap! calls conj :cleanup) (assoc opts :green/exit 1))
                  tools/infrastructure-step (fn [opts] (swap! calls conj :destroy) opts)]
      (is (not (zero? (:green/exit (wf/run sut/workflow (assoc fixtures/fixture :green/event :delete :compute-prevent-destroy false))))))
      (is (= [:cleanup] @calls)))))
(deftest deleted-deployment-does-not-run-application-cleanup
  (with-redefs [validate/state-errors (constantly []) validate/secret-errors (constantly [])
                sut/adopt-existing-state #(assoc % :rama/already-destroyed true :green/exit 0)
                tools/ansible-step (fn [_] (throw (Exception. "must not clean missing application")))]
    (is (zero? (:green/exit (wf/run sut/workflow (assoc fixtures/fixture :green/event :delete :compute-prevent-destroy false)))))))

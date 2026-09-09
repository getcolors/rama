(ns io.github.getcolors.rama.tools-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.rama.tools :as sut]))
(deftest dns-is-unproxied
  (let [s (sut/app-dns-json {:cloudflare-zone "example.com" :rama-host "rama.example.com" :ip "192.0.2.1"})]
    (is (str/includes? s "\"proxied\" : false"))
    (is (str/includes? s "192.0.2.1"))))
(deftest inventory-has-local-and-rama
  (let [s (sut/inventory {:profile "x" :provider-compute "digitalocean" :colors-compute/cluster {:nodes [{:node_id "0" :provider "digitalocean" :name "x" :ip "192.0.2.1" :vpc_ip "10.0.0.1" :user "root" :sudoer "root"}]}})]
    (is (str/includes? s "localhost"))
    (is (str/includes? s "192.0.2.1"))))

(require '[cheshire.core :as json]
         '[clj-yaml.core :as yaml]
         '[green.ansible :as ansible]
         '[io.github.getcolors.rama.compute :as compute]
         '[io.github.getcolors.compute-planning :as planning]
         '[io.github.getcolors.compute-orchestration :as orchestration]
         '[io.github.getcolors.compute-inspection :as inspection])
(def fixture (json/parse-string (json/generate-string (yaml/parse-string (slurp "test/fixtures/colors.yml"))) true))
(deftest library-plan-preserves-vpn-only-ingress-and-created-network
  (doseq [opts [fixture (dissoc fixture :digitalocean-ssh-authorized-keys)]]
    (let [result (planning/plan-deployment opts (compute/topology opts) (compute/requirements opts))
          shared (first (vals (get-in result [:documents :shared])))
          node (get-in result [:cluster :nodes 0])
          serialized (json/generate-string (:documents result))]
      (is (= "planned" (:status result)))
      (is (= "0" (:node_id node)))
      (is (= "10.24.0.10" (:vpc_ip node)))
      (is (str/includes? serialized "digitalocean_vpc"))
      (is (str/includes? serialized "51820"))
      (doseq [port ["2000" "8888" "20000"]] (is (not (str/includes? serialized (str "\"port_range\":\"" port "\"")))))
      (is (= (if (contains? opts :digitalocean-ssh-authorized-keys) "external" "managed") (get-in result [:key :mode]))))))
(deftest live-compute-refusal-does-not-invent-inventory
  (with-redefs [orchestration/orchestrate (fn [& _] {:status "error"})]
    (let [result (sut/infrastructure-step (assoc fixture :green/event :create))]
      (is (= 1 (:green/exit result))) (is (nil? (:ip result)))))
  (is (thrown? Exception (sut/inventory fixture))))
(deftest inspection-preserves-ambient-auth-and-normalized-user
  (with-redefs [inspection/read-deployment
                (fn [opts env deps requirements]
                  (is (map? env)) (is (contains? env "HOME")) (is (= {} deps))
                  (is (= [(str (:profile opts) "/rama-infrastructure.tfstate")] (:legacy_state_keys requirements)))
                  {:status "present" :cluster {:nodes [{:node_id "0" :provider "digitalocean" :name (:profile opts)
                                                       :ip "203.0.113.4" :vpc_ip "10.24.0.4" :user "ubuntu" :sudoer "ubuntu"
                                                       :ssh_identity_file "/operator/key"}]}})]
    (let [result (sut/load-infrastructure-step fixture)]
      (is (= "ubuntu" (:user result)))
      (is (= "/operator/key" (:ssh-private-key-path result)))
      (is (str/includes? (sut/inventory result) "ubuntu")))))
(deftest ssh-config-receives-normalized-connection-metadata
  (let [opts (assoc fixture :green/event :build)]
    (with-redefs [ansible/ansible-with-spec
                  (fn [received config specs]
                    (is (= [{:name "rama-fixture" :ip "192.0.2.10" :user "root"}]
                           (get-in config [:extra-vars :ssh_hosts])))
                    (is (= "rama" (get-in config [:extra-vars :ssh_legacy_marker_prefix])))
                    received)]
      (is (= opts (sut/ansible-local-step opts))))))

(ns io.github.getcolors.rama.validate-test
  (:require [clojure.test :refer [deftest is testing]]
            [io.github.getcolors.rama.validate :as sut]))
(def valid {:profile "x" :workdir ".colors" :provider-compute "digitalocean"
 :provider-backend "local" :provider-dns false :provider-smtp false
 :compute-prevent-destroy true :rama-cluster-name "x" :rama-deployment "single-node"
 :rama-version "1" :rama-source-url "https://x" :zookeeper-version "1"
 :zookeeper-source-url "https://x" :java-version 21 :rama-data-dir "/data/rama"
 :rama-supervisor-port-range [20000 21000] :digitalocean-name "x"
 :digitalocean-region "ams3" :digitalocean-size "s" :digitalocean-image "ubuntu"
 :digitalocean-ssh-authorized-keys "~/.ssh/id.pub" :digitalocean-vpc-cidr "10.0.0.0/20"
 :digitalocean-ssh-sources ["1.2.3.4/32"] :digitalocean-wireguard-sources ["0.0.0.0/0"]
 :wireguard-port 51820 :wireguard-network-cidr "10.1.0.0/24"
 :wireguard-server-address "10.1.0.1/24" :wireguard-client-address "10.1.0.2/32"
 :wireguard-client-name "x" :rama-host "rama.example.com" :cloudflare-zone "example.com"
 :rama-smtp-from "rama@notifications.example.com"})
(deftest validates-all-errors
  (is (empty? (sut/state-errors valid)))
  (is (< 1 (count (sut/state-errors (-> valid (dissoc :profile) (assoc :provider-dns "bad")))))))
(deftest optional-provider-secrets
  (is (= ["required credential is not set: COLORS_PAR_DO_TOKEN"]
         (vec (sut/secret-errors valid))))
  (is (= 4 (count (sut/secret-errors (assoc valid :provider-dns "cloudflare" :provider-smtp "resend"))))))

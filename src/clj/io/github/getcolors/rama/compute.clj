(ns io.github.getcolors.rama.compute
  "One private Rama host; compute providers and state belong to the library."
  (:require [io.github.getcolors.compute :as compute]
            [io.github.getcolors.compute-deployment-request :as deployment]
            [io.github.getcolors.compute-planning :as planning]))
(defn topology [_] [{:role nil :count 1}])
(defn requirements [opts]
  {:single_host true :private true :network {:mode "created"}
   :legacy_state_keys [(str (:profile opts) "/rama-infrastructure.tfstate")]
   :security {:egress "all" :private_filter false
              :ingress [{:id "ssh" :protocol "tcp" :from_port 22 :to_port 22
                         :sources (deployment/source-cidrs opts "ssh-sources" "rama-ssh-sources")}
                        {:id "wireguard" :protocol "udp" :from_port (:wireguard-port opts) :to_port (:wireguard-port opts)
                         :sources (deployment/source-cidrs opts "wireguard-sources" "rama-wireguard-sources")}]}})
(defn node [opts]
  (let [cluster (or (:colors-compute/cluster opts)
                    (when (or (= :build (:green/event opts)) (:green/dry-run opts))
                      (:cluster (planning/plan-deployment opts (topology opts) (requirements opts)))))]
    (when-not cluster (throw (ex-info "compute result unavailable; refusing placeholder inventory" {})))
    (first (:nodes (compute/collect (mapv #(assoc % :provider (:provider-compute opts)) (compute/expand (topology opts))) (:nodes cluster) "0")))))

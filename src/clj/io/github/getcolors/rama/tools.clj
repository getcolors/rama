(ns io.github.getcolors.rama.tools
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [green.ansible :as ansible]
            [green.cli :as green-cli]
            [green.process :as process]
            [green.scaffold :as sc]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.once.tools :as once-tools]
            [io.github.getcolors.rama.operator :as operator]
            [io.github.getcolors.rama.compute :as compute]
            [io.github.getcolors.compute :as library]
            [io.github.getcolors.compute-deployment-request :as deployment]
            [io.github.getcolors.compute-planning :as planning]
            [io.github.getcolors.compute-orchestration :as orchestration]
            [io.github.getcolors.compute-inspection :as inspection]
            [io.github.getcolors.rama.utils :as utils]
            [io.github.getcolors.rama.validate :as validate]))

(def infrastructure-tool "rama-infrastructure")
(def smtp-tool "tofu-smtp")
(def dns-tool "rama-dns")
(def smtp-post-tool "tofu-smtp-post")
(def ansible-tool "rama-ansible")
(def ansible-local-tool "rama-ansible-local")
(def root "io.github.getcolors.rama.tools")
(def template-opts sc/preserve-jinja-delimiters)

(defn tool-dir [opts tool]
  (green-cli/stage-dir opts tool {:default-profile "rama"}))
(defn delegated-tool-dir [opts tool] (once-tools/tool-dir opts tool))
(defn template [path file] (keyword (str root "." path) file))
(defn spec [template target data] {:template template :target target :data data :opts template-opts})
(defn raw-spec [target content] (sc/content-spec target content))

(defn cidrs [opts k]
  (let [v (get opts k) xs (if (sequential? v) v (str/split (str v) #"[,\s]+"))]
    (->> xs (map (comp str/trim str)) (remove str/blank?) vec)))

(defn credential-env [opts & slots]
  (not-empty
   (into {} (keep (fn [[k env-var]]
                    (when-let [v (not-empty (str (get opts k)))] [env-var v])))
         (apply merge (map #(validate/tofu-env opts %) (conj (vec slots) :provider-backend))))))
(defn backend-credential-env [opts] (credential-env opts))

(defn- compute-json [value indent]
  (let [padding #(apply str (repeat % " "))]
    (cond
      (map? value) (if (empty? value) "{}"
                      (str "{\n" (str/join ",\n" (for [[key item] (sort-by key value)]
                                                       (str (padding (+ indent 2)) (json/generate-string key) ": " (compute-json item (+ indent 2)))))
                           "\n" (padding indent) "}"))
      (sequential? value) (if (empty? value) "[]"
                              (str "[\n" (str/join ",\n" (map #(str (padding (+ indent 2)) (compute-json % (+ indent 2))) value)) "\n" (padding indent) "]"))
      :else (json/generate-string value))))

(defn infrastructure-step [opts]
  (try
    (let [planning? (or (= :build (:green/event opts)) (:green/dry-run opts))
          result (if planning?
                   (planning/plan-deployment opts (compute/topology opts) (compute/requirements opts))
                   (orchestration/orchestrate opts (compute/topology opts) (compute/requirements opts)))]
      (when planning?
        (doseq [[stage key] (cons ["shared" (get-in result [:state_keys :shared])]
                                 (map (fn [[id key]] [(str "nodes/" (name id)) key]) (get-in result [:state_keys :nodes]))) ]
          (let [target (io/file (tool-dir opts infrastructure-tool) stage "backend.tf.json")]
            (io/make-parents target)
            (spit target (str (compute-json (:config (library/backend-plan opts key)) 0) "\n"))))
        (doseq [[stage documents] (cons ["shared" (get-in result [:documents :shared])]
                                      (map (fn [[id documents]] [(str "nodes/" id) documents]) (get-in result [:documents :nodes])))
                [filename document] documents]
          (let [target (io/file (tool-dir opts infrastructure-tool) stage filename)]
            (io/make-parents target)
            (spit target (str (compute-json document 0) "\n")))))
      (if-not (contains? #{"ready" "planned" "destroyed"} (:status result))
        (assoc opts :green/exit 1 :green/err (if (seq (:errors result)) (str/join "\n" (:errors result)) "compute lifecycle refused; inspect state ownership and configuration"))
        (cond-> (assoc opts :green/exit 0)
          (:shared result) (assoc :colors-compute/shared (:shared result))
          (:cluster result) (assoc :colors-compute/cluster (:cluster result) :ip (get-in result [:cluster :nodes 0 :ip]) :user (get-in result [:cluster :nodes 0 :user]))
          (get-in result [:key :private_key_path])
          (assoc :ssh-private-key-path (if planning? (str/replace (get-in result [:key :private_key_path]) "$HOME/.ssh" "/home/build-placeholder/.ssh") (get-in result [:key :private_key_path]))))))
    (catch Exception _ (assoc opts :green/exit 1 :green/err "compute lifecycle refused; legacy monolithic state requires explicit migration"))))

(defn load-infrastructure-step [opts]
  (try
    (let [result (inspection/read-deployment opts (into {} (System/getenv)) {} (compute/requirements opts))]
      (case (:status result)
        "present" (let [node (first (get-in result [:cluster :nodes]))]
                    (cond-> (assoc opts :colors-compute/cluster (:cluster result)
                                       :colors-compute/shared (:shared result)
                                       :ip (:ip node) :user (:user node) :green/exit 0)
                      (:ssh_identity_file node) (assoc :ssh-private-key-path (:ssh_identity_file node))))
        "destroyed" (if (= :delete (:green/event opts)) (assoc opts :rama/already-destroyed true :green/exit 0)
                        (assoc opts :green/exit 1 :green/err "compute deployment is destroyed"))
        (assoc opts :green/exit 1 :green/err "compute inspection refused; existing owned state is required")))
    (catch Exception _ (assoc opts :green/exit 1 :green/err "compute inspection refused; existing owned state is required"))))


(defn fallback-params [opts] (compute/node opts))

(defn ansible-local-specs [opts]
  (let [dir (tool-dir opts ansible-local-tool)
        data (assoc (merge opts (compute/node opts) {:host-alias (utils/host-alias opts)})
                    :ssh-keygen (validate/keygen? opts)
                    :ssh-config-identity-file (str "~/.ssh/" (:profile opts)))]
    [(spec (template "ansible-local" "ansible.cfg")
           (str dir "/ansible.cfg") data)
     (spec (template "ansible-local" "inventory.ini")
           (str dir "/inventory.ini") data)
     (spec (template "ansible-local" "main.yml")
           (str dir "/main.yml") data)]))

(defn ansible-local-step [opts]
  (let [dir (tool-dir opts ansible-local-tool)
        data (merge opts (compute/node opts) {:host-alias (utils/host-alias opts)})
        delete? (= :delete (:green/event opts))]
    (ansible/ansible-with-spec
     opts
     {:dir dir :inventory "inventory.ini"
      :playbooks {:create "main.yml" :delete "main.yml"}
      :extra-vars {:host_alias (:host-alias data)
                   :ssh_hosts [{:name (:host-alias data) :ip (:ip data) :user (:user data)}]
                   :ssh_legacy_marker_prefix "rama"
                   :block_state (if delete? "absent" "present")}}
     (ansible-local-specs opts))))


(defn smtp-step [opts]
  (once-tools/tofu-smtp-step (utils/once-shape opts)))
(defn smtp-post-step [opts]
  (once-tools/tofu-smtp-post-step (utils/once-shape opts)))

(defn cloudflare-zone-id [zone]
  (format "${data.cloudflare_zone.domains[%s].id}" (pr-str zone)))
(defn app-dns-json [opts]
  (tofu/constructs-json
   [(tofu/construct :resource :cloudflare_dns_record :rama
                    {:zone_id (cloudflare-zone-id (:cloudflare-zone opts))
                     :name (:rama-host opts) :content (:ip opts) :type "A"
                     :proxied false :ttl 1})]))

(defn dns-step [opts]
  (let [opts (merge opts (:once/smtp-params opts))
        dir (tool-dir opts dns-tool) enabled? (= "cloudflare" (utils/provider (:provider-dns opts)))
        data (assoc opts :ip (or (:ip opts) (:ip (fallback-params opts))))
        specs (if enabled?
                [(spec (template "tofu" "dns.tf") (str dir "/main.tf") data)
                 (raw-spec (str dir "/app.tf.json") (app-dns-json data))
                 (raw-spec (str dir "/smtp.tf.json")
                           (once-tools/render-fn :smtp {:domains (:domains data)}))]
                [(raw-spec (str dir "/main.tf") "terraform {}\n")])]
    (tofu/tofu-with-spec opts specs {:dir dir :env (credential-env opts :provider-dns)})))

(defn inventory [opts]
  (json/generate-string
   {:all {:children
          {:rama {:hosts {(utils/host-alias opts)
                          (cond-> {:ansible_host (:ip (compute/node opts)) :ansible_user (:user (compute/node opts))}
                            (:ssh-private-key-path opts) (assoc :ansible_ssh_private_key_file (:ssh-private-key-path opts))
                            (validate/keygen? opts) (assoc :ansible_ssh_common_args "-o IdentitiesOnly=yes"))}}
           :local {:hosts {:localhost {:ansible_connection "local"}}}}}}
   {:pretty true}))

(defn ansible-data [opts]
  (let [[lo hi] (:rama-supervisor-port-range opts)
        server-ip (utils/vpn-ip (:wireguard-server-address opts))
        ssh-source (first (deployment/source-cidrs opts "ssh-sources" "rama-ssh-sources"))]
    (assoc opts
           :ip (or (:ip opts) "192.0.2.10")
           :wireguard-server-ip server-ip
           :wireguard-interface "wg-rama"
           :wireguard-endpoint (if (= "cloudflare" (utils/provider (:provider-dns opts)))
                                 (:rama-host opts) (or (:ip opts) "192.0.2.10"))
           :supervisor-port-low lo :supervisor-port-high hi
           :ssh-source ssh-source
           :smtp-enabled (= "resend" (utils/provider (:provider-smtp opts))))))

(defn ansible-specs [opts]
  (let [dir (tool-dir opts ansible-tool) data (ansible-data opts)]
    [(spec (template "ansible" "ansible.cfg") (str dir "/ansible.cfg") data)
     (spec (template "ansible" "main.yml") (str dir "/main.yml") data)
     (spec (template "ansible" "cleanup.yml") (str dir "/cleanup.yml") data)
     (raw-spec (str dir "/inventory.json") (inventory data))]))

(defn ansible-step [opts]
  (let [dir (tool-dir opts ansible-tool)]
    (ansible/ansible-with-spec opts {:dir dir :inventory "inventory.json"
                                     :playbooks {:create "main.yml" :delete "cleanup.yml"}
                                     :host-key-checking false}
                                (ansible-specs opts))))

(defn public-port-open? [ip port]
  (zero? (:exit (process/run-with-timeout ["nc" "-z" "-w" "3" ip (str port)] {} 5000))))

(defn acceptance-step [opts]
  (if (not= :create (:green/event opts))
    (assoc opts :green/exit 0)
    (let [ip (:ip opts)
          remote (process/run-with-timeout
                  (vec (concat ["ssh" "-o" "StrictHostKeyChecking=no" "-o" "ConnectTimeout=10"]
                               (when (:ssh-private-key-path opts) ["-i" (:ssh-private-key-path opts)])
                               (when (validate/keygen? opts) ["-o" "IdentitiesOnly=yes"])
                               [(str (:user (compute/node opts)) "@" ip) "sudo systemctl is-active zookeeper conductor supervisor wg-quick@wg-rama"]))
                  {} 30000)
          state-file (:green/state-file opts)
          ready (when (zero? (:exit remote)) (operator/run state-file ["conductorReady"]))
          supervisors (when (and ready (zero? (:green/exit ready)))
                        (operator/run state-file ["numSupervisors"]))
          exposed (some #(public-port-open? ip %) [2000 8888 20000])]
      (cond
        (not (zero? (:exit remote))) (assoc opts :green/exit 1 :green/err (str "services unhealthy: " (:err remote) (:out remote)))
        (not (zero? (:green/exit ready))) (assoc opts :green/exit 1 :green/err "local Rama conductorReady failed")
        (not (zero? (:green/exit supervisors))) (assoc opts :green/exit 1 :green/err "local Rama numSupervisors failed")
        exposed (assoc opts :green/exit 1 :green/err "a Rama service port is reachable publicly")
        :else (assoc opts :green/exit 0)))))

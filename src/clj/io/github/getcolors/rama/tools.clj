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
            [io.github.getcolors.rama.utils :as utils]
            [io.github.getcolors.rama.validate :as validate]))

(def infrastructure-tool "rama-infrastructure")
(def smtp-tool "tofu-smtp")
(def dns-tool "rama-dns")
(def smtp-post-tool "tofu-smtp-post")
(def ansible-tool "rama-ansible")
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

(defn ssh-fingerprint [path]
  (let [path (str/replace (str path) "~/" (str (System/getProperty "user.home") "/"))
        result (process/run ["ssh-keygen" "-E" "md5" "-lf" path])]
    (if (zero? (:exit result))
      (or (some-> (second (re-find #"(MD5:[0-9a-f:]+)" (:out result)))
                  (str/replace "MD5:" ""))
          "00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00")
      "00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00")))

(defn fallback-params [opts]
  {:ip "192.0.2.10" :user "root" :sudoer "root" :name (:profile opts)})

(defn infrastructure-data [opts]
  (assoc opts
         :digitalocean-ssh-key-fingerprint
         (if (= :build (:green/event opts))
           "00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00"
           (ssh-fingerprint (:digitalocean-ssh-authorized-keys opts)))
         :ssh-sources-hcl (tofu/hcl-list (cidrs opts :digitalocean-ssh-sources))
         :wireguard-sources-hcl (tofu/hcl-list (cidrs opts :digitalocean-wireguard-sources))))

(defn output-params [result]
  (some-> (get-in result [:tofu/outputs :params]) walk/keywordize-keys))

(defn infrastructure-step [opts]
  (let [dir (tool-dir opts infrastructure-tool) data (infrastructure-data opts)
        specs [(spec (template "infrastructure" "main.tf") (str dir "/main.tf") data)]
        result (tofu/tofu-with-spec opts specs
                                    {:dir dir :env (credential-env opts :provider-compute)})]
    (cond
      (wf/failed? result) result
      (= :build (:green/event opts)) (merge result (fallback-params opts))
      (= :delete (:green/event opts)) result
      :else (merge result (fallback-params opts) (output-params result)))))

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
                          {:ansible_host (or (:ip opts) "192.0.2.10")
                           :ansible_user "root"}}}
           :local {:hosts {:localhost {:ansible_connection "local"}}}}}}
   {:pretty true}))

(defn ansible-data [opts]
  (let [[lo hi] (:rama-supervisor-port-range opts)
        server-ip (utils/vpn-ip (:wireguard-server-address opts))
        ssh-source (first (cidrs opts :digitalocean-ssh-sources))]
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
                  ["ssh" "-o" "StrictHostKeyChecking=no" "-o" "ConnectTimeout=10"
                   (str "root@" ip) "systemctl is-active zookeeper conductor supervisor wg-quick@wg-rama"]
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

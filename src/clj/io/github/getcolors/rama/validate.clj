(ns io.github.getcolors.rama.validate
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
            [io.github.getcolors.once.validate :as once-validate]
            [io.github.getcolors.rama.utils :as utils]))

(def profile-par (green-cli/par-name :profile))
(defn env-errors [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set; profile must come from colors.yml only")]))

(def required
  [:profile :workdir :provider-compute :provider-backend :compute-prevent-destroy
   :rama-cluster-name :rama-deployment :rama-version :rama-source-url
   :zookeeper-version :zookeeper-source-url :java-version :rama-data-dir
   :rama-supervisor-port-range :digitalocean-name :digitalocean-region
   :digitalocean-size :digitalocean-image :digitalocean-ssh-authorized-keys
   :digitalocean-vpc-cidr :digitalocean-ssh-sources
   :digitalocean-wireguard-sources :wireguard-port :wireguard-network-cidr
   :wireguard-server-address :wireguard-client-address :wireguard-client-name
   :rama-host :cloudflare-zone :rama-smtp-from])

(defn missing? [x] (or (nil? x) (and (string? x) (str/blank? x))))
(def host-re #"^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")

(defn state-errors [opts]
  (vec (concat
        (for [k required :when (missing? (get opts k))] (str k " is required"))
        (when-not (= "digitalocean" (:provider-compute opts))
          [":provider-compute must be digitalocean"])
        (when-not (contains? #{"local" "s3" "r2"} (:provider-backend opts))
          [":provider-backend must be local, s3, or r2"])
        (when-not (contains? #{nil "cloudflare"} (utils/provider (:provider-dns opts)))
          [":provider-dns must be cloudflare, null, false, or no"])
        (when-not (contains? #{nil "resend"} (utils/provider (:provider-smtp opts)))
          [":provider-smtp must be resend, null, false, or no"])
        (when-not (boolean? (:compute-prevent-destroy opts))
          [":compute-prevent-destroy must be true or false"])
        (when-not (= "single-node" (:rama-deployment opts))
          [":rama-deployment must be single-node"])
        (when-not (or (missing? (:rama-host opts)) (re-matches host-re (str (:rama-host opts))))
          [":rama-host must be a fully qualified hostname"])
        (when-not (and (vector? (:rama-supervisor-port-range opts))
                       (= 2 (count (:rama-supervisor-port-range opts)))
                       (every? integer? (:rama-supervisor-port-range opts)))
          [":rama-supervisor-port-range must contain two integer ports"]))))

(defn tofu-env [opts slot]
  (case slot
    :provider-compute {:do-token "DIGITALOCEAN_TOKEN"}
    :provider-dns (if (= "cloudflare" (utils/provider (:provider-dns opts)))
                    {:cloudflare-api-token "CLOUDFLARE_API_TOKEN"} {})
    :provider-smtp (if (= "resend" (utils/provider (:provider-smtp opts)))
                     {:resend-api-key "RESEND_API_KEY"} {})
    :provider-backend (:tofu-env (get-in once-validate/providers
                                         [:provider-backend (:provider-backend opts)]) {})
    {}))

(defn secret-errors [opts]
  (let [keys (concat [:do-token]
                     (when (= "cloudflare" (utils/provider (:provider-dns opts)))
                       [:cloudflare-api-token])
                     (when (= "resend" (utils/provider (:provider-smtp opts)))
                       [:resend-api-key :resend-password])
                     (when (:rama-license opts) [:rama-license-source-path])
                     (:secrets (get-in once-validate/providers
                                       [:provider-backend (:provider-backend opts)])))]
    (for [k (distinct keys) :when (missing? (get opts k))]
      (str "required credential is not set: " (green-cli/par-name k)))))

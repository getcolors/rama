(ns io.github.getcolors.rama.workflow
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [green.cli :as green-cli]
            [green.dry-run :as dry-run]
            [green.progress :as progress]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.rama.tools :as tools]
            [io.github.getcolors.rama.validate :as validate]))

(def defaults {:provider-compute "digitalocean" :provider-backend "local"
               :provider-dns false :provider-smtp false :rama-license false
               :compute-prevent-destroy true :workdir ".colors"})

(defn state-output [opts dir]
  (try (some-> (tofu/outputs dir (tools/backend-credential-env opts))
               :params walk/keywordize-keys)
       (catch Exception _ nil)))

(defn adopt-existing-state [opts]
  (let [infra (state-output opts (tools/tool-dir opts tools/infrastructure-tool))
        smtp (state-output opts (tools/delegated-tool-dir opts tools/smtp-tool))]
    (cond-> opts
      infra (merge infra)
      smtp (-> (merge smtp) (assoc :once/smtp-params smtp)))))

(defn start-step
  ([opts] (start-step opts (System/getenv)))
  ([opts env]
   (let [opts (green-cli/read-pars (merge defaults opts) env)
         real? (not (:green/dry-run opts)) event (:green/event opts)
         lifecycle? (contains? #{:create :delete} event)
         errors (vec (concat (validate/env-errors env)
                             (validate/state-errors opts)
                             (when (and real? lifecycle?) (validate/secret-errors opts))
                             (when (and real? (= :delete event)
                                        (:compute-prevent-destroy opts))
                               [(str "compute destruction is protected; set "
                                     (green-cli/par-name :compute-prevent-destroy)
                                     "=false to delete")])))]
     (cond
       (seq errors) (assoc opts :green/exit 2 :green/err (str/join "\n" errors))
       (and real? (= :delete event)) (assoc (adopt-existing-state opts) :green/exit 0)
       :else (assoc opts :green/exit 0)))))

(defn wire-fn [step run-opts]
  (if (= :delete (:green/event run-opts))
    (case step
      :rama/start [start-step :rama/ansible]
      :rama/ansible [tools/ansible-step :rama/smtp-post]
      :rama/smtp-post [tools/smtp-post-step :rama/dns]
      :rama/dns [tools/dns-step :rama/smtp]
      :rama/smtp [tools/smtp-step :rama/infrastructure]
      :rama/infrastructure [tools/infrastructure-step])
    (case step
      :rama/start [start-step :rama/infrastructure]
      :rama/infrastructure [tools/infrastructure-step :rama/smtp]
      :rama/smtp [tools/smtp-step :rama/dns]
      :rama/dns [tools/dns-step :rama/smtp-post]
      :rama/smtp-post [tools/smtp-post-step :rama/ansible]
      :rama/ansible [tools/ansible-step :rama/acceptance]
      :rama/acceptance [tools/acceptance-step])))

(defn backend-advice [dir-fn tool]
  (let [key #(str (:profile %) "/" tool ".tfstate")]
    (tofu/backends
     #(or (:provider-backend %) "local")
     {"local" (tofu/local-backend-advice dir-fn)
      "s3" (tofu/s3-backend-advice dir-fn #(hash-map :bucket (:s3-bucket %)
                                                       :key (key %) :region (:s3-region %)))
      "r2" (tofu/r2-backend-advice dir-fn #(hash-map :bucket (:r2-bucket %)
                                                       :key (key %) :endpoint (:r2-endpoint %)))})))
(defn own-backend [tool] (backend-advice #(tools/tool-dir % tool) tool))
(defn delegated-backend [tool] (backend-advice #(tools/delegated-tool-dir % tool) tool))

(def side-effecting [:rama/infrastructure :rama/smtp :rama/dns :rama/smtp-post
                     :rama/ansible :rama/acceptance])

(def workflow
  (-> (wf/workflow {:start :rama/start :wire-fn wire-fn})
      (wf/advice-add :rama/infrastructure :before ::backend (own-backend tools/infrastructure-tool))
      (wf/advice-add :rama/smtp :before ::backend (delegated-backend tools/smtp-tool))
      (wf/advice-add :rama/dns :before ::backend (own-backend tools/dns-tool))
      (wf/advice-add :rama/smtp-post :before ::backend (delegated-backend tools/smtp-post-tool))
      progress/advise
      (dry-run/advise side-effecting)))

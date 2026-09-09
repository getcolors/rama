(ns io.github.getcolors.rama.workflow
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [green.cli :as green-cli]
            [green.dry-run :as dry-run]
            [green.lifecycle :as lifecycle]
            [green.progress :as progress]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.rama.tools :as tools]
            [io.github.getcolors.rama.validate :as validate]))

(def defaults {:provider-compute "digitalocean" :provider-backend "r2"
               :provider-dns false :provider-smtp false :rama-license false
               :compute-prevent-destroy true :workdir ".colors"})

(defn state-output [opts dir]
  (try (some-> (tofu/outputs dir (tools/backend-credential-env opts))
               :params walk/keywordize-keys)
       (catch Exception _ nil)))

(defn adopt-existing-state [opts]
  (let [result (tools/load-infrastructure-step opts)]
    (if (wf/failed? result) result
      (if-let [smtp (state-output result (tools/delegated-tool-dir result tools/smtp-tool))]
        (assoc (merge result smtp) :once/smtp-params smtp) result))))

(defn start-step
  ([opts] (start-step opts (System/getenv)))
  ([opts env]
   (lifecycle/preflight
    opts {:defaults defaults :overlay green-cli/read-pars
          :validators
          [(fn [_ env _] (validate/env-errors env))
           (fn [opts _ _] (validate/state-errors opts))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (contains? #{:create :delete} event))
               (validate/secret-errors opts)))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (= :delete event) (:compute-prevent-destroy opts))
               [(str "compute destruction is protected; set "
                     (green-cli/par-name :compute-prevent-destroy) "=false to delete")]))]
          :after-validate
          (fn [opts _ {:keys [event real?]}]
            (if (and real? (= :delete event))
              (adopt-existing-state opts)
              (assoc opts :green/exit 0)))}
    env)))

(defn wire-fn [step run-opts]
  (if (= :delete (:green/event run-opts))
    (case step
      :rama/start [start-step :rama/ansible]
      :rama/ansible [tools/ansible-step :rama/smtp-post]
      :rama/smtp-post [tools/smtp-post-step :rama/dns]
      :rama/dns [tools/dns-step :rama/smtp]
      :rama/smtp [tools/smtp-step :rama/ansible-local]
      :rama/ansible-local [tools/ansible-local-step :rama/infrastructure]
      :rama/infrastructure [tools/infrastructure-step])
    (case step
      :rama/start [start-step :rama/infrastructure]
      :rama/infrastructure [tools/infrastructure-step :rama/ansible-local]
      :rama/ansible-local [tools/ansible-local-step :rama/smtp]
      :rama/smtp [tools/smtp-step :rama/dns]
      :rama/dns [tools/dns-step :rama/smtp-post]
      :rama/smtp-post [tools/smtp-post-step :rama/ansible]
      :rama/ansible [tools/ansible-step :rama/acceptance]
      :rama/acceptance [tools/acceptance-step])))

(defn backend-advice [dir-fn tool]
  (tofu/conventional-backend-advice
   {:dir-fn dir-fn :key-fn #(str (:profile %) "/" tool ".tfstate")}))
(defn own-backend [tool] (backend-advice #(tools/tool-dir % tool) tool))
(defn delegated-backend [tool] (backend-advice #(tools/delegated-tool-dir % tool) tool))

(def side-effecting [:rama/infrastructure :rama/smtp :rama/dns :rama/smtp-post
                     :rama/ansible-local :rama/ansible :rama/acceptance])

(def workflow
  (-> (wf/workflow {:start :rama/start :wire-fn wire-fn
                    :next-fn (fn [_ successors opts] (if (or (wf/failed? opts) (:rama/already-destroyed opts)) [] (mapv #(vector % opts) successors)))})
      (wf/advice-add :rama/smtp :before ::backend (delegated-backend tools/smtp-tool))
      (wf/advice-add :rama/dns :before ::backend (own-backend tools/dns-tool))
      (wf/advice-add :rama/smtp-post :before ::backend (delegated-backend tools/smtp-post-tool))
      progress/advise
      (dry-run/advise side-effecting)))

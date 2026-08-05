(ns io.github.getcolors.rama.operator
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [green.cli :as green-cli]
            [green.process :as process]
            [io.github.getcolors.rama.utils :as utils]
            [io.github.getcolors.rama.validate :as validate]))

(defn cli-dir [opts]
  (str (io/file (System/getProperty "user.home") ".local" "state" "rama"
                (utils/host-alias opts) "cli")))

(defn rama-yaml [opts]
  (let [ip (utils/vpn-ip (:wireguard-server-address opts))
        [lo hi] (:rama-supervisor-port-range opts)]
    (str "supervisor.port.range:\n  - " lo "\n  - " hi
         "\nlocal.dir: local-rama-data\nzookeeper.servers:\n  - \"" ip
         "\"\nconductor.host: \"" ip "\"\nsupervisor.host: \"" ip "\"\n")))

(defn prepare! [opts]
  (let [dir (io/file (cli-dir opts)) zip (io/file dir "rama.zip")
        executable (io/file dir "rama")]
    (.mkdirs dir)
    (when-not (.exists executable)
      (let [download (process/run ["curl" "-fL" "--retry" "3" "-o" (str zip)
                                   (str (:rama-source-url opts))])]
        (when-not (zero? (:exit download))
          (throw (ex-info "failed to download Rama CLI" download)))
        (let [unzip (process/run ["unzip" "-qo" (str zip) "-d" (str dir)])]
          (when-not (zero? (:exit unzip))
            (throw (ex-info "failed to unpack Rama CLI" unzip)))
          (.delete zip))))
    (spit (io/file dir "rama.yaml") (rama-yaml opts))
    (str executable)))

(defn inherit-run [argv]
  (try
    (let [child (-> (ProcessBuilder. ^java.util.List (mapv str argv)) .inheritIO .start)]
      {:exit (.waitFor child)})
    (catch Exception e {:exit -1 :err (ex-message e)})))

(defn command [opts args] (into [(prepare! opts)] args))

(defn run
  ([state-file args] (run state-file args inherit-run (System/getenv)))
  ([state-file args runner env]
   (try
     (let [file (io/file state-file)
           opts (-> (green-cli/read-state file (slurp file))
                    (assoc :green/state-file (.getAbsolutePath file))
                    (green-cli/read-pars env))
           errors (concat (validate/env-errors env) (validate/state-errors opts))]
       (if (seq errors)
         {:green/exit 2 :green/err (str/join "\n" errors)}
         (let [{:keys [exit err]} (runner (command opts args))]
           (cond-> {:green/exit (if (zero? exit) 0 (max 1 exit))}
             (and (not (zero? exit)) err) (assoc :green/err err)))))
     (catch Throwable t {:green/exit 2 :green/err (or (ex-message t) (str t))}))))

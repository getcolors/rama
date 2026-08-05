(ns io.github.getcolors.rama.utils
  (:require [clojure.string :as str]))

(def contract 1)

(defn disabled-provider? [v]
  (or (nil? v) (false? v) (= "no" (str/lower-case (str v)))
      (= "false" (str/lower-case (str v))) (= "null" (str/lower-case (str v)))))

(defn provider [v]
  (when-not (disabled-provider? v) (str/lower-case (str v))))

(defn host-alias [opts] (or (not-empty (str (:profile opts))) "rama"))

(defn registrable-domain [host]
  (let [labels (str/split (str host) #"\.")]
    (str/join "." (take-last 2 labels))))

(defn once-shape [opts]
  (assoc opts :provider-smtp (or (provider (:provider-smtp opts)) "no-infra")
              :once {:applications [{:host (:rama-host opts)}]}))

(defn vpn-ip [cidr] (first (str/split (str cidr) #"/")))

(ns io.github.getcolors.rama.operator-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.rama.operator :as sut]))
(deftest local-config-uses-vpn
  (let [s (sut/rama-yaml {:wireguard-server-address "10.25.0.1/24"
                          :rama-supervisor-port-range [20000 21000]})]
    (is (str/includes? s "conductor.host: \"10.25.0.1\""))
    (is (str/includes? s "21000"))))

(ns io.github.getcolors.rama.tools-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.rama.tools :as sut]))
(deftest dns-is-unproxied
  (let [s (sut/app-dns-json {:cloudflare-zone "example.com" :rama-host "rama.example.com" :ip "192.0.2.1"})]
    (is (str/includes? s "\"proxied\" : false"))
    (is (str/includes? s "192.0.2.1"))))
(deftest inventory-has-local-and-rama
  (let [s (sut/inventory {:profile "x" :ip "192.0.2.1"})]
    (is (str/includes? s "localhost"))
    (is (str/includes? s "192.0.2.1"))))

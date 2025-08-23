(ns dev.eca.eca-intellij.shared
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.walk :as walk]))

(defn map->camel-cased-map [m]
  (let [f (fn [[k v]]
            (if (keyword? k)
              [(csk/->camelCase k) v]
              [k v]))]
    (walk/postwalk (fn [x]
                     (if (map? x)
                       (into {} (map f x))
                       x))
                   m)))

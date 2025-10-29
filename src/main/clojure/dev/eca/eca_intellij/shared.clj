(ns dev.eca.eca-intellij.shared
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.core.async :refer [<! chan go-loop put! sliding-buffer timeout]]
   [clojure.walk :as walk])
  (:import
   [com.intellij.util.ui UIUtil]
   [javax.swing Icon]
   [dev.eca.eca_intellij Icons]))

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

(defn throttle [f time]
  (let [c (chan (sliding-buffer 1))]
    (go-loop []
      (apply f (<! c))
      (<! (timeout time))
      (recur))
    (fn [& args]
      (put! c (or args [])))))

(defn logo-icon ^Icon []
  (if (UIUtil/isUnderDarcula)
    Icons/ECA_DARK
    Icons/ECA_LIGHT))

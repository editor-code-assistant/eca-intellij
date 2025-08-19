(ns dev.eca.eca-intellij.notification
  (:import
   [com.intellij.notification NotificationGroupManager NotificationType]
   [com.intellij.openapi.project Project]))

(set! *warn-on-reflection* true)

(def ^:private type->notification-type
  {:error NotificationType/ERROR
   :warning NotificationType/WARNING
   :info NotificationType/INFORMATION})

(defn show-notification! [{:keys [project type title message]}]
  (-> (NotificationGroupManager/getInstance)
      (.getNotificationGroup "ECA notifications")
      (.createNotification ^String title ^String message ^NotificationType (type->notification-type type))
      (.notify ^Project project)))

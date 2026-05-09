(ns io.github.bigconfig-ai.once-ai.plugin
  (:require
   [babashka.process :as process]
   [big-config :as bc]
   [big-config.core :as core]
   [big-config.pluggable :refer [handle-step]]
   [big-config.workflow :as workflow]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def ^:private org "bigconfig-ai")
(def ^:private secret-name "SERVER_IP")

(defn- secret-visibility
  "Reads the org secret's current visibility. Returns the visibility string, or
  nil if the secret doesn't exist (HTTP 404). Throws on any other gh failure so
  auth/network problems don't silently widen the secret's scope."
  []
  (let [{:keys [exit out err]}
        (process/shell {:out :string :err :string :continue true}
                       "gh" "api"
                       (str "/orgs/" org "/actions/secrets/" secret-name))]
    (cond
      (zero? exit) (:visibility (json/parse-string out true))
      (str/includes? err "Not Found") nil
      :else (throw (ex-info "gh api failed reading SERVER_IP"
                            {:exit exit :err err})))))

(defn- selected-repo-ids
  []
  (let [{:keys [out]}
        (process/shell {:out :string}
                       "gh" "api"
                       (str "/orgs/" org "/actions/secrets/" secret-name
                            "/repositories"))]
    (->> (json/parse-string out true)
         :repositories
         (map :id))))

(defn update-github-secret
  [opts]
  (let [ip (get-in opts [::workflow/params :ip])
        visibility (or (secret-visibility) "all")
        repos (when (= visibility "selected") (selected-repo-ids))
        cmd (cond-> ["gh" "secret" "set" secret-name
                     "--org" org
                     "--body" ip
                     "--visibility" visibility]
              repos (into ["--repos" (str/join "," repos)]))]
    (apply process/shell cmd)
    (merge opts (core/ok))))

(defmethod handle-step :io.github.amiorin.once.tools/ansible-local
  [f _step _step-fns opts]
  (let [opts' (f opts)]
    (if (zero? (::bc/exit opts'))
      (update-github-secret opts')
      opts')))

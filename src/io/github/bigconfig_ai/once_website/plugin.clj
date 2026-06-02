(ns io.github.bigconfig-ai.once-website.plugin
  (:require
   [babashka.process :as process]
   [big-config :as bc]
   [big-config.core :as core]
   [big-config.pluggable :as pluggable]
   [big-config.workflow :as workflow]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def ^:private org "bigconfig-ai")
(def ^:private secret-name "SERVER_IP")
(def ^:private fallback-ips #{"192.168.0.1"})

(defn- register-plugin-handler!
  [step handler]
  (if-let [register (ns-resolve 'big-config.pluggable 'register-handle-step)]
    (@register step handler)
    (if (instance? clojure.lang.MultiFn pluggable/handle-step)
      (.addMethod ^clojure.lang.MultiFn pluggable/handle-step step handler)
      (throw (ex-info "Unsupported big-config.pluggable API" {:step step})))))

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

(defn- selected-repo-names
  []
  (let [{:keys [out]}
        (process/shell {:out :string}
                       "gh" "api" "--paginate"
                       (str "/orgs/" org "/actions/secrets/" secret-name
                            "/repositories"))]
    (->> (json/parse-string out true)
         :repositories
         (map :name))))

(defn- real-ip
  [opts]
  (let [ip (some-> (get-in opts [::workflow/params :ip]) str)]
    (when (or (str/blank? ip) (contains? fallback-ips ip))
      (throw (ex-info "Refusing to update SERVER_IP without a real IP"
                      {:ip ip
                       :fallback-ips fallback-ips})))
    ip))

(defn- update-github-secret
  [opts]
  (let [ip (real-ip opts)
        visibility (or (secret-visibility) "all")
        repos (when (= visibility "selected") (selected-repo-names))
        cmd (cond-> ["gh" "secret" "set" secret-name
                     "--org" org
                     "--body" ip
                     "--visibility" visibility]
              repos (into ["--repos" (str/join "," repos)]))]
    (apply process/shell cmd)
    (core/ok opts)))

(defn- handle-ansible-local
  [f _step _step-fns opts]
  (let [opts' (f opts)]
    (if (zero? (get opts' ::bc/exit 0))
      (update-github-secret opts')
      opts')))

(register-plugin-handler! :io.github.bigconfig-ai.once.tools/ansible-local handle-ansible-local)

(comment
  (update-github-secret {::workflow/params {:ip "92.5.179.95"}}))

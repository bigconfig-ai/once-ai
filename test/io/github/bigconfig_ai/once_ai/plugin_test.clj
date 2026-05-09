(ns io.github.bigconfig-ai.once-ai.plugin-test
  (:require
   [babashka.process :as process]
   [big-config :as bc]
   [big-config.pluggable :as pluggable]
   [big-config.workflow :as workflow]
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [io.github.bigconfig-ai.once-ai.plugin :as sut]))

(defn- shell-stub
  "Replacement for `babashka.process/shell`. Records each call's command vector
  (with any leading opts map stripped) into `calls` and returns whatever
  `responder` produces for that command."
  [calls responder]
  (fn [& args]
    (let [cmd (vec (if (map? (first args)) (rest args) args))]
      (swap! calls conj cmd)
      (responder cmd))))

(defn- flag-value [cmd flag]
  (->> cmd (drop-while #(not= flag %)) second))

(defn- find-cmd [calls pred]
  (some #(when (pred %) %) calls))

(defn- gh-secret-set? [cmd]
  (= ["gh" "secret" "set"] (vec (take 3 cmd))))

(defn- gh-api-secret? [cmd]
  (= cmd ["gh" "api" "/orgs/bigconfig-ai/actions/secrets/SERVER_IP"]))

(defn- gh-api-repos? [cmd]
  (= cmd ["gh" "api" "/orgs/bigconfig-ai/actions/secrets/SERVER_IP/repositories"]))

(deftest update-github-secret-preserves-visibility-all
  (testing "passes --visibility all and no --repos when secret is org-wide"
    (let [calls (atom [])
          responder (fn [cmd]
                      (if (gh-api-secret? cmd)
                        {:exit 0
                         :out (json/generate-string {:visibility "all"})
                         :err ""}
                        {:exit 0 :out "" :err ""}))]
      (with-redefs [process/shell (shell-stub calls responder)]
        (sut/update-github-secret {::workflow/params {:ip "1.2.3.4"}}))
      (let [set-cmd (find-cmd @calls gh-secret-set?)]
        (is (some? set-cmd))
        (is (= "1.2.3.4" (flag-value set-cmd "--body")))
        (is (= "all" (flag-value set-cmd "--visibility")))
        (is (nil? (flag-value set-cmd "--repos")))
        (is (not-any? gh-api-repos? @calls)
            "should not query repositories endpoint when visibility is all")))))

(deftest update-github-secret-preserves-visibility-selected
  (testing "passes --visibility selected and --repos with comma-joined ids"
    (let [calls (atom [])
          responder (fn [cmd]
                      (cond
                        (gh-api-secret? cmd)
                        {:exit 0
                         :out (json/generate-string {:visibility "selected"})
                         :err ""}
                        (gh-api-repos? cmd)
                        {:exit 0
                         :out (json/generate-string
                               {:repositories [{:id 111 :name "a"}
                                               {:id 222 :name "b"}]})
                         :err ""}
                        :else {:exit 0 :out "" :err ""}))]
      (with-redefs [process/shell (shell-stub calls responder)]
        (sut/update-github-secret {::workflow/params {:ip "9.9.9.9"}}))
      (let [set-cmd (find-cmd @calls gh-secret-set?)]
        (is (= "selected" (flag-value set-cmd "--visibility")))
        (is (= "111,222" (flag-value set-cmd "--repos")))))))

(deftest update-github-secret-defaults-to-all-on-404
  (testing "secret missing → uses --visibility all and skips the repos lookup"
    (let [calls (atom [])
          responder (fn [cmd]
                      (if (gh-api-secret? cmd)
                        {:exit 1 :out "" :err "gh: Not Found (HTTP 404)"}
                        {:exit 0 :out "" :err ""}))]
      (with-redefs [process/shell (shell-stub calls responder)]
        (sut/update-github-secret {::workflow/params {:ip "5.6.7.8"}}))
      (let [set-cmd (find-cmd @calls gh-secret-set?)]
        (is (= "all" (flag-value set-cmd "--visibility")))
        (is (nil? (flag-value set-cmd "--repos")))
        (is (not-any? gh-api-repos? @calls))))))

(deftest update-github-secret-throws-on-non-404-gh-failure
  (testing "auth/network failures surface as exceptions instead of widening scope"
    (let [responder (fn [_]
                      {:exit 1 :out "" :err "gh: Bad credentials (HTTP 401)"})]
      (with-redefs [process/shell (shell-stub (atom []) responder)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"gh api failed"
             (sut/update-github-secret {::workflow/params {:ip "1.2.3.4"}})))))))

(deftest handle-step-runs-update-on-upstream-success
  (testing "override calls (f opts), then update-github-secret when ::bc/exit is 0"
    (let [calls (atom [])
          responder (fn [cmd]
                      (if (gh-api-secret? cmd)
                        {:exit 0
                         :out (json/generate-string {:visibility "all"})
                         :err ""}
                        {:exit 0 :out "" :err ""}))
          upstream-ran? (atom false)
          upstream (fn [opts]
                     (reset! upstream-ran? true)
                     (assoc opts ::bc/exit 0))]
      (with-redefs [process/shell (shell-stub calls responder)]
        (pluggable/handle-step upstream
                               :io.github.amiorin.once.tools/ansible-local
                               []
                               {::workflow/params {:ip "1.2.3.4"}}))
      (is (true? @upstream-ran?))
      (is (some gh-secret-set? @calls)))))

(deftest handle-step-skips-update-on-upstream-failure
  (testing "override returns the failed opts unchanged and runs no gh commands"
    (let [calls (atom [])
          responder (fn [_] {:exit 0 :out "" :err ""})
          upstream (fn [opts] (assoc opts ::bc/exit 1 ::bc/err "boom"))
          result (with-redefs [process/shell (shell-stub calls responder)]
                   (pluggable/handle-step
                    upstream
                    :io.github.amiorin.once.tools/ansible-local
                    []
                    {::workflow/params {:ip "1.2.3.4"}}))]
      (is (= 1 (::bc/exit result)))
      (is (= "boom" (::bc/err result)))
      (is (empty? @calls)))))

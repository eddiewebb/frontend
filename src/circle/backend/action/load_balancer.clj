(ns circle.backend.action.load-balancer
  (:import com.amazonaws.AmazonClientException)
  (:require [circle.backend.nodes :as nodes])
  (:use [circle.backend.action :only (defaction action-fn)])
  (:require [circle.backend.load-balancer :as lb])
  (:require [circle.backend.ec2 :as ec2])
  (:require [circle.backend.ec2-tag :as tag]))

(defaction add-instances []
  {:name "add to load balancer"}
  (fn [context]
    (try
      (let [lb-name (-> context :build :lb-name)
            instance-ids (nodes/group-instance-ids (-> context :build :group))
            _ (doseq [i instance-ids
                      :let [az (ec2/get-availability-zone i)]]
                (lb/ensure-availability-zone lb-name az))
            result (lb/add-instances lb-name instance-ids)]
        {:success true
         :out (format "add" instance-ids "to load balancer" lb-name "successful")})
      (catch AmazonClientException e
        (println "add-instances:" e)
        {:success false
         :continue false
         :err (.getMessage e)}))))

(defn wait-for-healthy* [lb-name & {:keys [instance-ids 
                                           retries ;; number of times to retry
                                           sleep ;; how long to sleep between attempt, seconds
                                           ]}]
  (loop [retries retries]
    (if (> retries 0)
      (if (apply lb/healthy? lb-name instance-ids)
        true
        (do
          (Thread/sleep (* 1000 sleep))
          (recur (dec retries))))
      false)))

(defaction wait-for-healthy []
  {:name "wait for nodes LB healthy"}
  (fn [context]
    (let [instance-ids (nodes/group-instance-ids (-> context :build :group))]
      (if (wait-for-healthy* (-> context :build :lb-name)
                             :instance-ids instance-ids
                             :sleep 10
                             :retries 10)
        {:success true
         :out (str instance-ids "are all healthy")}
        {:success false
         :continue false}))))

(defn get-old-revisions [lb-name current-rev]
  (let [lb-ids (set (lb/instance-ids lb-name))]
    (filter (fn [tag]
              (and (contains? lb-ids (-> tag :resourceId))
                   (= (-> tag :key) :rev)
                   (not= (-> tag :value) current-rev))) (tag/describe-tags))))

(defaction shutdown-remove-old-revisions []
  {:name "remove old revisions"}
  (fn [context]
    (try
      (let [lb-name (-> context :build :lb-name)
            old-instances (get-old-revisions lb-name
                                             (-> context :build :vcs-revision))]
        (println "shutdown-remove-old:" old-instances)
        (when (seq old-instances)
          (lb/remove-instances lb-name old-instances)
          (ec2/terminate-instances old-instances))        
        {:success true})
      (catch AmazonClientException e
        {:success false
         :continue false
         :err (.getMessage e)}))))
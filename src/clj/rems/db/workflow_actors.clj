(ns rems.db.workflow-actors
  (:require [rems.db.core :as db]))

(defn add-approver! [wfid userid round]
  "Adds an approver to a round of a given workflow"
  (db/create-workflow-actor! {:wfid wfid :actoruserid userid :role "approver" :round round}))

(defn add-reviewer! [wfid userid round]
  "Adds a reviewer to a round of a given workflow"
  (db/create-workflow-actor! {:wfid wfid :actoruserid userid :role "reviewer" :round round}))

(defn get-by-role
  "Returns a structure containing actoruserids."
  ([application role]
   "Gets all the possible actors with the specified role that are set as actors in the workflow rounds the given application contains."
   (map :actoruserid (filter #(= role (:role %)) (db/get-workflow-actors {:application application}))))
  ([application round role]
   "Gets all the actors that have been defined for the specified workflow round in the given application."
   (map :actoruserid (filter #(= role (:role %)) (db/get-workflow-actors {:application application :round round})))))

(ns ^:integration rems.db.test-blacklist
  (:require [clj-time.core :as time]
            [clojure.test :refer :all]
            [rems.db.blacklist :as blacklist]
            [rems.db.testing :refer [test-db-fixture rollback-db-fixture]]))

(use-fixtures
  :once
  test-db-fixture
  rollback-db-fixture)

(deftest test-blacklist-event-storage
  (blacklist/add-event! {:event/type :blacklist.event/add
                         :event/actor "handler"
                         :event/time (time/date-time 2019 1 2 8 0 0)
                         :userid "baddie"
                         :resource/ext-id "urn.fi/123"
                         :event/comment nil})
  (blacklist/add-event! {:event/type :blacklist.event/remove
                         :event/actor "handler"
                         :event/time (time/date-time 2019 2 3 9 0 0)
                         :userid "baddie"
                         :resource/ext-id "urn.fi/123"
                         :event/comment "it was ok"})
  (blacklist/add-event! {:event/type :blacklist.event/add
                         :event/actor "handler"
                         :event/time (time/date-time 2019 1 1 1 0 0)
                         :userid "goodie"
                         :resource/ext-id "urn.fi/124"
                         :event/comment nil})
  (is (= [{:event/id 1
           :event/type :blacklist.event/add
           :event/time (time/date-time 2019 1 2 8 0 0)
           :event/actor "handler"
           :userid "baddie"
           :resource/ext-id "urn.fi/123"
           :event/comment nil}
          {:event/id 2
           :event/type :blacklist.event/remove
           :event/time (time/date-time 2019 2 3 9 0 0)
           :event/actor "handler"
           :userid "baddie"
           :resource/ext-id "urn.fi/123"
           :event/comment "it was ok"}]
         (blacklist/get-events {:resource/ext-id "urn.fi/123"})))
  (is (= [{:event/id 3
           :event/type :blacklist.event/add
           :event/time (time/date-time 2019 01 01 01)
           :event/actor "handler"
           :userid "goodie"
           :resource/ext-id "urn.fi/124"
           :event/comment nil}]
         (blacklist/get-events {:userid "goodie"})))
  (is (not (blacklist/blacklisted? "baddie" "urn.fi/123")))
  (is (not (blacklist/blacklisted? "baddie" "urn.fi/124")))
  (is (not (blacklist/blacklisted? "goodie" "urn.fi/123")))
  (is (blacklist/blacklisted? "goodie" "urn.fi/124")))

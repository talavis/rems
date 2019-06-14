(ns rems.db.test-data
  "Populating the database with nice test data."
  (:require [clj-time.core :as time]
            [rems.context :as context]
            [rems.db.applications :as applications]
            [rems.db.applications.legacy :as legacy]
            [rems.db.catalogue :as catalogue]
            [rems.db.core :as db]
            [rems.db.form :as form]
            [rems.db.licenses :as licenses]
            [rems.db.resource :as resource]
            [rems.db.roles :as roles]
            [rems.db.users :as users]
            [rems.db.workflow :as workflow]
            [rems.db.workflow-actors :as actors]
            [rems.locales :as locales]
            [rems.poller.email :as email]
            [rems.poller.entitlements :as entitlements-poller]
            [ring.util.http-response :refer [bad-request!]]
            [rems.poller.entitlements :as entitlements-poller])
  (:import [java.util UUID]
           [org.joda.time DateTimeUtils DateTime]))

(def ^DateTime creation-time (time/now)) ; TODO: no more used, remove?

(def +fake-users+
  {:applicant1 "alice"
   :applicant2 "malice"
   :approver1 "developer"
   :approver2 "bob"
   :owner "owner"
   :reporter "reporter"
   :reviewer "carl"
   :roleless1 "elsa"
   :roleless2 "frank"})

(def +fake-user-data+
  {"developer" {:eppn "developer" :mail "developer@example.com" :commonName "Developer"}
   "alice" {:eppn "alice" :mail "alice@example.com" :commonName "Alice Applicant"}
   "malice" {:eppn "malice" :mail "malice@example.com" :commonName "Malice Applicant" :twinOf "alice" :other "Attribute Value"}
   "bob" {:eppn "bob" :mail "bob@example.com" :commonName "Bob Approver"}
   "carl" {:eppn "carl" :mail "carl@example.com" :commonName "Carl Reviewer"}
   "elsa" {:eppn "elsa" :mail "elsa@example.com" :commonName "Elsa Roleless"}
   "frank" {:eppn "frank" :mail "frank@example.com" :commonName "Frank Roleless"}
   "owner" {:eppn "owner" :mail "owner@example.com" :commonName "Owner"}
   "reporter" {:eppn "reporter" :mail "reporter@example.com" :commonName "Reporter"}})

(def +demo-users+
  {:applicant1 "RDapplicant1@funet.fi"
   :applicant2 "RDapplicant2@funet.fi"
   :approver1 "RDapprover1@funet.fi"
   :approver2 "RDapprover2@funet.fi"
   :reviewer "RDreview@funet.fi"
   :owner "RDowner@funet.fi"
   :reporter "RDdomainreporter@funet.fi"})

(def +demo-user-data+
  {"RDapplicant1@funet.fi" {:eppn "RDapplicant1@funet.fi" :mail "RDapplicant1.test@test_example.org" :commonName "RDapplicant1 REMSDEMO1"}
   "RDapplicant2@funet.fi" {:eppn "RDapplicant2@funet.fi" :mail "RDapplicant2.test@test_example.org" :commonName "RDapplicant2 REMSDEMO"}
   "RDapprover1@funet.fi" {:eppn "RDapprover1@funet.fi" :mail "RDapprover1.test@rems_example.org" :commonName "RDapprover1 REMSDEMO"}
   "RDapprover2@funet.fi" {:eppn "RDapprover2@funet.fi" :mail "RDapprover2.test@rems_example.org" :commonName "RDapprover2 REMSDEMO"}
   "RDreview@funet.fi" {:eppn "RDreview@funet.fi" :mail "RDreview.test@rems_example.org" :commonName "RDreview REMSDEMO"}
   "RDowner@funet.fi" {:eppn "RDowner@funet.fi" :mail "RDowner.test@test_example.org" :commonName "RDowner REMSDEMO"}
   "RDdomainreporter@funet.fi" {:eppn "RDdomainreporter@funet.fi" :mail "RDdomainreporter.test@test_example.org" :commonName "RDdomainreporter REMSDEMO"}})

(defn- create-user! [user-attributes & roles]
  (let [user (:eppn user-attributes)]
    (users/add-user! user user-attributes)
    (doseq [role roles]
      (roles/add-role! user role))))

(defn- create-users-and-roles! []
  ;; users provided by the fake login
  (let [users (comp +fake-user-data+ +fake-users+)]
    (create-user! (users :applicant1))
    (create-user! (users :applicant2))
    (create-user! (users :approver1))
    (create-user! (users :approver2))
    (create-user! (users :reviewer))
    (create-user! (users :roleless1))
    (create-user! (users :roleless2))
    (create-user! (users :owner) :owner)
    (create-user! (users :reporter) :reporter))
  ;; invalid user for tests
  (db/add-user! {:user "invalid" :userattrs nil}))

(defn- create-demo-users-and-roles! []
  ;; users used on remsdemo
  (let [users (comp +demo-user-data+ +demo-users+)]
    (create-user! (users :applicant1))
    (create-user! (users :applicant2))
    (create-user! (users :approver1))
    (create-user! (users :approver2))
    (create-user! (users :reviewer))
    (create-user! (users :owner) :owner)
    (create-user! (users :reporter) :reporter)))

(defn- create-archived-form! []
  ;; only used from create-test-data!
  (let [yesterday (time/minus (time/now) (time/days 1))
        id (:id (form/create-form! (+fake-users+ :owner) {:organization "nbn" :title "Archived form, should not be seen by applicants" :fields []}))]
    (form/update-form! {:id id :enabled true :archived true})))

(defn- create-expired-license! []
  (let [owner (+fake-users+ :owner) ; only used from create-test-data!
        yesterday (time/minus (time/now) (time/days 1))]
    (db/create-license! {:modifieruserid owner :owneruserid owner :title "expired license" :type "link" :textcontent "http://expired" :end yesterday})))

(defn- create-basic-form!
  "Creates a bilingual form with all supported field types. Returns id of the form meta."
  [users]
  (:id (form/create-form!
        (users :owner)
        {:organization "nbn"
         :title "Basic form"
         :fields [;; all form field types
                  {:title {:en "Project name"
                           :fi "Projektin nimi"}
                   :optional false
                   :type "description"
                   :input-prompt {:en "Project"
                                  :fi "Projekti"}}

                  {:title {:en "Here would be some helpful instructions."
                           :fi "Tässä olisi jotain täyttöohjeita."}
                   :optional false
                   :type "label"}

                  {:title {:en "Purpose of the project"
                           :fi "Projektin tarkoitus"}
                   :optional false
                   :type "texta"
                   :input-prompt {:en "The purpose of the project is to..."
                                  :fi "Projektin tarkoitus on..."}}

                  {:title {:en "Start date of the project"
                           :fi "Projektin aloituspäivä"}
                   :optional true
                   :type "date"}

                  {:title {:en "Project plan"
                           :fi "Projektisuunnitelma"}
                   :optional true
                   :type "attachment"}

                  {:title {:en "Project team size"
                           :fi "Projektitiimin koko"}
                   :optional true
                   :type "option"
                   :options [{:key "1-5"
                              :label {:en "1-5 persons"
                                      :fi "1-5 henkilöä"}}
                             {:key "6-20"
                              :label {:en "6-20 persons"
                                      :fi "6-20 henkilöä"}}
                             {:key "20+"
                              :label {:en "over 20 persons"
                                      :fi "yli 20 henkilöä"}}]}

                  {:title {:en "Where will the data be used?"
                           :fi "Missä dataa tullaan käyttämään?"}
                   :optional true
                   :type "multiselect"
                   :options [{:key "EU"
                              :label {:en "Inside EU"
                                      :fi "EU:n sisällä"}}
                             {:key "USA"
                              :label {:en "Inside USA"
                                      :fi "Yhdysvalloissa"}}
                             {:key "Other"
                              :label {:en "Elsewhere"
                                      :fi "Muualla"}}]}

                  ;; fields which support maxlength
                  {:title {:en "Project acronym"
                           :fi "Projektin lyhenne"}
                   :optional true
                   :type "text"
                   :maxlength 10}

                  {:title {:en "Research plan"
                           :fi "Tutkimussuunnitelma"}
                   :optional true
                   :type "texta"
                   :maxlength 100}]})))

(defn create-thl-demo-form!
  [users]
  (:id (form/create-form!
        (users :owner)
        {:organization "nbn"
         :title "THL form"
         :fields [{:title {:en "Application title"
                           :fi "Hakemuksen otsikko"}
                   :optional true
                   :type "description"
                   :input-prompt {:en "Study of.."
                                  :fi "Tutkimus aiheesta.."}}
                  {:title {:en "1. Research project full title"
                           :fi "1. Tutkimusprojektin täysi nimi"}
                   :optional false
                   :type "texta"
                   :maxlength 100}
                  {:title {:en "2. This is an amendment of a previous approved application"
                           :fi "2. Hakemus täydentää edellistä hakemusta"}
                   :optional false
                   :type "option"
                   :options [{:key "false"
                              :label {:en "no"
                                      :fi "ei"}}
                             {:key "true"
                              :label {:en "yes"
                                      :fi "kyllä"}}]}
                  {:title {:en "If yes, what were the previous project permit code/s?"
                           :fi "Jos kyllä, mitkä olivat edelliset projektin lupakoodit?"}
                   :optional true
                   :type "text"}
                  {:title {:en "3. Study PIs (name, titile, affiliation, email)"
                           :fi "3. Henkilöstö (nimi, titteli, yhteys projektiin, sähköposti)"}
                   :optional false
                   :type "texta"
                   :maxlength 100}
                  {:title {:en "4. Contact person for application if different than applicant (name, email)"
                           :fi "4. Yhteyshenkilö, jos ei sama kuin hakija (nimi, sähköposti)"}
                   :optional true
                   :type "texta"
                   :maxlength 100}
                  {:title {:en "5. Research project start date"
                           :fi "5. Projektin aloituspäivä"}
                   :optional false
                   :type "date"}
                  {:title {:en "6. Research project end date"
                           :fi "6. Projektin lopetuspäivä"}
                   :optional false
                   :type "date"}
                  {:title {:en "7. Describe in detail the aims of the study and analysis plan"
                           :fi "7. Kuvaile yksityiskohtaisesti tutkimussuunnitelma"}
                   :optional false
                   :type "texta"
                   :maxlength 100}
                  {:title {:en "8. If this is an amendment, please describe briefly what is new"
                           :fi "8. Jos tämä on täydennys edelliseen hakemukseen, kuvaile tiiviisti, mikä on muuttunut."}
                   :optional true
                   :type "texta"
                   :maxlength 100}
                  {:title {:en "9. Public description of the project (in Finnish, when possible), to be published in THL Biobank."
                           :fi "9. Kuvaile yksityiskohtaisesti tutkimussuunnitelma"}
                   :input-prompt {:en "Meant for sample donors and for anyone interested in the research done using THL Biobank's sample collections. This summary and the name of the Study PI will be published in THL Biobank's web pages."
                                  :fi "Tarkoitettu aineistojen lahjoittajille ja kaikille, joita kiinnostaa THL:n Biopankkia käyttävät tutkimusprojektit. Tämä kuvaus sekä tutkijan nimi julkaistaan THL:n nettisivuilla, kun sopimus on allekirjoitettu."}
                   :optional false
                   :type "texta"
                   :maxlength 100}
                  {:title {:en "10. Place/plces of research, including place of sample and/or data analysis."
                           :fi "10. Tutkimuksen yysinen sijainti, mukaanlukien paikka, missä data-analyysi toteutetaan."}
                   :input-prompt {:en "List all research center involved in this study, and each center's role. Specify which centers will analyze which data and/or samples.."
                                  :fi "Listaa kaikki tutkimuskeskukset, jotka osallistuvat tähän tutkimukseen, ml. niiden roolit tutkimuksessa. Erittele, missä analysoidaan mikäkin näyte."}
                   :optional false
                   :type "texta"
                   :maxlength 100}
                  {:title {:en "11. Description of other research group members and their role in the applied project."
                           :fi "11. Kuvaus muista tutkimukseen osallistuvista henkilöistä, ja heidän roolistaan projektissa."}
                   :input-prompt {:en "For every group member: name, title, affiliation, contact information. In addition describe earch member's role in the project (e.g. cohor representative, data analyst, etc.)"
                                  :fi "Anna jokaisesta jäsenestä: nimi, titteli, yhteys projektiin, yhteystiedot. Kuvaile lisäki jokaisen henkilön rooli projektissa."}
                   :optional false
                   :type "texta"
                   :maxlength 100}
                  {:title {:en "12. Specify selection criteria of study participants (if applicable)"
                           :fi "12. Erottele tukimuksen osallistujien valintakriteerit (jos käytetty)"}
                   :input-prompt {:en "Describe any specific criteria by which study participans will be selected. For example, selection for specific age group, gender, area/locality, disease status etc."
                                  :fi "Kuvaa tarkat valintakriteerit, joilla tutkimuksen osallistujat valitaan. Esimerkiksi ikäryhmä, sukupuoli, alue, taudin tila jne."}
                   :optional false
                   :type "texta"
                   :maxlength 100}
                  {:title {:en "13. Specify requested phenotype data (information on variables is found at https://kite.fimm.fi)"
                           :fi "13. Tarkenna pyydetty fenotyyppidatta (tietoa muuttujista on saatavilla osoitteesta https://kite.fimm.fi)"}
                   :input-prompt {:en "Desrcibe in detail the phenotype data needed for the study. Lists of variables are to be attached to the application (below)."
                                  :fi "Kuvaile yksityiskohtaisesti tutkimukseen tarvittava fenotyyppidata. Lista muuttujista lisätään hakemukseen liitteenä."}
                   :optional false
                   :type "texta"
                   :maxlength 100}
                  {:title {:en "14. Specify requested genomics or other omics data (if applicable)"
                           :fi "14. Kuvaile tarvittava genomiikkadata."}
                   :input-prompt {:en "Specify in detail the requested data format for different genomics or other omics data types. Information of available omics data is found at THL Biobank web page (www.thl.fi/biobank/researchers)"
                                  :fi "Kuvaile tarvitsemasi genomiikkadata. Lisätietoa saatavilla osoitteesta www.thl.fi/biobank/researchers"}
                   :optional true
                   :type "texta"
                   :maxlength 100}
                  {:title {:en "16. Are biological samples requested?"
                           :fi "16. Pyydetäänkö biologisia näytteitä?"}
                   :optional false
                   :type "option"
                   :options [{:key "false"
                              :label {:en "no"
                                      :fi "ei"}}
                             {:key "true"
                              :label {:en "yes"
                                      :fi "kyllä"}}]}
                  {:title {:en "The type and amount of biological samples requested"
                           :fi "Biologisten näytteiden tyypit ja määrät."}
                   :input-prompt {:en "Type and amount of samples and any additional specific criteria."
                                  :fi "Biologisten näytteiden määrät, tyypit, ja mahdolliset muut kriteerit."}
                   :optional true
                   :type "texta"
                   :maxlength 100}
                  {:title {:en "17. What study results will be returned to THL Biobank (if any)?"
                           :fi "17. Mitä tutkimustuloksia tullaan palauttamaan THL Biopankkiin?"}
                   :input-prompt {:en "Study results such as new laboratory measurements, produced omics data and other analysis data (\"raw data\")"
                                  :fi "Tutkimustuloksia kuten mittaustuloksia, uutta biologista dataa, tai muita analyysien tuloksia (\"raaka-dataa\")"}
                   :optional false
                   :type "texta"
                   :maxlength 100}
                  {:title {:en "Expected date for return of study results"
                           :fi "Odotettu tutkimustuloksien palautuspäivämäärä"}
                   :optional true
                   :type "date"}
                  {:title {:en "18. Ethical aspects of the project"
                           :fi "18. Tutkimuksen eettiset puolet"}
                   :input-prompt {:en "If you have any documents from an ethical board, please provide them as an attachment."
                                  :fi "Liitä mahdolliset eettisen toimikunnan lausunnot hakemuksen loppuun."}
                   :optional false
                   :type "texta"
                   :maxlength 100}
                  {:title {:en "19. Project keywords (max 5)"
                           :fi "19. Projektin avainsanat (maks. 5)"}
                   :input-prompt {:en "List a few keywords that are related to this research project (please separate with comma)"
                                  :fi "Listaa muutama projektiin liittyvä avainsana, pilkuilla erotettuina."}
                   :optional false
                   :type "texta"
                   :maxlength 100}
                  {:title {:en "20. Planned publications (max 3)"
                           :fi "20. Suunnitellut julkaisut (maks. 3)"}
                   :input-prompt {:en "Planned publication titles / research topics"
                                  :fi "Suunniteltujen julkaisujen otsikot / tutkimusaiheet"}
                   :optional false
                   :type "texta"
                   :maxlength 100}
                  {:title {:en "21. Funding information"
                           :fi "21. Rahoitus"}
                   :input-prompt {:en "List all funding sources which will be used for this research project."
                                  :fi "Listaa kaikki rahoituslähteet joita tullaan käyttämään tähän tutkimusprojektiin"}
                   :optional false
                   :type "texta"
                   :maxlength 100}
                  {:title {:en "22. Invoice address (Service prices: www.thl.fi/biobank/researchers)"
                           :fi "22. Laskutusosoite (Palveluhinnasto: www.thl.fi/biobank/researchers)"}
                   :input-prompt {:en "Electronic invoice address when possible + invoicing reference"
                                  :fi "Sähköinen laskutus, kun mahdollista. Lisäksi viitenumero."}
                   :optional false
                   :type "texta"
                   :maxlength 100}
                  {:title {:en "23. Other information"
                           :fi "23. Muuta"}
                   :input-prompt {:en "Any other relevant information for the application"
                                  :fi "Muuta hakemukseen liittyvää oleellista tietoa"}
                   :optional true
                   :type "texta"
                   :maxlength 100}
                  {:title {:en "THL Biobank's registered area/s of operation to which the research project complies:"
                           :fi "THL Biobankin toimialueet, joihin tutkimusprojekti liittyy:"}
                   :optional false
                   :type "multiselect"
                   :options [{:key "population_health"
                              :label {:en "Promoting the population's health"
                                      :fi "Edistää kansanterveytttä"}}
                             {:key "disease_mechanisms"
                              :label {:en "Identifying factors involved in disease mechanisms"
                                      :fi "Tunnistaa tautien mekanismeja"}}
                             {:key "disease_prevention"
                              :label {:en "Disease prevention"
                                      :fi "Estää tautien leviämistä"}}
                             {:key "health_product_development"
                              :label {:en "Developing products that promote the welfare and health of the population"
                                      :fi "Kehittää tuotteita, jotka edistävät kansanterveyttä."}}
                             {:key "treatment_development"
                              :label {:en "Developing products and treatments for diseases"
                                      :fi "Kehittää tuotteita ja parannuskeinoja tautien varalle"}}
                             {:key "other"
                              :label {:en "Other"
                                      :fi "Muuta"}}]}
                  {:title {:en "Other, specify"
                           :fi "Muuta, tarkenna"}
                   :optional true
                   :type "texta"
                   :maxlength 100}
                  {:title {:en "Data management plan (pdf)"
                           :fi "Datanhallintasuunnitelma (pdf)"}
                   :optional true
                   :type "attachment"}]})))

(defn- create-workflows! [users]
  (let [approver1 (users :approver1)
        approver2 (users :approver2)
        reviewer (users :reviewer)
        owner (users :owner)
        minimal (:id (db/create-workflow! {:organization "nbn" :owneruserid owner :modifieruserid owner :title "minimal" :fnlround 0}))
        simple (:id (db/create-workflow! {:organization "nbn" :owneruserid owner :modifieruserid owner :title "simple" :fnlround 0}))
        with-review (:id (db/create-workflow! {:organization "nbn" :owneruserid owner :modifieruserid owner :title "with review" :fnlround 1}))
        two-round (:id (db/create-workflow! {:organization "nbn" :owneruserid owner :modifieruserid owner :title "two rounds" :fnlround 1}))
        different (:id (db/create-workflow! {:organization "nbn" :owneruserid owner :modifieruserid owner :title "two rounds, different approvers" :fnlround 1}))
        expired (:id (db/create-workflow! {:organization "nbn" :owneruserid owner :modifieruserid owner :title "workflow has already expired, should not be seen" :fnlround 0 :end (time/minus (time/now) (time/years 1))}))
        dynamic (:id (workflow/create-workflow! {:user-id owner
                                                 :organization "nbn"
                                                 :title "dynamic workflow"
                                                 :type :dynamic
                                                 :handlers [approver1]}))]
    ;; either approver1 or approver2 can approve
    (actors/add-approver! simple approver1 0)
    (actors/add-approver! simple approver2 0)
    ;; first reviewer reviews, then approver1 can approve
    (actors/add-reviewer! with-review reviewer 0)
    (actors/add-approver! with-review approver1 1)
    ;; only approver1 can approve
    (actors/add-approver! two-round approver1 0)
    (actors/add-approver! two-round approver1 1)
    ;; first approver2, then approver1
    (actors/add-approver! different approver2 0)
    (actors/add-approver! different approver1 1)

    ;; attach both kinds of licenses to all workflows
    (let [link (:id (db/create-license!
                     {:modifieruserid owner :owneruserid owner :title "non-localized link license"
                      :type "link" :textcontent "http://invalid"}))
          text (:id (db/create-license!
                     {:modifieruserid owner :owneruserid owner :title "non-localized text license"
                      :type "text" :textcontent "non-localized content"}))]
      (db/create-license-localization!
       {:licid link :langcode "en" :title "CC Attribution 4.0"
        :textcontent "https://creativecommons.org/licenses/by/4.0/legalcode"})
      (db/create-license-localization!
       {:licid link :langcode "fi" :title "CC Nimeä 4.0"
        :textcontent "https://creativecommons.org/licenses/by/4.0/legalcode.fi"})
      (db/create-license-localization!
       {:licid text :langcode "fi" :title "Yleiset käyttöehdot"
        :textcontent (apply str (repeat 10 "Suomenkielinen lisenssiteksti. "))})
      (db/create-license-localization!
       {:licid text :langcode "en" :title "General Terms of Use"
        :textcontent (apply str (repeat 10 "License text in English. "))})

      (doseq [wfid [minimal simple with-review two-round different dynamic]]
        (db/create-workflow-license! {:wfid wfid :licid link :round 0})
        (db/create-workflow-license! {:wfid wfid :licid text :round 0})
        (db/set-workflow-license-validity! {:licid link :start (time/minus (time/now) (time/years 1)) :end nil})
        (db/set-workflow-license-validity! {:licid text :start (time/minus (time/now) (time/years 1)) :end nil})))

    {:minimal minimal
     :simple simple
     :with-review with-review
     :dynamic-with-review dynamic
     :two-round two-round
     :different different
     :expired expired
     :dynamic dynamic}))

(defn- create-resource-license! [resid text owner]
  (let [licid (:id (db/create-license!
                    {:modifieruserid owner :owneruserid owner :title "resource license"
                     :type "link" :textcontent "http://invalid"}))]
    (db/create-license-localization!
     {:licid licid :langcode "en" :title (str text " (en)")
      :textcontent "https://www.apache.org/licenses/LICENSE-2.0"})
    (db/create-license-localization!
     {:licid licid :langcode "fi" :title (str text " (fi)")
      :textcontent "https://www.apache.org/licenses/LICENSE-2.0"})
    (db/create-resource-license! {:resid resid :licid licid})
    (db/set-resource-license-validity! {:licid licid :start (time/minus (time/now) (time/years 1)) :end nil})
    licid))

(defn- create-catalogue-item! [resource workflow form localizations]
  (let [id (:id (db/create-catalogue-item!
                 {:title "non-localized title" :resid resource :wfid workflow :form form}))]
    (doseq [[lang title] localizations]
      (catalogue/create-catalogue-item-localization! {:id id :langcode lang :title title}))
    id))

(defn trim-value-if-longer-than-fields-maxlength [value maxlength]
  (if (and maxlength (> (count value) maxlength))
    (subs value 0 maxlength)
    value))

(defn- create-draft! [user-id catids wfid field-value & [now]]
  (let [app-id (legacy/create-new-draft-at-time user-id wfid (or now (time/now)))
        catids (if (vector? catids) catids [catids])
        _ (doseq [catid catids]
            (db/add-application-item! {:application app-id :item catid}))
        ;; TODO don't use legacy get-form-for
        form (binding [context/*lang* :en]
               (legacy/get-form-for user-id app-id))
        dynamic-workflow? (= :workflow/dynamic (get-in form [:application :workflow :type]))
        save-draft-command (atom {:type :application.command/save-draft
                                  :actor user-id
                                  :application-id app-id
                                  :time (get-in form [:application :start])
                                  :field-values []})]
    (when dynamic-workflow?
      (applications/add-application-created-event! {:application-id app-id
                                                    :catalogue-item-ids catids
                                                    :time (get-in form [:application :start])
                                                    :actor user-id}))
    (doseq [{item-id :id maxlength :maxlength} (:items form)
            :let [trimmed-value (trim-value-if-longer-than-fields-maxlength field-value maxlength)]]
      (db/save-field-value! {:application app-id :form (:id form)
                             :item item-id :user user-id :value trimmed-value})
      (swap! save-draft-command
             update :field-values
             conj {:field item-id :value trimmed-value}))
    (db/update-application-description! {:id app-id :description field-value})
    (when-not dynamic-workflow?
      (doseq [{license-id :id} (:licenses form)]
        (db/save-license-approval! {:catappid app-id
                                    :round 0
                                    :licid license-id
                                    :actoruserid user-id
                                    :state "approved"})))
    (when dynamic-workflow?
      (let [error (applications/command! {:type :application.command/accept-licenses
                                          :actor user-id
                                          :application-id app-id
                                          :accepted-licenses (map :id (:licenses form))
                                          :time (time/now)})]
        (assert (nil? error) error))
      (let [error (applications/command! @save-draft-command)]
        (assert (nil? error) error)))
    app-id))

(defn- create-applications! [catid wfid applicant approver]
  (create-draft! applicant catid wfid "draft application")
  (let [application (create-draft! applicant catid wfid "applied application")]
    (legacy/submit-application applicant application))
  (let [application (create-draft! applicant catid wfid "rejected application")]
    (legacy/submit-application applicant application)
    (legacy/reject-application approver application 0 "comment for rejection"))
  (let [application (create-draft! applicant catid wfid "accepted application")]
    (legacy/submit-application applicant application)
    (legacy/approve-application approver application 0 "comment for approval"))
  (let [application (create-draft! applicant catid wfid "returned application")]
    (legacy/submit-application applicant application)
    (legacy/return-application approver application 0 "comment for return")))

(defn- run-and-check-dynamic-command! [& args]
  (let [result (apply applications/command! args)]
    (assert (nil? result) {:actual result})
    result))

(defn- create-disabled-applications! [catid wfid applicant approver]
  (create-draft! applicant catid wfid "draft with disabled item")
  (let [appid1 (create-draft! applicant catid wfid "approved application with disabled item")]
    (run-and-check-dynamic-command! {:application-id appid1
                                     :actor applicant
                                     :time (time/now)
                                     :type :application.command/submit}))
  (let [appid2 (create-draft! applicant catid wfid "submitted application with disabled item")]
    (run-and-check-dynamic-command! {:application-id appid2
                                     :actor applicant
                                     :time (time/now)
                                     :type :application.command/submit})
    (run-and-check-dynamic-command! {:application-id appid2
                                     :actor applicant
                                     :time (time/now)
                                     :type :application.command/approve
                                     :comment "Looking good"})))

(defn- create-bundled-application! [catid catid2 wfid applicant approver]
  (let [app-id (create-draft! applicant [catid catid2] wfid "bundled application")]
    (legacy/submit-application applicant app-id)
    (legacy/return-application approver app-id 0 "comment for return")
    (legacy/submit-application applicant app-id)))

(defn- create-member-applications! [catid wfid applicant approver members]
  (let [appid1 (create-draft! applicant catid wfid "draft with invited members")]
    (run-and-check-dynamic-command! {:application-id appid1
                                     :actor applicant
                                     :time (time/now)
                                     :type :application.command/invite-member
                                     :member {:name "John Smith" :email "john.smith@example.org"}}))
  (let [appid2 (create-draft! applicant catid wfid "submitted with members")]
    (run-and-check-dynamic-command! {:application-id appid2
                                     :actor applicant
                                     :time (time/now)
                                     :type :application.command/invite-member
                                     :member {:name "John Smith" :email "john.smith@example.org"}})
    (run-and-check-dynamic-command! {:application-id appid2
                                     :actor applicant
                                     :time (time/now)
                                     :type :application.command/submit})
    (doseq [member members]
      (run-and-check-dynamic-command! {:application-id appid2
                                       :actor approver
                                       :time (time/now)
                                       :type :application.command/add-member
                                       :member member}))))

(defn- create-dynamic-applications! [catid wfid users]
  (let [applicant (users :applicant1)
        approver (users :approver1)
        reviewer (users :reviewer)]
    (let [app-id (create-draft! applicant [catid] wfid "dynamic application")]
      (run-and-check-dynamic-command! {:application-id app-id :actor applicant :time (time/now) :type :application.command/submit}))
    (let [app-id (create-draft! applicant catid wfid "application with comment")] ; approved with comment
      (run-and-check-dynamic-command! {:application-id app-id :actor applicant :time (time/now) :type :application.command/submit}) ; submit
      (run-and-check-dynamic-command! {:application-id app-id :actor approver :time (time/now) :type :application.command/request-comment :commenters [reviewer] :comment "please have a look"})
      (run-and-check-dynamic-command! {:application-id app-id :actor reviewer :time (time/now) :type :application.command/comment :comment "looking good"})
      (run-and-check-dynamic-command! {:application-id app-id :actor approver :time (time/now) :type :application.command/approve :comment "Thank you! Approved!"}))

    (let [app-id (create-draft! applicant catid wfid "approved application that is closed")] ; approved then closed
      (run-and-check-dynamic-command! {:application-id app-id :actor applicant :time (time/now) :type :application.command/submit}) ; submit
      (run-and-check-dynamic-command! {:application-id app-id :actor approver :time (time/now) :type :application.command/request-comment :commenters [reviewer] :comment "please have a look"})
      (run-and-check-dynamic-command! {:application-id app-id :actor reviewer :time (time/now) :type :application.command/comment :comment "looking good"})
      (run-and-check-dynamic-command! {:application-id app-id :actor approver :time (time/now) :type :application.command/approve :comment "Thank you! Approved!"})
      (entitlements-poller/run)
      (run-and-check-dynamic-command! {:application-id app-id :actor approver :time (time/now) :type :application.command/close :comment "Research project complete, closing."}))
    (let [app-id (create-draft! applicant catid wfid "application in commenting")] ; still in commenting
      (run-and-check-dynamic-command! {:application-id app-id :actor applicant :time (time/now) :type :application.command/submit})
      (run-and-check-dynamic-command! {:application-id app-id :actor approver :time (time/now) :type :application.command/request-comment :commenters [reviewer] :comment ""}))
    (let [app-id (create-draft! applicant catid wfid "application in deciding")] ; still in deciding
      (run-and-check-dynamic-command! {:application-id app-id :actor applicant :time (time/now) :type :application.command/submit})
      (run-and-check-dynamic-command! {:application-id app-id :actor approver :time (time/now) :type :application.command/request-decision :deciders [reviewer] :comment ""}))))

(defn- create-review-applications! [catid wfid users]
  (let [applicant (users :applicant1)
        approver (users :approver1)
        reviewer (users :reviewer)]
    (let [app-id (create-draft! applicant catid wfid "application with review")]
      (legacy/submit-application applicant app-id)
      (legacy/review-application reviewer app-id 0 "comment for review")
      (legacy/approve-application approver app-id 1 "comment for approval")) ; already reviewed and approved
    (let [app-id (create-draft! applicant catid wfid "application in review")]
      (legacy/submit-application applicant app-id)))) ; still in review

(defn- create-application-with-expired-resource-license! [wfid form users]
  (let [applicant (users :applicant1)
        owner (users :owner)
        resource-id (:id (db/create-resource! {:resid "Resource that has expired license" :organization "nbn" :owneruserid owner :modifieruserid owner}))
        year-ago (time/minus (time/now) (time/years 1))
        yesterday (time/minus (time/now) (time/days 1))
        licid-expired (create-resource-license! resource-id "License that has expired" owner)
        _ (db/set-resource-license-validity! {:licid licid-expired :start year-ago :end yesterday})
        item-with-expired-license (create-catalogue-item! resource-id wfid form {"en" "Resource with expired resource license"
                                                                                 "fi" "Resurssi jolla on vanhentunut resurssilisenssi"})]
    (let [application (create-draft! applicant item-with-expired-license wfid "applied when license was valid that has since expired" (time/minus (time/now) (time/days 2)))]
      (legacy/submit-application applicant application))))

(defn- create-application-before-new-resource-license! [wfid form users]
  (let [applicant (users :applicant1)
        owner (users :owner)
        resource-id (:id (db/create-resource! {:resid "Resource that has a new resource license" :organization "nbn" :owneruserid owner :modifieruserid owner}))
        licid-new (create-resource-license! resource-id "License that was just created" owner)
        _ (db/set-resource-license-validity! {:licid licid-new :start (time/now) :end nil})
        item-without-new-license (create-catalogue-item! resource-id wfid form {"en" "Resource with just created new resource license"
                                                                                "fi" "Resurssi jolla on uusi resurssilisenssi"})
        application (create-draft! applicant item-without-new-license wfid "applied before license was valid" (time/minus (time/now) (time/days 2)))]
    (legacy/submit-application applicant application)))

(defn create-performance-test-data! []
  (let [resource-count 1000
        application-count 1000
        user-count 1000
        handlers [(+fake-users+ :approver1)
                  (+fake-users+ :approver2)]
        owner (+fake-users+ :owner)
        workflow-id (:id (workflow/create-workflow! {:user-id owner
                                                     :organization "perf"
                                                     :title "Performance tests"
                                                     :type :dynamic
                                                     :handlers handlers}))
        form-id (:id (form/create-form!
                      owner
                      {:organization "perf"
                       :title "Performance tests"
                       :fields [{:title {:en "Project name"
                                         :fi "Projektin nimi"}
                                 :optional false
                                 :type "description"
                                 :input-prompt {:en "Project"
                                                :fi "Projekti"}}

                                {:title {:en "Project description"
                                         :fi "Projektin kuvaus"}
                                 :optional false
                                 :type "texta"
                                 :input-prompt {:en "The purpose of the project is to..."
                                                :fi "Projektin tarkoitus on..."}}]}))
        form (form/get-form-template form-id)
        license-id (:id (licenses/create-license!
                         {:licensetype "text"
                          :title "Performance License"
                          :textcontent "Be fast."
                          :localizations {:en {:title "Performance license"
                                               :textcontent "Be fast."}
                                          :fi {:title "Suorituskykylisenssi"
                                               :textcontent "Ole nopea."}}}
                         owner))
        cat-item-ids (vec (for [_ (range resource-count)]
                            (let [uuid (UUID/randomUUID)
                                  resource (resource/create-resource!
                                            {:resid (str "urn:uuid:" uuid)
                                             :organization "perf"
                                             :licenses [license-id]}
                                            owner)
                                  _ (assert (:success resource))
                                  cat-item (catalogue/create-catalogue-item! {:title (str "Performance test resource " uuid)
                                                                              :form form-id
                                                                              :resid (:id resource)
                                                                              :wfid workflow-id})]
                              (:id cat-item))))
        user-ids (vec (for [n (range 1 (inc user-count))]
                        (let [user-id (str "perftester" n)]
                          (users/add-user! user-id {:eppn user-id
                                                    :mail (str user-id "@example.com")
                                                    :commonName (str "Performance Tester " n)})
                          user-id)))]
    (dotimes [_ application-count]
      (let [cat-item-id (rand-nth cat-item-ids)
            user-id (rand-nth user-ids)
            handler (rand-nth handlers)
            app-id (:application-id (applications/create-application! user-id [cat-item-id]))]
        (assert (nil? (applications/command!
                       {:type :application.command/save-draft
                        :actor user-id
                        :time (time/now)
                        :application-id app-id
                        :field-values [{:field (:id (first (:fields form)))
                                        :value (str "Performance test application " (UUID/randomUUID))}
                                       {:field (:id (second (:fields form)))
                                        ;; 5000 characters (10 KB) of lorem ipsum generated with www.lipsum.com
                                        ;; to increase the memory requirements of an application
                                        :value (str "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Ut non diam vel erat dapibus facilisis vel vitae nunc. Curabitur at fermentum lorem. Cras et bibendum ante. Etiam convallis erat justo. Phasellus cursus molestie vehicula. Etiam molestie tellus vitae consectetur dignissim. Pellentesque euismod hendrerit mi sed tincidunt. Integer quis lorem ut ipsum egestas hendrerit. Aenean est nunc, mattis euismod erat in, sodales rutrum mauris. Praesent sit amet risus quis felis congue ultricies. Nulla facilisi. Sed mollis justo id tristique volutpat.\n\nPhasellus augue mi, facilisis ac velit et, pharetra tristique nunc. Pellentesque eget arcu quam. Curabitur dictum nulla varius hendrerit varius. Proin vulputate, ex lacinia commodo varius, ipsum velit viverra est, eget molestie dui nisi non eros. Nulla lobortis odio a magna mollis placerat. Interdum et malesuada fames ac ante ipsum primis in faucibus. Integer consectetur libero ut gravida ullamcorper. Pellentesque habitant morbi tristique senectus et netus et malesuada fames ac turpis egestas. Donec aliquam feugiat mollis. Quisque massa lacus, efficitur vel justo vel, elementum mollis magna. Maecenas at sem sem. Praesent sed ex mattis, egestas dui non, volutpat lorem. Nulla tempor, nisi rutrum accumsan varius, tellus elit faucibus nulla, vel mattis lacus justo at ante. Sed ut mollis ex, sed tincidunt ex.\n\nMauris laoreet nibh eget erat tincidunt pharetra. Aenean sagittis maximus consectetur. Curabitur interdum nibh sed tincidunt finibus. Sed blandit nec lorem at iaculis. Morbi non augue nec tortor hendrerit mollis ut non arcu. Suspendisse maximus nec ligula a efficitur. Etiam ultrices rhoncus leo quis dapibus. Integer vel rhoncus est. Integer blandit varius auctor. Vestibulum suscipit suscipit risus, sit amet venenatis lacus iaculis a. Duis eu turpis sit amet nibh sagittis convallis at quis ligula. Sed eget justo quis risus iaculis lacinia vitae a justo. In hac habitasse platea dictumst. Maecenas euismod et lorem vel viverra.\n\nDonec bibendum nec ipsum in volutpat. Vivamus in elit venenatis, venenatis libero ac, ultrices dolor. Morbi quis odio in neque consequat rutrum. Suspendisse quis sapien id sapien fermentum dignissim. Nam eu est vel risus volutpat mollis sed quis eros. Proin leo nulla, dictum id hendrerit vitae, scelerisque in elit. Proin consectetur sodales arcu ac tristique. Suspendisse ut elementum ligula, at rhoncus mauris. Aliquam lacinia at diam eget mattis. Phasellus quam leo, hendrerit sit amet mi eget, porttitor aliquet velit. Proin turpis ante, consequat in enim nec, tempus consequat magna. Vestibulum fringilla ac turpis nec malesuada. Proin id lectus iaculis, suscipit erat at, volutpat turpis. In quis faucibus elit, ut maximus nibh. Sed egestas egestas dolor.\n\nNulla varius orci quam, id auctor enim ultrices nec. Morbi et tellus ac metus sodales convallis sed vehicula neque. Pellentesque rhoncus mattis massa a bibendum. Vestibulum ante ipsum primis in faucibus orci luctus et ultrices posuere cubilia Curae; Fusce tincidunt nulla non aliquet facilisis. Praesent nisl nisi, finibus id odio sed, consectetur feugiat mauris. Suspendisse sed lacinia ligula. Duis vitae nisl leo. Donec erat arcu, feugiat sit amet sagittis ac, scelerisque nec est. Pellentesque finibus mauris nulla, in maximus sapien pharetra vitae. Sed leo elit, consequat eu aliquam vitae, feugiat ut eros. Pellentesque dictum feugiat odio sed commodo. Lorem ipsum dolor sit amet, consectetur adipiscing elit. Proin neque quam, varius vel libero sit amet, rhoncus sollicitudin ex. In a dui non neque malesuada pellentesque.\n\nProin tincidunt nisl non commodo faucibus. Sed porttitor arcu neque, vitae bibendum sapien placerat nec. Integer eget tristique orci. Class aptent taciti sociosqu ad litora torquent per conubia nostra, per inceptos himenaeos. Donec eu molestie eros. Nunc iaculis rhoncus enim, vel mattis felis fringilla condimentum. Interdum et malesuada fames ac ante ipsum primis in faucibus. Aenean ac augue nulla. Phasellus vitae nulla lobortis, mattis magna ac, gravida ipsum. Aenean ornare non nunc non luctus. Aenean lacinia lectus nec velit finibus egestas vel ut ipsum. Cras hendrerit rhoncus erat, vel maximus nunc.\n\nPraesent quis imperdiet quam. Praesent ligula tellus, consectetur sed lacus eu, malesuada condimentum tellus. Donec et diam hendrerit, dictum diam quis, aliquet purus. Suspendisse pulvinar neque at efficitur iaculis. Nulla erat orci, euismod id velit sed, dictum hendrerit arcu. Nulla aliquam molestie aliquam. Duis et semper nisi, eget commodo arcu. Praesent rhoncus, nulla id sodales eleifend, ante ipsum pellentesque augue, id iaculis sem est vitae est. Phasellus cursus diam a lorem vestibulum sodales. Nullam lacinia tortor vel tellus commodo, sit amet sodales quam malesuada.\n\nNulla tempor lectus vel arcu feugiat, vel dapibus ex dapibus. Maecenas purus justo, aliquet et sem sit amet, tincidunt venenatis dui. Nulla eget purus id sapien elementum rutrum eu vel libero. Cras non accumsan justo posuere.\n\n"
                                                    ;; prevent string interning, just to be sure
                                                    (UUID/randomUUID))}]})))
        (assert (nil? (applications/command!
                       {:type :application.command/submit
                        :actor user-id
                        :time (time/now)
                        :application-id app-id})))
        (assert (nil? (applications/command!
                       {:type :application.command/approve
                        :actor handler
                        :time (time/now)
                        :application-id app-id
                        :comment "Looks fine."})))))))

(defn create-test-data! []
  (DateTimeUtils/setCurrentMillisFixed (.getMillis creation-time))
  (try
    (db/add-api-key! {:apikey 42 :comment "test data"})
    (create-users-and-roles!)
    (let [res1 (:id (db/create-resource! {:resid "urn:nbn:fi:lb-201403262" :organization "nbn" :owneruserid (+fake-users+ :owner) :modifieruserid (+fake-users+ :owner)}))
          res2 (:id (db/create-resource! {:resid "Extra Data" :organization "nbn" :owneruserid (+fake-users+ :owner) :modifieruserid (+fake-users+ :owner)}))
          _ (:id (db/create-resource! {:resid "Expired Resource, should not be seen" :organization "nbn" :owneruserid (+fake-users+ :owner) :modifieruserid (+fake-users+ :owner) :end (time/minus (time/now) (time/years 1))}))
          form (create-basic-form! +fake-users+)
          _ (create-archived-form!)
          workflows (create-workflows! +fake-users+)
          _ (create-catalogue-item! res1 (:minimal workflows) form
                                    {"en" "ELFA Corpus, direct approval"
                                     "fi" "ELFA-korpus, suora hyväksyntä"})
          simple (create-catalogue-item! res1 (:simple workflows) form
                                         {"en" "ELFA Corpus, one approval"
                                          "fi" "ELFA-korpus, yksi hyväksyntä"})
          bundlable (create-catalogue-item! res2 (:simple workflows) form
                                            {"en" "ELFA Corpus, one approval (extra data)"
                                             "fi" "ELFA-korpus, yksi hyväksyntä (lisäpaketti)"})
          with-review (create-catalogue-item! res1 (:with-review workflows) form
                                              {"en" "ELFA Corpus, with review"
                                               "fi" "ELFA-korpus, katselmoinnilla"})
          _ (create-catalogue-item! res1 (:different workflows) form
                                    {"en" "ELFA Corpus, two rounds of approval by different approvers"
                                     "fi" "ELFA-korpus, kaksi hyväksyntäkierrosta eri hyväksyjillä"})
          disabled (create-catalogue-item! res1 (:simple workflows) form
                                           {"en" "ELFA Corpus, one approval (extra data, disabled)"
                                            "fi" "ELFA-korpus, yksi hyväksyntä (lisäpaketti, pois käytöstä)"})]
      (create-resource-license! res2 "Some test license" (+fake-users+ :owner))
      (db/set-catalogue-item-state! {:id disabled :enabled false})
      (create-applications! simple (:simple workflows) (+fake-users+ :approver1) (+fake-users+ :approver1))
      (create-bundled-application! simple bundlable (:simple workflows) (+fake-users+ :applicant1) (+fake-users+ :approver1))
      (create-review-applications! with-review (:with-review workflows) +fake-users+)
      (create-application-with-expired-resource-license! (:simple workflows) form +fake-users+)
      (create-application-before-new-resource-license! (:simple workflows) form +fake-users+)
      (create-expired-license!)
      (let [dynamic (create-catalogue-item! res1 (:dynamic workflows) form
                                            {"en" "Dynamic workflow" "fi" "Dynaaminen työvuo"})]
        (create-dynamic-applications! dynamic (:dynamic workflows) +fake-users+))
      (let [thlform (create-thl-demo-form! +fake-users+)
            thl-catid (create-catalogue-item! res1 (:dynamic workflows) thlform {"en" "THL catalogue item" "fi" "THL katalogi-itemi"})]
        (create-member-applications! thl-catid (:dynamic workflows) (+fake-users+ :applicant1) (+fake-users+ :approver1) [{:userid (+fake-users+ :applicant2)}]))
      (let [dynamic-disabled (create-catalogue-item! res1 (:dynamic workflows) form
                                                     {"en" "Dynamic workflow (disabled)"
                                                      "fi" "Dynaaminen työvuo (pois käytöstä)"})]
        (create-disabled-applications! dynamic-disabled (:dynamic workflows) (+fake-users+ :approver1) (+fake-users+ :approver1))
        (db/set-catalogue-item-state! {:id dynamic-disabled :enabled false}))
      (let [dynamic-expired (create-catalogue-item! res1 (:dynamic workflows) form
                                                    {"en" "Dynamic workflow (expired)"
                                                     "fi" "Dynaaminen työvuo (vanhentunut)"})]
        (db/set-catalogue-item-state! {:id dynamic-expired :end (time/now)})))
    (finally
      (DateTimeUtils/setCurrentMillisSystem))))

(defn create-demo-data! []
  (db/add-api-key! {:apikey 55 :comment "Finna"})
  (create-demo-users-and-roles!)
  (let [res1 (:id (db/create-resource! {:resid "urn:nbn:fi:lb-201403262" :organization "nbn" :owneruserid (+demo-users+ :owner) :modifieruserid (+demo-users+ :owner)}))
        res2 (:id (db/create-resource! {:resid "Extra Data" :organization "nbn" :owneruserid (+demo-users+ :owner) :modifieruserid (+demo-users+ :owner)}))
        form (create-basic-form! +demo-users+)
        workflows (create-workflows! +demo-users+)]
    (create-resource-license! res2 "Some demo license" (+demo-users+ :owner))
    (create-expired-license!)
    (let [dynamic (create-catalogue-item! res1 (:dynamic workflows) form
                                          {"en" "Dynamic workflow" "fi" "Dynaaminen työvuo"})]
      (create-dynamic-applications! dynamic (:dynamic workflows) +demo-users+))
    (let [thlform (create-thl-demo-form! +demo-users+)
          thl-catid (create-catalogue-item! res1 (:dynamic workflows) thlform {"en" "THL catalogue item" "fi" "THL katalogi-itemi"})]
      (create-member-applications! thl-catid (:dynamic workflows) (+demo-users+ :applicant1) (+demo-users+ :approver1) [{:userid (+demo-users+ :applicant2)}]))
    (let [dynamic-disabled (create-catalogue-item! res1 (:dynamic workflows) form
                                                   {"en" "Dynamic workflow (disabled)"
                                                    "fi" "Dynaaminen työvuo (pois käytöstä)"})]
      (create-disabled-applications! dynamic-disabled (:dynamic workflows) (+demo-users+ :approver1) (+demo-users+ :approver1))
      (db/set-catalogue-item-state! {:id dynamic-disabled :enabled false}))))

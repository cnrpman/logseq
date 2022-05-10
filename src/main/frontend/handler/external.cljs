(ns frontend.handler.external
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk]
            [frontend.external :as external]
            [frontend.handler.file :as file-handler]
            [frontend.handler.repo :as repo-handler]
            [frontend.state :as state]
            [frontend.date :as date]
            [frontend.config :as config]
            [clojure.string :as string]
            [frontend.db :as db]
            [frontend.format.mldoc :as mldoc]
            [frontend.format.block :as block]
            [logseq.graph-parser.util :as gp-util]
            [logseq.graph-parser.date-time-util :as date-time-util]
            [frontend.handler.page :as page]
            [frontend.handler.editor :as editor]
            [frontend.handler.notification :as notification]
            [frontend.util :as util]))

(defn index-files!
  "Create file structure, then parse into DB (client only)"
  [repo files finish-handler]
  (let [titles (->> files
                    (map :title)
                    (remove nil?))
        files (map (fn [file]
                     (let [title (:title file)
                           journal? (date/valid-journal-title? title)]
                       (when-let [text (:text file)]
                         (let [title (or
                                      (when journal?
                                        (date/journal-title->default title))
                                      (string/replace title "/" "-"))
                               title (-> (gp-util/page-name-sanity title)
                                         (string/replace "\n" " "))
                               path (str (if journal?
                                           (config/get-journals-directory)
                                           (config/get-pages-directory))
                                         "/"
                                         title
                                         ".md")]
                           {:file/path path
                            :file/content text}))))
                   files)
        files (remove nil? files)]
    (repo-handler/parse-files-and-load-to-db! repo files nil)
    (let [files (->> (map (fn [{:file/keys [path content]}] (when path [path content])) files)
                     (remove nil?))]
      (file-handler/alter-files repo files {:add-history? false
                                            :update-db? false
                                            :update-status? false
                                            :finish-handler finish-handler}))
    (let [journal-pages-tx (let [titles (filter date/valid-journal-title? titles)]
                             (map
                              (fn [title]
                                (let [day (date/journal-title->int title)
                                      page-name (util/page-name-sanity-lc (date-time-util/int->journal-title day (state/get-date-formatter)))]
                                  {:block/name page-name
                                   :block/journal? true
                                   :block/journal-day day}))
                              titles))]
      (when (seq journal-pages-tx)
        (db/transact! repo journal-pages-tx)))))

(defn import-from-roam-json!
  [data finished-ok-handler]
  (when-let [repo (state/get-current-repo)]
    (let [files (external/to-markdown-files :roam data {})]
      (index-files! repo files
                    (fn []
                      (finished-ok-handler))))))


;;; import OPML files
(defn import-from-opml!
  [data finished-ok-handler]
  #_:clj-kondo/ignore
  (when-let [repo (state/get-current-repo)]
    (let [[headers parsed-blocks] (mldoc/opml->edn data)
          parsed-blocks (->>
                         (block/extract-blocks parsed-blocks "" true :markdown)
                         (mapv editor/wrap-parse-block))
          page-name (:title headers)]
      (when (not (db/page-exists? page-name))
        (page/create! page-name {:redirect? false}))
      (let [page-block (db/entity [:block/name (util/page-name-sanity-lc page-name)])
            children (:block/_parent page-block)
            blocks (db/sort-by-left children page-block)
            last-block (last blocks)
            snd-last-block (last (butlast blocks))
            [target-block sibling?] (if (and last-block (seq (:block/content last-block)))
                                      [last-block true]
                                      (if snd-last-block
                                        [snd-last-block true]
                                        [page-block false]))]
        (editor/paste-blocks
         parsed-blocks
         {:target-block target-block
          :sibling? sibling?})
        (finished-ok-handler [page-name])))))

(defn create-page-with-exported-tree!
  "Create page from the per page object generated in `export-repo-as-edn-v2!`
   Return page-name (title)
   Extension to `insert-block-tree-after-target`
   :id       - page's uuid
   :title    - page's title (original name)
   :children - tree
   "
  [{:keys [id title children] :as tree}]
  (let [has-children? (seq children)
        page-format (some-> tree (:children) (first) (:format))]
    (try (page/create! title {:redirect?  false
                                  :format     page-format
                                  :uuid       id})
         (catch js/Error e
           (notification/show! (str "Error happens when creating page " title ":\n"
                                    e
                                    "\nSkipped and continue the remaining import.") :error)))
    (when has-children?
      (let [page-block (db/entity [:block/name (util/page-name-sanity-lc title)])]
        ;; Missing support for per block format (or deprecated?)
        (try (editor/insert-block-tree children page-format
                                       {:target-block page-block
                                        :sibling?     true
                                        :keep-uuid?   true})
             (catch js/Error e
               (notification/show! (str "Error happens when creating block content of page " title "\n"
                                        e
                                        "\nSkipped and continue the remaining import.") :error))))))
  title)

(defn- pre-transact-uuids
  "Collect all uuids from page trees and write them to the db before hand."
  [pages]
  (let [uuids (map (fn [block]
                     {:block/uuid (:uuid block)})
                   (mapcat #(tree-seq map? :children %)
                           pages))]
    (db/transact! uuids)
    pages))

(defn- import-from-tree!
  "Not rely on file system - backend compatible.
   tree-translator-fn: translate exported tree structure to the desired tree for import"
  [data tree-translator-fn]
  (when-let [_repo (state/get-current-repo)]
    (try (->> (:blocks data)
              (map tree-translator-fn)
              (pre-transact-uuids)
              (mapv create-page-with-exported-tree!))
         (editor/set-blocks-id! (db/get-all-referenced-blocks-uuid))
         (catch js/Error e
           (notification/show! (str "Error happens when importing:\n" e) :error)))))

(defn tree-vec-translate-edn
  "Actions to do for loading edn tree structure.
   1) Removes namespace `:block/` from all levels of the `tree-vec`
   2) Rename all :block/page-name to :title
   3) Rename all :block/id to :uuid
   4) Dissoc all :properties"
  ([tree-vec]
   (let [rm-kw-ns-fn #(-> %
                          str
                          (string/replace ":block/page-name" ":block/title")
                          (string/replace ":block/id" ":block/uuid")
                          (string/replace ":block/" "")
                          keyword)
         transform-map (fn [form]
                         (if (map? form)
                           ;; build a new map with the same keys but without the namespace
                           (reduce-kv (fn [acc k v]
                                        (if (not= :block/properties k)
                                          (assoc acc (rm-kw-ns-fn k) v)
                                          acc)) {} form)
                           form))]
     (walk/postwalk transform-map tree-vec))))

(defn import-from-edn!
  [raw finished-ok-handler]
  (import-from-tree! (edn/read-string raw) tree-vec-translate-edn)
  (finished-ok-handler nil)) ;; it was designed to accept a list of imported page names but now deprecated

(defn tree-vec-translate-json
  "Actions to do for loading json tree structure.
   1) Rename all :id to :uuid
   2) Rename all :page-name to :title
   3) Dissoc all :properties
   4) Rename all :format \"markdown\" to :format `:markdown`"
  ([tree-vec]
   (let [rm-kw-ns-fn #(-> %
                          str
                          (string/replace ":page-name" ":title")
                          (string/replace ":id" ":uuid")
                          (string/replace #"^:" "")
                          keyword)
         transform-map (fn [form]
                         (if (map? form)
                           (reduce-kv (fn [acc k v]
                                        (if (not= :properties k)
                                          (let [k (rm-kw-ns-fn k)
                                                v (if (= k :format) (keyword v) v)]
                                            (assoc acc k v))
                                          acc)) {} form)
                           form))
         _ (prn tree-vec)
         _ (prn (walk/postwalk transform-map tree-vec))]
     (walk/postwalk transform-map tree-vec))))

(defn import-from-json!
  [raw finished-ok-handler]
  (let [json     (js/JSON.parse raw)
        clj-data (js->clj json :keywordize-keys true)]
    (import-from-tree! clj-data tree-vec-translate-json))
  (finished-ok-handler nil)) ;; it was designed to accept a list of imported page names but now deprecated

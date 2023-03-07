(ns frontend.fs.diff-merge
  ;; Disable clj linters since we don't support clj
  #?(:clj {:clj-kondo/config {:linters {:unresolved-namespace {:level :off}
                                        :unresolved-symbol {:level :off}}}})
  (:require #?(:org.babashka/nbb ["@logseq/diff-merge$default" :refer [Merger Differ visualizeAsHTML attach_uuids]]
               :default ["@logseq/diff-merge" :refer [Differ Merger visualizeAsHTML attach_uuids]])
            [logseq.graph-parser.block :as gp-block]
            [logseq.graph-parser.property :as gp-property]
            [logseq.graph-parser.utf8 :as utf8]
            [cljs-bean.core :as bean]
            [frontend.db.utils :as db-utils]
            [frontend.db.model :as db-model]))

;; (defn diff-merge
;;   "N-ways diff & merge
;;    Accept: blocks
;;    https://github.com/logseq/diff-merge/blob/44546f2427f20bd417b898c8ba7b7d10a9254774/lib/mldoc.ts#L17-L22
;;    https://github.com/logseq/diff-merge/blob/85ca7e9bf7740d3880ed97d535a4f782a963395d/lib/merge.ts#L40"
;;   [base & branches]
;;   ()
;;   (let [merger (Merger.)]
;;     (.mergeBlocks merger (bean/->js base) (bean/->js branches))))

(defn diff 
  "2-ways diff
   Accept: blocks
   https://github.com/logseq/diff-merge/blob/44546f2427f20bd417b898c8ba7b7d10a9254774/lib/mldoc.ts#L17-L22"
  [base income]
  (let [differ (Differ.)]
    (.diff_logseqMode differ (bean/->js base) (bean/->js income))))

;; (defonce getHTML visualizeAsHTML)

(defonce attachUUID attach_uuids)

(defn db->diff-blocks
  "db: datascript db
   page-name: string"
  [page-name]
  {:pre (string? page-name)}
  (let [walked (db-model/get-sorted-page-block-ids-and-levels page-name)
        blocks (db-utils/pull-many [:block/uuid :block/content :block/level] (map :id walked))
        levels (map :level walked)
        blocks (map (fn [block level]
                      {:uuid   (str (:block/uuid block)) ;; Force to be string
                       :body   (:block/content block)
                       :level  level})
                    blocks levels)]
    blocks))

;; TODO Junyi: merge back to gp-block/extract-blocks
;; From back to first to ensure end_pos is correct
(defn ast->diff-blocks
  "Prepare the blocks for diff-merge
   blocks: ast of blocks
   content: corresponding raw content"
  [blocks content format {:keys [user-config block-pattern]}]
  {:pre [(string? content) (contains? #{:markdown :org} format)]}
  (let [encoded-content (utf8/encode content)]
    (loop [headings []
           blocks (reverse blocks)
           properties {}
           end-pos (.-length encoded-content)]
      (if (seq blocks)
        (let [[block pos-meta] (first blocks)
              ;; fix start_pos
              pos-meta (assoc pos-meta :end_pos end-pos)]
          (cond
            (gp-block/heading-block? block)
            (let [content (gp-block/get-block-content encoded-content block format pos-meta block-pattern)]
              (recur (conj headings {:body  content
                                     :level (:level (second block))
                                     :uuid  (:id properties)})
                     (rest blocks) {} (:start_pos pos-meta))) ;; The current block's start pos is the next block's end pos

            (gp-property/properties-ast? block)
            (let [new-props (:properties (gp-block/extract-properties (second block) (assoc user-config :format format)))]
              ;; sending the current end pos to next, as it's not finished yet
              ;; supports multiple properties sub-block possible in future
              (recur headings (rest blocks) (merge properties new-props) (:end_pos pos-meta)))

            :else
            (recur headings (rest blocks) properties (:end_pos pos-meta))))
        (if (empty? properties)
          (reverse headings)
          (let [[block _] (first blocks)
                pos-meta {:start_pos 0 :end_pos end-pos}
                content (gp-block/get-block-content encoded-content block format pos-meta block-pattern)
                uuid (:id properties)]
            (cons {:body content
                   :level 1
                   :uuid uuid}
                  (reverse headings))))))))

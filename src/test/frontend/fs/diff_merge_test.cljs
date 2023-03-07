(ns frontend.fs.diff-merge-test
  (:require [datascript.core :as d]
            [cljs.test :refer [deftest are is]]
            [logseq.db :as ldb]
            [logseq.graph-parser :as graph-parser]
            [frontend.fs.diff-merge :as fs-diff]
            [frontend.handler.common.file :as file-common-handler]
            [frontend.db.conn :as conn]
            [logseq.graph-parser.mldoc :as gp-mldoc]
            [cljs-bean.core :as bean]))

(defn test-db->diff-blocks
  "A hijacked version of db->diff-blocks for testing.
   It overwrites the internal db getter with the test db connection."
  [conn & args]
  (with-redefs [conn/get-db (constantly @conn)]
    (apply fs-diff/db->diff-blocks args)))

(defn org-text->diffblocks
  [text]
  (-> (gp-mldoc/->edn text (gp-mldoc/default-config :org))
      (fs-diff/ast->diff-blocks text :org {:block-pattern "-"})))

(deftest org->ast->diff-blocks-test
  (are [text diff-blocks]
       (= (org-text->diffblocks text)
          diff-blocks)
        ":PROPERTIES:
:ID:       72289d9a-eb2f-427b-ad97-b605a4b8c59b
:END:
#+tItLe: Well parsed!"
[{:body ":PROPERTIES:\n:ID:       72289d9a-eb2f-427b-ad97-b605a4b8c59b\n:END:\n#+tItLe: Well parsed!" 
  :uuid "72289d9a-eb2f-427b-ad97-b605a4b8c59b" 
  :level 1}]
    
    "#+title: Howdy"
    [{:body "#+title: Howdy" :uuid nil :level 1}]
    
    ":PROPERTIES:
:fiction: [[aldsjfklsda]]
:END:\n#+title: Howdy"
    [{:body ":PROPERTIES:\n:fiction: [[aldsjfklsda]]\n:END:\n#+title: Howdy" 
      :uuid nil 
      :level 1}]))

(deftest db<->ast-diff-blocks-test
  (let [conn (ldb/start-conn)
        text                                    ":PROPERTIES:
:ID:       72289d9a-eb2f-427b-ad97-b605a4b8c59b
:END:
#+tItLe: Well parsed!"]
    (graph-parser/parse-file conn "foo.org" text {})
    (is (= (test-db->diff-blocks conn "Well parsed!")
           (org-text->diffblocks text)))))

(defn text->diffblocks
  [text]
  (-> (gp-mldoc/->edn text (gp-mldoc/default-config :markdown))
      (fs-diff/ast->diff-blocks text :markdown {:block-pattern "-"})))

(deftest md->ast->diff-blocks-test
  (are [text diff-blocks]
       (= (text->diffblocks text)
          diff-blocks)
  "- a
\t- b
\t\t- c"
  [{:body "a" :uuid nil :level 1}
   {:body "b" :uuid nil :level 2}
   {:body "c" :uuid nil :level 3}]

  "## hello
\t- world
\t\t- nice
\t\t\t- nice
\t\t\t- bingo
\t\t\t- world"
  [{:body "## hello" :uuid nil :level 2}
   {:body "world" :uuid nil :level 2}
   {:body "nice" :uuid nil :level 3}
   {:body "nice" :uuid nil :level 4}
   {:body "bingo" :uuid nil :level 4}
   {:body "world" :uuid nil :level 4}]

  "# a
## b
### c
#### d
### e
- f
\t- g
\t\t- h
\t- i
- j"
  [{:body "# a" :uuid nil :level 1}
   {:body "## b" :uuid nil :level 2}
   {:body "### c" :uuid nil :level 3}
   {:body "#### d" :uuid nil :level 4}
   {:body "### e" :uuid nil :level 3}
   {:body "f" :uuid nil :level 1}
   {:body "g" :uuid nil :level 2}
   {:body "h" :uuid nil :level 3}
   {:body "i" :uuid nil :level 2}
   {:body "j" :uuid nil :level 1}]
  
    "- a\n  id:: 63e25526-3612-4fb1-8cf9-f66db1254a58
\t- b
\t\t- c"
[{:body "a\n id:: 63e25526-3612-4fb1-8cf9-f66db1254a58" 
  :uuid "63e25526-3612-4fb1-8cf9-f66db1254a58" :level 1}
 {:body "b" :uuid nil :level 2}
 {:body "c" :uuid nil :level 3}]))

(deftest diff-test
  (are [text1 text2 diffs]
       (= (bean/->clj (fs-diff/diff (text->diffblocks text1)
                                    (text->diffblocks text2)))
          diffs)
    "## hello
\t- world
\t\t- nice
\t\t\t- nice
\t\t\t- bingo
\t\t\t- world"
      "## Halooooo
\t- world
\t\t- nice
\t\t\t- nice
\t\t\t- bingo
\t\t\t- world"
    [[[-1 {:body "## hello"
          :level 2
          :uuid nil}]
      [1  {:body "## Halooooo"
          :level 2
          :uuid nil}]]
     [[0 {:body "world"
         :level 2
         :uuid nil}]]
     [[0 {:body "nice"
         :level 3
         :uuid nil}]]
     [[0 {:body "nice"
         :level 4
         :uuid nil}]]
     [[0 {:body "bingo"
         :level 4
         :uuid nil}]]
     [[0 {:body "world"
         :level 4
         :uuid nil}]]]
    
    "## hello
\t- world
\t  id:: 63e25526-3612-4fb1-8cf9-abcd12354abc
\t\t- nice
\t\t\t- nice
\t\t\t- bingo
\t\t\t- world"
"## Halooooo
\t- world
\t\t- nice
\t\t\t- nice
\t\t\t- bingo
\t\t\t- world"
[[[-1 {:body "## hello"
       :level 2
       :uuid nil}]
  [1  {:body "## Halooooo"
       :level 2
       :uuid nil}]
  [1 {:body "world"
      :level 2
      :uuid nil}]]
 [[-1 {:body "world\n  id:: 63e25526-3612-4fb1-8cf9-abcd12354abc"
      :level 2
      :uuid "63e25526-3612-4fb1-8cf9-abcd12354abc"}]]
 [[0 {:body "nice"
      :level 3
      :uuid nil}]]
 [[0 {:body "nice"
      :level 4
      :uuid nil}]]
 [[0 {:body "bingo"
      :level 4
      :uuid nil}]]
 [[0 {:body "world"
      :level 4
      :uuid nil}]]]

""
"- abc def"
[[[1 {:body "abc def"
      :level 1
      :uuid nil}]]]))

(deftest db->diffblocks
  (let [conn (ldb/start-conn)]
    (graph-parser/parse-file conn
                             "foo.md"
                             (str "- abc
  id:: 11451400-0000-0000-0000-000000000000\n"
                                  "- def
  id:: 63246324-6324-6324-6324-632463246324\n")
                             {})
    (graph-parser/parse-file conn
                             "bar.md"
                             (str "- ghi
  id:: 11451411-1111-1111-1111-111111111111\n"
                                  "\t- jkl
\t  id:: 63241234-1234-1234-1234-123412341234\n")
                             {})
    (are [page-name diff-blocks] (= (test-db->diff-blocks conn page-name)
                                    diff-blocks)
      "foo"
      [{:body "abc\nid:: 11451400-0000-0000-0000-000000000000" :uuid  "11451400-0000-0000-0000-000000000000" :level 1}
       {:body "def\nid:: 63246324-6324-6324-6324-632463246324" :uuid  "63246324-6324-6324-6324-632463246324" :level 1}]

      "bar"
      [{:body "ghi\nid:: 11451411-1111-1111-1111-111111111111" :uuid  "11451411-1111-1111-1111-111111111111" :level 1}
       {:body "jkl\nid:: 63241234-1234-1234-1234-123412341234" :uuid  "63241234-1234-1234-1234-123412341234" :level 2}]) 

    (are [page-name text new-uuids] (= (let [old-blks (test-db->diff-blocks conn page-name)
                                             new-blks (text->diffblocks text)
                                             diff-ops (fs-diff/diff old-blks new-blks)]
                                         (bean/->clj (fs-diff/attachUUID diff-ops (bean/->js (map :uuid old-blks)) "NEW_ID")))
                                       new-uuids)
      "foo"
      "- abc
- def"
      ["11451400-0000-0000-0000-000000000000"
       "NEW_ID"]

      "bar"
      "- ghi
\t- jkl"
      ["11451411-1111-1111-1111-111111111111"
       "NEW_ID"]

      "non exist page"
      "- k\n\t- l"
      ["NEW_ID" "NEW_ID"]

      "another non exist page"
      ":PROPERTIES:
:ID:       72289d9a-eb2f-427b-ad97-b605a4b8c59b
:END:
#+tItLe: Well parsed!"
      ["72289d9a-eb2f-427b-ad97-b605a4b8c59b"])))

(deftest ast->diff-blocks-test
  (are [ast text diff-blocks]
       (= (fs-diff/ast->diff-blocks ast text :org {:block-pattern "-"})
          diff-blocks)
    [[["Properties" [["TiTlE" "Howdy" []]]] nil]]
    "#+title: Howdy"
    [{:body "#+title: Howdy", :level 1, :uuid nil}])
  
  (are [ast text diff-blocks]
       (= (fs-diff/ast->diff-blocks ast text :org {:block-pattern "-" :user-config {:property-pages/enabled? true}})
          diff-blocks)
    [[["Property_Drawer" [["foo" "#bar" [["Tag" [["Plain" "bar"]]]]] ["baz" "#bing" [["Tag" [["Plain" "bing"]]]]]]] {:start_pos 0, :end_pos 22}]]
    "foo:: #bar\nbaz:: #bing"
     [{:body "foo:: #bar\nbaz:: #bing", :level 1, :uuid nil}]))

(deftest ast-empty-diff-test
  (are [ast text diff-ops]
       (= (bean/->clj (->> (fs-diff/ast->diff-blocks ast text :org {:block-pattern "-" :user-config {:property-pages/enabled? true}})
                           (fs-diff/diff [])))
          diff-ops)
    [[["Property_Drawer" [["foo" "#bar" [["Tag" [["Plain" "bar"]]]]] ["baz" "#bing" [["Tag" [["Plain" "bing"]]]]]]] {:start_pos 0, :end_pos 22}]]
    "foo:: #bar\nbaz:: #bing"
     [[[1 {:body "foo:: #bar\nbaz:: #bing", :level 1, :uuid nil}]]]))

;; Ensure diff-merge-uuids follows the id:: in the content
(deftest diff-merge-uuid-extract-test
  (let [conn (ldb/start-conn)
        foo-content (str "- abc
  id:: 11451400-0000-0000-0000-000000000000\n"
                 "- def
  id:: 63246324-6324-6324-6324-632463246324\n")
        bar-content (str "- ghi
  id:: 11451411-1111-1111-1111-111111111111\n"
                         "\t- jkl
\t  id:: 63241234-1234-1234-1234-123412341234\n") ]
    (graph-parser/parse-file conn "foo.md" foo-content {})
    (graph-parser/parse-file conn "bar.md" bar-content {})
    (are [ast content page-name uuids]
         (= (with-redefs [conn/get-db (constantly @conn)]
              (#'file-common-handler/diff-merge-uuids :markdown ast content {:page-name page-name
                                                                             :block-pattern "-"}))
            uuids)

      (gp-mldoc/->edn (str foo-content "- newline\n") (gp-mldoc/default-config :markdown))
      (str foo-content "- newline\n")
      "foo"
      ["11451400-0000-0000-0000-000000000000"
       "63246324-6324-6324-6324-632463246324"
       nil]

      (gp-mldoc/->edn (str bar-content "- newline\n") (gp-mldoc/default-config :markdown))
      (str bar-content "- newline\n")
      "bar"
      ["11451411-1111-1111-1111-111111111111"
       "63241234-1234-1234-1234-123412341234"
       nil])))

;; Ensure diff-merge-uuids keeps the block uuids unchanged at best effort
(deftest diff-merge-uuid-persist-test
  (let [conn (ldb/start-conn)
        foo-content (str "- abc\n"
                         "- def\n")
        bar-content (str "- ghi\n"
                         "\t- jkl\n")]
    (graph-parser/parse-file conn "foo.md" foo-content {})
    (graph-parser/parse-file conn "bar.md" bar-content {})
    (are [ast content page-name uuids]
         (= (with-redefs [conn/get-db (constantly @conn)]
              (#'file-common-handler/diff-merge-uuids :markdown ast content {:page-name page-name
                                                                             :block-pattern "-"}))
            ;; Get all uuids under the page
            (conj (->> page-name
                       (test-db->diff-blocks conn)
                       (map :uuid)
                       (vec)) nil))

      (gp-mldoc/->edn (str foo-content "- newline\n") (gp-mldoc/default-config :markdown))
      (str foo-content "- newline\n")
      "foo"
      ["11451400-0000-0000-0000-000000000000"
       "63246324-6324-6324-6324-632463246324"
       nil]

      (gp-mldoc/->edn (str bar-content "- newline\n") (gp-mldoc/default-config :markdown))
      (str bar-content "- newline\n")
      "bar"
      ["11451411-1111-1111-1111-111111111111"
       "63241234-1234-1234-1234-123412341234"
       nil])))

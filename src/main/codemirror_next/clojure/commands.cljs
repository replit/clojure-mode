(ns codemirror-next.clojure.commands
  (:require ["@codemirror/next/commands" :as commands :refer [defaultKeymap]]
            ["@codemirror/next/history" :as history :refer [historyKeymap]]
            ["@codemirror/next/state" :refer [EditorState IndentContext]]
            [codemirror-next.clojure.extensions.formatting :as indent]
            [applied-science.js-interop :as j]
            [codemirror-next.clojure.util :as u]
            [codemirror-next.clojure.selections :as sel]
            [codemirror-next.clojure.node :as n]
            [codemirror-next.clojure.extensions.formatting :as format]))

(defn cmd-wrap [f]
  (j/fn [^:js {:keys [^js state dispatch]}]
    (-> (f state)
        (format/format-transaction)
        (dispatch))
    true))

(defn unwrap [state]
  (u/update-ranges state
    (j/fn [^:js {:as range :keys [from to empty]}]
      (when empty
        (when-let [nearest-balanced-coll
                   (some-> (.resolve (.-tree state) from -1)
                           (n/closest n/coll?)
                           (u/guard n/balanced?))]
          {:range (sel/cursor (dec from))
           :changes [(n/range (.-firstChild nearest-balanced-coll))
                     (n/range (.-lastChild nearest-balanced-coll))]})))))

(defn kill [^js state]
  (u/update-ranges state
    (j/fn [^:js {:as range :keys [from to empty]}]
      (or (when empty
            (let [node (.resolve (.-tree state) from)
                  parent (n/closest node #(or (n/coll? %)
                                              (= "Program" (n/name %))))
                  line-end (.-to (.lineAt (.-doc state) from))
                  next-children (when parent (n/child-subtrees parent from 1))
                  last-child-on-line
                  (when parent (some->> next-children
                                        (take-while (every-pred
                                                     #(<= (.-start ^js %) line-end)))
                                        last))
                  end (cond (n/string? node) (dec (.-end node))
                            last-child-on-line (if (n/closing-bracket? last-child-on-line)
                                                 (.-start last-child-on-line)
                                                 (.-end last-child-on-line))
                            (some-> (first next-children)
                                    .-start
                                    (> line-end)) (-> (first next-children) .-start))]
              (when end
                {:range (sel/cursor from)
                 :changes {:from from
                           :to end}})))
          (when-not empty
            {:range (sel/cursor from)
             :changes (u/from-to from to)})))))

(defn nav-position [state from dir]
  (or (some-> (n/closest (.. state -tree (resolve from))
                         #(or (n/coll? %)
                              (n/top? %)))
              (n/child-subtrees from dir)
              first
              (j/get (case dir -1 :start 1 :end)))
      (sel/in-doc (+ from dir) state)))

(defn nav [dir]
  (fn [state]
    (u/update-ranges state
      (j/fn [^:js {:as range :keys [from to empty]}]
        (if empty
          {:range (sel/cursor (nav-position state from dir))}
          {:range (sel/cursor (j/get (u/from-to from to) (case dir -1 :from 1 :to)))})))))

(defn nav-select [dir]
  (fn [^js state]
    (u/update-ranges state
      (j/fn [^:js {:as range :keys [from to empty]}]
        (if empty
          {:range (n/balanced-range state from (nav-position state from dir))}
          {:range (j/let [^:js {:keys [from to]} (u/from-to from to)]
                    (case dir
                      1 (n/balanced-range state from (nav-position state to dir))
                      -1 (n/balanced-range state (nav-position state from dir) to)))})))))

(defn balance-ranges [^js state]
  (u/update-ranges state
    (j/fn [^:js {:keys [from to empty]}]
      (when-not empty
        {:range (n/balanced-range state from to)}))))

(defn slurp [direction]
  (fn [^js state]
    (u/update-ranges state
      (j/fn [^:js {:as range :keys [from to empty]}]
        (when empty
          (when-let [parent (n/closest (n/resolve state from) (every-pred n/coll?
                                                                          #(not (case direction 1 (some-> (n/right %) n/closing-bracket?)
                                                                                                -1 (some-> (n/left %) n/opening-bracket?)))))]
            (when-let [target (case direction 1 (first (remove n/line-comment? (n/rights parent)))
                                              -1 (first (remove n/line-comment? (n/lefts parent))))]
              {:map-cursor from
               :changes (case direction
                          1
                          [{:from (-> target n/end)
                            :insert (n/name (n/last-child parent))}
                           (-> parent
                               n/last-child
                               n/range
                               (j/assoc! :insert " "))]
                          -1
                          [(-> parent
                               n/first-child
                               n/range
                               (j/assoc! :insert " "))
                           {:from (n/start target)
                            :insert (n/name (n/first-child parent))}])})))))))

(defn barf [direction]
  (fn [^js state]
    (->> (j/fn [^:js {:as range :keys [from to empty]}]
           (when empty
             (when-let [parent (n/closest (n/resolve state from) n/coll?)]
               (case direction
                 1
                 (when-let [target (some->> (n/last-child parent)
                                            n/lefts
                                            (remove n/line-comment?)
                                            (drop 1)
                                            first)]

                   {:map-cursor (min (n/end target) from)
                    :changes [{:from (n/end target)
                               :insert (n/name (n/last-child parent))}
                              (-> (n/last-child parent)
                                  n/range
                                  (j/assoc! :insert " "))]})
                 -1
                 (when-let [target (some->> (n/first-child parent)
                                            n/rights
                                            (remove n/line-comment?)
                                            (drop 1)
                                            first)]
                   {
                    ;:map-cursor (max from (inc (n/start target)))
                    :range (sel/cursor (max from (inc (n/start target))))
                    :changes [{:from (n/start target)
                               :insert (-> parent n/first-child n/name)}
                              (-> (n/first-child parent)
                                  n/range
                                  (j/assoc! :insert " "))
                              ]})))))
         (u/update-ranges state))))

(def index
  "Mapping of keyword-id to command functions"
  {:cursorSyntaxLeft commands/cursorSyntaxLeft
   :selectSyntaxLeft commands/selectSyntaxLeft
   :cursorSyntaxRight commands/cursorSyntaxRight
   :selectSyntaxRight commands/selectSyntaxRight
   :moveLineUp commands/moveLineUp
   :copyLineUp commands/copyLineUp
   :moveLineDown commands/moveLineDown
   :copyLineDown commands/copyLineDown
   :simplifySelection commands/simplifySelection
   :selectLine commands/selectLine
   :selectParentSyntax commands/selectParentSyntax
   :indentLess commands/indentLess
   :indentMore commands/indentMore
   :indentSelection commands/indentSelection
   :deleteLine commands/deleteLine
   :cursorMatchingBracket commands/cursorMatchingBracket
   :cursorCharLeft commands/cursorCharLeft
   :selectCharLeft commands/selectCharLeft
   :cursorGroupLeft commands/cursorGroupLeft
   :selectGroupLeft commands/selectGroupLeft
   :cursorLineStart commands/cursorLineStart
   :selectLineStart commands/selectLineStart
   :cursorCharRight commands/cursorCharRight
   :selectCharRight commands/selectCharRight
   :cursorGroupRight commands/cursorGroupRight
   :selectGroupRight commands/selectGroupRight
   :cursorLineEnd commands/cursorLineEnd
   :selectLineEnd commands/selectLineEnd
   :cursorLineUp commands/cursorLineUp
   :selectLineUp commands/selectLineUp
   :cursorDocStart commands/cursorDocStart
   :selectDocStart commands/selectDocStart
   :cursorPageUp commands/cursorPageUp
   :selectPageUp commands/selectPageUp
   :cursorLineDown commands/cursorLineDown
   :selectLineDown commands/selectLineDown
   :cursorDocEnd commands/cursorDocEnd
   :selectDocEnd commands/selectDocEnd
   :cursorPageDown commands/cursorPageDown
   :selectPageDown commands/selectPageDown
   :cursorLineBoundaryBackward commands/cursorLineBoundaryBackward
   :selectLineBoundaryBackward commands/selectLineBoundaryBackward
   :cursorLineBoundaryForward commands/cursorLineBoundaryForward
   :selectLineBoundaryForward commands/selectLineBoundaryForward
   :insertNewlineAndIndent commands/insertNewlineAndIndent
   :selectAll commands/selectAll
   :deleteCharBackward commands/deleteCharBackward
   :deleteCharForward commands/deleteCharForward
   :deleteGroupBackward commands/deleteGroupBackward
   :deleteGroupForward commands/deleteGroupForward
   :cursorGroupBackward commands/cursorGroupBackward
   :selectGroupBackward commands/selectGroupBackward
   :cursorGroupForward commands/cursorGroupForward
   :selectGroupForward commands/selectGroupForward
   :splitLine commands/splitLine
   :transposeChars commands/transposeChars
   :deleteToLineEnd commands/deleteToLineEnd

   :undo history/undo
   :redo history/redo
   :undoSelection history/undoSelection
   :redoSelection history/redoSelection

   :indent (cmd-wrap #'indent/format)
   :unwrap (cmd-wrap #'unwrap)
   :kill (cmd-wrap #'kill)
   :nav-right (cmd-wrap (nav 1))
   :nav-left (cmd-wrap (nav -1))
   :nav-select-right (cmd-wrap (nav-select 1))
   :nav-select-left (cmd-wrap (nav-select -1))
   :slurp-forward (cmd-wrap (slurp 1))
   :slurp-backward (cmd-wrap (slurp -1))
   :barf-forward (cmd-wrap (barf 1))
   :barf-backward (cmd-wrap (barf -1))
   })

(def reverse-index
  "Lookup keyword-id by function"
  (reduce-kv #(assoc %1 %3 %2) {} index))
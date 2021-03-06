;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

;functional hierarchical zipper, with navigation, editing and enumeration
;see Huet

(ns
  term.zip
  (:refer-clojure :exclude (replace remove next))
  (:use clj-tuple)
  (:use term.protocols)
  (:import term.protocols.AtomicTerm term.protocols.CompoundTerm)

  )

(defrecord ZipperPath [l r ppath pnodes changed?])

(defrecord ZipperLocation [node path])

(defn zipper [root]  (ZipperLocation. root nil))

(defprotocol IBuildable
  (build [base content]))


(extend-protocol
    IBuildable
  term.protocols.CompoundTerm
  (build [base content]
    (term.protocols.CompoundTerm. (head base) content))

  clojure.lang.IMapEntry
  (build [base content] (clojure.lang.MapEntry. (first content) (second content)))
  clojure.lang.ISeq
  (build [base content] (seq content))
  clojure.lang.PersistentList
  (build [base content] (apply list content))
  clojure.lang.PersistentList$EmptyList
  (build [base content] base)
  clojure.lang.IRecord
  (build [base content] (reduce conj base content)))




(defn- build-transient [base content]
  (persistent!
   (reduce
    conj!
    (transient (empty base)) content)))

;; Persistent collections that support transients
(doseq [type [clojure.lang.PersistentArrayMap
              clojure.lang.PersistentHashMap
              clojure.lang.PersistentHashSet
              clojure.lang.PersistentVector]]
  (extend type IBuildable {:build build-transient}))


(defn- build-default [base content]
  (reduce
   conj
   (empty base)
   content))


;; Persistent collections that don't support transients
(doseq [type [clojure.lang.PersistentQueue
              clojure.lang.PersistentStructMap
              clojure.lang.PersistentTreeMap
              clojure.lang.PersistentTreeSet]]
  (extend type IBuildable {:build build-default}))


(defn node
  "Returns the node at loc"
  [^ZipperLocation loc]
  (.node loc))

(defn branch?
  "Returns true if the node at loc is a branch"
  [^ZipperLocation loc]
  (instance? clojure.lang.Seqable (.node loc)))

(defn children
  "Returns a seq of the children of node at loc, which must be a branch"
  [^ZipperLocation loc]
  (seq (.node loc)))

(defn make-node
  "Returns a new branch node, given an existing node and new children.
  The loc is only used to supply the constructor."
  [^ZipperLocation loc node children]
  children)

(defn path
  "Returns a seq of nodes leading to this loc"
  [^ZipperLocation loc]
  (.pnodes ^ZipperPath (.path loc)))

(defn down
  "Returns the loc of the leftmost child of the node at this loc,
  or nil if no children"
  [^ZipperLocation loc]
  (when (branch? loc)
    (when-let [cs (children loc)]
      (let [node (.node loc), path ^ZipperPath (.path loc)]
        (ZipperLocation.

          (first cs)
          (ZipperPath. (tuple)
                       (clojure.core/next cs)
                       path
                       (if path
                         (conj (.pnodes path) node)
                         [node])
                       nil))))))


(defn up
  "Returns the loc of the parent of the node at this loc, or nil if at the top"
  [^ZipperLocation loc]
  (let [node (.node loc), path ^ZipperPath (.path loc)]
    (when-let [pnodes (and path (.pnodes path))]
      (let [pnode (peek pnodes)]
        (if (.changed? path)
          (ZipperLocation.
           (build pnode (concat (.l path) (cons node (.r path))))
           (if-let [ppath ^ZipperPath (.ppath path)]
             (ZipperPath.
              (.l ppath)
              (.r ppath)
              (.ppath ppath)
              (.pnodes ppath)
              true)))
          (ZipperLocation.
            pnode
            (.ppath path)))))))

(defn root
  "zips all the way up and returns the root node, reflecting any changes."
  [^ZipperLocation loc]
  (if (identical? :end (.path loc))
    (.node loc)
    (let [p (up loc)]
      (if p
        (recur p)
        (.node loc)))))

(defn right
  "Returns the loc of the right sibling of the node at this loc, or nil"
  [^ZipperLocation loc]
  (let [path ^ZipperPath (.path loc)]
    (when-let [r (and path (.r path))]
      (ZipperLocation.

       (first r)
      ; (defrecord ZipperPath [l r ppath pnodes changed?])

       (ZipperPath.  (conj (.l path) (.node loc))
                     (clojure.core/next r)
                     (.ppath path)
                     (.pnodes path)
                     (.changed? path)
                     )
        ;(assoc path :l (conj (.l path) (.node loc)) :r (clojure.core/next r))
       ))))


(defn left
  "Returns the loc of the left sibling of the node at this loc, or nil"
  [^ZipperLocation loc]
  (let [path ^ZipperPath (.path loc)]
    (when (and path (seq (.l path)))
      (ZipperLocation.

        (peek (.l path))
        (assoc path :l (pop (.l path)) :r (cons (.node loc) (.r path)))))))


(defn replace
  "Replaces the node at this loc, without moving"
  [^ZipperLocation loc node]
  (ZipperLocation.

    node
    (if-let [path ^ZipperPath (.path loc)]
      (ZipperPath.
              (.l path)
              (.r path)
              (.ppath path)
              (.pnodes path)
              true))))


(defn next
  "Moves to the next loc in the hierarchy, depth-first. When reaching
  the end, returns a distinguished loc detectable via end?. If already
  at the end, stays there."
  [^ZipperLocation loc]
  (let [path (.path loc)]
    (if (identical? :end path)
      loc
      (or
        (and (branch? loc) (down loc))
        (right loc)
         (loop [p loc]
           (if-let [u (up p)]
            (or (right u) (recur u))
            (ZipperLocation. (.node p) :end)))))))

(defn next-nondescending
  "moves to the next location the the right or above node"
  [^ZipperLocation loc]
  (let [path (.path loc)]
    (if (identical? :end path)
      loc
      (or
        (right loc)
         (loop [p loc]
           (if-let [u (up p)]
            (or (right u) (recur u))
            (ZipperLocation. (.node p) :end)))))))




(defn end?
  "Returns true if loc represents the end of a depth-first walk"
  [^ZipperLocation loc]
  (identical? :end (.path loc)))

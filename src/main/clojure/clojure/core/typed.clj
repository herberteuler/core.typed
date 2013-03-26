(set! *warn-on-reflection* true)

(ns clojure.core.typed
  (:refer-clojure :exclude [defrecord type])
  (:import (clojure.lang IPersistentList IPersistentVector Symbol Cons Seqable IPersistentCollection
                         ISeq ASeq ILookup Var Namespace PersistentVector APersistentVector
                         IFn IPersistentStack Associative IPersistentSet IPersistentMap IMapEntry
                         Keyword Atom PersistentList IMeta PersistentArrayMap Compiler Named
                         IRef AReference ARef IDeref IReference APersistentSet PersistentHashSet Sorted
                         LazySeq APersistentMap Indexed))
  (:require [clojure.main]
            [clojure.set :as set]
            [clojure.reflect :as reflect]
            [clojure.string :as str]
            [clojure.repl :refer [pst]]
            [clojure.pprint :refer [pprint]]
            [clojure.math.combinatorics :as comb]
            [clojure.java.io :as io]
            [cljs
             [compiler]
             [analyzer :as cljs]]
            [clojure.tools.trace :refer [trace-vars untrace-vars
                                         trace-ns untrace-ns]]
            [clojure.tools.analyzer :as analyze]
            [clojure.tools.analyzer.hygienic :as hygienic]

            ;Note: defrecord is now trammel's defconstrainedrecord
            [clojure.core.typed.utils :refer :all]))

(declare ^:dynamic *current-env*)

;[Any * -> String]
(defn ^String error-msg 
  [& msg]
  (apply str (when *current-env*
               (str (:line *current-env*) ": "))
         (concat msg)))

;(ann analyze.hygienic/emit-hy [Any -> Any])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Special functions

;(ann print-filterset [String Any -> Any])
(defn print-filterset
  "Print the filter set attached to form, and debug-string"
  [debug-string frm] 
  frm)

(declare Method->Type unparse-type unparse-filter)

;(ann method-type [Symbol -> nil])
(defn method-type 
  "Given a method symbol, print the core.typed types assigned to it"
  [mname]
  (let [ms (->> (reflect/type-reflect (Class/forName (namespace mname)))
             :members
             (filter #(and (instance? clojure.reflect.Method %)
                           (= (str (:name %)) (name mname))))
             set)
        _ (assert (seq ms) (str "Method " mname " not found"))]
    (prn "Method name:" mname)
    (doseq [m ms]
      (prn (unparse-type (Method->Type m))))))

;(ann inst-poly [Any Any -> Any])
(defn inst-poly 
  [inst-of types-syn]
  inst-of)

;(ann inst-poly-ctor [Any Any -> Any])
(defn inst-poly-ctor [inst-of types-syn]
  inst-of)

(defmacro inst 
  "Instantiate a polymorphic type with a number of types"
  [inst-of & types]
  `(inst-poly ~inst-of '~types))

(defmacro inst-ctor
  "Instantiate a call to a constructor with a number of types.
  First argument must be an immediate call to a constructor."
  [inst-of & types]
  `(inst-poly-ctor ~inst-of '~types))

;(ann fn>-ann [Any Any -> Any])
(defn fn>-ann [fn-of param-types-syn]
  fn-of)

;(ann pfn>-ann [Any Any -> Any])
(defn pfn>-ann [fn-of polys param-types-syn]
  fn-of)

;(ann loop>-ann [Any Any -> Any])
(defn loop>-ann [loop-of bnding-types]
  loop-of)

(defmacro dotimes>
  "Like dotimes."
  [bindings & body]
  (@#'clojure.core/assert-args
     (vector? bindings) "a vector for its binding"
     (= 2 (count bindings)) "exactly 2 forms in binding vector")
  (let [i (first bindings)
        n (second bindings)]
    `(let [n# (long ~n)]
       (loop> [[~i :- (~'U Long Integer)] 0]
         (when (< ~i n#)
           ~@body
           (recur (unchecked-inc ~i)))))))

(defmacro for>
  "Like for but requires annotation for each loop variable: 
  [a [1 2]] becomes [[a :- Long] [1 2]]
  Also requires annotation for return type.
  
  eg.
  (for> :- Number
        [[a :- (U nil AnyInteger)] [1 nil 2 3]
         :when a]
     (inc a))"
  [tk ret-ann seq-exprs body-expr]
  (@#'clojure.core/assert-args
     (vector? seq-exprs) "a vector for its binding"
     (even? (count seq-exprs)) "an even number of forms in binding vector")
  (assert (#{:-} tk))
  (let [to-groups (fn [seq-exprs]
                    (@#'clojure.core/reduce1 (fn [groups [k v]]
                              (if (keyword? k)
                                (conj (pop groups) (conj (peek groups) [k v]))
                                (conj groups [k v])))
                            [] (partition 2 seq-exprs)))
        err (fn [& msg] (throw (IllegalArgumentException. ^String (apply str msg))))
        emit-bind (fn emit-bind [[[bind expr & mod-pairs]
                                  & [[_ next-expr] :as next-groups]]]
                    (let [_ (assert (and (vector? bind)
                                         (#{3} (count bind))
                                         (#{:-} (second bind))) 
                                    "Binder must be of the form [lhs :- type]")
                          bind-ann (nth bind 2)
                          bind (nth bind 0)
                          giter (gensym "iter__")
                          gxs (gensym "s__")
                          do-mod (fn do-mod [[[k v :as pair] & etc]]
                                   (cond
                                     (= k :let) `(let ~v ~(do-mod etc))
                                     (= k :while) `(when ~v ~(do-mod etc))
                                     (= k :when) `(if ~v
                                                    ~(do-mod etc)
                                                    (recur (rest ~gxs)))
                                     (keyword? k) (err "Invalid 'for' keyword " k)
                                     next-groups
                                      `(let [iterys# ~(emit-bind next-groups)
                                             fs# (seq (iterys# ~next-expr))]
                                         (if fs#
                                           (concat fs# (~giter (rest ~gxs)))
                                           (recur (rest ~gxs))))
                                     :else `(cons ~body-expr
                                                  (~giter (rest ~gxs)))))]
                      (if next-groups
                        #_"not the inner-most loop"
                        `(ann-form
                           (fn ~giter [~gxs]
                             (lazy-seq
                               (loop> [[~gxs :- (~'clojure.core.typed/Option (~'clojure.lang.Seqable ~bind-ann))] ~gxs]
                                 (when-first [~bind ~gxs]
                                   ~(do-mod mod-pairs)))))
                           [(~'clojure.core.typed/Option (~'clojure.lang.Seqable ~bind-ann)) ~'-> (~'clojure.lang.LazySeq ~ret-ann)])
                        #_"inner-most loop"
                        (let [gi (gensym "i__")
                              gb (gensym "b__")
                              do-cmod (fn do-cmod [[[k v :as pair] & etc]]
                                        (cond
                                          (= k :let) `(let ~v ~(do-cmod etc))
                                          (= k :while) `(when ~v ~(do-cmod etc))
                                          (= k :when) `(if ~v
                                                         ~(do-cmod etc)
                                                         (recur
                                                           (unchecked-inc ~gi)))
                                          (keyword? k)
                                            (err "Invalid 'for' keyword " k)
                                          :else
                                            `(do (chunk-append ~gb ~body-expr)
                                                 (recur (unchecked-inc ~gi)))))]
                          `(ann-form
                             (fn ~giter [~gxs]
                               (lazy-seq
                                 (loop> [[~gxs :- (~'clojure.core.typed/Option (~'clojure.lang.Seqable ~bind-ann))] ~gxs]
                                        (when-let [~gxs (seq ~gxs)]
                                          (if (chunked-seq? ~gxs)
                                            (let [c# (chunk-first ~gxs)
                                                  size# (int (count c#))
                                                  ~gb (ann-form (chunk-buffer size#)
                                                                (~'clojure.lang.ChunkBuffer Number))]
                                              (if (loop> [[~gi :- (~'U ~'Long ~'Integer)] (int 0)]
                                                         (if (< ~gi size#)
                                                           (let [;~bind (.nth c# ~gi)]
                                                                 ~bind (nth c# ~gi)]
                                                             ~(do-cmod mod-pairs))
                                                           true))
                                                (chunk-cons
                                                  (chunk ~gb)
                                                  (~giter (chunk-rest ~gxs)))
                                                (chunk-cons (chunk ~gb) nil)))
                                            (let [~bind (first ~gxs)]
                                              ~(do-mod mod-pairs)))))))
                             [(~'clojure.core.typed/Option (~'clojure.lang.Seqable ~bind-ann)) ~'->
                              (~'clojure.lang.LazySeq ~ret-ann)])))))]
    `(let [iter# ~(emit-bind (to-groups seq-exprs))]
        (iter# ~(second seq-exprs)))))

(defmacro doseq>
  "Like doseq but requires annotation for each loop variable: 
  [a [1 2]] becomes [[a :- Long] [1 2]]
  
  eg.
  (doseq> [[a :- (U nil AnyInteger)] [1 nil 2 3]
           :when a]
     (inc a))"
  [seq-exprs & body]
  (@#'clojure.core/assert-args
     (vector? seq-exprs) "a vector for its binding"
     (even? (count seq-exprs)) "an even number of forms in binding vector")
  (let [step (fn step [recform exprs]
               (if-not exprs
                 [true `(do ~@body)]
                 (let [k (first exprs)
                       v (second exprs)]
                   (if (keyword? k)
                     (let [steppair (step recform (nnext exprs))
                           needrec (steppair 0)
                           subform (steppair 1)]
                       (cond
                         (= k :let) [needrec `(let ~v ~subform)]
                         (= k :while) [false `(when ~v
                                                ~subform
                                                ~@(when needrec [recform]))]
                         (= k :when) [false `(if ~v
                                               (do
                                                 ~subform
                                                 ~@(when needrec [recform]))
                                               ~recform)]))
                     ;; k is [k :- k-ann]
                     (let [_ (assert (and (vector? k)
                                          (#{3} (count k))
                                          (#{:-} (second k))) 
                                     "Binder must be of the form [lhs :- type]")
                           k-ann (nth k 2)
                           k (nth k 0)
                           ; k is the lhs binding
                           seq- (gensym "seq_")
                           chunk- (with-meta (gensym "chunk_")
                                             {:tag 'clojure.lang.IChunk})
                           count- (gensym "count_")
                           i- (gensym "i_")
                           recform `(recur (next ~seq-) nil 0 0)
                           steppair (step recform (nnext exprs))
                           needrec (steppair 0)
                           subform (steppair 1)
                           recform-chunk 
                             `(recur ~seq- ~chunk- ~count- (unchecked-inc ~i-))
                           steppair-chunk (step recform-chunk (nnext exprs))
                           subform-chunk (steppair-chunk 1)]
                       [true
                        `(loop> [[~seq- :- (~'U nil (~'clojure.lang.Seqable ~k-ann))] (seq ~v), 
                                 [~chunk- :- (~'U nil (~'clojure.lang.IChunk ~k-ann))] nil
                                 [~count- :- ~'(U Integer Long)] 0,
                                 [~i- :- ~'(U Integer Long)] 0]
                           (if (and (< ~i- ~count-)
                                    ;; core.typed thinks chunk- could be nil here
                                    ~chunk-)
                             (let [;~k (.nth ~chunk- ~i-)
                                   ~k (nth ~chunk- ~i-)]
                               ~subform-chunk
                               ~@(when needrec [recform-chunk]))
                             (when-let [~seq- (seq ~seq-)]
                               (if (chunked-seq? ~seq-)
                                 (let [c# (chunk-first ~seq-)]
                                   (recur (chunk-rest ~seq-) c#
                                          (int (count c#)) (int 0)))
                                 (let [~k (first ~seq-)]
                                   ~subform
                                   ~@(when needrec [recform]))))))])))))]
    (nth (step nil (seq seq-exprs)) 1)))

;(ann parse-fn> [Any (Seqable Any) ->
;                '{:poly Any
;                  :fn Any ;Form
;                  :parsed-methods (Seqable '{:dom-syntax (Seqable Any)
;                                             :dom-lhs (Seqable Any)
;                                             :rng-syntax Any
;                                             :has-rng? Any
;                                             :body Any})}])
;for
(defn- parse-fn>
  "(fn> name? :- type? [[param :- type]* & [param :- type *]?] exprs*)
  (fn> name? (:- type? [[param :- type]* & [param :- type *]?] exprs*)+)"
  [is-poly forms]
  (let [name (when (symbol? (first forms))
               (first forms))
        forms (if name (rest forms) forms)
        poly (when is-poly
               (first forms))
        forms (if poly (rest forms) forms)
        methods (if ((some-fn vector? keyword?) (first forms))
                  (list forms)
                  forms)
        ;(fn> name? (:- type? [[param :- type]* & [param :- type *]?] exprs*)+)"
        ; (HMap {:dom (Seqable TypeSyntax)
        ;        :rng (U nil TypeSyntax)
        ;        :body Any})
        parsed-methods (doall 
                         (for [method methods]
                           (let [[ret has-ret?] (when (not (vector? (first method)))
                                                  (assert (= :- (first method))
                                                          "Return type for fn> must be prefixed by :-")
                                                  [(second method) true])
                                 method (if ret 
                                          (nnext method)
                                          method)
                                 body (rest method)
                                 arg-anns (first method)
                                 [required-params _ [rest-param]] (split-with #(not= '& %) arg-anns)]
                             (assert (sequential? required-params)
                                     "Must provide a sequence of typed parameters to fn>")
                             (assert (not rest-param) "fn> doesn't support rest parameters yet")
                             {:dom-syntax (doall (map (comp second next) required-params))
                              :dom-lhs (doall (map first required-params))
                              :rng-syntax ret
                              :has-rng? has-ret?
                              :body body})))]
    {:poly poly
     :fn `(fn ~@(concat
                  (when name
                    [name])
                  (for [{:keys [body dom-lhs]} parsed-methods]
                    (apply list (vec dom-lhs) body))))
     :parsed-methods parsed-methods}))

(defmacro pfn> 
  "Define a polymorphic typed anonymous function.
  (pfn> name? [binder+] :- type? [[param :- type]* & [param :- type *]?] exprs*)
  (pfn> name? [binder+] (:- type? [[param :- type]* & [param :- type *]?] exprs*)+)"
  [& forms]
  (let [{:keys [poly fn parsed-methods]} (parse-fn> true forms)]
    `(pfn>-ann ~fn '~poly '~parsed-methods)))

(defmacro fn> 
  "Define a typed anonymous function.
  (fn> name? :- type? [[param :- type]* & [param :- type *]?] exprs*)
  (fn> name? (:- type? [[param :- type]* & [param :- type *]?] exprs*)+)"
  [& forms]
  (let [{:keys [fn parsed-methods]} (parse-fn> false forms)]
    `(fn>-ann ~fn '~parsed-methods)))

(defmacro defprotocol> [& body]
  "Define a typed protocol"
  `(tc-ignore
     (defprotocol ~@body)))

(defmacro loop>
  "Define a typed loop"
  [bndings* & forms]
  (let [bnds (partition 2 bndings*)
        ; [[lhs :- bnd-ann] rhs]
        lhs (map ffirst bnds)
        rhs (map second bnds)
        bnd-anns (map #(-> % first next second) bnds)]
    `(loop>-ann (loop ~(vec (mapcat vector lhs rhs))
                  ~@forms)
                '~bnd-anns)))

(defmacro declare-datatypes 
  "Declare datatypes, similar to declare but on the type level."
  [& syms]
  `(tc-ignore
  (doseq [sym# '~syms]
    (assert (not (or (some #(= \. %) (str sym#))
                     (namespace sym#)))
            (str "Cannot declare qualified datatype: " sym#))
    (let [qsym# (symbol (str (munge (name (ns-name *ns*))) \. (name sym#)))]
      (declare-datatype* qsym#)))))

(defmacro declare-protocols 
  "Declare protocols, similar to declare but on the type level."
  [& syms]
  `(tc-ignore
  (doseq [sym# '~syms]
     (let [qsym# (if (namespace sym#)
                   sym#
                   (symbol (str (name (ns-name *ns*))) (name sym#)))]
       (declare-protocol* qsym#)))))

(defmacro declare-alias-kind
  "Declare a kind for an alias, similar to declare but on the kind level."
  [sym ty]
  `(tc-ignore
   (do (ensure-clojure)
     (let [sym# '~sym
           qsym# (if (namespace sym#)
                   sym#
                   (symbol (name (ns-name *ns*)) (name sym#)))
           ty# (parse-type '~ty)]
       (assert (not (namespace sym#)) (str "Cannot declare qualified name " sym#))
       (declare ~sym)
       (declare-names ~sym)
       (declare-alias-kind* qsym# ty#)))))

(defmacro declare-names 
  "Declare names, similar to declare but on the type level."
  [& syms]
  `(tc-ignore
  (doseq [sym# '~syms]
     (let [qsym# (if (namespace sym#)
                   sym#
                   (symbol (name (ns-name *ns*)) (name sym#)))]
       (declare-name* qsym#)))))

(defmacro def-alias 
  "Define a type alias"
  [sym type]
  `(tc-ignore
  (do (ensure-clojure)
    (let [sym# (if (namespace '~sym)
                 '~sym
                 (symbol (name (ns-name *ns*)) (name '~sym)))
          ty# (parse-type '~type)]
      (add-type-name sym# ty#)
      (declare ~sym)
      (when-let [tfn# (@DECLARED-KIND-ENV sym#)]
        (assert (subtype? ty# tfn#) (error-msg "Declared kind " (unparse-type tfn#)
                                               " does not match actual kind " (unparse-type ty#))))
      [sym# (unparse-type ty#)]))))

(declare Type? RClass? PrimitiveArray? RClass->Class parse-type
         requires-resolving? -resolve Nil? Value? Value->Class Union? Intersection?)

;Return a Class that generalises what this Clojure type will look like from Java,
;suitable  for use as a Java primitive array member type.
; 
; (Type->array-member-Class (parse-type 'nil)) => Object
; (Type->array-member-Class (parse-type '(U nil Number))) => Number
; (Type->array-member-Class (parse-type '(Array (U nil Number)))) =~> (Array Number)

;(ann Type->array-member-Class (Fn [Type -> (Option Class)]
;                                  [Type Any -> (Option Class)]))
(defn Type->array-member-Class 
  ([ty] (Type->array-member-Class ty false))
  ([ty nilok?]
   {:pre [(Type? ty)]}
   (cond
     (requires-resolving? ty) (Type->array-member-Class (-resolve ty) nilok?)
     (Nil? ty) (if nilok?
                 nil
                 Object)
     (Value? ty) (Value->Class ty)
     ;; handles most common case of (U nil Type)
     (Union? ty) (let [clss (map #(Type->array-member-Class % true) (:types ty))
                       prim-and-nil? (and (some nil? clss)
                                          (some #(when % (.isPrimitive ^Class %)) clss))
                       nonil-clss (remove nil? clss)]
                   (if (and (= 1 (count nonil-clss))
                            (not prim-and-nil?))
                     (first nonil-clss)
                     Object))
     (Intersection? ty) Object
     (RClass? ty) (RClass->Class ty)
     (PrimitiveArray? ty) (class (make-array (Type->array-member-Class (:jtype ty) false) 0))
     :else Object)))

;(ann into-array>* [Any Any -> Any])
(defn into-array>* 
  ([cljt coll]
   (into-array (-> cljt parse-type Type->array-member-Class) coll))
  ([javat cljt coll]
   (into-array (-> javat parse-type Type->array-member-Class) coll)))

(defmacro into-array> 
  "Make a Java array with Java class javat and Typed Clojure type
  cljt. Resulting array will be of type javat, but elements of coll must be under
  cljt. cljt should be a subtype of javat (the same or more specific)."
  ([cljt coll]
   `(into-array>* '~cljt ~coll))
  ([javat cljt coll]
   `(into-array>* '~javat '~cljt ~coll)))

(defn ann-form* [form ty]
  form)

(defmacro ann-form [form ty]
  `(ann-form* ~form '~ty))

;(ann unsafe-ann-form* [Any Any -> Any])
(defn unsafe-ann-form* [form ty]
  form)

(defmacro unsafe-ann-form [form ty]
  `(unsafe-ann-form* ~form '~ty))

;(ann tc-ignore-forms* [Any -> Any])
(defn tc-ignore-forms* [r]
  r)

;; `do` is special at the top level
(defmacro tc-ignore 
  "Ignore forms in body during type checking"
  [& body]
  `(do ~@(map (fn [b] `(tc-ignore-forms* ~b)) body)))

(defmacro non-nil-return 
  "Override the return type of qualified method msym to be non-nil.
  Takes a set of relevant arities,
  represented by the number of parameters it takes (rest parameter counts as one),
  or :all which overrides all arities.
  
  eg.  (non-nil-return java.lang.Class/getDeclaredMethod :all)"
  [msym arities]
  `(tc-ignore
  (add-nonnilable-method-return '~msym '~arities)))

(defmacro nilable-param 
  "Override which parameters in qualified method msym may accept
  nilable values. If the parameter is a parameterised type or
  an Array, this also declares the parameterised types and the Array type as nilable.

  mmap is a map mapping arity parameter number to a set of parameter
  positions (integers). If the map contains the key :all then this overrides
  other entries. The key can also be :all, which declares all parameters nilable."
  [msym mmap]
  `(tc-ignore
  (add-method-nilable-param '~msym '~mmap)))

(declare abstract-many instantiate-many)

(load "typed/type_rep"
      "typed/type_ops"
      "typed/filter_rep"
      "typed/filter_ops"
      "typed/path_rep"
      "typed/object_rep")

; must be after type/object/filter definitions
(load "typed/fold")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Annotations

(declare TCResult?)

;(ann (predicate (APersistentMap Symbol Any)))
(def lex-env? (hash-c? (every-pred symbol? (complement namespace)) Type?))

(defrecord PropEnv [l props]
  "A lexical environment l, props is a list of known propositions"
  [(lex-env? l)
   (set? props)
   (every? Filter? props)])

(defn -PropEnv [l props]
  (->PropEnv l (if (set? props)
                 props
                 (into #{} props))))

(def ^:dynamic *lexical-env* (-PropEnv {} #{}))
(set-validator! #'*lexical-env* PropEnv?)

(defn print-env [debug-str]
  nil)

(defn print-env*
  ([] (print-env* *lexical-env*))
  ([e]
   {:pre [(PropEnv? e)]}
   ;; DO NOT REMOVE
   (prn {:env (into {} (for [[k v] (:l e)]
                         [k (unparse-type v)]))
         :props (map unparse-filter (:props e))})))

(defonce VAR-ANNOTATIONS (atom {}))

(defmacro with-lexical-env [env & body]
  `(binding [*lexical-env* ~env]
     ~@body))

(set-validator! VAR-ANNOTATIONS #(and (every? (every-pred symbol? namespace) (keys %))
                                      (every? Type? (vals %))))

(defmacro ann [varsym typesyn]
  `(tc-ignore
 (do (ensure-clojure)
   (let [t# (parse-type '~typesyn)
         s# (if (namespace '~varsym)
              '~varsym
              (symbol (-> *ns* ns-name str) (str '~varsym)))]
     (do (add-var-type s# t#)
       [s# (unparse-type t#)])))))

(declare parse-type alter-class*)

(defn parse-field [[n _ t]]
  [n (parse-type t)])

(defn gen-datatype* [provided-name fields variances args ancests]
  `(do (ensure-clojure)
  (let [provided-name-str# (str '~provided-name)
         ;_# (prn "provided-name-str" provided-name-str#)
         munged-ns-str# (if (some #(= \. %) provided-name-str#)
                          (apply str (butlast (apply concat (butlast (partition-by #(= \. %) provided-name-str#)))))
                          (str (munge (-> *ns* ns-name))))
         ;_# (prn "munged-ns-str" munged-ns-str#)
         demunged-ns-str# (str (clojure.repl/demunge munged-ns-str#))
         ;_# (prn "demunged-ns-str" demunged-ns-str#)
         local-name# (if (some #(= \. %) provided-name-str#)
                       (symbol (apply str (last (partition-by #(= \. %) (str provided-name-str#)))))
                       provided-name-str#)
         ;_# (prn "local-name" local-name#)
         s# (symbol (str munged-ns-str# \. local-name#))
         fs# (apply array-map (apply concat (with-frees (mapv make-F '~args)
                                              (mapv parse-field (partition 3 '~fields)))))
         as# (set (with-frees (mapv make-F '~args)
                    (mapv parse-type '~ancests)))
         _# (add-datatype-ancestors s# as#)
         pos-ctor-name# (symbol demunged-ns-str# (str "->" local-name#))
         args# '~args
         vs# '~variances
         dt# (if args#
               (Poly* args# (repeat (count args#) no-bounds)
                      (->DataType s# vs# (map make-F args#) fs#)
                      args#)
               (->DataType s# nil nil fs#))
         pos-ctor# (if args#
                     (Poly* args# (repeat (count args#) no-bounds)
                            (make-FnIntersection
                              (make-Function (vec (vals fs#)) (->DataType s# vs# (map make-F args#) fs#)))
                            args#)
                     (make-FnIntersection
                       (make-Function (vec (vals fs#)) dt#)))]
     (do 
       (when vs#
         (let [f# (mapv make-F (repeatedly (count vs#) gensym))]
           (alter-class* s# (RClass* (map :name f#) vs# f# s# {}))))
       (add-datatype s# dt#)
       (add-var-type pos-ctor-name# pos-ctor#)
       [[s# (unparse-type dt#)]
        [pos-ctor-name# (unparse-type pos-ctor#)]]))))

(defmacro ann-datatype [dname fields & {ancests :unchecked-ancestors rplc :replace}]
  (assert (not rplc) "Replace NYI")
  (assert (symbol? dname)
          (str "Must provide name symbol: " dname))
  `(tc-ignore
     ~(gen-datatype* dname fields nil nil ancests)))

(defmacro ann-pdatatype [dname vbnd fields & {ancests :unchecked-ancestors rplc :replace}]
  (assert (not rplc) "Replace NYI")
  (assert (symbol? dname)
          (str "Must provide local symbol: " dname))
  `(tc-ignore
     ~(gen-datatype* dname fields (map second vbnd) (map first vbnd) ancests)))

(defmacro ann-record [& args]
  `(ann-datatype ~@args))

(defmacro ann-precord [& args]
  `(ann-pdatatype ~@args))

(defn gen-protocol* [local-varsym variances args mths]
  `(do (ensure-clojure)
  (let [local-vsym# '~local-varsym
         s# (symbol (-> *ns* ns-name str) (str local-vsym#))
         on-class# (symbol (str (munge (namespace s#)) \. local-vsym#))
         ; add a Name so the methods can be parsed
         _# (declare-protocol* s#)
         args# '~args
         fs# (when args# 
               (map make-F args#))
         ms# (into {} (for [[knq# v#] '~mths]
                        (do
                          (assert (not (namespace knq#))
                                  "Protocol method should be unqualified")
                          [knq# (with-frees fs# (parse-type v#))])))
         t# (if fs#
              (Poly* (map :name fs#) (repeat (count fs#) no-bounds) 
                     (->Protocol s# '~variances fs# on-class# ms#)
                     (map :name fs#))
              (->Protocol s# nil nil on-class# ms#))]
     (do
       (add-protocol s# t#)
       (doseq [[kuq# mt#] ms#]
         ;qualify method names when adding methods as vars
         (let [kq# (symbol (-> *ns* ns-name str) (str kuq#))]
           (add-var-type kq# mt#)))
       [s# (unparse-type t#)]))))

(defmacro ann-protocol [local-varsym & {:as mth}]
  (assert (not (or (namespace local-varsym)
                   (some #{\.} (str local-varsym))))
          (str "Must provide local var name for protocol: " local-varsym))
  `(tc-ignore
     ~(gen-protocol* local-varsym nil nil mth)))

(defmacro ann-pprotocol [local-varsym vbnd & {:as mth}]
  (assert (not (or (namespace local-varsym)
                   (some #{\.} (str local-varsym))))
          (str "Must provide local var name for protocol: " local-varsym))
  `(tc-ignore
     ~(gen-protocol* local-varsym (mapv second vbnd) (mapv first vbnd) mth)))

(defmacro override-constructor [ctorsym typesyn]
  `(tc-ignore
   (do (ensure-clojure)
     (let [t# (parse-type '~typesyn)
           s# '~ctorsym]
       (do (add-constructor-override s# t#)
         [s# (unparse-type t#)])))))

(defmacro override-method [methodsym typesyn]
  `(tc-ignore
   (do (ensure-clojure)
     (let [t# (parse-type '~typesyn)
           s# (if (namespace '~methodsym)
                '~methodsym
                (throw (Exception. "Method name must be a qualified symbol")))]
       (do (add-method-override s# t#)
         [s# (unparse-type t#)])))))

(defn add-var-type [sym type]
  (swap! VAR-ANNOTATIONS #(assoc % sym type))
  nil)

(defn lookup-local [sym]
  (-> *lexical-env* :l sym))

(def ^:dynamic *var-annotations*)

(defn lookup-Var [nsym]
  (assert (contains? @*var-annotations* nsym) 
          (str (when *current-env*
                 (str (:line *current-env*) ": "))
            "Untyped var reference: " nsym))
  (@*var-annotations* nsym))

(defn merge-locals [env new]
  (-> env
    (update-in [:l] #(merge % new))))

(defmacro with-locals [locals & body]
  `(binding [*lexical-env* (merge-locals *lexical-env* ~locals)]
     ~@body))

(declare ^:dynamic *current-env*)

(defn type-of [sym]
  {:pre [(symbol? sym)]
   :post [(or (Type? %)
              (TCResult? %))]}
  (cond
    (not (namespace sym)) (if-let [t (lookup-local sym)]
                            t
                            (throw (Exception. (str (when *current-env*
                                                      (str (:line *current-env*) ": "))
                                                    "Reference to untyped binding: " sym))))
    :else (lookup-Var sym)))


(derive ::clojurescript ::default)
(derive ::clojure ::default)

(def TYPED-IMPL (atom ::clojure))
(set-validator! TYPED-IMPL #(isa? % ::default))

(defn ensure-clojure []
  (reset! TYPED-IMPL ::clojure))

(defn ensure-clojurescript []
  (reset! TYPED-IMPL ::clojurescript))

(defn checking-clojure? []
  (= ::clojure @TYPED-IMPL))

(defn checking-clojurescript? []
  (= ::clojurescript @TYPED-IMPL))

(load "typed/dvar_env"
      "typed/datatype_ancestor_env"
      "typed/datatype_env"
      "typed/protocol_env"
      "typed/method_override_env"
      "typed/ctor_override_env"
      "typed/method_return_nilables"
      "typed/method_param_nilables"
      "typed/declared_kind_env"
      "typed/name_env"
      "typed/rclass_env"
      "typed/mm_env")

(load "typed/constant_type"
      "typed/parse"
      "typed/unparse"
      "typed/frees"
      "typed/promote_demote"
      "typed/cs_gen"
      "typed/subst_dots"
      "typed/infer"
      "typed/tvar_rep"
      "typed/subst"
      "typed/trans"
      "typed/inst"
      "typed/subtype"
      "typed/alter"
      "typed/ann"
      "typed/check"
      "typed/check_cljs")

;emit something that CLJS can display ie. a quoted unparsed typed
(defmacro cf-cljs
  "Type check a Clojurescript form and return its type"
  ([form]
   (let [t
         (do (ensure-clojurescript)
           (-> (ana-cljs {:locals {} :context :expr :ns {:name cljs/*cljs-ns*}} form) check-cljs expr-type unparse-TCResult))]
     `'~t))
  ([form expected]
   (let [t
         (do (ensure-clojurescript)
           (-> (ana-cljs {:locals {} :context :expr :ns {:name cljs/*cljs-ns*}}
                         (list `ann-form-cljs form expected))
             (#(check-cljs % (ret (parse-type expected)))) expr-type unparse-TCResult))]
     `'~t)))

(defmacro cf 
  "Type check a Clojure form and return its type"
  ([form]
  `(do (ensure-clojure)
     (tc-ignore
       (-> (analyze/ast ~form) hygienic/ast-hy check expr-type unparse-TCResult))))
  ([form expected]
  `(do (ensure-clojure)
     (tc-ignore
       (-> (analyze/ast (ann-form ~form ~expected)) hygienic/ast-hy (#(check % (ret (parse-type '~expected)))) expr-type unparse-TCResult)))))

(defn analyze-file-asts
  [^String f]
  (let [res (if (re-find #"^file://" f) (java.net.URL. f) (io/resource f))]
    (assert res (str "Can't find " f " in classpath"))
    (with-altered-specials
      (binding [cljs/*cljs-ns* 'cljs.user
                cljs/*cljs-file* (.getPath ^java.net.URL res)
                *ns* cljs/*reader-ns*]
        (with-open [r (io/reader res)]
          (let [env (cljs/empty-env)
                pbr (clojure.lang.LineNumberingPushbackReader. r)
                eof (Object.)]
            (loop [r (read pbr false eof false)
                   asts []]
              (let [env (assoc env :ns (cljs/get-namespace cljs/*cljs-ns*))]
                (if-not (identical? eof r)
                  (let [ast1 (cljs/analyze env r)]
                    (recur (read pbr false eof false) (conj asts ast1)))
                  asts)))))))))

(defn check-cljs-ns*
  "Type check a CLJS namespace. If not provided default to current namespace"
  ([] (check-cljs-ns* cljs/*cljs-ns*))
  ([nsym]
   (ensure-clojurescript)
   (let [asts (analyze-file-asts (cljs/ns->relpath nsym))]
     (doseq [ast asts]
       (check-cljs ast)))))

(defmacro check-cljs-ns
  ([] (check-cljs-ns*) `'~'success)
  ([nsym] (check-cljs-ns* nsym) `'~'success))

(def ^:dynamic *currently-checking-clj* #{})

(defn check-ns 
  "Type check a namespace. If not provided default to current namespace"
  ([] (check-ns (ns-name *ns*)))
  ([nsym]
   (when-not (*currently-checking-clj* nsym)
     (binding [*currently-checking-clj* (conj *currently-checking-clj* nsym)]
       (ensure-clojure)
       (with-open [^clojure.lang.LineNumberingPushbackReader pbr (analyze/pb-reader-for-ns nsym)]
         ;FIXME Ignore the first form, assumed to be a call to ns. Functions need to be proper RClasses before this
         ;checks properly. http://dev.clojure.org/jira/browse/CTYP-16
         (let [[_ns-decl_ & asts] (->> (analyze/analyze-ns pbr (analyze/uri-for-ns nsym) nsym)
                                      (map hygienic/ast-hy))]
           (doseq [ast asts]
             (check-expr ast))))))))

(defn trepl []
  (clojure.main/repl 
    :eval (fn [f] 
            (let [t (do (ensure-clojure)
                      (-> (analyze/analyze-form f) hygienic/ast-hy
                        check expr-type unparse-TCResult))]
              (prn t) 
              (eval f)))))

(comment 
  (check-ns 'clojure.core.typed.test.example)

  ; very slow because of update-composite
  (check-ns 'clojure.core.typed.test.rbt)

  (check-ns 'clojure.core.typed.test.macro)
  (check-ns 'clojure.core.typed.test.conduit)
  (check-ns 'clojure.core.typed.test.person)
  (check-ns 'clojure.core.typed.test.core-logic)
  (check-ns 'clojure.core.typed.test.ckanren)

  (check-cljs-ns 'clojure.core.typed.test.logic)
  )
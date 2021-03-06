(ns enliven.core.lenses
  (:refer-clojure :exclude [comp identity]))

(ns-unmap *ns* 'Readable)

(defprotocol Readable
  (expr-form [x]))

(defprotocol Lens
  (-fetch [lens value])
  (-putback [lens value subvalue]))

(defprotocol LensEx
  (-fetcher [lens]))

(defprotocol CompoundLens
  (-decompose [lc]))

(defn lens? [l]
  (satisfies? Lens l))

(declare compound)

(def identity
  (reify
    Lens
    (-fetch [id x] x)
    (-putback [id x x'] x')
    Readable
    (expr-form [id] `identity)
    CompoundLens
    (-decompose [id] ())
    LensEx
    (-fetcher [id]
      clojure.core/identity)))

(defn lens [x]
  (cond
    (satisfies? Lens x) x
    (nil? x) identity ; not sure about this one, or even making nil the identity
    (sequential? x) (apply compound x)
    :else
    (throw (IllegalArgumentException. (str "Don't know how to create a lens from: " x)))))

(defn fetch [value l]
  (-fetch (lens l) value))

(defn putback [value l subvalue]
  (-putback (lens l) value subvalue))

(defn update [node l f & args]
  (let [l (lens l)]
    (-putback l node (apply f (-fetch l node) args))))

(defn fetcher [l]
  (-fetcher (lens l)))

(doseq [t [clojure.lang.Keyword clojure.lang.Symbol String]]
  (extend t
    Lens {:-fetch #(get %2 %1)
          :-putback #(assoc %2 %1 %3)}
    Readable {:expr-form clojure.core/identity}))

(extend java.util.regex.Pattern
  Lens
  {:-fetch (clojure.core/comp vec re-seq)
   :-putback (fn [p s replacements]
               (let [m (re-matcher p s)]
                 (loop [sb (StringBuilder.) i 0 [s' & ss] replacements]
                   (if (.find m)
                     (recur (-> sb (.append (subs s i (.start m))) (.append (str s')))
                       (.end m) ss)
                     (-> sb (.append (subs s i)) .toString)))))}
  Readable {:expr-form clojure.core/identity})

(extend-protocol LensEx
  Object
  (-fetcher [lens] #(-fetch lens %))
  String
  (-fetcher [lens] #(get % lens)))

(doseq [t [clojure.lang.Keyword clojure.lang.Symbol]]
  (extend t
    LensEx {:-fetcher clojure.core/identity}))

(defn decompose [l]
  (-decompose (lens l)))

(extend-protocol CompoundLens
  Object
  (-decompose [l]
    (if (lens? l)
      (do
        (extend (class l) CompoundLens {:-decompose list})
        (list l))
      (throw (IllegalArgumentException. (str "Don't know how to decompose " l))))
    (list l)))

(defrecord LensComposition [lenses]
  Lens
  (-fetch [lc x] (reduce fetch x lenses))
  (-putback [lc value subvalue]
    (letfn [(pb [lenses value]
              (if-let [[lens & lenses] (seq lenses)]
                (-putback lens value
                  (pb lenses (-fetch lens value)))
                subvalue))]
      (pb lenses value)))
  Readable
  (expr-form [lc] (list* `compound lenses))
  CompoundLens
  (-decompose [lc] lenses)
  LensEx
  (-fetcher [id]
    (apply clojure.core/comp (map fetcher lenses))))

(defn compound [& lenses]
  (let [lenses (mapcat decompose lenses)]
    (cond
      (next lenses) (LensComposition. lenses)
      (seq lenses) (first lenses)
      :else identity)))

(defn- bound [mn n mx]
  (-> n (max mn) (min mx)))

(defn spliceable? [x]
  (or (nil? x) (sequential? x)))

(defrecord Slice [from to]
  Lens
  (-fetch [lens x]
    (let [n (count x)]
      (subvec x (bound 0 from n) (bound 0 to n))))
  (-putback [lens x v]
    (let [n (count x)]
      (-> x
        (subvec 0 (bound 0 from n))
        (into (if (spliceable? v) v (list v)))
        (into (subvec x (bound 0 to n) n)))))
  Readable
  (expr-form [lens] (list `slice from to))
  LensEx
  (-fetcher [lens]
    (fn [x]
      (let [n (count x)]
        (subvec x (bound 0 from n) (bound 0 to n)))))
  Comparable
  (compareTo [a b]
    (cond
      (= a b) 0
      (<= (:to a) (:from b)) -1
      :else +1)))

(defn slice [from to] (Slice. from to))

(defn slice? [lens] (instance? Slice lens))

(extend Number
  Lens {:-fetch #(nth %2 %1)
        :-putback #(assoc %2 %1 %3)}
  Readable {:expr-form clojure.core/identity}
  LensEx {:-fetcher (fn [n] #(nth % n))})

(defn lens-class [lens]
  (cond
    (slice? lens) :range
    (number? lens) :number
    :else :misc))

(defn bounds
  ([lens]
    (case (lens-class lens)
                      :range [(:from lens) (:to lens)]
                      :number [lens (inc lens)]))
  ([lens v]
    (let [[from to] (bounds lens)
          n (count v)]
      [(bound 0 from n) (bound 0 to n)])))

(defrecord Constant [v]
  Lens
  (-fetch [lens _] v)
  (-putback [lens x _] x)
  Readable
  (expr-form [lens] (list `const v))
  LensEx
  (-fetcher [lens]
    (constantly v)))

(defn const [v] (Constant. v))

(defn const? [lens] (instance? Constant lens))

(defmacro deflens [name doc? initial-args & methods]
  (let [[name initial-args methods] (if (string? doc?)
                                      [(vary-meta name assoc :doc doc?) initial-args methods]
                                      [name doc? (cons initial-args methods)])
        [value-arg subvalue-arg & args] initial-args
        fqname (symbol (clojure.core/name (ns-name *ns*))
                 (clojure.core/name name))
        singleton-lens (empty? args)
        [args [_ & rest-arg]] (split-with #(not= (clojure.core/name %) "&") args)
        plain-args (take (count args) (repeatedly gensym))
        plain-rest-arg (when rest-arg (gensym))
        fetchexpr-form (some (fn [[name expr]] (case name :fetch expr nil)) (partition 2 methods))
        methods (for [[name expr] (partition 2 methods)]
                  (case name
                    :fetch `(-fetch [_# ~value-arg] ~expr)
                    :putback `(-putback [_# ~value-arg ~subvalue-arg] ~expr)))
        f `(fn [~@plain-args ~@(when plain-rest-arg `[& ~plain-rest-arg])]
             (let [~@(interleave args plain-args)
                   ~@(when plain-rest-arg [rest-arg plain-rest-arg])]
               (reify
                 Lens
                 ~@methods
                 Readable
                 (expr-form [_#] ~(if singleton-lens
                                `'~fqname
                                `(list* '~fqname ~@plain-args ~plain-rest-arg)))
                 LensEx
                 (-fetcher [_#]
                   (fn [~value-arg] ~fetchexpr-form))
                 Object
                 (equals [this# that#]
                   (and (satisfies? Lens that#)
                     (= (expr-form this#) (expr-form that#))))
                 (hashCode [this#] (hash (expr-form this#)))
                 (toString [this#] (pr-str (expr-form this#))))))]
    `(def ~name ~(if singleton-lens (list f) f))))

(deflens append-on-assoc
  "Presents a sequence of key-value pairs (an alist) as a map.
   On putback, unmodified key-value pairs appear first and in their original
   order, then followed by new or updated key-value pairs in unspecified order.
   Example:
   # (update [[:a 1] [:b 2] [:c 3]]
       append-on-assoc assoc :a 5 :d 6)
   # [[:b 2] [:c 3] [:d 6] [:a 5]]"
  [pairs hash]
  :fetch (into {} pairs)
  :putback (loop [kvs [] okvs pairs m hash]
             (if-let [[[k v :as kv] & okvs] okvs]
               (if (= (get m k m) v)
                 (recur (conj kvs kv) okvs (dissoc m k))
                 (recur kvs okvs m))
               (into kvs m))))

(def ^:private transitions {})

(defn deftransition [from lens-or-lens-type to]
  (let [{to :type :as transition-info} (if (map? to) to {:type to})]
    (when-not (and (namespace from) (namespace to))
      (throw (ex-info "from and to must be namespaced" {:from from :to to})))
    (alter-var-root #'transitions update-in [from lens-or-lens-type] merge transition-info)))

(defn deftransitions [transitions-map]
  (doseq [[from to-map] transitions-map
          [lens-type to] to-map]
    (deftransition from lens-type to)))

(defn lens-types [lens]
  (let [e (expr-form lens)
        type (cond
               (= e lens) (class lens)
               (symbol? e) e
               :else (first e))]
    (cons lens (cons type (ancestors type)))))

(defn transition-info [value-type lens]
  (when-let [to-map (get transitions value-type)]
    (some #(get to-map %) (lens-types lens))))

(defn fetch-type [value-type lens]
  (reduce (clojure.core/comp :type transition-info)
    value-type (decompose lens)))

(defn simplify
  "Simplify constant lenses and collapse compounded slice lenses."
  [l]
  (lens
    (reduce
      (fn [lenses l]
        (let [prev-lens (peek lenses)]
          (cond
            (const? prev-lens) [(const (fetch (fetch nil prev-lens) l))]
            (slice? prev-lens)
            (let [[pfrom] (bounds prev-lens)]
              (if (slice? l)
                (let [[from to] (bounds l)]
                  ; TODO check for special bounds
                  (-> lenses pop (conj (slice (+ pfrom from) (+ pfrom to)))))
                (conj lenses l)))
            (const? l) [l]
            :else (conj lenses l))))
     [] (decompose l))))

(defn canonical
  "Canonicalize a path: simplify and add a parent slice for each indexed access."
  [l]
  (lens
    (reduce
      (fn [lenses l]
        (let [prev-lens (peek lenses)]
          (cond
            (not (number? l)) (conj lenses l)
            (slice? prev-lens)
            (let [[pfrom] (bounds prev-lens)]
              (let [from (+ pfrom l)]
                ; TODO check for special bounds
                (-> lenses pop (conj (slice from (inc from)) 0))))
            :else (conj lenses (slice l (inc l)) 0))))
      [] (decompose (simplify l)))))

(defn minimal [l]
  (lens
    (reduce
     (fn [lenses l]
       (let [prev-lens (peek lenses)]
         (if (and (number? l) (slice? prev-lens))
           (let [[pfrom] (bounds prev-lens)]
             ; TODO check for special bounds
             (-> lenses pop (conj (+ pfrom l))))
           (conj lenses l))))
     [] (decompose (simplify l)))))

(defn relativize [l prefix-lens]
  ; lenses should be canonical
  (loop [lenses (decompose l) prefix-lenses (decompose prefix-lens)]
    (if-let [[plens & more-prefix-lenses] (seq prefix-lenses)]
      (when-let [[l & more-lenses] (seq lenses)]
        (cond
          (= l plens) (recur more-lenses more-prefix-lenses)
          (and (slice? l) (slice? plens) (empty? more-prefix-lenses))
          (let [[pfrom pto] (bounds plens)
                [from to] (bounds l)]
            (when (and (<= pfrom from) (<= to pto))
              (lens (into [(slice (- from pfrom) (- to pfrom))] more-lenses))))))
      (lens lenses))))

(defn focus
  "Takes a n-arg function and returns a 1-arg function. All arguments to the original
   function are fetched from the single argument using the arg-lenses, the return
   vaues is putback in the single argument using ret-lens."
  [ret-lens f & arg-lenses]
  (fn [x]
    (putback x ret-lens
      (apply f (map #(fetch x %) arg-lenses)))))

(defn af
  "autofocus: like focus except the main-lens is both the ret-lens and
   the first arg-lens."
  [f main-lens & other-lenses]
  (apply focus main-lens f main-lens other-lenses))

#_(defn lens-map [m]
   (let [m (if (map? m) m (zipmap m m))]
     (reify Lens
       (-fetch [_ x]
         (zipmap (keys m) (map #(fetch x %) (vals m))))
       (-putback [_ x y]
         (reduce-kv (fn [x k l]
                      (putback x k (get y k)))
           x m)
         (zipmap (keys m) (map #(fetch x %) (vals m)))))))

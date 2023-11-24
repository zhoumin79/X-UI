;; Run with ./script/vdom.sh

(ns vdom
  (:refer-clojure :exclude [flatten])
  (:require
    [clojure.core.server :as server]
    [clojure.math :as math]
    [clojure.string :as str]
    [clj-async-profiler.core :as profiler]
    [io.github.humbleui.app :as app]
    [io.github.humbleui.canvas :as canvas]
    [io.github.humbleui.core :as core]
    [io.github.humbleui.font :as font]
    [io.github.humbleui.signal :as s]
    [io.github.humbleui.paint :as paint]
    [io.github.humbleui.protocols :as protocols]
    [io.github.humbleui.typeface :as typeface]
    [io.github.humbleui.ui :as ui]
    [io.github.humbleui.window :as window]
    [state :as state])
  (:import
    [io.github.humbleui.types IRect]
    [io.github.humbleui.skija Canvas TextLine]
    [io.github.humbleui.skija.shaper ShapingOptions]))

(declare reconciler)

;; Constants

(def padding
  20)


;; Utils

(defn flatten [xs]
  (mapcat #(if (and (not (vector? %)) (sequential? %)) % [%]) xs))

(defn single [xs]
  (assert (<= (count xs) 1) (str "Expected one, got: " (doall xs)))
  (first xs))

(defn some-vec [& xs]
  (filterv some? xs))


;; base classes

(def ctor-border
  (paint/stroke 0x80FF00FF 2))

(core/defparent AComponent3
  [^:mut props
   ^:mut children
   ^:mut mounted?
   ^:mut self-rect]
  protocols/IComponent
  (-measure [this ctx cs]
    (protocols/-measure-impl this ctx cs))
  
  (-measure-impl [this ctx cs]
    (core/measure (single children) ctx cs))
  
  (-draw [this ctx rect canvas]
    (set! self-rect rect)
    (protocols/-draw-impl this ctx rect canvas)
    (when-not mounted?
      (canvas/draw-rect canvas (-> ^IRect rect .toRect (.inflate 4)) ctor-border)
      (set! mounted? true)))

  (-draw-impl [this ctx rect canvas]
    (core/draw-child (single children) ctx rect canvas))
  
  (-event [this ctx event]
    (protocols/-event-impl this ctx event))
  
  (-event-impl [this ctx event]
    (reduce #(core/eager-or %1 (protocols/-event %2 ctx event)) nil children)))


;; parse-desc

(defn parse-desc [[tag & body]]
  (if (map? (first body))
    {:tag      tag
     :props    (first body)
     :children (next body)}
    {:tag      tag
     :children body}))

;; compatible?

(defmulti compatible? (fn [ctx past desc] (first desc)))

(defmethod compatible? :default [ctx past desc]
  (and past desc
    (let [{:keys [tag]} (parse-desc desc)]
      (cond
        (class? tag)
        (= (class past) tag)
      
        (ifn? tag)
        (= (:ctor past) tag)
      
        :else
        (throw (ex-info "I’m confused" {:past past, :desc desc}))))))

    
;; upgrade

(defmulti upgrade (fn [ctx past desc] (first desc)))

(defmethod upgrade :default [ctx past desc]
  (let [{:keys [tag props]} (parse-desc desc)]
    (protocols/-set! past :props props))
  past)

;; ctor

(defmulti ctor (fn [ctx desc] (first desc)))

(defmethod ctor :default [ctx desc]
  (let [{:keys [tag props children]} (parse-desc desc)]
    (cond
      (class? tag)
      (let [sym  (symbol (str "map->" (.getSimpleName ^Class tag)))
            ctor (ns-resolve 'vdom sym)]
        (ctor props))
      
      (ifn? tag)
      (reconciler tag props children)
      
      :else
      (throw (ex-info "I’m confused" {:desc desc})))))


;; components

;; Label

(core/deftype+ Label [^:mut text
                      ^:mut font
                      ^:mut ^TextLine line]
  :extends AComponent3
  protocols/IComponent
  (-measure-impl [_ ctx cs]
    (core/ipoint
      (math/ceil (.getWidth line))
      (* (:scale ctx) (:leading ctx))))
  
  (-draw-impl [this ctx rect ^Canvas canvas]
    (.drawTextLine canvas
      line
      (:x rect)
      (+ (:y rect) (* (:scale ctx) (:leading ctx)))
      (:fill-text ctx)))
  
  protocols/ILifecycle
  (-on-unmount-impl [_]
    (.close line)))
  
(defmethod ctor Label [ctx desc]
  (let [props    (:props (parse-desc desc))
        text     (:text props)
        font     (:font-ui ctx)
        features (or (:features props) (:features ctx) ShapingOptions/DEFAULT)]
    (map->Label
      {:props props
       :font  font
       :line  (.shapeLine core/shaper text font features)})))

(defmethod compatible? Label [ctx past desc]
  (and
    past
    desc
    (= (class past) (first desc))
    (= (:props past) (:props (parse-desc desc)))))
        

;; Center

(core/deftype+ Center []
  :extends AComponent3
  protocols/IComponent
  (-measure-impl [_ ctx cs]
    cs)

  (-draw-impl [this ctx rect canvas]
    (let [child      (single children)
          w          (:width rect)
          h          (:height rect)
          child-size (protocols/-measure child ctx (core/isize w h))
          cw         (:width child-size)
          ch         (:height child-size)
          rect'      (core/irect-xywh
                       (-> (:x rect) (+ (/ w 2)) (- (/ cw 2)))
                       (-> (:y rect) (+ (/ h 2)) (- (/ ch 2)))
                       cw ch)]
      (protocols/-draw child ctx rect' canvas))))


;; OnClick

(core/deftype+ OnClick []
  :extends AComponent3
  protocols/IComponent
  (-measure-impl [_ ctx cs]
    (protocols/-measure (single children) ctx cs))

  (-draw-impl [this ctx rect canvas]
    (protocols/-draw (single children) ctx rect canvas))
  
  (-event-impl [this ctx event]
    (when (and
            (= :mouse-button (:event event))
            (:pressed? event)
            (core/rect-contains? self-rect (core/ipoint (:x event) (:y event))))
      ((:on-click props) event))
    (core/event-child (single children) ctx event)))


;; Padding

(core/deftype+ Padding []
  :extends AComponent3
  protocols/IComponent
  (-measure-impl [_ ctx cs]
    (let [scale  (:scale ctx)
          left   (* scale (or (:left props)   (:horizontal props) (:padding props) 0))
          right  (* scale (or (:right props)  (:horizontal props) (:padding props) 0))
          top    (* scale (or (:top props)    (:vertical props)   (:padding props) 0))
          bottom (* scale (or (:bottom props) (:vertical props)   (:padding props) 0))
          cs'    (core/ipoint
                   (- (:width cs) left right)
                   (- (:height cs) top bottom))
          size'  (core/measure (single children) ctx cs')]
      (core/ipoint
        (+ (:width size') left right)
        (+ (:height size') top bottom))))

  (-draw-impl [this ctx rect canvas]
    (let [scale  (:scale ctx)
          left   (* scale (or (:left props)   (:horizontal props) (:padding props) 0))
          right  (* scale (or (:right props)  (:horizontal props) (:padding props) 0))
          top    (* scale (or (:top props)    (:vertical props)   (:padding props) 0))
          bottom (* scale (or (:bottom props) (:vertical props)   (:padding props) 0))
          rect'  (core/irect-ltrb
                   (+ (:x rect) left)
                   (+ (:y rect) top)
                   (- (:right rect) right)
                   (- (:bottom rect) bottom))]
      (protocols/-draw (single children) ctx rect' canvas))))


;; Rect

(core/deftype+ Rect []
  :extends AComponent3
  protocols/IComponent  
  (-draw-impl [this ctx rect canvas]
    (canvas/draw-rect canvas rect (:fill props))
    (core/draw-child (single children) ctx rect canvas)))


;; Column

(core/deftype+ Column []
  :extends AComponent3
  protocols/IComponent
  (-measure-impl [_ ctx cs]
    (let [gap (* (:scale ctx) padding)]
      (loop [children children
             w        0
             h        0]
        (if-some [child (first children)]
          (let [size (protocols/-measure child ctx cs)]
            (recur
              (next children)
              (long (max w (:width size)))
              (long (+ h (:height size) gap))))
          (core/isize w h)))))
  
  (-draw-impl [this ctx rect canvas]
    (let [gap   (* (:scale ctx) padding)
          width (:width rect)]
      (loop [children children
             top      (:y rect)]
        (when-some [child (first children)]
          (let [size (protocols/-measure child ctx (core/isize (:width rect) (:height rect)))
                x    (+ (:x rect) (/ (- width (:width size)) 2))]
            (protocols/-draw child ctx (core/irect-xywh x top (:width size) (:height size)) canvas)
            (recur (next children) (+ top (:height size) gap))))))))

;; Row

(core/deftype+ Row []
  :extends AComponent3
  protocols/IComponent
  (-measure-impl [_ ctx cs]
    (let [gap (* (:scale ctx) padding)]
      (loop [children children
             w        0
             h        0]
        (if-some [child (first children)]
          (let [size (protocols/-measure child ctx cs)]
            (recur
              (next children)
              (long (+ w (:width size) gap))
              (long (max h (:height size)))))
          (core/isize w h)))))
  
  (-draw-impl [this ctx rect canvas]
    (let [gap (* (:scale ctx) padding)
          height   (:height rect)]
      (loop [children children
             left     (:x rect)]
        (when-some [child (first children)]
          (let [size (protocols/-measure child ctx (core/isize (:width rect) (:height rect)))
                y    (+ (:y rect) (/ (- height (:height size)) 2))]
            (protocols/-draw child ctx (core/irect-xywh left y (:width size) (:height size)) canvas)
            (recur (next children) (+ left (:width size) gap))))))))

;; App

(defn reconcile [ctx past current]
  (let [past-keyed (into {}
                     (keep #(when-some [key (:key (:props %))] [key %]) past))]
    (loop [past-coll    (remove #(:key (:props %)) past)
           current-coll (flatten current)
           future-coll  []]
      (if (empty? current-coll)
        future-coll
        (let [current           (first current-coll)
              {:keys [tag props children]} (parse-desc current)
              current-coll'     (next current-coll)
              past              (past-keyed (:key props))
              [past past-coll'] (core/cond+
                                  ;; key match
                                  (and past (compatible? ctx past current))
                                  [past past-coll]
                                  
                                  ;; full match
                                  (compatible? ctx (first past-coll) current)
                                  [(first past-coll) (next past-coll)]
                                
                                  ;; inserted new widget
                                  (compatible? ctx (first past-coll) (fnext current-coll))
                                  [nil past-coll]
                                
                                  ;; deleted a widget
                                  (compatible? ctx (fnext past-coll) current)
                                  [(fnext past-coll) (nnext past-coll)]
                                
                                  ;; no match
                                  :else
                                  [nil (next past-coll)])
              future            (if past
                                  (upgrade ctx past current)
                                  (ctor ctx current))]
          (when (class? tag)
            (protocols/-set! future :children (reconcile ctx (:children past) children)))
          (recur past-coll' current-coll' (conj future-coll future)))))))

(def ^:dynamic *reconciler*)

(defn ensure-children [reconciler ctx]
  (binding [*reconciler* reconciler]
    (let [children' (reconcile ctx
                      (:children reconciler)
                      [((:ctor reconciler)
                        (:props reconciler)
                        (:children-desc reconciler))])]
      (protocols/-set! reconciler :children children'))))

(core/deftype+ Reconciler [^:mut ctor
                           ^:mut props
                           ^:mut children
                           ^:mut state
                           ^:mut children-desc]
  protocols/IComponent
  (-measure [this ctx cs]
    (ensure-children this ctx)
    (core/measure (single children) ctx cs))
  
  (-draw [this ctx rect canvas]
    (ensure-children this ctx)
    (core/draw-child (single children) ctx rect canvas))

  (-event [this ctx event]
    (core/event-child (single children) ctx event))

  (-iterate [this ctx cb]
    (or
      (cb this)
      (core/iterate-child (single children) ctx cb))))

(defn reconciler
  ([ctor]
   (reconciler ctor {} nil))
  ([ctor props children]
   (map->Reconciler
     {:ctor          ctor
      :props         props
      :children-desc children})))

(defn use-state [init]
  (let [*a  (or
              (:state *reconciler*)
              (atom init))
        _   (protocols/-set! *reconciler* :state *a)
        val @*a
        set #(do
               (reset! *a %)
               (state/request-frame))]
    [val set]))

(def *state
  (atom
    (sorted-map 0 0, 1 0, 2 0)))

(defn button [{:keys [on-click]} children]
  [OnClick {:on-click on-click}
   [Rect {:fill (paint/fill 0xFFB2D7FE)}
    [Padding {:padding 10}
     children]]])

(defn row [{:keys [id count]} _]
  (let [[local set-local] (use-state 0)]
    [Row
     [Label {:text (str "Id: " id)}]
     [button {:on-click (fn [_] (swap! *state dissoc id))}
      [Label {:text "DEL"}]]
     [Label {:text (str "Global: " count)}]
     [button {:on-click (fn [_] (swap! *state update id inc))}
      [Label {:text "INC"}]]
     [Label {:text (str "Local: " local)}]
     [button {:on-click (fn [_] (set-local (inc local)))}
      [Label {:text "INC"}]]]))

(defn root [_ _]
  [Center
   [Column
    (for [[id count] @*state]
      [row {:key id, :id id, :count count}])
    [Row
     [button {:on-click (fn [_] (swap! *state assoc (inc (last (keys @*state))) 0))}
      [Label {:text "ADD"}]]]]])

(def app
  (ui/default-theme
    {:cap-height 15}
    (ui/with-context
      {:features (.withFeatures ShapingOptions/DEFAULT "tnum")}
      (reconciler root))))

(add-watch *state ::redraw
  (fn [_ _ _ _]
    (state/request-frame)))

(comment
  (-> app
    :child
    :child
    :child
    :children)
  )
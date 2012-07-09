(ns reactive.core
  (:require [clojure.string :as string]))

; UI programming tends to be ugly, imparative, and side-effect ridden code. 
; Functional Reactive Programming is a subset of declarative programming that attempts
; to rectify this by expression relationships between values as contraints instead
; forcing the programmer to manualy update values.
;
; For example, in normal imparative programming the following code:
; let x = y + 1
; 
; Would say "add one to the value of y and assign it to x"
; In FRP, the above expression would be interpreted as "bind x to y incremented 
; by one". In FRP, if y changes, the value of x will be automatically updated as well.
;
; Most UI requirements can be boiled down into this relatinship of bindings. For
; instance, let's assume the requirement was to display the mouse's x and y positions
; in the title of the window and to update them as the mouse moves. In traditional,
; imparative programming, this would be accomplished thusly:
; (create-window my-window
;  :on-mouse-move (fn [e]
;                  (.setTitle my-window (str "x:" (:x e) " y: " (:y e)))))
;
; Not only is this code pure side-effects, but it also does not properly convey
; the concept. That is, if we want to find the code updating the window's title, 
; we must look at a mouse-move event, and not at the title property of the window. 
; 
; The FRP approach to this method could be expressed in a much different manner
; (create-window my-window
;  :title (str "x:" my-window:mouse-x " y: " mywindow:mouse-y))
; 
; The syntax my-window:mouse-x says "the event stream called mouse-x on the object
; my-window. Not only is this code now free of side-effects, but it properly expresses
; the relationship between titles and mouse positions. 
; 
; --
; This implementation of FRP is based on a mark/push/pull methodology that borrows
; very slightly from the concepts found in "Integrating Dataflow Evaluation into 
; a Practical Higher-Order Call-by-Value Language". 
; http://www.cs.brown.edu/research/pubs/theses/phd/2008/cooper.pdf
; 
; I say slightly, as there is really no code shared between the projects, the paper
; as used mostly for inspiration. 

; Now, let's look over the implementation of these concepts. In this implementation
; there are three main steps a event goes through. First, let's example the expression
; let x = y
; 
; In this case, Y must be a IObservable, and x must be a IEventSink. Since observers
; can have multiple inputs, the IObserver simply allows for selection of inputs, and
; then delegates all logic to the IEventSink interface. When y is updated, it is marked
; as dirty and then it marks it's children in the expression tree as dirty. Once
; x has been marked dirty, getting the value of x will cause x to call y. So these are the
; three stages
; 
; Mark - mark all dependants of the node as dirty
; Push - notify all dependants that they can now update as all children that should be 
;        dirty have been marked thus
; Pull - One or more children need a value, so they call their parents and the cache
;        any values. 
; 
; This three stage process allows both for complex expression trees, as well as 
; decent performance due to value caching, calling (deref-value x) several times
; will not cause a re-invocation of y's calculations. 
;
; A note about mark-dirty. In order to avoid back-references from children to parents
; when a child is marked as being dirty it is passed a function. This function is called
; by the child to get the value of its parent. This will become clearer in the code below
; but it's good to be aware of it for now


(defprotocol IObservableLookup
  (get-observable [this k] "Gets the observable event stream with the given name"))

(defprotocol IObservable
  (attach [this k sink] "Attaches sink to this event stream with the specified key")
  (detatch [this k] "Detatches the sink associated with k")
  (deref-value [this] "Gets the current value of this value"))

(defprotocol IObserver
  (get-socket [this] [this nm] "Gets a socket with the given name, defaults to :default"))

(defprotocol IEventSocket
  (mark-dirty [this f] "Marks this socket as dirty, instructing it to clear its cache and call f when it needs to
                        update its cache")
  (push-update [this]))


(defprotocol INextEvent
  (next-event [this v] "Push a new event into this node"))



(deftype LiftedValue [v]
  IObservable
  (attach [this k sink]
          (mark-dirty sink #(identity v))
          (push-update sink))
  (detatch [this k] nil)
  (deref-value [this] v))

(defn lift
  "This represents a contstant or a variable that has been lifted. This takes a 
  normal value and converts it to a never-changing event stream"
  [v]
  (if-not (extends? IObservable (type v))
    (LiftedValue. v)
    v))

(defn reactive-cell 
  "Reactive cells are object that allow arbitrary code to cause re-evaluation
  of bindings. This is for interop between normal, imparative code, and binding
  trees"  
  [init]
  (let [value (atom init)
        listeners (atom {})]
    (reify
      IObservable
      (attach [this k sink]
        (swap! listeners assoc k sink)
        (mark-dirty sink #(deref-value this))
        (push-update sink))
      (detatch [this k]
        (swap! listeners dissoc k))
      (deref-value [this] @value)
      INextEvent
      (next-event [this v]
        (reset! value v)
        (doall
         (for [[_ l] @listeners]
           (mark-dirty l #(deref-value this))))
        (doall
         (for [[_ l] @listeners]
           (push-update l)))))))

(defn unknown?
  "Is a value ::unknown?"
  [v]
  (= v ::unknown))

(defn not-unknown?
  "Is a value not ::unknown?"
  [v]
  (not (unknown? v)))

(defn get-vals
  "Given a reactive node state map, return the values of the inputs. 
  These will either be functions that take no arguments (and return a value),
  or ::unknown. ::unknown will be the value for unbound inputs."
  [state]
  (map :value-fn (vals (:slots @state))))

(defn should-mark-dirty? [state]
  (let [vals (get-vals state)]
    (apply (:mark-dirty? @state) vals)))

(defn get-deref-value [state]
  (let [vals (get-vals state)
        update? (apply (:push-update? @state) vals)]
    (if update?
      (apply (:map-fn @state) vals)
      ::unknown)))



(defn reactive-node 
  "This is a helper function for creating reactive nodes. The value returned 
  by this function has multiple inputs and one output.
  slots - a vector of inputs. Should be keywords, integers, or symbols
  mark-dirty? - a function that accepts n number of items (where n is (count slots))
               and returns true if this node should be marked as dirty
  push-update? - same as mark-dirty? but specifies if a given event should be pushed
                 to children
  map-fn - same signature as mark-dirty? but this function returns the new state
           of this node"
  [slots mark-dirty? push-update? map-fn]
  (let [state {:slots (into {}
                            (map (fn [s]
                                   [s {:value-fn ::unknown
                                       :is-dirty? true}])
                                 slots))
               :mark-dirty? mark-dirty?
               :push-update? push-update?
               :map-fn map-fn
               :value ::unknown
               :listeners {}}
        state (atom state)]
    (reify
      IObservable
      (attach [this k sink]
        (swap! state assoc-in [:listeners k] sink)
        (mark-dirty sink #(deref-value this)))
      (detatch [this k]
        (swap! state update-in [:listeners] #(dissoc k)))
      (deref-value [this]
        (if (unknown? (:value @state))
          (get-deref-value state)
          (:value @state)))

      clojure.lang.IDeref
      (deref [this]
        (deref-value this))

      IObserver
      (get-socket [this] (get-socket this (first slots)))
      (get-socket [this s]
        (reify
          IEventSocket
          (mark-dirty [sthis value-fn]
            (swap! state assoc-in [:slots s :value-fn] value-fn)
            (when (should-mark-dirty? state)
              (swap! state assoc :value ::unknown)
              (doall (for [[_ v] (:listeners @state)]
                       (mark-dirty v #(deref-value this))))))

          (push-update [sthis]
            (when (apply (:push-update? @state) (get-vals state))
              (doall (for [[_ v] (:listeners @state)]
                       (push-update v))))))))))


(defn reactive-atom 
  "Creates a bindable variable. Has one input and one output."
  []
  (reactive-node
   [:default]
   (fn [default] true)
   (fn [default] true)
   (fn [default]
     (if (unknown? default)
       default
       (default)))))

(defn reactive-action [action]
  "Same as reactive-atom except this item calls (action value) for every new value
   that comes through the event stream"
  (reactive-node
   [:default]
   (fn [default] true)
   (fn [default]
     (when (not-unknown? default)
           (action (default)))
     true)
   (fn [default]
     (when (not-unknown? default)
       (default)))))

(defn reactive-invoke
  "Creates a node that takes one or more argument. As each new event is passed 
  to this node it applies the most recent value of the first binding to the most recent
  value of every other input binding. A great example of this would be the binding
  (str (vector form:mouse-x form:mouse-y)). Here str and vector are lifted, and then
  a reactive-invoke is created for each form"
  [slots]
  (reactive-node
   (vec (range slots))
   (fn [& slots] true)
   (fn [& slots]
     (every? not-unknown? slots))
   (fn [& slots]
     (if (every? not-unknown? slots)
       (apply ((first slots))
              (map #(%) (next slots)))))))

(def interpret-binding)

(defn interpret-invoke [bindings]
  (let [bindings (vec bindings)
        nd (gensym "invokesym")]
    `(let [~nd (reactive-invoke ~(count bindings))]
       ~@(for [b (range (count bindings))]

           `(connect ~(interpret-binding (bindings b))
                     ~nd
                     ~b))
       ~nd)))


(defn interpret-symbol [s]
  (let [sp (clojure.string/split (name s) #":")]
    (if (= (count sp) 2)
      `(get-observable ~(symbol (first sp)) ~(keyword (second sp)))
       `(lift ~s))))

(defn interpret-binding [bindings]
  (cond (seq? bindings)
        (interpret-invoke bindings)
        (symbol? bindings)
        (interpret-symbol bindings)
        :else
        `(lift ~bindings)))

(defn debug [x]
  (println x)
  x)

(defmacro bind
  "Bind itm with a given binding expression. In this context, symbols that contain
  colons (e.g. form:mouse-x) are considered to to be the equivalent to:
  (get-observable form :mouse-x)"
  ([itm bindings]
      `(connect ~(interpret-binding bindings) ~itm))
  ( [itm slot bindings]
      (debug `(connect ~(interpret-binding bindings) ~itm ~slot))))

(defn connect
   "Connect two nodes. The input slot for the destination can be specified" 
   ([src dest]
              (connect src dest :default))
           ( [src dest dk]
               (let [dests (get-socket dest dk)]
                 (attach src dest dests))))

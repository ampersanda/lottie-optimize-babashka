(ns optimize-lottie.json)

(defn truncate-float [x decimals]
  (if (float? x)
    (let [factor (Math/pow 10 decimals)
          rounded (/ (Math/round (* (double x) factor)) factor)]
      (if (== rounded (long rounded))
        (long rounded)
        rounded))
    x))

(defn truncate-precision
  "Recursively truncate float precision, skipping strings."
  [obj decimals]
  (cond
    (float? obj)   (truncate-float obj decimals)
    (map? obj)     (persistent!
                    (reduce-kv (fn [m k v]
                                 (assoc! m k (truncate-precision v decimals)))
                               (transient {}) obj))
    (vector? obj)  (mapv #(truncate-precision % decimals) obj)
    (list? obj)    (map #(truncate-precision % decimals) obj)
    :else          obj))

(defn remove-editor-metadata
  "Remove properties only needed by After Effects / editors."
  [obj]
  (cond
    (map? obj) (persistent!
                (reduce-kv (fn [m k v]
                             (if (= k :mn)
                               m
                               (assoc! m k (remove-editor-metadata v))))
                           (transient {}) obj))
    (vector? obj) (mapv remove-editor-metadata obj)
    (list? obj)   (map remove-editor-metadata obj)
    :else         obj))

(defn scale-2d-prop
  "Scale a 2D vector property (static or animated) by sx/sy ratios."
  [prop sx sy]
  (if (= 1 (:a prop))
    (update prop :k
      (fn [kfs]
        (mapv (fn [kf]
                (cond-> kf
                  (:s kf) (update :s (fn [[x y & rest]] (into [(* x sx) (* y sy)] rest)))
                  (:e kf) (update :e (fn [[x y & rest]] (into [(* x sx) (* y sy)] rest)))))
              kfs)))
    (update prop :k (fn [[x y & rest]] (into [(* x sx) (* y sy)] rest)))))

(defn adjust-image-layers
  "Adjust anchor points and scale of image layers whose assets were resized."
  [layers scale-map]
  (mapv (fn [layer]
          (let [layer (if (and (= 2 (:ty layer))
                               (contains? scale-map (:refId layer)))
                        (let [{:keys [sx sy]} (get scale-map (:refId layer))
                              inv-sx (/ 1.0 sx)
                              inv-sy (/ 1.0 sy)]
                          (cond-> layer
                            (get-in layer [:ks :a])
                            (update-in [:ks :a] scale-2d-prop sx sy)
                            (get-in layer [:ks :s])
                            (update-in [:ks :s] scale-2d-prop inv-sx inv-sy)))
                        layer)]
            (if (:layers layer)
              (update layer :layers adjust-image-layers scale-map)
              layer)))
        layers))

(defn reduce-framerate [data target-fps]
  (let [orig-fps (:fr data 60)]
    (if (<= orig-fps target-fps)
      data
      (let [ratio (/ (double target-fps) (double orig-fps))]
        (letfn [(scale-time [v] (Math/round (* (double v) ratio)))
                (walk [obj]
                  (cond
                    (map? obj)
                    (let [obj (cond-> obj
                                (number? (:t obj))
                                (update :t scale-time)
                                (and (number? (:ip obj)) (number? (:op obj)))
                                (-> (update :ip scale-time)
                                    (update :op scale-time))
                                (number? (:st obj))
                                (update :st scale-time))]
                      (persistent!
                       (reduce-kv (fn [m k v] (assoc! m k (walk v)))
                                  (transient {}) obj)))
                    (vector? obj) (mapv walk obj)
                    (list? obj)   (map walk obj)
                    :else         obj))]
          (-> data
              (assoc :fr target-fps)
              (update :ip scale-time)
              (update :op scale-time)
              (update :layers #(mapv walk %))
              (update :assets #(mapv walk %))))))))

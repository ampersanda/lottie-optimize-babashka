(ns optimize-lottie.pipeline
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [optimize-lottie.util :as util]
            [optimize-lottie.image :as image]
            [optimize-lottie.json :as ojson]))

(defn optimize!
  "Optimize a single file. Returns {:orig-size n :final-size n :output-path s}."
  [{:keys [input output max-image-size webp-quality precision fps lossless]}]
  (let [pipe-mode   (util/stdin? input)
        raw-input   (try
                      (if pipe-mode
                        (slurp *in*)
                        (slurp input))
                      (catch Exception e
                        (util/log (str "Error: cannot read input: " (.getMessage e)))
                        nil))
        _           (when-not raw-input (System/exit 2))
        data        (try
                      (json/parse-string raw-input true)
                      (catch Exception e
                        (util/log (str "Error: invalid JSON: " (.getMessage e)))
                        nil))
        _           (when-not data (System/exit 3))
        _           (when-not (and (:w data) (:h data) (:layers data))
                      (util/log "Error: input does not appear to be a Lottie file (missing w, h, or layers)")
                      (System/exit 3))
        orig-json   (json/generate-string data)
        orig-size   (count orig-json)
        canvas-max  (max (:w data 512) (:h data 512))
        max-dim     (or max-image-size canvas-max)
        webp-q      (if lossless :lossless (or webp-quality 80))
        prec        (or precision 3)
        output-path (cond
                      (some? output)  output
                      pipe-mode       "-"
                      :else           (str (str/replace input #"\.json$" "")
                                          ".optimized.json"))
        input-label (if pipe-mode "<stdin>" input)]

    (util/log (str "Input:     " input-label))
    (util/log (str "Size:      " (util/format-bytes orig-size)))
    (util/log (str "Canvas:    " (:w data) "x" (:h data) " @ " (:fr data) "fps"))
    (util/log (str "Frames:    " (:ip data) "-" (:op data)))
    (util/log)

    ;; 1. Optimize images
    (util/log "Optimizing images...")
    (let [results    (mapv #(image/optimize-image-asset % max-dim webp-q) (:assets data))
          new-assets (mapv first results)
          total-orig (reduce + (map second results))
          total-new  (reduce + (map #(nth % 2) results))
          img-count  (count (filter #(pos? (second %)) results))
          scale-map  (into {}
                       (for [info (keep #(nth % 3) results)
                             :when (:id info)]
                         [(:id info) info]))
          data       (assoc data :assets new-assets)
          data       (if (seq scale-map)
                       (-> data
                           (update :layers ojson/adjust-image-layers scale-map)
                           (update :assets (fn [assets]
                                            (mapv (fn [a]
                                                    (if (:layers a)
                                                      (update a :layers ojson/adjust-image-layers scale-map)
                                                      a))
                                                  assets))))
                       data)]

      (let [img-saved    (when (pos? img-count) (- total-orig total-new))
            after-images (count (json/generate-string data))]

        (when img-saved
          (util/log (str "  " img-count " images: "
                         (util/format-bytes total-orig) " -> "
                         (util/format-bytes total-new)
                         " (saved " (util/format-bytes img-saved) ", "
                         (util/pct img-saved total-orig) "%)")))

        ;; 2. Truncate precision (preserve image data URIs)
        (util/log (str "Truncating float precision (max " prec " decimals)..."))
        (let [img-uris (into {}
                             (for [a (:assets data)
                                   :when (and (string? (:p a))
                                              (str/starts-with? (:p a) "data:"))]
                               [(:id a) (:p a)]))
              data     (ojson/truncate-precision data prec)
              data     (update data :assets
                              (fn [assets]
                                (mapv (fn [a]
                                        (if-let [uri (get img-uris (:id a))]
                                          (assoc a :p uri)
                                          a))
                                      assets)))
              after-precision (count (json/generate-string data))
              precision-saved (- after-images after-precision)]

          (util/log (str "  saved " (util/format-bytes precision-saved)))

          ;; 3. Remove editor metadata
          (util/log "Removing editor metadata...")
          (let [data (ojson/remove-editor-metadata data)
                after-metadata (count (json/generate-string data))
                metadata-saved (- after-precision after-metadata)]

            (util/log (str "  saved " (util/format-bytes metadata-saved)))

            ;; 4. Optional framerate reduction
            (let [[data fps-saved]
                  (if fps
                    (let [d (ojson/reduce-framerate data fps)
                          after-fps (count (json/generate-string d))
                          s (- after-metadata after-fps)]
                      (util/log (str "Reducing framerate to " fps "fps..."))
                      (util/log (str "  saved " (util/format-bytes s)))
                      [d s])
                    [data 0])

                  final-json  (json/generate-string data)
                  final-size  (count final-json)
                  total-saved (- orig-size final-size)]

              ;; Write output
              (if (util/stdout? output-path)
                (print final-json)
                (try
                  (spit output-path final-json)
                  (catch Exception e
                    (util/log (str "Error: cannot write output file: " (.getMessage e)))
                    (System/exit 6))))

              {:orig-size   orig-size
               :final-size  final-size
               :total-saved total-saved
               :output-path output-path})))))))

(defn print-summary [{:keys [orig-size final-size total-saved output-path]}]
  (util/log)
  (util/log (str (util/format-bytes orig-size) " -> " (util/format-bytes final-size)
                 " (" (util/pct total-saved orig-size) "% smaller)"
                 " -> " (if (util/stdout? output-path) "<stdout>" output-path))))

(defn print-batch-summary [results]
  (let [total-orig  (reduce + (map :orig-size results))
        total-final (reduce + (map :final-size results))
        total-saved (- total-orig total-final)]
    (util/log)
    (util/log (str "Batch: " (count results) " files, "
                   (util/format-bytes total-orig) " -> " (util/format-bytes total-final)
                   " (" (util/pct total-saved total-orig) "% smaller)"))))

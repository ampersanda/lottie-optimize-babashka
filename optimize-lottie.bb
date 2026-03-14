#!/usr/bin/env bb

(ns optimize-lottie
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :as p]
            [babashka.fs :as fs]
            [babashka.cli :as cli])
  (:import [java.util Base64]
           [java.io File]))

;; ---------------------------------------------------------------------------
;; Image helpers (delegates to ImageMagick + cwebp)
;; ---------------------------------------------------------------------------

(defn decode-b64 [^String s]
  (.decode (Base64/getDecoder) s))

(defn encode-b64 [^bytes bs]
  (.encodeToString (Base64/getEncoder) bs))

(defn parse-data-uri
  "Returns {:mime \"image/png\" :data <byte-array>} or nil."
  [p]
  (when (and (string? p) (str/starts-with? p "data:"))
    (let [[header b64] (str/split p #"," 2)
          mime (-> header (str/replace "data:" "") (str/replace ";base64" ""))]
      {:mime mime :data (decode-b64 b64)})))

(defn file->bytes [^File f]
  (let [ba (byte-array (.length f))]
    (with-open [is (io/input-stream f)]
      (.read is ba))
    ba))

(defn which [cmd]
  (try
    (let [r (p/sh ["which" cmd])]
      (when (zero? (:exit r))
        (str/trim (:out r))))
    (catch Exception _ nil)))

(defn identify-image
  "Get image dimensions via ImageMagick identify. Returns [w h] or nil."
  [^File f]
  (try
    (let [r (p/sh ["magick" "identify" "-format" "%w %h" (.getAbsolutePath f)])]
      (when (zero? (:exit r))
        (let [[w h] (str/split (str/trim (:out r)) #"\s+")]
          [(parse-long w) (parse-long h)])))
    (catch Exception _ nil)))

(defn resize-and-convert!
  "Resize image to max-dim and save as PNG. Returns the output file."
  [^File input-file max-dim ^File output-file]
  (let [result (p/sh ["magick" (.getAbsolutePath input-file)
                       "-resize" (str max-dim "x" max-dim ">")
                       "-define" "png:compression-level=9"
                       (.getAbsolutePath output-file)])]
    (when (zero? (:exit result))
      output-file)))

(defn png->webp!
  "Convert PNG to WebP via cwebp. Returns WebP file or nil.
   quality: 0-100 for lossy, :lossless for lossless."
  [^File png-file quality ^File webp-file]
  (let [args (if (= quality :lossless)
               ["cwebp" "-lossless" "-m" "6" "-z" "9"
                (.getAbsolutePath png-file)
                "-o" (.getAbsolutePath webp-file)]
               ["cwebp" "-q" (str quality) "-m" "6"
                "-alpha_q" "100"
                (.getAbsolutePath png-file)
                "-o" (.getAbsolutePath webp-file)])
        result (p/sh args)]
    (when (zero? (:exit result))
      webp-file)))

(defn optimize-image-asset
  "Optimize a single embedded image asset. Returns [updated-asset orig-size new-size]."
  [asset max-dim webp-quality]
  (if-let [{:keys [data]} (parse-data-uri (:p asset))]
    (let [tmp-dir    (System/getProperty "java.io.tmpdir")
          in-file    (File. tmp-dir (str "lottie_in_" (gensym) ".png"))
          resized    (File. tmp-dir (str "lottie_rsz_" (gensym) ".png"))
          webp-file  (File. tmp-dir (str "lottie_out_" (gensym) ".webp"))
          orig-size  (count data)]
      (try
        ;; Write original to temp file
        (io/copy (java.io.ByteArrayInputStream. data) in-file)

        ;; Resize via ImageMagick
        (resize-and-convert! in-file max-dim resized)

        ;; Get new dimensions
        (let [[new-w new-h] (or (identify-image resized)
                                [(:w asset) (:h asset)])
              ;; Try WebP conversion
              webp-ok    (png->webp! resized webp-quality webp-file)
              ;; Compare sizes
              png-bytes  (file->bytes resized)
              webp-bytes (when (and webp-ok (.exists webp-file))
                           (file->bytes webp-file))
              ;; Pick smaller
              [best-bytes best-mime]
              (if (and webp-bytes (< (count webp-bytes) (count png-bytes)))
                [webp-bytes "image/webp"]
                [png-bytes "image/png"])
              new-uri (str "data:" best-mime ";base64," (encode-b64 best-bytes))]
          ;; Update w/h to actual resized dimensions — Android's
          ;; lottie renderer uses pixel dims, not declared size
          [(-> asset
               (assoc :p new-uri :u "" :e 1 :w new-w :h new-h))
           orig-size
           (count best-bytes)
           (when (and (:w asset) (:h asset)
                      (pos? (:w asset)) (pos? (:h asset))
                      (or (not= new-w (:w asset))
                          (not= new-h (:h asset))))
             {:id (:id asset)
              :sx (/ (double new-w) (double (:w asset)))
              :sy (/ (double new-h) (double (:h asset)))})])
        (finally
          (.delete in-file)
          (.delete resized)
          (.delete webp-file))))
    [asset 0 0 nil]))

;; ---------------------------------------------------------------------------
;; JSON / keyframe optimization
;; ---------------------------------------------------------------------------

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

;; ---------------------------------------------------------------------------
;; Main pipeline
;; ---------------------------------------------------------------------------

(defn format-bytes [n]
  (cond
    (>= n 1048576) (format "%.1fMB" (/ (double n) 1048576.0))
    (>= n 1024)    (format "%.1fKB" (/ (double n) 1024.0))
    :else           (str n "B")))

(defn pct [part whole]
  (if (pos? whole)
    (Math/round (* 100.0 (/ (double part) (double whole))))
    0))

(defn optimize! [{:keys [input output max-image-size webp-quality precision fps lossless]}]
  ;; Verify dependencies
  (when-not (which "magick")
    (println "Error: ImageMagick (magick) not found. Install via: brew install imagemagick")
    (System/exit 1))
  (when-not (which "cwebp")
    (println "Warning: cwebp not found. WebP conversion disabled. Install via: brew install webp"))

  (let [data         (json/parse-string (slurp input) true)
        orig-json    (json/generate-string data)
        orig-size    (count orig-json)
        canvas-max   (max (:w data 512) (:h data 512))
        max-dim      (or max-image-size canvas-max)
        webp-q       (if lossless :lossless (or webp-quality 80))
        prec         (or precision 3)
        output-path  (or output
                         (str (str/replace input #"\.json$" "")
                              ".optimized.json"))]

    (println (str "Input:     " input))
    (println (str "Size:      " (format-bytes orig-size)))
    (println (str "Canvas:    " (:w data) "x" (:h data) " @ " (:fr data) "fps"))
    (println (str "Frames:    " (:ip data) "-" (:op data)))
    (println)

    ;; 1. Optimize images
    (println "Optimizing images...")
    (let [results    (mapv #(optimize-image-asset % max-dim webp-q) (:assets data))
          new-assets (mapv first results)
          total-orig (reduce + (map second results))
          total-new  (reduce + (map #(nth % 2) results))
          img-count  (count (filter #(pos? (second %)) results))
          ;; Collect scale ratios for resized image assets
          scale-map  (into {}
                       (for [info (keep #(nth % 3) results)
                             :when (:id info)]
                         [(:id info) info]))
          data       (assoc data :assets new-assets)
          ;; Adjust anchor points and scale for resized assets
          data       (if (seq scale-map)
                       (-> data
                           (update :layers adjust-image-layers scale-map)
                           (update :assets (fn [assets]
                                            (mapv (fn [a]
                                                    (if (:layers a)
                                                      (update a :layers adjust-image-layers scale-map)
                                                      a))
                                                  assets))))
                       data)]

      (when (pos? img-count)
        (let [saved (- total-orig total-new)]
          (println (str "  " img-count " images: "
                        (format-bytes total-orig) " -> "
                        (format-bytes total-new)
                        " (saved " (format-bytes saved) ", "
                        (pct saved total-orig) "%)"))))

      ;; 2. Truncate precision (preserve image data URIs)
      (println (str "Truncating float precision (max " prec " decimals)..."))
      (let [img-uris (into {}
                           (for [a (:assets data)
                                 :when (and (string? (:p a))
                                            (str/starts-with? (:p a) "data:"))]
                             [(:id a) (:p a)]))
            data     (truncate-precision data prec)
            data     (update data :assets
                            (fn [assets]
                              (mapv (fn [a]
                                      (if-let [uri (get img-uris (:id a))]
                                        (assoc a :p uri)
                                        a))
                                    assets)))]

        ;; 3. Remove editor metadata
        (println "Removing editor metadata...")
        (let [data (remove-editor-metadata data)

              ;; 4. Optional framerate reduction
              data (if fps
                     (do (println (str "Reducing framerate to " fps "fps..."))
                         (reduce-framerate data fps))
                     data)

              ;; 5. Minify
              final-json (json/generate-string data)
              final-size (count final-json)
              saved      (- orig-size final-size)]

          (spit output-path final-json)

          (println)
          (println (str "Output:    " output-path))
          (println (str "Final:     " (format-bytes final-size)))
          (println (str "Saved:     " (format-bytes saved)
                        " (" (pct saved orig-size) "%)")))))))

;; ---------------------------------------------------------------------------
;; CLI
;; ---------------------------------------------------------------------------

(def cli-spec
  {:input          {:desc    "Input Lottie JSON file"
                    :alias   :i
                    :require true}
   :output         {:desc  "Output file path (default: <input>.optimized.json)"
                    :alias :o}
   :max-image-size {:desc   "Max image dimension in px (default: canvas size)"
                    :alias  :s
                    :coerce :int}
   :webp-quality   {:desc    "WebP quality 0-100 (default: 80)"
                    :alias   :q
                    :coerce  :int
                    :default 80}
   :precision      {:desc    "Float decimal precision (default: 3)"
                    :alias   :p
                    :coerce  :int
                    :default 3}
   :fps            {:desc   "Target framerate (default: keep original)"
                    :alias  :f
                    :coerce :int}
   :lossless       {:desc    "Use lossless WebP (no quality loss, larger files)"
                    :alias   :l
                    :coerce  :boolean
                    :default false}
   :help           {:desc  "Show help"
                    :alias :h}
   :version        {:desc  "Show version"
                    :alias :v}})

(defn print-help []
  (println "Usage: optimize-lottie.bb -i <file.json> [options]")
  (println)
  (println "Optimize Lottie animation files by compressing embedded images,")
  (println "truncating float precision, removing editor metadata, and minifying JSON.")
  (println)
  (println "Requires: ImageMagick (magick), cwebp (libwebp)")
  (println "Install:  brew install imagemagick webp")
  (println)
  (println "Options:")
  (println "  -i, --input FILE          Input Lottie JSON file (required)")
  (println "  -o, --output FILE         Output path (default: <input>.optimized.json)")
  (println "  -s, --max-image-size PX   Max image dimension (default: canvas size)")
  (println "  -q, --webp-quality 0-100  WebP quality (default: 80)")
  (println "  -p, --precision N         Float decimal places (default: 3)")
  (println "  -f, --fps N              Target framerate (default: keep original)")
  (println "  -l, --lossless           Lossless WebP (no quality loss, larger files)")
  (println "  -h, --help               Show this help")
  (println "  -v, --version            Show version")
  (println)
  (println "Examples:")
  (println "  bb optimize-lottie.bb -i anim.json")
  (println "  bb optimize-lottie.bb -i anim.json -o small.json -q 75 -s 512")
  (println "  bb optimize-lottie.bb -i anim.json --fps 30"))

(def version "1.2.0")

(cond
  (some #(contains? #{"-h" "--help"} %) *command-line-args*)
  (print-help)

  (some #(contains? #{"-v" "--version"} %) *command-line-args*)
  (println (str "optimize-lottie " version))

  :else
  (let [opts (cli/parse-opts *command-line-args* {:spec cli-spec})]
    (cond
      (not (:input opts))
      (do (println "Error: --input is required")
          (println)
          (print-help)
          (System/exit 1))

      (not (fs/exists? (:input opts)))
      (do (println (str "Error: file not found: " (:input opts)))
          (System/exit 1))

      :else
      (optimize! opts))))

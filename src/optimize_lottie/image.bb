(ns optimize-lottie.image
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :as p])
  (:import [java.util Base64]
           [java.io File]))

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
  "Optimize a single embedded image asset. Returns [updated-asset orig-size new-size scale-info]."
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
          ;; Update w/h to actual resized dimensions -- Android's
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

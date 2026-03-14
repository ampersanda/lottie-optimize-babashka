(ns optimize-lottie.cli
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [optimize-lottie.util :as util]
            [optimize-lottie.pipeline :as pipeline]))

;; ---------------------------------------------------------------------------
;; Exit codes
;; ---------------------------------------------------------------------------
;;   0  Success
;;   1  Missing required argument
;;   2  Input file not found
;;   3  Invalid input (not valid JSON or not a Lottie file)
;;   4  Missing required dependency (magick)
;;   5  Image optimization failed
;;   6  Write error (cannot write output file)

(def cli-spec
  {:input          {:desc  "Input Lottie JSON file (- for stdin)"
                    :alias :i}
   :output         {:desc  "Output file path (default: <input>.optimized.json, - for stdout)"
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

(def version "1.3.1")

(defn print-help []
  (println "Usage: optimize-lottie -i <file.json> [options]")
  (println "       optimize-lottie file1.json file2.json [options]")
  (println "       cat anim.json | optimize-lottie > output.json")
  (println)
  (println "Optimize Lottie animation files by compressing embedded images,")
  (println "truncating float precision, removing editor metadata, and minifying JSON.")
  (println)
  (println "Requires: ImageMagick (magick), cwebp (libwebp)")
  (println "Install:  brew install imagemagick webp")
  (println)
  (println "Options:")
  (println "  -i, --input FILE          Input Lottie JSON file (- for stdin)")
  (println "  -o, --output FILE         Output path (- for stdout)")
  (println "  -s, --max-image-size PX   Max image dimension (default: canvas size)")
  (println "  -q, --webp-quality 0-100  WebP quality (default: 80)")
  (println "  -p, --precision N         Float decimal places (default: 3)")
  (println "  -f, --fps N              Target framerate (default: keep original)")
  (println "  -l, --lossless           Lossless WebP (no quality loss, larger files)")
  (println "  -h, --help               Show this help")
  (println "  -v, --version            Show version")
  (println)
  (println "Examples:")
  (println "  optimize-lottie -i anim.json")
  (println "  optimize-lottie -i anim.json -o small.json -q 75 -s 512")
  (println "  optimize-lottie -i anim.json --fps 30")
  (println "  optimize-lottie *.json")
  (println "  cat anim.json | optimize-lottie > small.json")
  (println "  optimize-lottie -i - -o optimized.json < anim.json"))

(defn stdin-ready? []
  (try
    (pos? (.available System/in))
    (catch Exception _ false)))

(defn check-deps! []
  (when-not (util/which "magick")
    (util/log "Error: ImageMagick (magick) not found. Install via: brew install imagemagick")
    (System/exit 4))
  (when-not (util/which "cwebp")
    (util/log "Warning: cwebp not found. WebP conversion disabled. Install via: brew install webp")))

(defn -main [& cli-args]
  (let [args (or cli-args *command-line-args*)]
    (cond
      (some #(contains? #{"-h" "--help"} %) args)
      (print-help)

      (some #(contains? #{"-v" "--version"} %) args)
      (println (str "optimize-lottie " version))

      :else
      (let [{:keys [opts args]} (cli/parse-args args {:spec cli-spec})
            inputs (cond-> (vec args)
                     (:input opts) (conj (:input opts)))
            has-stdin (stdin-ready?)]

        (cond
          ;; -o with multiple files is ambiguous
          (and (:output opts) (> (count inputs) 1))
          (do (util/log "Error: -o cannot be used with multiple input files")
              (System/exit 1))

          ;; Multiple files: batch mode
          (> (count inputs) 1)
          (do
            (check-deps!)
            (let [total   (count inputs)
                  errors  (atom 0)
                  results (atom [])]
              (doseq [[idx input] (map-indexed vector inputs)]
                (util/log (str "[" (inc idx) "/" total "] " input))
                (if (fs/exists? input)
                  (let [result (pipeline/optimize! (assoc opts :input input))]
                    (pipeline/print-summary result)
                    (swap! results conj result))
                  (do (util/log (str "Error: file not found: " input))
                      (swap! errors inc)))
                (when (< (inc idx) total) (util/log)))
              (pipeline/print-batch-summary @results)
              (when (pos? @errors)
                (util/log (str @errors " file(s) skipped due to errors"))
                (System/exit 2))))

          ;; Single file via -i or positional arg
          (= (count inputs) 1)
          (let [input (first inputs)]
            (if (and (not= "-" input) (not (fs/exists? input)))
              (do (util/log (str "Error: file not found: " input))
                  (System/exit 2))
              (do
                (check-deps!)
                (let [result (pipeline/optimize! (assoc opts :input input))]
                  (pipeline/print-summary result)))))

          ;; No -i flag but stdin has data
          has-stdin
          (do
            (check-deps!)
            (let [result (pipeline/optimize! (assoc opts :input "-"))]
              (pipeline/print-summary result)))

          ;; No input at all
          :else
          (do (util/log "Error: --input is required")
              (println)
              (print-help)
              (System/exit 1)))))))

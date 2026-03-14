(ns optimize-lottie.util
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(defn format-bytes [n]
  (cond
    (>= n 1048576) (format "%.1fMB" (/ (double n) 1048576.0))
    (>= n 1024)    (format "%.1fKB" (/ (double n) 1024.0))
    :else           (str n "B")))

(defn pct [part whole]
  (if (pos? whole)
    (Math/round (* 100.0 (/ (double part) (double whole))))
    0))

(defn log [& args]
  (binding [*out* *err*]
    (apply println args)))

(defn which [cmd]
  (try
    (let [r (p/sh ["which" cmd])]
      (when (zero? (:exit r))
        (str/trim (:out r))))
    (catch Exception _ nil)))

(defn stdin? [input]
  (or (nil? input) (= "-" input)))

(defn stdout? [output]
  (= "-" output))

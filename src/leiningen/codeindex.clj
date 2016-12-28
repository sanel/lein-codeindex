(ns leiningen.codeindex
  "Index code in this project and all dependencies using etags or ctags."
  (:require [leiningen.core.classpath :as classpath]
            [leiningen.core.project :as p]
            [leiningen.core.main :as m]
            [leiningen.core.eval :refer [eval-in-project]]
            [clojure.java.shell :as shell])
  (:import java.io.File))

(def ^{:doc "Returns index folder. First checks if LEIN_CODEINDEX_DIR env
and fallback to .lein-codeindex hardcoded value."
       :private true}
  index-dir (memoize
             #(or (System/getenv "LEIN_CODEINDEX_DIR")
                  ".lein-codeindex")))

;; stolen clojure.tools.file-utils
(defn- recursive-delete
  "Deletes the given directory even if it contains files or subdirectories.
This function will attempt to delete all of the files and directories in the given
directory first, before deleting the directory. If the directory cannot be
deleted, this function aborts and returns nil. If the delete finishes successfully,
then this function returns true."
  [dir]
  (let [directory (if (string? dir) (File. dir) dir)]
    (if (.isDirectory directory)
      (when (reduce #(and %1 (recursive-delete %2)) true (.listFiles directory))
        (.delete directory))
      (.delete directory))))

(defn- all-jars
  "Get all jars this project depends on."
  [project]
  (for [path (classpath/resolve-managed-dependencies
              :dependencies
              :managed-dependencies
              project)]
    (.getAbsolutePath path)))

(defn- extract-jars
  "Extract all project jars inside '.lein-codeindex' folder."
  [project]
  (m/info "Scanning and extracting jars...")
  (let [fd (File. (index-dir))]
    (.mkdir fd)
    ;; mkdir() will fail too if folder is already present so we assume
    ;; if folder is not created, something bad happened
    (if-not (.exists fd)
      (m/warn "Unable to create" (index-dir) " folder. Indexing will NOT be done")
      (doseq [jar (all-jars project)]
        ;; assumed 'jar' command is present (part of jdk)
        (let [cmd (format "cd %s; jar xf %s" (index-dir) jar)
              ret (shell/sh "sh" "-c" cmd)]
          (when-not (= 0 (:exit ret))
            (m/warn "Failed to extract" jar "Got:\n" (:err ret))))))))

(defn- remove-index-dir
  "Remove 'index-dir' folder."
  []
  (when (recursive-delete (index-dir))
    (m/info "Index successfully removed")))

(defn- gen-tags-etags
  "Generate tags using etags. etags does not support vi-style indexes."
  []
  (m/info "Indexing using etags...")
  (shell/sh "/bin/sh" "-c"
   (str "find . "
        "-not -path '*/META-INF/*' "
        "-not -path '*/project.clj' "
        "-name '*.clj' -o "
        "-name '*.cljs' -o "
        "-name '*.cljc' -o "
        "-name '*.edn' |"
        "etags --regex='/[ \\t\\(]*def[a-z]* \\([a-z-!?]+\\)/\\1/' --regex='/[ \\t\\(]*ns \\([a-z0-9.\\-]+\\)/\\1/' -"
        )))

(defn- gen-tags-ctags
  "Generate tags using ctags. If vi-tags is set to true, generate vim compatible index.
When no-builtin-map is set to true, invoke ctags without specific Clojure definitions. This way
user can set custom mapping in $HOME/.ctags file."
  [vi-tags local-map]
  (m/info "Indexing using ctags...")
  (let [mp (str "--langdef=clojure"
                " --langmap=clojure:.clj"
                " --langmap=clojure:.cljs"
                " --langmap=clojure:.cljc"
                " --langmap=clojure:.edn"
                " --regex-clojure=/\\([ \\t]*create-ns[ \\t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/n,namespace/"
                " --regex-clojure=/\\([ \\t]*def[ \\t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/d,definition/"
                " --regex-clojure=/\\([ \\t]*defn-?[ \\t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/f,function/"
                " --regex-clojure=/\\([ \\t]*defmacro[ \\t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/m,macro/"
                " --regex-clojure=/\\([ \\t]*definline[ \\t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/i,inline/"
                " --regex-clojure=/\\([ \\t]*defmulti[ \\t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/a,multimethod definition/"
                " --regex-clojure=/\\([ \\t]*defmethod[ \\t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/b,multimethod instance/"
                " --regex-clojure=/\\([ \\t]*defonce[ \\t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/c,definition (once)/"
                " --regex-clojure=/\\([ \\t]*defstruct[ \\t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/s,struct/"
                " --regex-clojure=/\\([ \\t]*intern[ \\t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/v,intern/"
                " --regex-clojure=/\\([ \\t]*ns[ \\t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/n,namespace/")]
    (apply shell/sh
           (remove nil? ["ctags" "-R" (if-not vi-tags "-e") (if local-map mp)]))))

(defn- gen-tags
  "Generate tags, using engine depending on parameters."
  [args]
  (if (some #{":ctags"} args)
    (gen-tags-ctags (some #{":vi" ":vim"} args)
                    (some #{":builtin"} args))
    (gen-tags-etags)))

(defn codeindex
  "Index code in this project and all dependencies using etags or ctags.

This command will scan all .clj, .cljs, .cljc and .edn files and create tag or index
file, suitable for usage from Emacs, Vi/Vim and many other editors for easier symbol
navigation.

It accepts couple of parameters, like:

 :vi (or :vim) - generate Vi/Vim compatible tags. Works only with ctags
 :update       - do not scan and extract dependencies, but just update index
 :clean        - cleans index folder by _erasing_ it

Sample usage:

  lein codeindex         - generate index using etags
  lein codeindex :update - do not extract jars but only update index
  lein codeindex :vi     - generate Vi/Vim compatible tags
"
  [project & args]
  (condp = (first args)
    ":clean"  (remove-index-dir)
    ;; update command will just reindex code, without extracting
    ":update" (do (gen-tags args) (System/exit 0))
    (do
      (extract-jars project)
      (gen-tags args))))

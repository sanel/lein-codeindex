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

(defn- recursive-walk
  "Recursively walk given folder and execute action."
  [dir action]
  (let [fd (if (string? dir) (File. dir) dir)]
    (if (.isDirectory fd)
      (doseq [f (.listFiles fd)]
        (recursive-walk f action)
        (action f)))))

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
        (m/debug " Extracting" jar)
        ;; assumed 'jar' command is present (part of jdk)
        (let [cmd (format "cd %s; jar xf %s" (index-dir) jar)
              ret (shell/sh "sh" "-c" cmd)]
          (when-not (= 0 (:exit ret))
            (m/warn "Failed to extract" jar "Got:\n" (:err ret))))))))

(defn- remove-index-dir
  "Remove 'index-dir' folder."
  []
  (m/info "Removing index folder...")
  (recursive-walk (index-dir) #(.delete %)))

(defn- gen-tags-etags
  "Generate tags using etags. etags does not support vi-style indexes."
  []
  (m/info "Indexing using etags...")
  ;; Remove previous tags. 'etags -a' will append to existing index file, but
  ;; we want fresh content here. Another option is to cram all file paths to 'etags' command
  ;; but large number of files this could hit the limit of args on OS - ~65K on Linux, but
  ;; if this happens, number of files in args is the last thing to be concerned with.
  (-> "TAGS" File. .delete)
  (recursive-walk "." (fn [path]
                        (let [spath (str path)]
                          (when (and (not (.isDirectory path))
                                     (not (.endsWith spath "project.clj"))
                                     (re-find #"\.(clj[scx]?|edn)$" spath))
                            (m/debug "  Scanning" spath)
                            (let [ret (shell/sh "etags"
                                                "-a"
                                                "--regex=/[ \\t\\(]*def[a-z]* \\([a-z-!?]+\\)/\\1/"
                                                "--regex=/[ \\t\\(]*ns \\([a-z0-9.\\-]+\\)/\\1/"
                                                spath)]
                              (when-not (= "" (:err ret))
                                (m/warn (:err ret)) )))))))

(defn- gen-tags-ctags
  "Generate tags using ctags. If vi-tags is set to true, generate vim compatible index.
When no-builtin-map is set to true, invoke ctags without specific Clojure definitions. This way
user can set custom mapping in $HOME/.ctags file."
  [vi-tags local-map]
  (m/info "Indexing using ctags...")
  (let [mp ["--langdef=clojure"
            "--langmap=clojure:.clj"
            "--langmap=clojure:+.cljs"
            "--langmap=clojure:+.cljc"
            "--langmap=clojure:+.edn"
            "--regex-clojure=/\\([ \t]*create-ns[ \t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/n,namespace/"
            "--regex-clojure=/\\([ \t]*def[ \t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/d,definition/"
            "--regex-clojure=/\\([ \t]*defn[ \t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/f,function/"
            "--regex-clojure=/\\([ \t]*defn-[ \t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/p,private function/"
            "--regex-clojure=/\\([ \t]*defmacro[ \t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/m,macro/"
            "--regex-clojure=/\\([ \t]*definline[ \t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/i,inline/"
            "--regex-clojure=/\\([ \t]*defmulti[ \t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/a,multimethod definition/"
            "--regex-clojure=/\\([ \t]*defmethod[ \t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/b,multimethod instance/"
            "--regex-clojure=/\\([ \t]*defonce[ \t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/c,definition (once)/"
            "--regex-clojure=/\\([ \t]*defstruct[ \t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/s,struct/"
            "--regex-clojure=/\\([ \t]*intern[ \t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/v,intern/"
            "--regex-clojure=/\\([ \t]*ns[ \t]+([-[:alnum:]*+!_:\\/.?]+)/\\1/n,namespace/"]]
    ;; build args this way as 'shell/sh' is sensitive to nil arguments
    (->> (concat ["ctags" "-R" (if-not vi-tags "-e")]
                 (if local-map mp))
         (remove nil?)
         (apply shell/sh))))

(defn- gen-tags
  "Generate tags, using engine depending on parameters."
  [args]
  (if (some #{"--ctags" "--vi" "--vim"} args)
    (gen-tags-ctags (some #{"--vi" "--vim"} args)
                    (not (some #{"--no-langmap"} args)))
    (gen-tags-etags)))

(defn codeindex
  "Index code in this project and all dependencies using etags or ctags.

This command will scan all .clj, .cljs, .cljc and .edn files and create tag or index
file, suitable for usage from Emacs, Vi/Vim and many other editors for easier symbol
navigation.

It accepts couple of parameters, like:

 --vi (or --vim) - generate Vi/Vim compatible tags. Works only with ctags
 --update        - do not scan and extract dependencies, but just update index
 --clean         - cleans index folder by erasing content in it
 --no-langmap    - do not use builtin Clojure language map; useful if you provide own
                   language map in $HOME/.ctags file; works only with ctags

Sample usage:

  lein codeindex                      - generate index using etags
  lein codeindex --update             - do not extract jars but only update index
  lein codeindex --vi                 - generate Vi/Vim compatible tags
  lein codeindex --ctags --no-langmap - use ctags but also user custom Clojure language mappings
"
  [project & args]
  (condp = (first args)
    "--clean"  (remove-index-dir)
    ;; update command will just reindex code, without extracting
    "--update" (do (gen-tags args) (System/exit 0))
    (do
      (extract-jars project)
      (gen-tags args))))

# lein-codeindex

Index your Clojure and Java project code with all dependencies using
[etags](https://www.emacswiki.org/emacs/EmacsTags),
[ctags](http://ctags.sourceforge.net/) or [gtags](https://www.gnu.org/software/global/).

In short, this [Leiningen](http://leiningen.org) plugin allows you to
easily index the code and find any function, variable or namespace
definition used through the code from editors like Emacs, Vim, Sublime
Text and [many more](https://en.wikipedia.org/wiki/Ctags).

## Prerequisites

Make sure you have installed `etags` (comes with Emacs), `ctags`
(distributed usually with Vim) or `gtags` (comes with `GNU Global`).

## Usage

To enable `lein-codeindex` for your project, put

![latest-version](https://clojars.org/lein-codeindex/latest-version.svg)

into the `:plugins` vector of your project.clj. If you'd like to
enable it globally for every project, put it in `$HOME/.lein/profiles.clj`.

To run it, use:

    $ lein codeindex

This will generate Emacs compatible tags using `etags`.

If you'd like to use `ctags` and generate Vi/Vim compatible tags, use:

    $ lein codeindex --vim

or

    $ lein codeindex --ctags --vim

To generate Emacs tags using `ctags`, use:

    $ lein codeindex --ctags

To see other options, run:

    $ lein help codeindex

## License

Copyright © 2018 Sanel Zukan

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.

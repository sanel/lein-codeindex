define do-version-bump
	lein change version leiningen.release/bump-version $(1)
endef

define do-deploy
	lein deploy clojars
endef

.PHONY: clean install deploy deploy-release

all: install

clean:
	lein codeindex :clean
	lein clean
	$(RM) pom.xml
	$(RM) TAGS GPATH GRTAGS GTAGS tags

install:
	lein install

deploy:
	$(call do-deploy)

# make version-bump LEVEL=(major|minor|patch|release)
version-bump:
	$(call do-version-bump,$(LEVEL))

deploy-release:
	$(call do-version-bump,release)
	$(eval VERSION ?= $(shell awk '/defproject/ {print $$3}' project.clj | sed -e 's/"//g' -e 's/-SNAPSHOT//g'))
	@git commit project.clj -m "Release bump to $(VERSION)"
	@echo "Creating git tag: $(VERSION)"
	@git tag $(VERSION)
	@echo "Deploying: $(VERSION)"
	$(call do-deploy)
	@echo "Setting new application version..."
	$(call do-version-bump,minor)

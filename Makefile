BUILDLIB = build/libs
JARNAME = fitsfileprocessor.jar
BUILDJAR = $(BUILDLIB)/$(JARNAME)
GSRC = src/main/groovy/edu/arizona/astrolabe/ffp/*.groovy

.PHONY: help build clean cleanout exec reset run run-bash rund runq watch

help:
	@echo "Make what? Try: build, clean, cleanout, docker, exec, jar, macjar, reset, run, run-bash rund, runq, testjar, watch"

build:
	gradle clean build

clean:
	gradle clean

cleanout:
	rm -f out/ffp*

docker: $(BUILDJAR)
	docker build -t ffp .

exec:
	docker exec -it ffp bash

reset:
	docker rm -f ffp

run:
	docker run -it --rm --name ffp -v $(PWD)/images:/images -v $(PWD)/out:/out ffp

run-bash:
	docker run -it --rm --name ffp -v $(PWD)/images:/images -v $(PWD)/out:/out --entrypoint /bin/bash ffp

rund:
	docker run -it --rm --name ffp -v $(PWD)/images:/images -v $(PWD)/out:/out ffp -v -d -o /out /images

runq:
	@docker run -d --rm --name ffp -v $(PWD)/images:/images -v $(PWD)/out:/out ffp

testjar: $(BUILDJAR)
	mkdir -p out
	java -jar $(BUILDJAR) -v -o out images

watch:
	docker logs -f ffp

macjar: $(GSRC)
	gradle clean build -Pos=darwin

jar: $(BUILDJAR)

$(BUILDJAR): $(GSRC)
	gradle clean build

%:
	@:

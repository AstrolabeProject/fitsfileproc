BUILDLIB = build/libs
JARNAME = fitsfileprocessor.jar
BUILDJAR = $(BUILDLIB)/$(JARNAME)
GSRC = src/main/groovy/edu/arizona/astrolabe/ffp/*.groovy

.PHONY: help build clean cleanout exec reset run rund runq watch

help:
	@echo "Make what? Try: build, clean, cleanout, docker, exec, reset, run, rund, runq, testjar, jar, watch"

build:
	gradle clean build

clean:
	gradle clean

cleanout:
	rm -f out/ffp*

docker: $(BUILDJAR)
	docker build -t ffp .

exec:
	docker run -it --rm --name ffp -v $(PWD)/data:/data -v $(PWD)/out:/out --entrypoint /bin/bash ffp

reset:
	docker stop ffp
	docker rm ffp

run:
	docker run -it --rm --name ffp -v $(PWD)/data:/data -v $(PWD)/out:/out ffp

rund:
	docker run -it --rm --name ffp -v $(PWD)/data:/data -v $(PWD)/out:/out ffp -v -d -o /out /data

runq:
	@docker run -d --rm --name ffp -v $(PWD)/data:/data -v $(PWD)/out:/out ffp

testjar: $(BUILDJAR)
	mkdir -p out
	java -jar $(BUILDJAR) -v -o out data

watch:
	docker logs -f ffp

jar: $(BUILDJAR)

$(BUILDJAR): $(GSRC)
	gradle clean build

%:
	@:

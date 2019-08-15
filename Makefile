BUILDLIB = build/libs
JARNAME = fitsfileprocessor.jar
BUILDJAR = $(BUILDLIB)/$(JARNAME)

.PHONY: help clean build gen jar exec watch reset

help:
	@echo "Make what? Try: clean, cleanout, build, exec, reset, run, rund, runq, testjar, jar, watch"

clean:
	gradle clean

cleanout:
	rm -f out/ffp*

build: $(BUILDJAR)
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

$(BUILDJAR):
	gradle clean build

%:
	@:

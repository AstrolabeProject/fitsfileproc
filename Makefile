IMG=ffp
BUILDJAR = build/libs/fitsfileprocessor.jar
GSRC = src/main/groovy/edu/arizona/astrolabe/ffp/*.groovy
NAME=ffp

.PHONY: help build clean cleanout exec reset run run-bash rund runq run-sc watch

help:
	@echo "Make what? Try: build, clean, cleanout, docker, exec, jar, macjar, reset, run, run-bash rund, runq, run-sc, testjar, watch"

build:
	gradle clean build

clean:
	gradle clean

cleanout:
	rm -f out/ffp*

docker: ${BUILDJAR}
	docker build -t ${IMG} .

exec:
	docker exec -it ${NAME} bash

macjar: ${GSRC}
	gradle clean build -Pos=darwin

reset:
	docker rm -f ${NAME}

run:
	docker run -it --rm --name ${NAME} -v ${PWD}/images:/images -v ${PWD}/out:/out ${IMG}

run-bash:
	docker run -it --rm --name ${NAME} -v ${PWD}/images:/images -v ${PWD}/out:/out --entrypoint /bin/bash ${IMG}

rund:
	docker run -it --rm --name ${NAME} -v ${PWD}/images:/images -v ${PWD}/out:/out ${IMG} -v -d -o /out /images

runq:
	@docker run -d --rm --name ${NAME} -v ${PWD}/images:/images -v ${PWD}/out:/out ${IMG}

run-sc:
	docker run -it --rm --name ${NAME} -v ${PWD}/images:/images -v ${PWD}/out:/out ${IMG} -v -sc -o /out /images

testjar: macjar
	mkdir -p out
	java -jar ${BUILDJAR} -v -o out images

watch:
	docker logs -f ${NAME}

jar: ${BUILDJAR}

${BUILDJAR}: ${GSRC}
	gradle clean build

%:
	@:

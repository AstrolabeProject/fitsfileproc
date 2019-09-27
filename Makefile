IMG=ffp
BUILDJAR = build/libs/fitsfileprocessor.jar
GSRC = src/main/groovy/edu/arizona/astrolabe/ffp/*.groovy
NAME=ffp
NET=vos_net

.PHONY: help build clean cleanout exec reset run-bash run runq run-sc rundb rundbq rundb-sc watch

help:
	@echo "Make what? Try: build, clean, cleanout, docker, exec, jar, macjar, reset, run-bash"
	@echo "                run, runq, run-sc, rundb, rundbq, rundb-sc, testjar, testjardb, watch"

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

run-bash:
	docker run -it --rm --name ${NAME} -v ${PWD}/images:/images -v ${PWD}/out:/out --entrypoint /bin/bash ${IMG}

run:
	docker run -it --rm --name ${NAME} -v ${PWD}/images:/images -v ${PWD}/out:/out ${IMG}

runq:
	@docker run -d --rm --name ${NAME} -v ${PWD}/images:/images -v ${PWD}/out:/out ${IMG}

run-sc:
	docker run -it --rm --name ${NAME} -v ${PWD}/images:/images -v ${PWD}/out:/out ${IMG} --verbose -sc -o /out /images

rundb:
	docker run -it --rm --name ${NAME} -v ${PWD}/images:/images ${IMG} --verbose -of db /images

rundbq:
	@docker run -d --rm --name ${NAME} -v ${PWD}/images:/images ${IMG} -of db /images

rundb-sc:
	docker run -it --rm --name ${NAME} -v ${PWD}/images:/images ${IMG} --verbose -sc -of db /images

testjar: macjar
	mkdir -p out
	java -jar ${BUILDJAR} --verbose -o out images

testjardb: macjar
	java -jar ${BUILDJAR} --verbose -of db -db ${PWD}/src/test/resources/db.properties images

watch:
	docker logs -f ${NAME}

jar: ${BUILDJAR}

${BUILDJAR}: ${GSRC}
	gradle clean build

%:
	@:

IMG=ffp
BUILDJAR = build/libs/fitsfileprocessor.jar
GSRC = src/main/groovy/edu/arizona/astrolabe/ffp/*.groovy
NAME=ffp
NET=vos_net

.PHONY: help build clean cleanout exec reset run-bash run-mnt run-mnt-sc run-db run-db-q run-db-sc watch

help:
	@echo "Make what? build, clean, cleanout, docker, exec, jar, macjar, reset,"
	@echo "           run-bash, run-mnt, run-mnt-sc, run-db, run-db-q, run-db-sc,"
	@echo "           testjar-mnt, testjar-db, watch"

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
	docker run -it --rm --network ${NET} --name ${NAME} -v ${PWD}/images:/images -v ${PWD}/out:/out --entrypoint /bin/bash ${IMG}

run-mnt:
	docker run -it --rm --name ${NAME} -v ${PWD}/images:/images -v ${PWD}/out:/out ${IMG} --verbose

run-mnt-sc:
	docker run -it --rm --name ${NAME} -v ${PWD}/images:/images -v ${PWD}/out:/out ${IMG} --verbose -sc -o /out /images

run-db:
	docker run -it --rm --network ${NET} --name ${NAME} -v ${PWD}/images:/images ${IMG} --verbose -of db /images

run-db-q:
	@docker run -d --rm --network ${NET} --name ${NAME} -v ${PWD}/images:/images ${IMG} -of db /images

run-db-sc:
	docker run -it --rm --network ${NET} --name ${NAME} -v ${PWD}/images:/images ${IMG} --verbose -sc -of db /images

testjar-mnt: macjar
	mkdir -p out
	java -jar ${BUILDJAR} --verbose -o out images

testjar-db: macjar
	java -jar ${BUILDJAR} --verbose -of db -db ${PWD}/src/test/resources/db.properties images

watch:
	docker logs -f ${NAME}

jar: ${BUILDJAR}

${BUILDJAR}: ${GSRC}
	gradle clean build

%:
	@:

IMG=ffp:devel
BUILDJAR = build/libs/fitsfileprocessor.jar
COLLECTION=JWST
GSRC = src/main/groovy/edu/arizona/astrolabe/ffp/*.groovy
IMGS=${PWD}/images
NAME=ffp
NET=vos_net
OUTDIR=${PWD}/out

.PHONY: help build clean cleanout exec reset run-bash run-db run-db-q run-mnt run-mnt-sc watch

help:
	@echo 'Make what? build, clean, cleanout, docker, exec, jar, macjar, reset,'
	@echo '           run-bash, run-db, run-db-q, run-mnt, run-mnt-sc,'
	@echo '           testjar-mnt, testjar-db, watch'
	@echo '  where:'
	@echo '     help       - show this help message'
	@echo '     build      - build project: clean all, recompile, make JAR file'
	@echo '     clean      - remove build files and build output products'
	@echo '     cleanout   - remove all result SQL files from the output directory'
	@echo '     docker     - build a docker image'
	@echo '     exec       - exec into running development server (CLI arg: NAME=containerID)'
	@echo '     jar        - build a Linux (production) version of the JAR file'
	@echo '     macjar     - build an OS X (testing) version of the JAR file'
	@echo '     reset      - stop the running FFP container and force its removal'
	@echo '     run-bash   - run Bash in the FFP container on the VOS network (interactive)'
	@echo '     run-db     - run FFP container on the VOS network (verbose)'
	@echo '     run-db-q   - run FFP container on the VOS network quietly (no output)'
	@echo '     run-mnt    - run FFP container to output SQL file (verbose)'
	@echo '     run-mnt-sc  - run FFP container to output SQL file, skip catalogs (verbose)'
	@echo '     testjar-db  - Build a Mac FFP JAR and run it against the VosDB (verbose)'
	@echo '     testjar-mnt - Build a Mac FFP JAR and run it to output SQL file (verbose)'
	@echo '     watch      - show logs for a running FFP container'

build:
	gradle clean build

clean:
	gradle clean

cleanout:
	rm -f ${OUTDIR}/ffp*

docker: ${BUILDJAR}
	docker build -t ${IMG} .

exec:
	docker exec -it ${NAME} bash

macjar: ${GSRC}
	gradle clean build -Pos=darwin

reset:
	docker rm -f ${NAME}

run-bash:
	docker run -it --rm --network ${NET} --name ${NAME} -v ${IMGS}:/images -v ${OUTDIR}:/out --entrypoint /bin/bash ${IMG}

run-db:
	docker run -it --rm --network ${NET} --name ${NAME} -v ${IMGS}:/images ${IMG} --verbose -c ${COLLECTION} /images

run-db-q:
	@docker run -d --rm --network ${NET} --name ${NAME} -v ${IMGS}:/images ${IMG} -c ${COLLECTION} /images

run-mnt:
	docker run -it --rm --name ${NAME} -v ${IMGS}:/images -v ${OUTDIR}:/out ${IMG} --verbose -c ${COLLECTION} -of sql -o /out /images

run-mnt-sc:
	docker run -it --rm --name ${NAME} -v ${IMGS}:/images -v ${OUTDIR}:/out ${IMG} --verbose --skip-catalogs -c ${COLLECTION} -of sql -o /out /images

testjar-db: macjar
	java -jar ${BUILDJAR} --verbose -db ${PWD}/src/test/resources/db.properties images

testjar-mnt: macjar
	mkdir -p ${OUTDIR}
	java -jar ${BUILDJAR} --verbose -of sql -o ${OUTDIR} images

watch:
	docker logs -f ${NAME}

jar: ${BUILDJAR}

${BUILDJAR}: ${GSRC}
	gradle clean build

%:
	@:

FROM azul/zulu-openjdk:8

MAINTAINER Tom Hicks <hickst@email.arizona.edu>

RUN apt-get update \
    && apt-get install -y --no-install-recommends libwcs5 \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /images /out
COPY ./build/libs/fitsfileprocessor.jar /

ENV _JAVA_OPTIONS -Xms1024m -Xmx8192m

ENTRYPOINT [ "java", "-jar", "fitsfileprocessor.jar" ]
CMD [ "-v", "-o", "/out", "/images" ]

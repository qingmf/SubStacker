FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21 AS build

WORKDIR /project

COPY pom.xml ./
COPY src ./src

RUN mvn -B package -DskipTests -Dnative

FROM registry.access.redhat.com/ubi9/ubi-micro:9.5 AS runtime

WORKDIR /work

COPY --from=build /project/target/*-runner /work/substacker
COPY data /work/data

RUN chmod 755 /work/substacker

ENV QUARKUS_HTTP_HOST=0.0.0.0
ENV SUBSTACKER_DATA_DIR=/work/data

EXPOSE 8080

ENTRYPOINT ["/work/substacker"]

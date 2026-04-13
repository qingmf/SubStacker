FROM eclipse-temurin:21-jre-jammy AS runtime

WORKDIR /work

COPY substacker-0.1.0-SNAPSHOT.jar /work/substacker.jar
COPY data /work/data

ENV QUARKUS_HTTP_HOST=0.0.0.0
ENV SUBSTACKER_DATA_DIR=/work/data

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/work/substacker.jar"]

# Stage 1: Build the native executable
FROM quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21 AS build
USER quarkus
WORKDIR /code
COPY pom.xml .
COPY src ./src
RUN mvn package -Dnative -DskipTests

# Stage 2: Create the runtime image
FROM quay.io/quarkus/quarkus-micro-image:2.0
WORKDIR /work/
COPY --from=build /code/target/*-runner /work/application
RUN chmod 775 /work/application
EXPOSE 8080
USER 1001
ENTRYPOINT ["./application", "-Dquarkus.http.host=0.0.0.0"]

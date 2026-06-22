FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./pom.xml
COPY backend/pom.xml ./backend/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q -pl backend -am -DskipTests dependency:go-offline
COPY backend/src ./backend/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -pl backend -am -DskipTests package

FROM maven:3.9.9-eclipse-temurin-21
WORKDIR /app
COPY --from=build /workspace/backend/target/backend-0.0.1-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

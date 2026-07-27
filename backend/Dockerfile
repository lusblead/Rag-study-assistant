FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./pom.xml
COPY backend/pom.xml ./backend/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q -pl backend -am -DskipTests dependency:go-offline
COPY backend/src ./backend/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -pl backend -am -DskipTests package

FROM maven:3.9.9-eclipse-temurin-21
WORKDIR /app
ARG INSTALL_TESSERACT=false
ARG APT_MIRROR=http://mirrors.aliyun.com/ubuntu
RUN if [ "$INSTALL_TESSERACT" = "true" ]; then \
        if [ -n "$APT_MIRROR" ]; then \
            for file in /etc/apt/sources.list /etc/apt/sources.list.d/*.sources; do \
                [ -f "$file" ] && sed -i \
                    -e "s|http://archive.ubuntu.com/ubuntu|$APT_MIRROR|g" \
                    -e "s|http://security.ubuntu.com/ubuntu|$APT_MIRROR|g" \
                    "$file"; \
            done; \
        fi \
        && apt-get -o Acquire::Retries=5 -o Acquire::http::Timeout=30 update \
        && apt-get -o Acquire::Retries=5 -o Acquire::http::Timeout=30 install -y --no-install-recommends \
            tesseract-ocr tesseract-ocr-eng tesseract-ocr-chi-sim \
        && rm -rf /var/lib/apt/lists/*; \
    else \
        echo "Skipping Tesseract install. Set INSTALL_TESSERACT=true to build OCR into the image."; \
    fi
COPY --from=build /workspace/backend/target/backend-0.0.1-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

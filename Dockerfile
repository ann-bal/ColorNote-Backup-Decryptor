FROM eclipse-temurin:8-jdk-alpine

ARG BC_VERSION=1.54
ARG JACKSON_VERSION=2.17.2

RUN apk add --no-cache curl \
    && mkdir -p /app/lib \
    && curl -fsSL -o /app/lib/bcprov.jar \
      "https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk15on/${BC_VERSION}/bcprov-jdk15on-${BC_VERSION}.jar" \
    && curl -fsSL -o /app/lib/jackson-annotations.jar \
      "https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-annotations/${JACKSON_VERSION}/jackson-annotations-${JACKSON_VERSION}.jar" \
    && curl -fsSL -o /app/lib/jackson-core.jar \
      "https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/${JACKSON_VERSION}/jackson-core-${JACKSON_VERSION}.jar" \
    && curl -fsSL -o /app/lib/jackson-databind.jar \
      "https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-databind/${JACKSON_VERSION}/jackson-databind-${JACKSON_VERSION}.jar"

WORKDIR /app
COPY Decryptor.java /app/Decryptor.java
RUN javac -cp '/app/lib/*' /app/Decryptor.java

ENTRYPOINT ["java", "-cp", "/app:/app/lib/*", "Decryptor"]

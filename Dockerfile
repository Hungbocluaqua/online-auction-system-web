FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN mkdir -p /app/data /app/uploads && addgroup -S appuser && adduser -S appuser -G appuser
COPY --from=build /app/target/online-auction-system-web-1.0-SNAPSHOT.jar app.jar
EXPOSE 8080 8889
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/health/live || exit 1
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]

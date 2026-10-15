FROM eclipse-temurin:17-jre
WORKDIR /app
COPY api/target/rag-api-1.0.0.jar app.jar
EXPOSE 8089
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/*.jar app.jar
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]

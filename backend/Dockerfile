FROM amazoncorretto:25.0.5-al2023-headless

ARG MODULE

COPY ${MODULE}/build/libs/${MODULE}.jar /app/service.jar

USER 65532:65532

ENTRYPOINT ["java", "-jar", "/app/service.jar"]

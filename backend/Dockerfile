FROM amazoncorretto:25.0.5-al2023-headless

ARG MODULE

RUN install -d -o 65532 -g 65532 -m 0700 /run/idp
COPY ${MODULE}/build/libs/${MODULE}.jar /app/service.jar

USER 65532:65532

# ECS runs the task with a read-only root, and fills a volume at this path from the image. Tomcat keeps its files there.
VOLUME /run/idp

ENTRYPOINT ["java", "-Djava.io.tmpdir=/run/idp", "-jar", "/app/service.jar"]

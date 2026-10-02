# Java 25 runs the console and current Minecraft versions (26.x needs Java 25).
# Older servers can point to another Java per server ("Java" in the settings).
FROM eclipse-temurin:25-jre

WORKDIR /app

COPY target/*.jar app.jar

ENV SIMPLYCRAFT_SERVERS_PATH=/data/servers \
    SIMPLYCRAFT_BACKUPS_PATH=/data/backups

VOLUME /data
EXPOSE 8080 25565

# exec form: Docker's SIGTERM reaches Spring, which stops all Minecraft servers cleanly
ENTRYPOINT ["java", "-jar", "app.jar"]

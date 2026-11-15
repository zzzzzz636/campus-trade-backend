FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system campus && useradd --system --gid campus campus \
    && mkdir -p /data/uploads && chown -R campus:campus /data/uploads
COPY --chown=campus:campus target/campus-trade-backend-0.0.1-SNAPSHOT.jar /app/app.jar
USER campus
ENV CAMPUS_UPLOAD_DIR=/data/uploads
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.profiles.active=mysql"]

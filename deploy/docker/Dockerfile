FROM eclipse-temurin:25-jdk as builder
WORKDIR /home/app
COPY . .
RUN chmod +x ./gradlew && ./gradlew --no-daemon clean bootJar -x test

FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 oswl \
    && useradd --system --uid 10001 --gid oswl --home-dir /home/app --shell /usr/sbin/nologin oswl \
    && mkdir -p /app /home/app /var/log/oswl \
    && chown -R oswl:oswl /app /home/app /var/log/oswl
WORKDIR /home/app
COPY --from=builder --chown=oswl:oswl /home/app/build/libs/*.jar /app/app.jar
EXPOSE 8080
ENV JAVA_OPTS="-Xms256m -Xmx512m"
USER 10001:10001
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]

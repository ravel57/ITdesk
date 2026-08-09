FROM node:22-alpine AS nodejs
RUN apk add --no-cache \
    git \
    python3 \
    make \
    g++ \
    vips-dev \
    libc6-compat
WORKDIR /usr/src/node
RUN git clone https://github.com/ravel57/itdesk-front
WORKDIR /usr/src/node/itdesk-front
RUN yarn install --network-timeout 300000
RUN yarn build

FROM gradle:8.14.3-jdk21-alpine AS gradle
COPY --chown=gradle:gradle . /home/gradle/
COPY --from=nodejs /usr/src/node/itdesk-front/dist/spa/. /home/gradle/src/main/resources/static/
WORKDIR /home/gradle/
RUN gradle bootJar

# Extract the already-built Spring Boot jar once. The runner needs only the
# application classes/libraries; it does not receive source code or Gradle.
FROM alpine/java:22-jdk AS artifact
WORKDIR /work
COPY --from=gradle /home/gradle/build/libs/*.jar /work/ItDesk.jar
RUN mkdir -p /work/app && cd /work/app && jar -xf /work/ItDesk.jar

# Dedicated runner for untrusted plugins. Container-level restrictions
# (network_mode:none, cap_drop:ALL, seccomp/AppArmor, read_only) are configured
# in docker-compose; every invocation is additionally isolated by bubblewrap.
FROM alpine/java:22-jdk AS plugin-runner
RUN apk add --no-cache bubblewrap \
    && addgroup -S -g 10000 pluginipc \
    && adduser -S -D -H -u 10002 -G pluginipc -h /home/runner runner \
    && mkdir -p /home/runner /home/java/app /home/java/plugins /run/uldesk-ipc \
    && chown -R 10002:10000 /home/runner /home/java /run/uldesk-ipc
COPY --from=artifact --chown=10002:10000 /work/app /home/java/app
USER 10002:10000
ENV ULDESK_PLUGIN_RUNNER_SOCKET=/run/uldesk-ipc/plugin-runner.sock \
    ULDESK_PLUGIN_BROKER_SOCKET=/run/uldesk-ipc/plugin-broker.sock \
    ULDESK_PLUGINS_DIR=/home/java/plugins \
    ULDESK_APP_DIR=/home/java/app \
    ULDESK_BWRAP=/usr/bin/bwrap
CMD ["java", "-cp", "/home/java/app/BOOT-INF/classes:/home/java/app/BOOT-INF/lib/*", "ru.ravel.ItDesk.plugins.PluginRunnerMain"]

# Keep webapp as the final stage so a legacy `docker build .` still builds the
# application, not the runner. The webapp itself does not contain bubblewrap.
FROM alpine/java:22-jdk AS webapp
RUN addgroup -S -g 10000 pluginipc \
    && adduser -S -D -H -u 10001 -G pluginipc -h /home/java itdesk \
    && mkdir -p /home/java/plugins /home/java/logs /run/uldesk-ipc \
    && chown -R 10001:10000 /home/java /run/uldesk-ipc
WORKDIR /home/java/
COPY --from=artifact --chown=10001:10000 /work/ItDesk.jar /home/java/ItDesk.jar
USER 10001:10000
CMD ["java", "-jar", "ItDesk.jar"]

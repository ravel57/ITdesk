FROM node:22-alpine AS nodejs
RUN apk add --no-cache \
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
COPY --from=nodejs /usr/src/node/itdesk-front/dist/spa/.   /home/gradle/src/main/resources/static/
WORKDIR /home/gradle/
RUN gradle bootJar

FROM alpine/java:22-jdk AS java
WORKDIR /home/java/
COPY --from=gradle /home/gradle/build/libs/*.jar /home/java/ItDesk.jar
RUN mkdir -p /home/java/plugins
CMD ["java", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5705", "-jar", "ItDesk.jar"]
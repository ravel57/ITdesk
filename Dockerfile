FROM node:20-alpine3.19 AS nodejs
RUN apk add --no-cache git
WORKDIR /usr/src/node
RUN git clone https://github.com/ravel57/itdesk-front
WORKDIR /usr/src/node/itdesk-front
RUN yarn install
RUN yarn build

FROM gradle:8.7.0-jdk21-alpine AS gradle
COPY --chown=gradle:gradle . /home/gradle/
COPY --from=nodejs /usr/src/node/itdesk-front/dist/spa/   /home/gradle/src/main/webapp/
WORKDIR /home/gradle/src/main/webapp/
RUN mv js/app.*.js        js/app.js
RUN mv js/vendor.*.js     js/vendor.js
RUN mv css/app.*.css      css/app.css
RUN mv css/vendor.*.css   css/vendor.css
WORKDIR /home/gradle/
RUN gradle bootJar

FROM alpine/java:21-jdk AS java
WORKDIR /home/java/
COPY --from=gradle /home/gradle/build/libs/*.jar /home/java/ItDesk.jar
CMD ["java", "-jar", "ItDesk.jar"]
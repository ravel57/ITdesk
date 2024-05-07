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
RUN gradle war

FROM tomcat:10.1.23-jre21 AS tomcat9
RUN rm -rf /usr/local/tomcat/webapps/* /usr/local/tomcat/conf/server.xml
COPY ./src/main/resources/toTomcat/* /usr/local/tomcat/conf/
COPY --from=gradle /home/gradle/build/libs/*.war /usr/local/tomcat/webapps/ROOT.war
RUN chmod +x /usr/local/tomcat/bin/catalina.sh
CMD ["catalina.sh", "run"]
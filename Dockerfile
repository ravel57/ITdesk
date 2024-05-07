FROM tomcat:10.1.23-jre21 AS tomcat9
RUN rm -rf /usr/local/tomcat/webapps/* /usr/local/tomcat/conf/server.xml
COPY ./src/main/resources/toTomcat/* /usr/local/tomcat/conf/
COPY ./build/libs/*.war /usr/local/tomcat/webapps/ROOT.war
RUN chmod +x /usr/local/tomcat/bin/catalina.sh
CMD ["catalina.sh", "run"]
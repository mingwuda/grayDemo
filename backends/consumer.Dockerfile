FROM docker.xuanyuan.run/library/openjdk:26-ea-8-jdk

WORKDIR /app
COPY ./consumer-app.jar /app/app.jar

EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
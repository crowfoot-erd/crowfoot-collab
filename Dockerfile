# crowfoot-collab — 협업 WebSocket 서버 실행 이미지 (CI에서 mvn package 후 빌드)
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]

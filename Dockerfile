# 빌드 스테이지
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY gradlew gradle/ build.gradle settings.gradle ./
COPY src ./src
RUN ./gradlew clean bootJar -x test

# 런타임 스테이지(슬림 JRE)
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
ENV PORT=8080
EXPOSE 8080
COPY --from=build /app/build/libs/*SNAPSHOT*.jar /app/app.jar
ENTRYPOINT ["java","-jar","/app/app.jar"]
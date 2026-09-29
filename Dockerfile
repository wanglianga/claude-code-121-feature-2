# 多阶段构建：Maven 编译 + JRE 运行
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/cold-chain-delivery-1.0.0.jar app.jar
EXPOSE 8080
ENV REDIS_HOST=redis REDIS_PORT=6379 REDIS_DB=0 SERVER_PORT=8080
ENTRYPOINT ["java", "-jar", "app.jar"]

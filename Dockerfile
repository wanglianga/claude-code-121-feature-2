# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
# 先预热依赖缓存（利用 Docker 层缓存）
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/cold-chain-delivery-1.0.0.jar app.jar
EXPOSE 8080
# Redis 地址默认指向 compose 中的 redis 服务，可用环境变量覆盖
ENV REDIS_HOST=redis \
    REDIS_PORT=6379 \
    REDIS_DB=0 \
    SERVER_PORT=8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

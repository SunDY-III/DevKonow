# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# 构建工具
RUN apk add --no-cache protobuf-dev

# 利用 Docker 缓存层：先复制构建配置
COPY pom.xml ./
RUN mvn dependency:go-offline -B 2>/dev/null || true

# 复制源码并构建（含 Spring Boot 分层 JAR）
COPY src/ ./src/
RUN mvn package -DskipTests -B

# Stage 2: 分层提取（优化远程仓库拉取缓存）
FROM builder AS layer-extractor
WORKDIR /app
RUN java -Djarmode=layertools -jar target/*.jar extract --destination /app/extract

# Stage 3: Runtime
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app

# 分层复制：依赖层（不常变）与应用层（常变）分离，利用 Docker 缓存层
COPY --from=layer-extractor /app/extract/dependencies/ ./
COPY --from=layer-extractor /app/extract/spring-boot-loader/ ./
COPY --from=layer-extractor /app/extract/snapshot-dependencies/ ./
COPY --from=layer-extractor /app/extract/application/ ./

EXPOSE 8080

# JVM 容器优化
# GC 选择（通过 JAVA_GC 环境变量切换）：
#   G1GC（默认）：稳定、内存效率高，适合 DevKnow 的 1.5~4GB 堆
#   ZGC（实验）：亚毫秒级暂停，适合 SSE 流式场景，需实测验证
#   切换方式：docker compose run -e JAVA_GC=ZGC backend
ENV JAVA_GC="G1GC"
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
ENV _JAVA_GC_OPTIONS=""

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "case \"$JAVA_GC\" in ZGC) GC_OPTS=\"-XX:+UseZGC -XX:MaxHeapSize=${JAVA_MAX_HEAP:-2g}\" ;; *) GC_OPTS=\"-XX:+UseG1GC\" ;; esac; exec java $GC_OPTS $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]

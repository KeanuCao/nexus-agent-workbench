# =============================================================================
# nexus-backend 多阶段构建（task.1 / 0.1 交付物）
# 构建上下文：../backend（0.4 落码后的 Maven 多模块工程；本 Dockerfile 位于 docker-compose/backend/）
#   - stage 1：共用构建阶段 nexus-builder（mvn 3.9/JDK17 工具链，见 builder/Dockerfile）
#   - stage 2：eclipse-temurin:17-jre-alpine 瘦身运行镜像，非 root 用户运行
# =============================================================================

# ── stage 1：Maven 打包（本地仓库走 BuildKit cache mount，依赖缓存跨构建复用）──
FROM nexus-builder:dev AS build
WORKDIR /build
COPY . .
# 0.4 约定：多模块工程仅 nexus-start 产出可执行 boot jar（target 下唯一 *.jar；
# *.jar.original 不命中通配；若 0.4 启用 sources/javadoc 插件请在 nexus-start 关闭或改此处通配）
#
# ⚠️ cache target 必须是 /root/.m2/repository，**不能**写成 /root/.m2：
#    cache mount 是覆盖式挂载，挂到 /root/.m2 会把镜像层里的 settings.xml（builder 注入的
#    阿里云源配置）整个遮蔽 —— Maven 找不到它便静默回落到境外中央仓库，配了源等于没配。
#    只缓存 repository 子目录即可，settings.xml 保持可见。
#    生效判据：构建日志出现 `Downloading from aliyun-central:` 而非 `Downloading from central:`。
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -DskipTests package

# ── stage 2：JRE 运行镜像（不含构建工具链，体积与攻击面双瘦身）──
FROM docker.m.daocloud.io/library/eclipse-temurin:17-jre-alpine
# 非 root 用户运行（alpine adduser 语法）
RUN addgroup -S nexus && adduser -S nexus -G nexus
WORKDIR /app
COPY --from=build /build/nexus-start/target/*.jar /app/app.jar
USER nexus
EXPOSE 8089
# JAVA_OPTS 预留（如 -Xmx 内存上限），compose environment 传入；exec 保证信号直达 java
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:-} -jar /app/app.jar"]

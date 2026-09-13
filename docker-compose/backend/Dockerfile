# =============================================================================
# nexus-backend 运行镜像（2026-09-13 按新宪法重建）
#
# 与旧版的根本差别：**镜像里不再包含应用 jar**。
#   旧（多阶段构建）：stage 1 以 nexus-builder 跑 mvn package，stage 2 把 jar COPY 进运行镜像。
#   新（产物共享目录）：jar 由 **builder 容器**构建后写进命名卷 build-artifacts，
#                       本容器启动时从挂载点 /artifacts/backend/app.jar 读取。
#
# 因此本镜像 = JRE + 非 root 用户 + 入口脚本，构建上下文只需本目录（秒级构建）。
#
# ⚠️ 代价（已确认接受）：镜像**不再自包含** —— 产物尚未生成时容器会启动失败。
#    这是刻意的：失败信息必须**可读**，而不是让 JVM 抛一个 NoSuchFileException 就完事。
#    参见 entrypoint.sh。
# =============================================================================
FROM docker.m.daocloud.io/library/eclipse-temurin:17-jre-alpine

# 非 root 用户运行（alpine adduser 语法）
RUN addgroup -S nexus && adduser -S nexus -G nexus

WORKDIR /app

# 入口脚本：产物存在性预检 + exec java（chmod 必需 —— 构建上下文来自 Windows 侧，
# 文件模式不可靠，不能指望 git 保留了可执行位）
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

USER nexus
EXPOSE 8089

# JAVA_OPTS 预留（如 -Xmx 内存上限），compose environment 传入
ENTRYPOINT ["/app/entrypoint.sh"]

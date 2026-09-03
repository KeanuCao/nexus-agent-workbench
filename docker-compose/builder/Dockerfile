# =============================================================================
# nexus-builder：专门的打包容器（task.1 / 0.1 交付物）
# 工具链：mvn 3.9 + JDK 17（基础镜像自带，含 git）+ node 20（nodesource）+ postgresql-client
#
# 职责：
#   ① 前后端镜像共用的构建阶段（stage 1）—— 0.4 落码后由 backend/frontend Dockerfile FROM 引用；
#   ② db-patch 迁移执行器 —— 0.2 起 `docker compose run --rm builder <迁移命令>`（nexus-net 内连 postgres）。
# 运行期不常驻：compose 中 profiles: ["build"] 隔离，不进默认 up -d 服务图。
#
# 国内源支撑（0.4 构建期注入，本步仅设计、不实现）：
#   - Maven 阿里云源：挂载/注入 settings.xml（如
#     --mount=type=bind,src=./builder/settings.xml,target=/root/.m2/settings.xml
#     或 BuildKit secret 注入，0.4 落码后定）；
#   - npm npmmirror：构建期 `npm config set registry https://registry.npmmirror.com`
#     或注入 .npmrc（同属 0.4 构建期配置）。
# =============================================================================
FROM docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17

# 补装 node 20（nodesource 官方 apt 源；与 Windows 侧本地开发保持同一大版本，避免行为漂移）
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
 && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
 && apt-get install -y --no-install-recommends nodejs \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

# 补装 postgresql-client（psql）：db-patch 主执行器为 Java PatchCli（JDBC），
# psql 用于人工排查/手工操作；基础镜像为 Ubuntu jammy，apt 装到的是 v14 客户端，
# 与 PG16 服务端兼容（客户端向后兼容）。
RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-client \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

# 构建期自证工具链（面试演示/排查友好：一条命令看全版本）
RUN mvn -version && node -v && npm -v && psql --version && git --version

WORKDIR /workspace

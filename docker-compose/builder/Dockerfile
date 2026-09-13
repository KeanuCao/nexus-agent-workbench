# =============================================================================
# nexus-builder：专门的打包容器（2026-09-13 按新宪法重建，CLAUDE.md「构建容器（builder）」）
#
# 定位（与 0.1 时的「多阶段构建 stage 1」已是两套架构）：
#   · 不挂载宿主源代码 —— 容器内自己 git pull，仓库地址可配置（NEXUS_REPO_URL）
#   · 手工启停：`docker compose up -d builder` / `stop builder`（profiles 隔离，
#     裸 `docker compose up -d` 不会把它拉起来）
#   · 手工在里面执行打包与 db-patch 迁移
#   · 产物拷到共享目录 ${NEXUS_ARTIFACT_DIR}（命名卷 build-artifacts），
#     前后端容器挂载同一个卷，重启即取到最新包
#
# 工具链：mvn 3.9 + JDK 17 + git（基础镜像自带）+ node 20（nodesource）+ postgresql-client
#
# 与旧版的差别（改动前请先读这里，避免按旧认知动手）：
#   1. 不再有 `FROM nexus-builder:dev AS build` 的后端/前端 stage 1 ——
#      产物不再焊死在镜像层里，改为落共享目录；
#   2. 不再需要 libpostgresql-jdbc-java 之类的「给 CLI 单独备一个 JDBC 驱动」——
#      源码与 Maven 都在容器内，db-patch-migrate 直接走 Maven 解析出的 classpath；
#   3. 因仓库是公开的且走 HTTPS，**不需要 openssh-client、也不需要任何密钥**。
#
# 国内源支撑（0.4 落地，保留）：
#   - Maven 阿里云源：COPY settings.xml → /root/.m2/settings.xml（用户级配置位）。
#     ⚠️ 挂载若覆盖 /root/.m2 会**遮蔽**本文件、源静默失效 ——
#        因此 compose 里 Maven 缓存的挂载点是 /root/.m2/repository（不是 /root/.m2）。
#   - npm：package-lock.json 的 resolved URL 已全部固化为 registry.npmmirror.com（实测
#     148/148），npm ci 按 lock 下载即走国内源；下面那行 npm config 是为将来新增依赖
#     （重新解析 registry 时）留的兜底。
# =============================================================================
FROM docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17

# 补装 node 20（nodesource 官方 apt 源）
# 注：设计文档 §5.2 原写"与 Windows 侧本地开发同一大版本，避免行为漂移"，但实测 Windows
#     侧为 Node 24，该意图已不成立。实际是靠依赖锁定的 engines 兼容范围兜住的（vite 5.4.21
#     engines 为 ^18.0.0 || >=20.0.0，覆盖 Node 20），**不是靠版本对齐**——排查时别往这条使劲。
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
 && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
 && apt-get install -y --no-install-recommends nodejs \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

# 补装 postgresql-client（psql）：db-patch 主执行器为 Java PatchCli（走 JDBC），
# psql 用于人工排查/手工操作；基础镜像为 Ubuntu jammy，apt 装到的是 v14 客户端，
# 与 PG16 服务端兼容（客户端向后兼容）。
RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-client \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

# ── 国内源注入 ──
# 放在 apt 层之后：改源配置不会让上面几条昂贵的 apt 层缓存失效。
# Maven 用户级配置位（${user.home}/.m2/settings.xml），优先级高于全局配置。
# 之所以用 COPY 而非 bind mount/secret：源地址是公开信息、随仓库版本化、改一处生效。
COPY settings.xml /root/.m2/settings.xml
# npm 兜底源（写 /root/.npmrc，与 /root/.npm 缓存目录不同路径、不冲突）
RUN npm config set registry https://registry.npmmirror.com

# ── 入口命令（手工执行 / up.sh 调用，两者是同一批入口，不存在"脚本文档与手工操作不一致"）──
# chmod 是**必须**的：这些脚本从 Windows 侧（/mnt/c）进构建上下文，
# 挂载层的文件模式不可靠，不能指望 git 保留了可执行位。
COPY git-sync          /usr/local/bin/git-sync
COPY build-backend     /usr/local/bin/build-backend
COPY build-frontend    /usr/local/bin/build-frontend
COPY build-all         /usr/local/bin/build-all
COPY db-patch-migrate  /usr/local/bin/db-patch-migrate
RUN chmod +x /usr/local/bin/git-sync \
             /usr/local/bin/build-backend \
             /usr/local/bin/build-frontend \
             /usr/local/bin/build-all \
             /usr/local/bin/db-patch-migrate

# 构建期自证工具链 + 入口脚本齐备（任一缺失即整个构建失败）
# 面试演示/排查友好：一条命令看全版本。顺带验证源配置确实落到了位
# （被遮蔽时这条不会报错，需靠构建日志的 Downloading from 判断）。
RUN mvn -version && node -v && npm -v && psql --version && git --version \
 && cat /root/.m2/settings.xml > /dev/null && npm config get registry \
 && for cmd in git-sync build-backend build-frontend build-all db-patch-migrate; do \
        command -v "$cmd" > /dev/null || { echo "缺少入口脚本: $cmd" >&2; exit 1; }; \
    done

# 工作区：compose 会把命名卷 builder-src 挂到这里（容器内的 git 工作区）
WORKDIR /workspace

# 常驻但**不干活**：compose 里 command 为 sleep infinity，等着被 exec 进去执行命令。
# 之所以不设默认 CMD 为某个构建命令：CLAUDE.md 明确要求"手工启动、手工在里面执行"。
CMD ["sleep", "infinity"]

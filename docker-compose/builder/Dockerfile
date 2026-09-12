# =============================================================================
# nexus-builder：专门的打包容器（task.1 / 0.1 交付物）
# 工具链：mvn 3.9 + JDK 17（基础镜像自带，含 git）+ node 20（nodesource）+ postgresql-client
#
# 职责：
#   ① 前后端镜像共用的构建阶段（stage 1）—— 0.4 落码后由 backend/frontend Dockerfile FROM 引用；
#   ② db-patch 迁移执行器 —— 0.2 起 `docker compose run --rm builder <迁移命令>`（nexus-net 内连 postgres）。
# 运行期不常驻：compose 中 profiles: ["build"] 隔离，不进默认 up -d 服务图。
#
# 国内源支撑（已于 0.4 落地）：
#   - Maven 阿里云源：COPY settings.xml → /root/.m2/settings.xml（用户级配置位）。
#     ⚠️ 应用侧 Dockerfile 若把 cache mount 挂在 /root/.m2，会**遮蔽**本文件、源静默失效
#        —— 因此 backend/Dockerfile 的 cache target 是 /root/.m2/repository。
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

# 补装 postgresql-client（psql）：db-patch 主执行器为 Java PatchCli（JDBC），
# psql 用于人工排查/手工操作；基础镜像为 Ubuntu jammy，apt 装到的是 v14 客户端，
# 与 PG16 服务端兼容（客户端向后兼容）。
RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-client \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

# ── 国内源注入 ──
# 放在 apt 层之后：改源配置不会让上面几条昂贵的 apt 层缓存失效。
# Maven 用户级配置位（${user.home}/.m2/settings.xml），优先级高于全局配置。
# 之所以用 COPY 而非 bind mount/secret：源地址是公开信息、随仓库版本化、改一处生效；
# 且 backend 的 RUN 已在 /root/.m2 下有 cache mount，再叠 bind 会形成嵌套挂载易踩坑。
COPY settings.xml /root/.m2/settings.xml
# npm 兜底源（写 /root/.npmrc，与 /root/.npm 缓存目录不同路径、不冲突）
RUN npm config set registry https://registry.npmmirror.com

# 构建期自证工具链（面试演示/排查友好：一条命令看全版本）
# 顺带验证源配置确实落到了位（被遮蔽时这条不会报错，需靠构建日志的 Downloading from 判断）
RUN mvn -version && node -v && npm -v && psql --version && git --version \
 && cat /root/.m2/settings.xml > /dev/null && npm config get registry

WORKDIR /workspace

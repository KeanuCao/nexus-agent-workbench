#!/bin/sh
# =============================================================================
# nexus-backend 容器入口
#
# 存在的唯一理由：本镜像**不自带产物**（jar 由 builder 容器写进共享卷 build-artifacts）。
# 产物缺失时，裸 `java -jar` 只会甩一句 NoSuchFileException —— 那对排查毫无帮助，
# 而"忘了先跑构建"恰恰是最常见的失败。所以在这里把它翻译成人能看懂的三行提示。
# =============================================================================
set -e

JAR="/artifacts/backend/app.jar"

if [ ! -f "$JAR" ]; then
    echo "[nexus-backend] 启动中止：${JAR} 不存在。" >&2
    echo "  本镜像不自带产物 —— jar 由 builder 容器生成到共享卷 build-artifacts。" >&2
    echo "  手工三步：" >&2
    echo "    docker compose up -d builder" >&2
    echo "    docker compose exec builder git-sync        # 拉取【已 push】的代码" >&2
    echo "    docker compose exec builder build-backend   # 产出 /artifacts/backend/app.jar" >&2
    echo "  或者直接跑一键脚本：./scripts/sh/up.sh" >&2
    exit 1
fi

# JAVA_OPTS 需要按空格拆成多个参数，故这里**故意不加引号**（加引号会被当成单个参数）
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -jar "$JAR"

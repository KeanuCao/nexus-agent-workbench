# 记忆索引

- [Docker 运行限制](feedback-docker-runtime-restrictions.md) — 未经批准禁 up/pull/build，只做 compose config 静态校验；docker 一律经 `wsl -d nexus-agent-workbench` 执行
- [DaoCloud 镜像源已验证](project-mirror-daocloud-verified.md) — 检查点 B/C/D/F/G/H 通过：镜像源 OK、三镜像已拉取、三件套 healthy、两模型已入卷、重启持久化验证通过
- [compose 构建路径解析](project-compose-build-path-resolution.md) — dockerfile 相对 context 拼（config 不解析），静态预检用 `docker compose build --print` + 存在性检查
- [本环境无外网](project-no-internet-verify-empirically.md) — 在线文档不可达，规则判断改走本机实测/--help，未验证的标注为推断
- [健康检查实测偏差](project-health-probe-findings.md) — 前端/ollama-init 两条已在 compose 修复（待 up -d 生效）；data.timestamp 用 `Z` 未闭环；0.3.3 探针决策
- [WSL 内工具实况](project-wsl-tooling-facts.md) — **jq 缺失**（sed 兜底才是实际路径）、python3 可做 stub、经 wsl 传长脚本要用 heredoc

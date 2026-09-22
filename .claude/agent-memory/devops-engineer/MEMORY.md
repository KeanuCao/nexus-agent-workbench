# 记忆索引

- [Docker 运行限制](feedback-docker-runtime-restrictions.md) — 未经批准禁 up/pull/build，只做 compose config 静态校验；docker 一律经 `wsl -d nexus-agent-workbench` 执行
- [DaoCloud 镜像源已验证](project-mirror-daocloud-verified.md) — 检查点 B/C/D/F/G/H 通过：镜像源 OK、三件套 healthy；末尾含 **2026-09-22 模型清单改 bge-m3**（1024 维实测、唯一真源在 compose 一行）
- [compose 构建路径解析](project-compose-build-path-resolution.md) — dockerfile 相对 context 拼（config 不解析），静态预检用 `docker compose build --print` + 存在性检查
- [本环境无外网](project-no-internet-verify-empirically.md) — 在线文档不可达，规则判断改走本机实测/--help，未验证的标注为推断；**末尾订正：无外网只对 WSL 宿主成立，容器经 Docker Desktop 能出网且会间歇性 DNS 抖动（先重试）**
- [健康检查实测偏差](project-health-probe-findings.md) — 前端/ollama-init 两条已在 compose 修复（待 up -d 生效）；data.timestamp 用 `Z` 未闭环；0.3.3 探针决策
- [WSL 内工具实况](project-wsl-tooling-facts.md) — **jq 缺失**（sed 兜底才是实际路径）、python3 是最佳机械探针、SQL 走文件、含 `docker exec -i` 的脚本必须落盘；`wsl -- bash -c '…'` 传 `2>/dev/null`/`$(( ))` 会被 Git Bash 打乱 → 落盘或用 `MSYS_NO_PATHCONV=1`
- [builder-m2 持久卷隐患](project-builder-m2-persistent-volume-hazard.md) — 持久卷静默复用旧构件，把「配置没生效」伪装成构建成功；判据要落在产物本身
- [builder 构建分支的真源](project-builder-git-sync-branch-source.md) — 真源是容器 env `NEXUS_REPO_BRANCH`（默认 main），**不是宿主当前分支**；push 功能分支 ≠ builder 能拿到
- [RAG 检索召回缺陷](project-rag-retrieval-recall-defect.md) — 阶段3 验收未过：答案块排 27+/587、无关块 score 0.75 ⇒ 阈值标定救不了；已定修法=换 bge-m3，**重灌复测未做≠已修好**
- [builder 容器工具实况](project-builder-container-tooling.md) — **无 unzip**（验 jar 内文件用 JDK `jar xf`）；与 WSL 宿主工具清单分开看，镜像重建判据见 `wsl.abc.md` §3.9
- [RAG 夹具上传耗时](project-rag-ingest-timing.md) — 587 块 ≈3.6s/批、总 130~132s（设计写的 80~90s 偏低）；前端超时 300s 余量仅 ~2.3×

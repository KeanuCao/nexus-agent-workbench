# task.1 环境准备设计文档

- **Agent**：devops-engineer ｜ **耗时**：约 6 分钟 ｜ **task-id**：a12c62c6defab8ce0

## 做了什么
- 产出 `docs/design/00-环境与部署.md`（§1-§8）：Windows↔WSL 执行边界、compose 拓扑、镜像选型、builder 多阶段构建、db-patch 契约与时序、脚本设计、Maven 五模块、风险与验收对照。
- 关键决策点：builder 拆分 vs 共用、自研 db-patch vs Flyway、模型拉取用一次性 ollama-init 容器。

## 遇到的坑
- 初稿假设"docker 可能在 Windows 直接可用"，被用户叫停并纠正：docker 全在 WSL 内、发行版由用户指定、禁止自行探测。
- 第一版镜像加速设计写成 daemon.json 的 registry-mirrors，后按用户规则改为 Dockerfile `FROM` / compose `image:` 前缀方式。

## 下次怎么改进
- 动笔前先读项目记忆（wsl-env-convention / docker-mirror-rule 等用户规则），把"禁探测、禁 daemon.json"当作前置约束而不是中途修正。
- 用户拍板的约束（端口、镜像源、执行位置）第一时间落进设计文档的"待确认清单"并逐项销账。

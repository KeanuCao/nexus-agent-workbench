# Step 6 模型拉取（ollama-init）

- **Agent**：devops-engineer ｜ **耗时**：约 2 分钟 ｜ **task-id**：ac3de654c1122f80b

## 做了什么
- `docker compose up -d ollama-init` 一次性拉取容器，约 2 分钟完成（实测 24 MB/s）：
  - qwen2.5:7b — 4.68 GB（Q4_K_M）
  - nomic-embed-text — 274 MB（F16）
- 容器 `Exited (0)`，模型落共享卷 `ollama-models`（4.7G），`/api/tags` 两模型在列。

## 遇到的坑
- 无。计划担心的"registry.ollama.ai 直连慢/断"未发生；幂等跳过逻辑未走到（首次拉取即成功）。

## 下次怎么改进
- 模型下载走 registry.ollama.ai 直连不受 docker 镜像加速影响——后续若拉不动，先查 registry.ollama.ai 连通性而非换 docker 源。
- 一次性 init 容器（restart: no + `ollama list` 判存在跳过）这个模式可直接复用到其他"就绪即退"的初始化任务。

# Step 3 拉取基础镜像

- **Agent**：devops-engineer ｜ **耗时**：约 6 分钟 ｜ **task-id**：a62d35ae81a870832

## 做了什么
- `docker compose pull postgres redis ollama` 一次成功、零重试：pgvector:pg16 621MB、redis:7-alpine 57.8MB、ollama:latest 8.45GB，实拉耗时 4m18s。

## 遇到的坑
- ollama `latest` 实测 **8.45GB**，远超计划预估的 0.6-1.5GB——官方 latest 自 v0.6.x 起是捆绑 CUDA 的 fat 镜像。本项目已定 CPU 推理，这个体积是纯浪费。

## 下次怎么改进
- CPU 推理项目选 ollama 的 CPU-only tag（落地时以 registry 实际 tag 为准），并在 Step 10 把 `OLLAMA_TAG` 固定为实测版本，不长期挂 latest。
- 大镜像 pull 前先 `manifest inspect` 看 layer 总量或查官方 tag 说明，避免盲拉 fat 镜像。

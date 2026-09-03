# Step 4-5 健康检查修正 + 拉起三件套

- **Agent**：devops-engineer ｜ **耗时**：约 3 分钟 ｜ **task-id**：a33ca9c64322d310a

## 做了什么
- 按批准改 `docker-compose.yml` 第 78 行：ollama 健康检查 `curl -fsS ...` → `["CMD", "ollama", "list"]`（镜像内无 curl/wget，Step 4 已探测），`compose config --quiet` 通过。
- `docker compose up -d postgres redis ollama` 一次成功，3 容器全部 `Up (healthy)`；容器内实际挂载新 healthcheck 并持续通过（GIN 日志 /api/tags 200 探针）。

## 遇到的坑
- 无。ollama 镜像无 curl 的坑在 Step 4 被提前探测并修正，up 一次成功——"先探测后下载"策略生效。

## 下次怎么改进
- 凡镜像内可用命令不确定的健康检查，先 `docker run --rm --entrypoint /bin/sh <镜像> -c 'command -v xxx'` 探测，再定 `test` 写法，避免 up 后卡 waiting。
- 改了 healthcheck 后顺手 `docker inspect <容器> --format '{{json .State.Health}}'` 验证实际生效，而不只看文件。

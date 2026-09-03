# Step 2 三镜像 manifest 探测

- **Agent**：devops-engineer ｜ **耗时**：约 2 分钟 ｜ **task-id**：a798f92b09c4211e4

## 做了什么
- `docker manifest inspect` 零下载探测：pgvector:pg16 / redis:7-alpine / ollama:latest 在 DaoCloud 上的路径全部 OK，与 compose/.env 现状一致，零文件改动。
- ollama digest 留档（多架构 OCI index，amd64 + arm64）。

## 遇到的坑
- 计划中的 `for img in ...; do docker manifest inspect $img; done` 在 Windows Git Bash → `wsl -d ... -- bash -c "..."` 双层 shell 嵌套下 `$img` 变量展开丢失，循环体拿到空串。
- 按计划预置的降级路径改为三条显式命令，结果不受影响。

## 下次怎么改进
- 凡是经 `wsl -d ... -- bash -c` 包装的循环/变量命令，默认放弃内层变量，直接展开成多条显式命令；或用 heredoc/base64 传脚本体。
- 需要留档 digest 的探测，一次 `manifest inspect` 顺手取完关键字段。

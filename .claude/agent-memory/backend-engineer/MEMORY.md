# 记忆索引

- [首次后端构建：瞬时截断](project_backend_first_build.md) — 重跑即过；不改 POM、不加 maven.test.skip，nexus-start 保留 starter-test
- [maven.test.skip 不省依赖解析](maven_test_skip_resolution.md) — 执行器级预解析先于 mojo；看报错行带不带 goal 坐标来判定
- [agent-log 与草稿复核](agent-log-cross-session-lessons.md) — docs/agent-log/ 是教训入口；草稿/子代理结论先实测复核再采用
- [文档写"单一真源"的三档口径](feedback_doc-single-source.md) — 判据写「等于真源」+取数命令；样例标注"示例值"；文件名走通配；别只判 semver 正则
- [临时工作区 .tmp/<agent>/](feedback_tmp-workspace.md) — 脚本放仓库内免审批；看源码走 Grep/Read 工具，Bash 复合命令必被拦
- [KB 上传 415/40002 根因](project_kb-upload-multipart-415.md) — 实例默认头 application/json 让 axios 把 FormData 转成 JSON；假断言抄在 5 处文档里
- [RAG 上传耗时口径与冻结区](project_rag-upload-timing.md) — 131.9 s / 3.6 s/批 / 唯一硬上界 300 s；db-patch 旧数字改不得（checksum → exit 2）
- [msg 即契约](feedback_msg-is-contract.md) — 改文本前分 Javadoc 与 msg；msg 必须连带同步 README + openapi 并点名上报

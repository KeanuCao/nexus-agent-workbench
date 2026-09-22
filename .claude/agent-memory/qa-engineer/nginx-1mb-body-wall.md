---
name: nginx-1mb-body-wall
description: 走 8088（nginx）上传 >1MB 被 413 挡下 —— 前端链路体量墙（2026-09-22 实测，阶段3 验收夹具 1.6MB 受影响）
metadata:
  type: project
---

**事实（2026-09-22，AI 侧只读探针实测）**：`docker-compose/frontend/nginx.conf` 的 `location /api/` **没有配
`client_max_body_size`** ⇒ 用的是 nginx 默认 **1m**。实测：

| 体量（POST /api/kb/documents，不带 token） | 走 8088（nginx） | 走 8089（直连后端） |
| --- | --- | --- |
| 900,000 字节 | 401（到后端，被鉴权挡下） | 401 |
| 1,100,000 字节 | **413**（nginx 的 HTML 错误页） | 401 |
| 1,682,689 字节（= `qa/fixtures/rag/公司年报.pdf`） | **413** | 401 |

**Why 重要**：后端（`spring.servlet.multipart.max-file-size: 10MB`）与契约 `docs/api/README.md` §7.1 都声明
支持 ≤10MB，而**前端链路只支持 1MB** ⇒ 阶段3 的验收夹具（1.6MB）走 8088 必然失败。修法是 devops 在
`location /api/` 里加 `client_max_body_size 12m;`（与后端 `max-request-size: 12MB` 同源）——**不是 qa 的改动范围**。

**How to apply**：
- 任何"前端链路 / 走 8088"的上传类用例，先确认这条墙是否已修；没修就别把 1MB 以上的夹具拿去走 8088，
  更不要把它记成"上传功能坏了"。已修的话，**删掉这条记忆**（`grep -n client_max_body_size docker-compose/frontend/nginx.conf` 一查便知）。
- 探针写法（零写业务数据）：`head -c <字节数> /dev/zero | curl -s -o /tmp/p.out -w '%{http_code}\n' -X POST
  -F "file=@-;filename=probe.docx" http://localhost:8088/api/kb/documents` —— 用 `.docx` 是刻意的：
  万一请求真的透到后端，也只会得到 `10201`，**不会建文档**。
- 同族知识：`--force-recreate` 换容器 IP ⇒ nginx 启动期解析的 upstream 失效（8088 变 502），
  修法是 `docker compose restart nexus-frontend`；见 [[recreate-breaks-nginx-upstream]]。

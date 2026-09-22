---
name: recreate-breaks-nginx-upstream
description: 造法里出现 `up -d --force-recreate nexus-backend` 时，必须成对加「跑前验 8088 链路 + 还原后复验」—— 换 IP 会让 nginx 打旧 upstream 出 502（2026-09-21 用户点名要求）
metadata:
  type: feedback
---

**规则**：凡造法要**重建 `nexus-backend` 容器**（典型：改 `.env.local` 再 `docker compose up -d --force-recreate nexus-backend`），
用例步骤里必须成对出现两件额外的事：

1. **跑前先验浏览器链路**：`curl -s -o /dev/null -w '%{http_code}' http://localhost:8088/api/health` 期望 **200**；
   不是 200 就 `docker compose restart nexus-frontend` 再验一次 —— **仍不是 200 就停下别测**。
2. **还原之后再验一次**（别让用例跑完把浏览器链路留在 502）。

**Why**：`--force-recreate` 会换容器 IP，而 `nexus-frontend` 的 nginx 写的是 `proxy_pass http://nexus-backend:8089;`
—— **启动期解析、配置里没有 `resolver`**（`docker-compose/frontend/nginx.conf`）⇒ 换 IP 后 nginx 仍打旧地址，
浏览器走 8088 直接 **502**。这个 502 会**伪装成用例判据**：页面上出现的是传输层红字（`无法连接后端服务…`），
而不是用例要验的那个业务码 —— 是最容易漏、也最容易被误读的一类坑。2026-09-21 用户点名要求把它写进步骤并在备注里给理由
（[[criteria-falsifiability]] 的同族问题：判据要能区分"被测对象坏了"与"夹具把环境弄坏了"）。
反面对照：只用 `docker compose restart nexus-backend` 的造法（TC-02-2.4-4 造 EOF）**不换 IP**，因此躲开了这个坑
—— **能用 `restart` 就别 `--force-recreate`**。

**How to apply**：
- "容器里读不到某个环境变量/密钥"这类造法**只能重建**（环境变量**只在创建容器时注入**，`restart` 读不到新值）⇒
  上面两条自证是**必带项**，不是可选优化。
- 更省事的等价做法：把前后端**一起**重建（`up -d --force-recreate nexus-backend nexus-frontend`），
  前端重建时自然会重新解析 —— 代价是多等一次 `service_healthy`。
- "验链路"这一步永远排在**取判据之前**：链路 502 时页面上的一切表现都不构成结论。
- 还原自证同理要**两侧都证**：`printenv DEEPSEEK_API_KEY | wc -c`（容器吃到了）**和**
  `grep -c '^DEEPSEEK_API_KEY=' .env.local`（文件里没留下注释行）—— 少任何一条，还原都可能是假的。

相关：[[fixture-cleanup-claims]]（自证为什么必须实测而不是"我保证"）、[[tc-edit-record-discipline]]

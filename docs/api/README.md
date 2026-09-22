# 后端 API 契约（阶段0 + 阶段1 + 阶段2 + 阶段3）

> 面向 `frontend-engineer`（§1~§3 + **§5 认证接口** + **§6 对话接口** + **§7 知识库接口**）与 `devops-engineer`（**§4 是健康检查脚本的唯一判据来源**）的接口文档。
> 机器可读版本：[`openapi.yaml`](./openapi.yaml)（OpenAPI 3.0.3）。
> 契约来源：`docs/design/00-环境与部署.md` §5.3 + `docs/design/01-多租户与认证.md` §5 + `docs/design/02-统一AI网关.md` §3 + `docs/design/03-RAG知识库.md` §3（均已确认）；
> 实现：`backend/nexus-start`（健康检查）+ `backend/nexus-module-system`（认证）+ `backend/nexus-module-ai`（对话 + 知识库）。
> 更新纪律：协议变更**先改本文件**再改代码，前后端以本文件为唯一事实源。
> ⚠️ **本文件若自相矛盾，以"字段说明"为准、"代码示例"次之，但必须上报矛盾点**（并发起修正），
> 不得默不作声地挑一条照做 —— 实例：§5.2 的 `data.tokenType` 曾同时写着"固定 `Bearer`"与"不要硬编码"。
> ⚠️ 章节编号约定：**§4（健康检查判据）被 `scripts/` 的三个脚本按编号引用，编号不得变动** ——
> 阶段1 的新增章节因此追加在 §4 之后（§5 认证接口），而不是插在它前面。

## 0. 文件说明

| 文件 | 用途 |
| --- | --- |
| `openapi.yaml` | OpenAPI 3.0.3 规范，可直接导入 Apifox / Postman / 生成 TS 类型 |
| `README.md` | 本文件：人工速查版（字段表、示例、错误码、常见坑）<br>§4 = 健康检查判据（0.3 的 `check-env.sh` / `check-health.sh` 照此写） |

现有接口：`GET /api/health`（阶段0）、`/api/auth/login|logout|me`（阶段1）、
`POST /api/chat/stream`（阶段2，**流式**，见 §6）、`/api/kb/*`（阶段3，知识库上传 / 列表 / 删除 + 问答，
**同步 JSON**，见 §7）。

---

## 1. 全局约定

### 1.1 统一响应体 `Result<T>`

所有接口（含各类错误出口）都返回下面这个外层结构，HTTP 响应体的 JSON 只有这三个字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer | 是 | `0` = 成功；非 0 = 失败，见 §1.3 错误码表 |
| `msg` | string | 是 | 成功固定为 `success`；失败时是**可直接展示**给用户的文案 |
| `data` | object \| null | 是（可为 `null`） | 业务数据；失败且无数据时为 `null` |

### 1.2 HTTP 状态码的使用策略（**前后端必须一致**）

| 场景 | HTTP | 响应体 `code` | 前端处理分支 |
| --- | --- | --- | --- |
| 成功 | 200 | 0 | axios 成功分支，拦截器已解包为 `data` |
| **业务失败**（参数不合法、业务规则拒绝等） | **200** | 非 0（如 10000） | axios **成功**分支，拦截器判 `code !== 0` → 弹 `msg` + reject `BusinessError` |
| **未登录 / 登录态失效**（阶段1 起） | **401** | 40100 / 40101 / 40102 | axios 失败分支的 401 专用处理：清 token + 跳 `/login`（见 §1.4 与 §5.1） |
| 路径不存在 | 404 | 40400 | axios 失败分支，弹 `error.response.data.msg` |
| **请求媒体类型不支持**（阶段2 起） | **415** | 40002 | axios 失败分支，弹 `error.response.data.msg` |
| 系统异常 | 500 | 50000 | 同上（`msg` 是通用话术，不含堆栈） |
| **依赖不可用**（健康检查探活失败） | **503** | 20000 | 同上；但 `data` 仍带完整报告，可定位故障依赖 |

设计理由：HTTP 状态码只承载**传输/可用性**语义（401/404/415/500/503），业务成败由 `code` 判定 ——
这样业务失败不会污染 axios 的失败分支，异常提示统一由拦截器负责，调用点只关心 `data`。

**415 为什么不用 200 + 业务码**：缺 `Content-Type` 时请求**根本没被解析成业务入参**，
不属于"业务失败"。它更接近 401/404 那类传输层语义。⚠️ 这条是**实测补上的**：
在 2026-09-20 之前，`HttpMediaTypeNotSupportedException` 没有专门出口，会落进兜底 →
客户端忘带头被报成 **500 + 50000「系统繁忙」**，把排查方向整个带偏
（正是 §6.1 那段"看到 415 就知道是 Content-Type 没带"的诊断想防的事）。当日修复。

**401 为什么必须用 HTTP 状态码、而不是塞进 200 + 业务码**：前端的"清 token + 跳登录"这条路
（`request.ts` 里已预留的 `if (status === 401)` 分支）是**传输层**语义。若把登录态失效做成
200 + 非 0 业务码，它会落进成功分支，与"业务失败弹个提示"混淆 —— 两者处置完全不同
（前者要跳登录，后者只是提示）。取舍记录见设计决策 D6。

### 1.3 错误码表（阶段0 + 阶段1 已落地）

| code | 常量（后端 `ResultCode`） | 配套 HTTP | `msg` 示例 |
| --- | --- | --- | --- |
| 0 | `SUCCESS` | 200 | `success` |
| 10000 | `BUSINESS_ERROR` | 200 | 业务处理失败（`BusinessException` 默认码） |
| **10100** | `LOGIN_FAILED` | 200 | 用户名或密码错误 |
| **10101** | `ACCOUNT_DISABLED` | 200 | 账号已停用，请联系管理员 |
| **10102** | `TENANT_DISABLED` | 200 | 租户已停用，请联系管理员 |
| 20000 | `SERVICE_UNAVAILABLE` | 503 | `依赖服务不可用：postgres、ollama` |
| 40400 | `NOT_FOUND` | 404 | 请求的资源不存在 |
| **40002** | `UNSUPPORTED_MEDIA_TYPE` | **415** | 请求格式不支持，请使用 application/json |
| **40003** | `FILE_TOO_LARGE` | **200** | 文件过大，最大支持 10MB（multipart 上限，见 §7.8） |
| **40100** | `UNAUTHENTICATED` | 401 | 未登录，请先登录 |
| **40101** | `TOKEN_INVALID` | 401 | 登录状态无效，请重新登录 |
| **40102** | `TOKEN_EXPIRED` | 401 | 登录已过期，请重新登录 |
| 50000 | `SYSTEM_ERROR` | 500 | 系统繁忙，请稍后重试 |

编码分段（按模块细分，**不复用**上表已有值）：`1xxxx` 业务 / `2xxxx` 可用性 / `4xxxx` 请求侧 / `5xxxx` 系统。

> **阶段2 新增 4 个码**：`40002` 列在**本表**（它是**通用**的 —— 任何 `consumes=application/json`
> 的接口都可能触发，不只对话接口）；另 3 个（`40001` / `10200` / `20100`）列在 **§6.4**。
> 三者与上表一起构成完整错误码表 —— 上表其余行保持原貌不动，避免改动被 `scripts/` 引用的编号。
>
> **阶段3 新增 5 个码**：`40003` 列在**本表**（它同样是**通用**的 —— 约束的是 multipart 请求体本身，
> 任何上传接口都会撞上，与"知识库"这个业务域无关）；另 4 个（`10201`~`10204`）列在 **§7.8**。

三条 401 分开的理由：**处置动作不同** —— `40100` 是客户端压根没带 token（接入问题）；
`40102` 过期，重新登录即可；`40101` 是"token 签名不对"或"Redis 白名单里已无此 jti（已登出/被清）"，
属异常信号（有人在动 token）。前端文案与排查方向都不同，故不合并。

`10100` 对"用户名不存在"与"密码错误"**统一回同一文案**（防账号枚举）：
前端**不得**依据 `msg` 做分支判断（设计 §5.1）。

### 1.4 通用请求头

| Header | 阶段1 | 说明 |
| --- | --- | --- |
| `Content-Type: application/json` | 仅带请求体的接口需要 | 健康检查是 GET、登出无请求体，都不需要 |
| `Accept: application/json` | 可选 | 后端已用 `produces = application/json` 声明 |
| `Authorization: Bearer <token>` | **除白名单外一律必需** | 值取自 `POST /api/auth/login` 的 `data.token`；由 `request.ts` 请求拦截器统一注入 |

白名单（`nexus.security.whitelist`，见 `application.yml`，**除下列路径外一律需要 token**）：

| 白名单路径 | 为什么免鉴权 |
| --- | --- |
| `/api/auth/login` | 登录本身不能要求先登录 |
| `/api/health` | 容器与 `scripts/` 三个脚本的探活口（加了鉴权会让 0.3 验收全线 FAIL） |
| `/actuator/**` | `docker-compose` 中 `nexus-backend` 的 healthcheck 探活口 |

两点前端要记住：

1. **本地有 token ≠ 服务端仍认**：登录态以 Redis 白名单为准，可能在别处登出、或 Redis 被清。
   刷新页面后调一次 `GET /api/auth/me` 做真实校验（这也是"假登录态"的第二道闸）。
2. **Redis 不可用会表现为全员 401**（`40101`）—— 这是**有意**的 fail-closed：宁可让所有人重新登录，
   也不放过无法验证的凭据。遇到"谁都登不进"，排查顺序先看 `/api/health` 的 `checks.redis`。

---

## 2. `GET /api/health` —— 健康检查

### 2.1 请求

| 项 | 值 |
| --- | --- |
| URL | `/api/health`（前端 `baseURL='/api'` + `url='/health'`） |
| Method | `GET` |
| Query 参数 | 无 |
| Request Body | **无**（不要发送 body） |
| 请求头 | 无必需头 |
| 鉴权 | 无（`security: []`） |
| 响应 `Content-Type` | `application/json` |

前端可直接复用 `frontend/src/api/health.ts` 的 `getHealth()`。

### 2.2 响应 200（依赖全部可用）

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "status": "UP",
    "service": "nexus-start",
    "version": "0.2.0",
    "timestamp": "2026-09-03T10:00:00+08:00",
    "checks": { "postgres": "UP", "redis": "UP", "ollama": "UP" }
  }
}
```

> ⚠️ 上面样例里的 `"version": "0.2.0"` 是**示例值，非真源；实际以 `/api/health` 的实际返回为准**
> （真源与判据见 §4.2.2）。

#### `data` 字段完整定义（`HealthReport`）

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `data.status` | `"UP" \| "DOWN"` | 是 | 总体状态：三个依赖全 UP 才是 `UP` |
| `data.service` | string | 是 | 服务名，固定 `nexus-start` |
| `data.version` | string | 是 | 服务版本。**真源 = `backend/pom.xml` 的 `<revision>`**，构建期由 Maven 资源过滤注入 `application.yml` 的 `@project.version@`；`0.2.0` 只是**示例值，非真源**，实际以 `/api/health` 的实际返回为准（判据见 §4.2.2） |
| `data.timestamp` | string (`date-time`) | 是 | ISO-8601 带时区偏移，格式固定 `yyyy-MM-dd'T'HH:mm:ssXXX`（**秒级、无小数秒**）。偏移量 = 服务端默认时区：容器内通常 `+00:00`，Windows 本地直跑 `+08:00` |
| `data.checks` | object | 是 | 各依赖探测结果，见下 |

#### `data.checks` 字段定义（`HealthChecks`）

| 字段 | 类型 | 必填 | 探测方式（真实连通性，非配置判断） |
| --- | --- | --- | --- |
| `data.checks.postgres` | `"UP" \| "DOWN"` | 是 | 取 JDBC 连接执行 `SELECT 1` |
| `data.checks.redis` | `"UP" \| "DOWN"` | 是 | 发 `PING`，期望 `PONG` |
| `data.checks.ollama` | `"UP" \| "DOWN"` | 是 | 请求 `GET /api/version`，有响应体即 UP（**不**校验模型是否已拉取） |

> `timestamp` 与 `checks` 的取值严格限定为 `UP` / `DOWN` 两种 —— 后端刻意不使用 Spring `HealthStatus`
> （其取值含 `OUT_OF_SERVICE` / `UNKNOWN`），避免前端出现未定义分支。

### 2.3 响应 503（任一依赖不可用）

**响应体结构完全不变**（仍是 `Result<HealthReport>`），只有 `code` / `msg` 与 HTTP 状态不同：

```json
{
  "code": 20000,
  "msg": "依赖服务不可用：postgres",
  "data": {
    "status": "DOWN",
    "service": "nexus-start",
    "version": "0.2.0",
    "timestamp": "2026-09-03T10:00:00+08:00",
    "checks": { "postgres": "DOWN", "redis": "UP", "ollama": "UP" }
  }
}
```

> 同样：这里的 `"version": "0.2.0"` 是**示例值，非真源；实际以 `/api/health` 的实际返回为准**。

- `msg` 已把故障依赖拼进文案（多个依赖用 `、` 连接），可直接 `ElMessage.error(msg)`；
- 若要在页面上逐项标红，可在 `catch` 分支读取 `error.response.data.data.checks`
  （`request.ts` 的失败分支里 `error.response.data` 即完整 `Result`）。

### 2.4 前端对接示例（TS，对齐现有 `request.ts`）

```ts
// 成功路径：拦截器已解包，直接拿 data
const health = await getHealth()        // HealthData
console.log(health.checks.postgres)     // 'UP' | 'DOWN'

// 失败路径：503 / 4xx / 5xx 走 reject，error.response.data 是完整 Result
try {
  await getHealth()
} catch (error) {
  const result = (error as AxiosError<Result<HealthData>>).response?.data
  ElMessage.error(result?.msg ?? '健康检查失败')
  result?.data?.checks  // 可选：逐项展示 DOWN 的依赖
}
```

### 2.5 容器级探活（前端**不要**调用）

`GET /actuator/health` 由 Spring Boot Actuator 提供，供 `docker-compose.yml` 的 healthcheck 使用。
它与 `/api/health` 的区别：

| 维度 | `GET /api/health` | `GET /actuator/health` |
| --- | --- | --- |
| 使用者 | 前端页面 / 演示链路 | 容器编排 / 运维 |
| 响应体 | 统一 `Result<T>` | Actuator 自有格式（`{"status":"UP",...}`） |
| 探测范围 | postgres + redis + ollama（含 AI 依赖） | db / redis / diskSpace 等容器自身依赖（**不含 ollama**） |
| 设计取舍 | 应用"业务可用性" | 容器"是否需要重启" |

取舍理由：Ollama 模型拉取耗时长，若纳入容器健康判据，会让后端容器被编排判为不健康并阻塞前端启动 ——
故 AI 依赖只在业务级健康检查中体现。

---

## 3. 已知行为（联调时先看这里）

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 页面刚打开时 `/api/health` 返回 503，`checks.ollama = DOWN` | Ollama 容器已起但模型（约 5GB）仍在拉取，或 Ollama 尚未就绪 | 属预期：`up.sh` 会轮询 `/api/tags` 等模型就绪；Ollama 只要进程可响应即 UP |
| **三个 checks 全 DOWN**，但后端进程正常 | 后端未接入 compose 网络，或本地直跑时数据源/Redis 地址仍指向容器服务名 | Windows 本地直跑请设置 `POSTGRES_HOST=localhost`、`REDIS_HOST=localhost`、`OLLAMA_BASE_URL=http://localhost:11434` |
| `timestamp` 偏移是 `+00:00` 而不是 `+08:00` | 容器时区为 UTC，契约只要求"ISO-8601 带偏移"，偏移量随服务端时区 | 前端格式化展示即可；不改契约 |
| 依赖全挂时后端仍能启动 | 有意为之：Hikari `initialization-fail-timeout=-1`，启动不依赖外部服务 | 便于"先起后端、再逐个拉起依赖"的调试顺序 |

---

## 4. 健康检查判据（`scripts/check-env.sh` / `scripts/check-health.sh` 的唯一判据来源）

> **本节面向 `devops-engineer`**：0.3 的脚本以本节为判据来源，**不要**从 `openapi.yaml` 反推规则，
> **不要**直接沿用 `docs/drafts/环境检查脚本.md` 的检查方式（该草稿有多处探活/模型就绪判据已核实有误，逐条见 §4.6）。
> 本节取值与 `backend/nexus-start` 实现逐行核对（`HealthController` / `HealthServiceImpl` / 三个 Probe / `application.yml` /
> `docker-compose/docker-compose.yml`）。**凡推断而非实测的条目均显式标注**，落地时以实机为准。

### 4.1 两个探活口的分工（**先看这节，混用是最大坑源**）

同一份"健康"，后端有两个出口，**覆盖面不同，不能互相替代**：

| 维度 | `GET /actuator/health` | `GET /api/health` |
| --- | --- | --- |
| 谁在用 | compose 里 `nexus-backend` 的 healthcheck（容器内 `wget -qO- localhost:8089/actuator/health`）、运维 | 脚本（0.3）、前端页面、演示链路 |
| 实现 | Spring Boot Actuator 内建 indicators | 自研 `HealthService`，**真实连通性探测** |
| 覆盖的依赖 | **db / redis / diskSpace**（Boot 自动装配；实际组件以响应 `components` 为准，**勿硬编码**） | **postgres / redis / ollama** 三项 |
| 是否含 ollama | **不含**（后端没为它写 indicator） | **含**（`data.checks.ollama`） |
| 响应体 | `{"status":"UP","components":{...}}`（`show-details: always`），**非** `Result` | `Result<HealthReport>` |
| 状态取值域 | Spring `HealthStatus`：`UP` / `DOWN` / `OUT_OF_SERVICE` / `UNKNOWN` | **只有** `UP` / `DOWN` |
| HTTP 语义 | `UP`→200；`DOWN`/`OUT_OF_SERVICE`→503（Boot 默认映射） | 三项全 UP→200；任一 DOWN→**503** |
| 宿主访问 | `http://localhost:${BACKEND_PORT:-8089}/actuator/health` | `http://localhost:${BACKEND_PORT:-8089}/api/health` |

#### 三条必须写进脚本的结论

**① 容器 healthy ≠ 依赖全通。** 两个方向都成立：

- `docker compose stop ollama` → `nexus-backend` **仍然 healthy**（actuator 根本不看 ollama），
  但 `/api/health` 立刻变 503、`checks.ollama = DOWN`。
  → **只查容器状态的脚本会完全漏掉 AI 依赖故障**，这是"两级都要查"最硬的证据。
- `docker compose stop postgres`（或 redis）→ backend 容器在约 2 分钟后转 `unhealthy`
  （推算：12 次重试 × 10s 间隔，另加每次 5s 超时；**未实测**），但 **unhealthy 不触发重启**
  —— `restart: unless-stopped` 只响应进程退出，容器仍是 `running` + `unhealthy`；
  而且 unhealthy 这个状态**不告诉你挂的是哪个依赖** → 仍要读 `/api/health` 的 `data.checks`。

**② 脚本必须三级都查**（0.3 的验收判据）：

| 级别 | 查什么 | 判据 | 回答的问题 |
| --- | --- | --- | --- |
| L1 容器级 | `docker compose ps` | `nexus-backend` 是否 `running` / `healthy` | 进程起没起来、要不要重启 |
| L2 业务级 | `GET /api/health` | HTTP + `code` + `data.checks`（§4.2） | 三个依赖**此刻**是否真的通 |
| L3 模型级 | `GET /api/tags` | `qwen2.5:7b` 与 `nomic-embed-text` 是否都在（§4.5） | AI 能力是否真的可用 |

L2 是**唯一**同时覆盖 postgres / redis / ollama 的入口 —— 依赖判据一律以它为准。

**③ 判"后端进程起来了吗"不要看状态码，看"能不能拿到 HTTP 响应"。**
`/api/health` 与 `/actuator/health` 在依赖挂掉时都会返回 **503**，但服务本身是正常的。
只有 `curl` 连不上（exit code 7 / `Connection refused`）才代表进程没起。
把 503 当成"后端没起来"是新手脚本最常见的误判。

> 脚本编排建议：用 `/actuator/health`（Boot 内建、通常 <1s、不探 ollama）做**第一道闸门**快速判断"进程活了没"，
> 再用 `/api/health`（串行真实探测，最坏约 7s，见 §4.3）做**完整依赖判定**。顺序反了会白等。

### 4.2 `GET /api/health` 判定规则表

#### 4.2.1 HTTP 状态码 + `code` 语义（穷举）

| HTTP | `code` | `msg` | 脚本动作 |
| --- | --- | --- | --- |
| **200** | `0` | `success` | 通过 → 继续做 §4.2.2 的字段校验 |
| **503** | `20000` | `依赖服务不可用：<DOWN 的依赖名>` | **依赖不可用**（正常契约响应，不是"接口挂了"）→ 用 `data.checks` 定位 |
| 404 | `40400` | `请求的资源不存在` | 路径写错 / 版本不匹配 |
| 500 | `50000` | `系统繁忙，请稍后重试` | 后端内部异常 → 看 `docker logs --tail 100 nexus-backend` |
| 000（curl exit 7） | 无响应体 | — | 后端未启动 / 端口不对（`BACKEND_PORT` 与 `.env` 不一致） |
| **200 但 body 是 HTML** | — | — | 打到了 nginx 的 SPA 回退（`try_files → /index.html`）。**假通过**，见 §4.6 |

**关键实现细节**：503 的响应体是**完整、可解析的 `Result<HealthReport>` JSON**（`data` 不为 null）。
所以取数命令必须是 `curl -s`（**不加 `-f`**）——
`curl -f` 在 503 时会丢 body 并返回非 0，等于把"能定位故障依赖"的信息扔掉。

#### 4.2.2 `data` 字段逐项判据

| 字段 | 期望值 | 判据 / 备注 |
| --- | --- | --- |
| `data.status` | `"UP"` | 与 HTTP 200 同真同假。若 `status=UP` 但 HTTP=503（或反之）→ **契约被破坏，报后端 bug** |
| `data.service` | `"nexus-start"` | 固定值（配置 `nexus.health.service-name`）。不匹配 → 打到别的服务了 |
| `data.version` | 等于 `backend/pom.xml` 的 `<revision>`（**不要写死具体版本号**） | 取真源值：`grep -o '<revision>[^<]*</revision>' backend/pom.xml`。**专条见下** |
| `data.timestamp` | 见下方**专条**（原判据写死的正则会把容器内的形态判错） | 秒级、**无小数秒**。**不要断言时区**：容器内是 `Z`（零偏移）、Windows 本地直跑 `+08:00`，两者都合法 |
| `data.checks.postgres` / `.redis` / `.ollama` | 三项均 `"UP"` | **取值域只有 `UP` / `DOWN`**（后端刻意不复用 Spring `HealthStatus`，就没有 `OUT_OF_SERVICE`/`UNKNOWN`）。出现第三值 → 契约破坏，脚本应报错而非通过 |
| `data.checks` 的键数 | **恰好 3 个** | 多出未知键 → 契约已变更，报告而非静默通过 |

**`data.timestamp` 专条（2026-09-22 实测订正）**

原判据写的是正则 `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$`（**只认 `±HH:MM`**）。
但容器内的实测响应是 `"timestamp":"2026-09-22T12:59:02Z"` —— **零偏移在 ISO-8601 里就渲染成 `Z`**，
原正则**不匹配** ⇒ 照它实现的脚本会在容器内**假失败**（报"契约破坏"，而契约根本没被破坏）。
✅ 好消息：`scripts/check-health.sh:379` 本来就是按"**不断言时区**"实现的（注释里写着"容器内 Z=UTC、
本地直跑 +08:00 都合法"）—— **脚本没错，是本节这句话写窄了**。

正确判据（两种形态都接受）：

```bash
# 秒级、无小数秒；偏移量 Z（零偏移）或 ±HH:MM 都合法
printf '%s' "$TS" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(Z|[+-][0-9]{2}:[0-9]{2})$'
```

⚠️ 同类措辞在 `openapi.yaml` 的健康检查 schema 描述里也写着"容器内通常为 `+00:00`" —— 已一并订正。
**新写的契约一律按"`Z` 或 `±HH:MM` 都合法"表述**（阶段3 的 `createdAt` 就是照此写的）。

**`data.version` 专条（脚本最容易漏的判据）**

- **期望值 = `backend/pom.xml` 的 `<revision>`**（版本号**唯一真源**，发版只改这一行）。
  构建期由 Maven 资源过滤替换 `application.yml` 里的 `@project.version@` 占位符。
  取真源值（WSL 内、仓库根目录执行）：
  ```bash
  cd /mnt/c/wp/nexus-agent-workbench && grep -o '<revision>[^<]*</revision>' backend/pom.xml
  ```
  ⚠️ 真源是 `<properties>` 里的 `<revision>`，**不是**父 POM 的 `<version>` ——
  后者是 `${revision}` 字面量，直接 grep 它取不到版本号。
- **不要**把期望值写成某个具体版本号：`main` 与功能分支上的语义版本可以不同，写死换个分支就错。
- **必须 FAIL 的取值**：字面量 `@project.version@`（= 资源过滤失效）、或空串。
  这是**构建层缺陷**，不是环境问题 —— 脚本要给出"检查 `maven-resources-plugin` 过滤是否生效"的提示，
  别让用户以为是容器没起好。
- **建议判据写法**：断言 `^[0-9]+\.[0-9]+\.[0-9]+$` 且显式拒绝 `^@.*@$`，
  **并且**与上面从 `<revision>` 取到的值**比对相等**。
  semver 正则**只挡「占位符没被替换」这类格式缺陷**；要防「版本号改了、产物却没跟着变」，
  必须比对 `<revision>` —— 那是版本漂移的唯一判据（2026-09-15 实景：父 POM 0.2.1 / 子模块 0.2.0，
  构建成功且零警告，产物仍是旧的，详见 `docs/design/00-环境与部署.md` §5.1）。
- **交叉验证**：同一个占位符还出现在 `/actuator/info` → `info.app.version`（**同样等于 `<revision>`**）。
  两个出口都查一遍，可确认是"同一处坏掉"还是只有一处。

#### 4.2.3 取数与解析示例（WSL 内、仓库根目录执行）

> 端口一律从 `docker-compose/.env` 读（`BACKEND_PORT=8089`），**不要硬编码** —— `.env` 是端口唯一真源。

```bash
BACKEND_PORT=$(grep -E '^BACKEND_PORT=' docker-compose/.env | cut -d= -f2)

# -s 静默、--max-time 见 §4.3、不加 -f（503 也要 body）
# 用 \n%{http_code} 把状态码追加到 body 末尾，一次请求同时拿到两者
RESP=$(curl -s --max-time 10 -w '\n%{http_code}' "http://localhost:${BACKEND_PORT}/api/health")
HTTP=$(printf '%s' "$RESP" | tail -n1)
JSON=$(printf '%s' "$RESP" | sed '$d')
```

解析方式二选一：

- **有 `jq`（优先；先探测 `command -v jq`，WSL 宿主是否预装未核实）**：
  ```bash
  printf '%s' "$JSON" | jq -r '.code, .data.status, .data.checks.postgres, .data.checks.redis, .data.checks.ollama'
  ```
- **无 `jq` 的兜底（后端 Jackson 默认非美化输出、无多余空白，子串匹配可用）**：
  ```bash
  case "$JSON" in
    *'"checks":{"postgres":"UP","redis":"UP","ollama":"UP"}'*) echo 'checks: all UP' ;;
    *) echo "checks: NOT all UP -> $JSON" ;;
  esac
  ```
  这种子串匹配对字段顺序/空格敏感（**脆弱但够用**）；注意 `ollama` 排在最后这个顺序来自
  `HealthChecks` record 的组件声明顺序（postgres → redis → ollama），是有意固定并可断言的契约顺序。

### 4.3 轮询策略（超时 / 间隔 / 通过判据）

| 参数 | 建议值 | 依据 |
| --- | --- | --- |
| 单次超时 —— `/actuator/health` | `--max-time 5` | Boot 内建 indicator，通常 < 1s |
| 单次超时 —— `/api/health` | **`--max-time 10`** | 三个探测**串行**执行：postgres 受 Hikari `connection-timeout: 3000ms` 约束（`nexus.health.probe-timeout-ms: 2000` 只映射到 SQL 的 `setQueryTimeout`，连接获取另算）+ redis 2s + ollama 2s（连接/读取各 2s）≈ 最坏 7s |
| 单次超时 —— `/api/tags`（模型） | `--max-time 5` | Ollama 本地接口，秒回 |
| 轮询间隔 —— 依赖探活 | 前 30s 用 **2s**，之后 **5s** | `/api/health` 每次都会真实打三个依赖；< 1s 的高频轮询既无意义，又会在 PG 侧堆连接 |
| 轮询间隔 —— 模型拉取 | **10s** | 5GB 级别下载，频繁轮询无收益 |
| **通过判据** | **连续 2 次 HTTP 200 且 `code=0`，间隔 5s** | 见下 |
| 总超时 —— 依赖探活 | ≥ 180s | 与容器 `start_period` / `retries` 量级匹配 |
| 总超时 —— 模型拉取 | 30 min（可配） | 与设计文档 §4.3 第 7 步一致 |

**`/api/health` 的单次超时不要设 2~3s**：依赖全挂时后端本来就要 7s 左右才应答，
3s 超时会把"依赖挂了的正常 503"误报成"接口超时"——两者处置完全不同（前者查依赖，后者查后端进程）。

**为什么"一次 200 就通过"不够**（务必按连续 2 次实现）：

1. **探活是瞬时快照。** `docker compose start redis` 之后，Redis 刚恢复、后端连接池还在重建
   （`stop` 期间失效的连接要被淘汰重建），"一个 200 紧跟一个 503"的抖动是常见现象；
   单次判据会把"还在抖"读成"已就绪"。
2. **启动期有窗口。** backend 容器 `start_period: 60s`（依赖容器同理，`pg_isready` 通过 ≠ PG 完成恢复），
   `/api/health` 完全可能先 503 再 200。一次采样落在窗口内就会误判。
3. **成本极低。** 连续 2 次 + 间隔 5s 只多花 5 秒，换来对抖动的免疫，性价比最高。
4. **失败时不要立刻退出。** 应跑完全部检查项、最后汇总失败清单（草稿里"继续跑完 + 汇总表"的思路是对的，可保留），
   并打印**最后一次 `/api/health` 的 `data.checks`** —— 那是定位故障依赖的唯一可靠信息。

### 4.4 503 场景的可复现造法（脚本自测 / 阶段验收用例）

```bash
# 在 WSL 的 distro nexus-agent-workbench 内执行；compose 目录含 .env
cd /mnt/c/wp/nexus-agent-workbench/docker-compose
BACKEND_PORT=$(grep -E '^BACKEND_PORT=' .env | cut -d= -f2)   # 下面的 8089 只是默认值，脚本里一律用变量

# ① 基线：三项全 UP
curl -s --max-time 10 http://localhost:8089/api/health
# → {"code":0,"msg":"success","data":{"status":"UP","service":"nexus-start","version":"0.2.0",
#     "timestamp":"...","checks":{"postgres":"UP","redis":"UP","ollama":"UP"}}}

# ② 制造故障：停 redis
docker compose stop redis
curl -s --max-time 10 -o /tmp/h.json -w '%{http_code}\n' http://localhost:8089/api/health
# → 503
cat /tmp/h.json
# → {"code":20000,"msg":"依赖服务不可用：redis","data":{"status":"DOWN","service":"nexus-start",
#     "version":"0.2.0","timestamp":"...","checks":{"postgres":"UP","redis":"DOWN","ollama":"UP"}}}

# ③ 恢复
docker compose start redis
curl -s --max-time 10 http://localhost:8089/api/health
# → 应回到 200；若首次仍是 503，等待数秒重试（连接池需重建，属预期，非缺陷）
```

> 上面两处输出里的 `"version":"0.2.0"` 是**示例值，非真源；实际以 `/api/health` 的实际返回为准**（§4.2.2）。

断言清单（自测脚本时逐条对）：

- [ ] HTTP = **503**（不是 500，也不是 200）
- [ ] `code` = **20000**
- [ ] `msg` = `依赖服务不可用：redis`（**全角冒号 `：`**；多个依赖用**顿号 `、`** 连接，顺序固定 postgres → redis → ollama，
      例：`依赖服务不可用：postgres、redis、ollama`）
- [ ] `data.status` = `DOWN`，且 `data` **不为 null**（仍是完整报告）
- [ ] `data.checks.redis` = `DOWN`，另两项仍 `UP`（能精确定位到单个依赖）
- [ ] 容器层面：`nexus-backend` **不退出**（仍 `running`）；约 2 分钟后可能转 `unhealthy`（见 §4.1 结论 ①）

等价用例（换一个依赖，判据相同）：

- `docker compose stop postgres` → `msg` = `依赖服务不可用：postgres`
- `docker compose stop ollama` → `/api/health` 503 + `checks.ollama=DOWN`，
  **但 `nexus-backend` 容器保持 `healthy`** —— 这一条正是"两级都要查"的活教材。

### 4.5 模型就绪的独立判据（**ollama 健康 ≠ 模型就绪**）

> ⚠️ **2026-09-22 晚：期望模型清单已变更** —— 向量化模型由 `nomic-embed-text` 换为 **`bge-m3`**
> （起因见 `docs/design/03-RAG知识库.md` §0.3：中文检索召回不足，答案块在 587 块中排第 27 名）。
> ⇒ 本节所有"就绪"判据里的期望模型名，一律读作 **`qwen2.5:7b` + `bge-m3`**；唯一真源同步变为
> `docker-compose.yml` 的 `ollama-init` 命令与 `scripts/lib/probe.sh` 的默认清单（两处由 devops 同步）。
> 下面保留 nomic 时代的原文与 `:latest` 归一化陷阱的实例 —— **那部分与具体模型名无关，仍然适用**
> （新模型同样可能以 `bge-m3:latest` 出现）。

> **这是本次审查暴露出的最大盲区，务必按本节实现。**

已核实的事实（不是推测）：

- compose 里 `nexus-ollama` 的 healthcheck 是 `["CMD", "ollama", "list"]`；
  而**零模型时 `ollama list` 的退出码也是 0**（`docs/agent-log/20260911-ollama-init诊断.md` 已记录）
  → 容器 `healthy` 与"模型是否已拉取"**完全无关**。
- 后端 `/api/health` 的 `checks.ollama` 打的是 `GET /api/version`
  （见 `backend/nexus-start/src/main/java/com/nexus/start/probe/OllamaProbe.java`）
  → **进程活着就 UP，不校验模型**。
- 结论：**两个探活口同时绿灯时，`qwen2.5:7b` 与 `nomic-embed-text` 可能一个都没拉下来。**
  故障会推迟到阶段2/3 的 AI 调用时以深层 500 暴露 —— 比启动期报错更难排查（agent-log 有先例）。

**模型就绪的唯一判据：`GET /api/tags` 的 `models[].name`**

```bash
OLLAMA_PORT=$(grep -E '^OLLAMA_PORT=' docker-compose/.env | cut -d= -f2)   # 默认 11434
curl -s --max-time 5 "http://localhost:${OLLAMA_PORT}/api/tags"
# → {"models":[{"name":"qwen2.5:7b","model":"qwen2.5:7b","size":...},
#              {"name":"nomic-embed-text:latest","model":"nomic-embed-text:latest","size":...}]}
```

- **就绪**：`models` 数组里**同时**存在 `qwen2.5:7b` 和 `bge-m3`（2026-09-22 晚之前为 `nomic-embed-text`）两项。
- **未就绪**：`{"models":[]}` 或 `models` 字段缺失 → WARN + 继续（或按 30 min 轮询等待），
  **不要报成"ollama 挂了"**，两者处置完全不同。
- 期望模型名的唯一来源：`docker-compose/docker-compose.yml` 的 `ollama-init` 命令
  （`for model in qwen2.5:7b nomic-embed-text`）。改模型清单要同步改脚本。
- **`:latest` 归一化陷阱（已出过真实事故）**：无 tag 拉取的模型在 `/api/tags` 与 `ollama list` 中显示为
  `nomic-embed-text:latest`。用 `grep -qx "nomic-embed-text"` 这类**整字段精确匹配会永不命中**
  （agent-log 记录的正是这个 bug）。正确写法二选一：
  - 归一化后比较（推荐）：`jq -r '.models[].name' | sed 's/:latest$//'`，再逐个 `grep -qx`；
  - 子串匹配（够用但不严谨）：`grep -q 'nomic-embed-text'`。
- 手动补拉（提示用户时的命令）：`docker exec nexus-ollama ollama pull qwen2.5:7b`。
- 轮询间隔 10s、总超时 30 min（§4.3）；脚本应在这条超时时给出**断点续拉指引**，而不是笼统报错。

### 4.6 现阶段不可用 / 不可信的检查项（避坑清单）

> 来源：草稿 `docs/drafts/环境检查脚本.md` 逐项复核。以下各条**按现状实现会得到错误结论**，须按下表改写。

| 草稿里的检查项 | 现状（已核实） | 脚本应如何处理 |
| --- | --- | --- |
| `pg_extension` 查 pgvector 已安装 | `db-patch/` 目录**尚不存在**（0.2 未开工），代码库内**没有任何 `CREATE EXTENSION vector`**；镜像 `pgvector/pgvector:pg16` 只保证扩展**可加载**，不等于已安装。**（2026-09-22 订正：`CREATE EXTENSION vector` 已由 `db-patch/202609131000` 执行，扩展已安装；RAG 的建表补丁依赖它）** | 查询：`SELECT installed_version FROM pg_available_extensions WHERE name='vector'` —— 有行 = 扩展可用，`installed_version IS NOT NULL` = 已安装。⚠️ **阶段3 起 `installed_version IS NOT NULL` 是硬判据**（RAG 落地后 `t_kb_chunk.embedding` 依赖 `vector` 类型，扩展没装上则建表/入库/检索全挂）；"扩展可用"那半只在"尚未迁移"的场景下才够用 |
| `t_db_patch` 记录数 | 该表由 PatchCli 在执行迁移时 `CREATE TABLE IF NOT EXISTS` 引导创建；0.2 未开工 → **表不存在**，直查会报 `relation "t_db_patch" does not exist`（极易被误读成"迁移失败"） | 先探存在性：`SELECT to_regclass('public.t_db_patch') IS NOT NULL`；为 false → **SKIP（不计失败）**；为 true 才查记录数与文件名/checksum 一致性 |
| 业务表 `tenant_id` 字段 | 阶段1（多租户）未开工，**目前没有任何业务表** | SKIP（或直接从清单移除），阶段1 后再补 |
| `/api/ai/ping`、AI 网关接口 | **不存在**（阶段2 才实现）。当前业务接口只有 `GET /api/health` 一个 | 删除该项；AI 链路可用性现阶段用 §4.5 的模型就绪 + `checks.ollama` 覆盖 |
| 前端反代 `curl localhost:8088/api/actuator/health` | nginx 只反代 `location /api/`，`/api/actuator/health` 会原样转到后端 → 后端无此映射 → **404 + `code:40400`**；而 `/actuator/health`（不带 `/api`）会命中 `location /` 的 `try_files $uri $uri/ /index.html` → **200 但返回的是 HTML** | 前端反代检查固定用 `http://localhost:${FRONTEND_PORT}/api/health`，并校验拿到的是完整 `Result` JSON（**不要**用 `/actuator/*` 走前端端口） |
| `nexus-frontend` 没有 healthcheck（草稿问题 2） | **已过时**：`docker-compose.yml` 已给 `nexus-frontend` 配了 `test: ["CMD-SHELL", "wget -qO- localhost"]` | 无需补；`docker inspect` 查它的 `Health.Status` 即可。前端容器另有 `depends_on: nexus-backend: service_healthy` 门控 |
| Redis 持久化 `config get appendonly` | 当前以默认配置启动（compose 未挂 `redis.conf`、未传 `--appendonly`），**AOF 默认关闭** | 若要断言，先确定预期值（现阶段预期就是 `no`）；**不要**写成"必须 yes"的失败判据 |
| Ollama GPU / `nvidia-smi` | 已定 CPU 推理（compose 中 GPU 直通为注释态） | 删除或标 N/A，否则在目标机器上恒 FAIL |
| 内存 `free -h`（< 8GB 警告）；Docker daemon / compose 插件 / 端口占用 / `docker compose config --quiet` | 可用 | 保留 |

**另外两条环境事实**（写脚本时会用到）：

- `nexus-backend` 运行镜像是 `eclipse-temurin:17-jre-alpine`（Dockerfile 只装了非 root 用户，无额外工具），
  **没有 `curl`**，只有 BusyBox `wget` —— 这正是 compose healthcheck 写 `wget -qO-` 的原因。
  要在该容器内探测请一律用 `wget -qO-`。
  已知 `nexus-builder` 镜像（Ubuntu 基底）装有 `curl`；其余镜像**不要假设**有 curl，脚本里先 `command -v curl` 探测。
- 宿主端口唯一真源是 `docker-compose/.env`（`PG_PORT=5432` / `REDIS_PORT=6379` / `OLLAMA_PORT=11434` /
  `BACKEND_PORT=8089` / `FRONTEND_PORT=8088`）—— 脚本一律读它，不要硬编码。

### 4.7 复核发现的偏差（如实记录，未修改任何实现）

1. **`/api/health` 的最坏耗时与注释不符。** `HealthServiceImpl` 类注释写"单次探测超时上限 2s，
   三个依赖最坏耗时 6s"，但 `application.yml` 里 Hikari `connection-timeout: 3000ms` 管的是**连接获取**，
   `nexus.health.probe-timeout-ms: 2000` 只映射到 PG 的 `Statement.setQueryTimeout` ——
   postgres 单项最坏 3s（连接）+ 2s（查询），三项串行最坏约 **7s**。
   对脚本的影响已按 §4.3 的 `--max-time 10` 兜住；注释与实现的偏差记在此处备查（改注释属 0.4 代码范畴，本次不动）。
2. **"Ollama 健康检查只打 `/api/version`"这句要写准。** 实际是两个不同入口、两套机制：
   compose 的 `nexus-ollama` healthcheck 是容器内 `ollama list`（CLI，退出码判据），
   后端的 `checks.ollama` 才是 `GET /api/version`（HTTP）。**两者都不校验模型**，
   §4.5 的结论（模型就绪必须单独查 `/api/tags`）不受影响，但引用出处别写错。
3. **`.env` 的库凭据目前只喂给 postgres 容器，没喂给 backend 容器。**
   compose 的 `nexus-postgres` 用 `${PG_USER:-nexus}` / `${PG_PASSWORD:-nexus123}` / `${PG_DB:-nexus}`，
   而 `nexus-backend` 的环境变量块里**还没有** `POSTGRES_*` / `REDIS_*`（compose 内注释已标注"0.4 落码后按需补充"），
   后端走的是 `application.yml` 的默认值（恰好相同，所以此刻能连上）。
   → **若 check-env 提示用户改 `.env` 里的 `PG_PASSWORD`，后端会连不上、`checks.postgres` 变 DOWN。**
   `check-env.sh` 若要校验凭据一致性，须把这条不对等关系考虑进去（或提示"改凭据需同时给 backend 容器补环境变量"）。
4. **`openapi.yaml` 与本文件一致**，本轮复核未发现二者与实现之间的字段级出入：
   字段名 / 取值域（`UP`|`DOWN`）/ 503 保留 `data` / 错误码 `20000` 均逐项对齐。

---

## 5. 认证接口（阶段1）

> 契约来源：`docs/design/01-多租户与认证.md` §5；实现：`backend/nexus-module-system`
> （`AuthController` / `AuthServiceImpl` / `JwtAuthenticationFilter`）。
> 三个接口的鉴权要求**不同**：`login` 免鉴权（白名单），`logout` 与 `me` 需要 `Authorization: Bearer <token>`。

### 5.1 认证机制（前端必读的一句话版）

1. 登录成功 → `data.token`（JWT，有效期 `data.expiresIn` 秒，默认 7200）；
2. 之后每个请求带上 `Authorization: Bearer <token>`；
3. 服务端每次请求做三步校验：**JWT 签名与有效期** → **Redis 白名单里该 `jti` 是否还在** →
   用 token 里的 `tenantId` 建立本次请求的租户上下文（后续所有 SQL 自动带 `tenant_id` 条件）；
4. 任一步失败 → HTTP **401** + 统一 `Result`（`code` = 40100 / 40101 / 40102），
   前端处置：**清 token → 跳 `/login?redirect=<当前路径>`**；
5. **登出 = 服务端删掉 Redis 里那条记录** → 同一个 token **立刻**失效（不必等它自然过期）。
   这是"纯 JWT 做不到登出"的补丁，也是为什么登录态依赖 Redis。

### 5.2 `POST /api/auth/login` —— 登录

| 项 | 值 |
| --- | --- |
| URL | `/api/auth/login` |
| Method | `POST` |
| 请求头 | `Content-Type: application/json`（必需）；无鉴权 |
| Request Body | `{"username":"...","password":"..."}`（两个字段都必填） |
| 成功 | HTTP 200 + `code=0`，`data` 见下 |
| 失败 | HTTP **200** + `code=10100`（用户名或密码错误）/ `10101`（账号停用）/ `10102`（租户停用） |

请求体字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `username` | string | 是 | 登录名，**全局唯一**（登录只收用户名，不收租户编码：先查出用户，才知道它属于哪个租户） |
| `password` | string | 是 | 明文口令（服务端只做 BCrypt 比对；空值按 `10100` 处理） |

响应 200：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 7200,
    "user": {
      "userId": 1,
      "username": "admin",
      "nickname": "默认租户管理员",
      "tenantId": 1,
      "tenantCode": "default",
      "tenantName": "默认租户"
    }
  }
}
```

`data` 字段定义（`LoginResponse`）：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `data.token` | string | 是 | JWS 紧凑序列化串 |
| `data.tokenType` | string | 是 | 固定 `Bearer`。前端拼 `Authorization: ${tokenType} ${token}`（用返回值而非硬编码，行为等价但更贴合 RFC 6750 的语义；后端目前只签发这一种） |
| `data.expiresIn` | integer | 是 | 有效期（秒），与 Redis 白名单 TTL 一致 |
| `data.user` | object | 是 | 当前用户信息，见下 |

`data.user` 字段定义（`UserInfoVO`，与 `GET /api/auth/me` 的 `data` **同构**）：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `userId` | integer | 是 | 用户 ID |
| `username` | string | 是 | 登录名 |
| `nickname` | string \| null | 是（可为 null） | 展示名 |
| `tenantId` | integer | 是 | 租户 ID |
| `tenantCode` | string | 是 | 租户编码（`default` / `demo`） |
| `tenantName` | string | 是 | 租户名称（前端展示用） |

失败响应示例（HTTP **200**，业务码非 0）：

```json
{ "code": 10100, "msg": "用户名或密码错误", "data": null }
```

> ⚠️ 前端**不要**按 `msg` 或 `code` 去区分"用户不存在"与"密码错误" —— 服务端刻意不区分（防账号枚举），
> 两者的响应完全一致。`10101`（账号停用）只在**口令正确**之后才可能返回。

### 5.3 `POST /api/auth/logout` —— 登出

| 项 | 值 |
| --- | --- |
| URL | `/api/auth/logout` |
| Method | `POST` |
| Request Body | **无**（不要发 body，也**不要**设 `Content-Type: application/json` —— 接口未声明 consumes，带了也无害） |
| 请求头 | `Authorization: Bearer <token>`（必需） |
| 成功 | HTTP 200 + `code=0`、`data=null`（Redis 中该 `jti` 立即失效） |
| 失败 | 无 token / 已失效 → HTTP **401** + `40100` / `40101` |

```json
{ "code": 0, "msg": "success", "data": null }
```

登出后**用同一个 token** 再调任何受保护接口都会得到 401 —— 这是验收项「登出即失效」（`TC-01-1.2-5`）的判据。

### 5.4 `GET /api/auth/me` —— 取当前用户

| 项 | 值 |
| --- | --- |
| URL | `/api/auth/me` |
| Method | `GET` |
| 请求头 | `Authorization: Bearer <token>`（必需） |
| 成功 | HTTP 200 + `code=0`，`data` = §5.2 的 `user` 对象 |
| 失败 | 无 token / 已失效 / 已过期 → HTTP **401** + `40100` / `40101` / `40102` |

**为什么需要它**（不是"多余的一个接口"）：登录态以 Redis 白名单为准，本地存着 token
不代表服务端仍认（可能在别处登出、或 Redis 被清）。前端刷新页面后调它做一次真实校验，
避免"假登录态"。它也是除 401 之外的第二道闸：
若 token 里的租户与用户实际所属租户不一致，查询会自动带 `tenant_id` 条件 → 查不到 → 401。

### 5.5 开发态演示账号（**仅限 dev 环境**）

种子数据由 `db-patch/202609131010_初始化用户表.sql` 落库：

| 用户名 | 明文口令 | 所属租户 | 昵称 |
| --- | --- | --- | --- |
| `admin` | `admin123` | `default`（默认租户） | 默认租户管理员 |
| `demo` | `demo123` | `demo`（演示租户） | 演示租户用户 |

- 口令哈希由 BCrypt 生成（`$2a$10$...`，10 轮、随机盐），与 Spring `BCryptPasswordEncoder` 的默认参数一致；
  生成方式见补丁文件头部注释（可复现）。
- **哈希是否正确的最终判据是"登录接口实跑成功"**，不是"看起来像 BCrypt"。
- 两个租户各一个账号，是为了现场演示**租户隔离**：用 A 的 token 查不到 B 的数据。
- ⚠️ 这是**随仓库公开的演示凭据**，仅用于本地/演示环境；任何真实环境都必须换掉
  （连同 `nexus.jwt.secret`，见 `application.yml` 注释）。

### 5.6 前端对接示例（TS，对齐现有 `request.ts`）

```ts
// 登录：拦截器已解包 Result，拿到的是 data
const data = await login({ username, password })   // LoginData
setToken(data.token)                                // 存进 Pinia store（手写 localStorage，见 D7）

// 之后每个请求由请求拦截器统一注入。
// 两点与 request.ts 的实际实现对齐（改动前请同步改那边）：
//   ① 在拦截器函数体内**动态 import** 取 store —— request.ts → stores/user.ts → api/auth.ts → request.ts
//      是一个真环，顶层静态 import 会让它在模块初始化期闭合；
//   ② 前缀取 data.tokenType，不硬编码 Bearer（见 §5.2 的字段说明）。
config.headers.Authorization = `${userStore.tokenType} ${userStore.token}`

// 401：清 token + 跳登录（并发多请求同时 401 时只跳一次 —— 需防抖）
if (status === 401) {
  userStore.clear()
  router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
}
```

---

## 6. 对话接口（阶段2）

> 契约来源：`docs/design/02-统一AI网关.md` §3（已确认设计）；实现：`backend/nexus-module-ai`
> （`ChatController` / `ChatService` / `ModelRouter` / `OllamaService` / `DeepSeekService`）。
> ⚠️ 这是本项目第一个**流式**接口：响应体不是一次性的 JSON，而是 `text/event-stream` 事件流。
> 但**每一帧的 `data` 仍是统一响应体 `Result<T>`**（设计决策 D4）——
> 这**不是**给「所有 API 返回 `Result`」开例外，而是把统一响应体搬进了事件帧，前端复用同一套解包/成功判定。

### 6.1 `POST /api/chat/stream` —— 请求

| 项 | 值 |
| --- | --- |
| URL | `/api/chat/stream`（前端 `baseURL='/api'` + `url='/chat/stream'`） |
| Method | `POST`（消息内容在 body：不进 URL、也不进访问日志） |
| `consumes` | `application/json`（显式声明） |
| `produces` | `text/event-stream`（显式声明） |
| 鉴权 | **必需** `Authorization: Bearer <token>`；**不在白名单**（漏 token → HTTP 401 + `40100`） |
| 成功 | HTTP **200** + `Content-Type: text/event-stream` + 事件流（§6.2） |

请求头两个都要带，缺一不可（前端不用 axios，**这两个头要自己补**，见设计 §5.1-2）：

| Header | 缺了会怎样 |
| --- | --- |
| `Content-Type: application/json` | 后端按契约声明了 `consumes` → **HTTP 415 + `40002`**（⚠️ **2026-09-22 订正**：此处曾写"响应体不是 `Result`" —— 自 2026-09-20 起 415 也有专门出口、返回的是统一 `Result`，`msg` 正是"请求格式不支持，请使用 application/json"，对本接口是**准确**的诊断；见 §1.2） |
| `Authorization: Bearer <token>` | HTTP 401 + `40100` |

请求体：

```json
{
  "messages": [
    { "role": "user",      "content": "你好" },
    { "role": "assistant", "content": "你好！有什么可以帮你的？" },
    { "role": "user",      "content": "介绍一下你自己" }
  ],
  "modelType": "OLLAMA"
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `messages` | array | 是 | 会话历史**全量上送**（无状态后端，不落库）。至少 1 条，最后一条必须是 `role=user` |
| `messages[].role` | string | 是 | `user` / `assistant`。本阶段**不支持** `system` —— 传其他值（含 `system`）都是 `40001` |
| `messages[].content` | string | 是 | 非空、去空白后非空 |
| `modelType` | string | **否** | `OLLAMA` / `DEEPSEEK`；**`null` 或缺省 = 由后端决定**（本轮解析为默认模型）。未知取值 → `10200` |

#### 三条补充约束

1. **允许连续同角色。** 失败或停止后前端若回滚该轮 assistant 消息，数组会以 `role=user` 结尾，
   下一次提问再 push 一条 `user` 就成了连续两条 —— 这是**契约允许**的（两个上游都接受），
   前端不必为此做特殊处理。
2. **`modelType` 用 `String` 承接、由后端 `ModelType.parse()` 转枚举。** 后端 DTO **不**把该字段声明为枚举：
   Jackson 遇未知值会抛 `HttpMessageNotReadableException` → 落兜底处理器 → **500 + 50000**，
   而不是契约要的 `10200`。
3. **`content` 上限：单条 8KB、总长 64KB**（按 UTF-8 字节数计，超出 → `40001`）。
   防的是误贴大段文本把上游上下文撑爆 —— 那种情况下上游报错会把排查方向带偏。

### 6.2 事件帧格式（四种，顺序固定）

```
event: meta
data: {"code":0,"msg":"success","data":{"modelType":"OLLAMA","model":"qwen2.5:7b","servedBy":"user-selected"}}

event: delta
data: {"code":0,"msg":"success","data":{"content":"你"}}

event: delta
data: {"code":0,"msg":"success","data":{"content":"好"}}

event: done
data: {"code":0,"msg":"success","data":{"finishReason":"stop","deltaCount":2,"durationMs":842}}
```

| 事件名 | 时机 | `data` 载荷 | 说明 |
| --- | --- | --- | --- |
| `meta` | **第一帧，仅有且必有一帧**（**成功路径**上；见下方注） | `modelType`、`model`（**真实模型名**）、`servedBy` | 见下方**时序规则 1**。`servedBy` 本轮取值 `user-selected` / `default` |
| `delta` | 0~N 帧 | `content`（增量文本片段，**不是累积全文**） | 前端做**追加**，不做替换 |
| `done` | 正常结束 | `finishReason`、`deltaCount`、`durationMs` | `finishReason`：`stop`（模型正常结束）/ `length`（触达 token 上限）/ `timeout`（**服务端软上限**截断） |
| `error` | 异常结束 | `{"deltaCount": N}` | **形状定死为对象**（不是 `null`）：前端靠它知道"已经吐了多少字" |

> ⚠️ **两处最容易读错的边界**（前端解析器必须容忍，否则会把正常情况当成故障）：
> 1. `meta` 的「必有」只对**成功路径**成立：**上游一个字都没吐就失败**时，这条流只有一帧 `error`
>    —— `meta` 的语义是"这次用了哪个模型"，而上游根本没给出响应。**不要断言"第一帧一定是 meta"**。
> 2. **服务端软上限截断走 `done`、不走 `error`**（`finishReason=timeout`，`code=0`）：它是"我们主动收尾"，
>    不是失败；失败形态表见 §6.3。

`data` 字段定义：

| 帧 | 字段 | 类型 | 说明 |
| --- | --- | --- | --- |
| `meta` | `data.modelType` | string | `OLLAMA` / `DEEPSEEK`（枚举名） |
| `meta` | `data.model` | string | 上游**真实模型名**：`qwen2.5:7b` / `deepseek-chat` |
| `meta` | `data.servedBy` | string | `user-selected`（用户选的）/ `default`（用户没选，后端取默认） |
| `delta` | `data.content` | string | 增量片段；单帧可能是**一个汉字** |
| `done` | `data.finishReason` | string | `stop` / `length` / `timeout` |
| `done` | `data.deltaCount` | integer | 本次共发出多少帧 `delta` |
| `done` | `data.durationMs` | integer | 从开始到结束的毫秒数 |
| `error` | `data.deltaCount` | integer | 已经发出的 `delta` 帧数（可能为 `0`，此时 `data` 仍是对象而非 `null`） |

> **机器可读版（`openapi.yaml`）的对应关系**：上表四种帧各有一个 schema —
> `ChatStreamMeta` / `ChatStreamDelta` / `ChatStreamDone` / `ChatStreamError`
> （OpenAPI 3.0.3 无法把 schema 挂到 SSE 事件上，故对应关系写在这里与 `openapi.yaml` 的
> `/api/chat/stream` 描述里各写一份）。**本节与这四份 schema 必须逐项一致**：任何一帧改了字段，
> 两处同改；少一个 schema，就等于"按 openapi 生成类型的人会漏掉那种帧"。
> `data.servedBy` 在 openapi 里刻意是 `string` 而不是 enum（取值集合会随自动路由扩大，见设计 §10.1）。

#### 三条时序规则

1. **`meta` 的发出时机**：「开流前」指的是**对客户端开流前**、而非「调用上游前」。具体定为：
   **在首次取到上游响应（首个分片）之后、第一个 `delta` 之前发出，且只发一次。**
   这样既满足"模型必须在有内容前定下"，也不会因为上游连接慢而让客户端干等。
2. **终止帧唯一**：`done` **xor** `error`，有且仅有一个；服务端发完终止帧立即 `complete()`。
3. **SSE 字段解析**（跨端协议边界，前后端都要遵守）：
   - 取 `event:` / `data:` 后的值时，**先剥掉至多一个前导空格**再 trim。各家实现
     （含 Spring 的 `SseEmitter`）是否带空格并不统一 —— 按示例字面写 `slice(6)` 会因一个空格
     **匹配不到任何事件名**，表现为页面一直空白而 curl 完全正常。
   - 行终止符按规范允许 `\r\n` / `\r` / `\n` 三种；零成本兜底：入缓冲区时先 `replace(/\r\n/g, '\n')` 归一。

#### 一条解析前提（破坏它立刻出坑）

**`data:` 行里不会出现真换行。** 因为每帧 `data` 都是 JSON，Jackson 会把内容里的换行序列化成
`\n` 两个字符、引号转成 `\"` —— **所以换行不需要额外转义**（手写解析器的前提）。
⚠️ 两个前提一旦破坏立刻出坑：① 若 `delta` 改成发裸文本；② **绝不能为调试开启 Jackson 美化输出**
（`indent-output` 会往 `data:` 里塞真换行）。当前 `application.yml` 无任何 jackson 配置 = 安全默认值。

### 6.3 失败形态总表（**全异步口径**）

`SseEmitter` 返回的那一刻，HTTP 响应头（`200` + `text/event-stream`）**已经发出去了**。
据此把所有失败分成两侧：

- **开流前**（控制器**尚未返回** emitter）→ 走既有 `GlobalExceptionHandler`，返回正常 `Result<T>` + 对应 HTTP 状态码。
- **开流后**（emitter 已交给容器）→ 状态码无法再改，**一律只能写 `event: error` 帧**。

| 失败点 | 时机 | 载体 | HTTP | code |
| --- | --- | --- | --- | --- |
| 无 token / 过期 / 伪造 | 开流前 | `Result` | 401 | `40100` / `40102` / `40101`（过滤器出口，见 §1.2） |
| 请求体校验失败 / JSON 畸形 | 开流前 | `Result` | **200** | `40001` |
| `modelType` 取值未知 | 开流前 | `Result` | **200** | `10200` |
| **模型线程池已满** | 开流前 | `Result` | **503** | `20100` |
| **上游模型不可达 / 超时** | 开流后 | `event: error` 帧 | *(已是 200)* | `20100` |
| **上游中途报错** | 开流后 | `event: error` 帧 | *(已是 200)* | `20100` |
| **服务端软上限截断** | 开流后 | `event: done` 帧 + `finishReason=timeout` | *(已是 200)* | **`0`**（不是失败） |
| 未预期的内部异常 | 开流后 | `event: error` 帧 | *(已是 200)* | `50000` |
| 客户端主动断开 | — | 无（连接已没了） | — | 服务端在 `send()` 抛异常时取消上游并释放资源 |

> **`20100` 有两种载体**（503 + `Result`、200 + `error` 帧），这是**有意的**：池满是本服务侧的、
> 同步可判的，能给出真正的 503；上游不可达只在工作线程上才暴露，那时响应头已发。
> 前端**两种都要处理**（成本极低：先看 `Content-Type`，再按帧解析）——
> 判断响应类型要用 `includes('application/json')`：**失败形态的 `application/json` 实测带 `;charset=UTF-8`（2026-09-20 实测），而成功形态的 `text/event-stream` 不带**（2026-09-21 实测）—— 两边形态不一致，全等比较会误判。
> ⚠️ **SSE 那半刻意不加 charset**：SSE 规范里 `charset` 是"仅为兼容遗留服务端"的可选参数，事件流恒为 UTF-8；本契约声明的就是裸 `text/event-stream`（§6.1），实现与契约一致。
> **为什么不再加一个码区分池满**：两者的用户动作相同（稍后重试），而排查方向已由 HTTP 状态码区分开
> （503 且有 `Result` = 本服务；200 + `error` 帧 = 上游）。

**流的三个出口**（前端任一即收尾并复位按钮）：收到 `done`、收到 `error`、
**或 `reader.read()` 返回 `done`（EOF）**。第三项不可省：后端崩溃或 nginx 断流时，连接会在
**没有任何终止帧**的情况下 EOF，若只认前两个，页面会永久停在"生成中"。EOF 且无终止帧时按 `error` 展示通用文案。

### 6.4 错误码增量（**不复用**既有值）

| code | 常量（后端 `ResultCode`） | 配套 HTTP | `msg` | 归属 |
| --- | --- | --- | --- | --- |
| 40001 | `PARAM_INVALID` | **200** | 请求参数不合法 | 4xxxx 请求侧 |
| 10200 | `CHAT_MODEL_UNSUPPORTED` | 200 | 不支持的模型类型 | 1xxxx 业务 |
| 20100 | `CHAT_UPSTREAM_UNAVAILABLE` | 503 / *流内* | 模型服务暂时不可用，请稍后重试 | 2xxxx 可用性 |

**`40001` 为什么配套 HTTP 200 而不是 400**：§1.2 的表格已把「参数不合法」明确归入"业务失败 → HTTP 200"，
并给了理由（业务失败不污染前端的 axios 失败分支）；`openapi.yaml` 头部同样只列举了 401/404/500/503。
选 400 就必须**在同一次改动里**改这两份已发布契约 —— 收益不抵成本。**保持 200 + 40001，零契约改动。**

### 6.5 curl 验证

> ⚠️ **端口必须从唯一真源取，不要写死** —— `.env` 是端口唯一真源（`docker-compose/.env` 的 `BACKEND_PORT`）。

```bash
# 前置：进入仓库根目录；后端宿主端口从 docker-compose/.env 取
cd /c/wp/nexus-agent-workbench
BACKEND_PORT=$(grep -E '^BACKEND_PORT=' docker-compose/.env | cut -d= -f2)

# ① 拿 token（账号见 §5.5 开发态演示账号）
TOKEN=$(curl -s -X POST "http://localhost:${BACKEND_PORT}/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

# ② 流式对话（-N 关掉 curl 自身缓冲，否则看不出增量）
curl -N -X POST "http://localhost:${BACKEND_PORT}/api/chat/stream" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"用三句话介绍杭州"}],"modelType":"OLLAMA"}'
```

**判据**：`meta` 帧立刻出现，随后 `delta` 帧**逐条随打随出**（不是等几秒后一次性倾泻）
—— 这就是「可见增量分片」的全部含义。

> `grep -o '"token":"[^"]*"'` 不会误命中 `"tokenType":"Bearer"`（正则要求 `token` 后紧跟引号）。
> ⚠️ **SSE 的验收与排查一律走前端端口 8088（nginx）**：nginx 默认会缓冲上游响应，
> 不关 `proxy_buffering` 时会退化成"一次性返回"，而**直连后端端口与后端日志都完全正常**；
> 反过来，用 Vite dev（5173）判定"SSE 坏了"也不成立。两条链路的行为差异见设计文档 §6.5。

---

## 7. 知识库接口（阶段3）

> 契约来源：`docs/design/03-RAG知识库.md` §3（已确认设计）；实现：`backend/nexus-module-ai`
> 的 `com.nexus.module.ai.rag` 包（`KbDocumentController` / `KbAskController` / 两个 Service）。
> **四个接口全部同步**：没有 SSE、没有轮询、没有"已受理"这类中间态 —— 每个失败点都有确定的载体与状态码（§7.7）。
> 本节结构：文档管理三个接口（§7.1~§7.3）→ 问答接口（§7.4）→ 两个响应结构（§7.5~§7.6）→
> 失败形态总表（§7.7）→ 错误码增量（§7.8）→ curl 验证（§7.9）。
> **编号约定**：本节 §7.N 与设计文档 `docs/design/03-RAG知识库.md` §3.N **一一对应**
> （沿用阶段2「契约 §6.N ↔ 设计 §3.N」的惯例）—— 后续阶段的新增章节请照此办理，
> 这样"设计里读到哪一节、契约里查哪一节"是机械查找，不需要记。
> ⚠️ **本章节为新增，§1~§6 的编号与内容一律未动**（§4 被 `scripts/` 三个脚本按编号引用），
> 原「变更记录」顺延为 §8。

**三条边界（前端必读的一句话版，与页面顶部那条 `el-alert` 同源）**：

1. **一个租户一个知识库**：检索范围 = 当前租户的全部文档；没有"知识库"这一层对象，
   也没有"只在这几个文档里找"的参数（多知识库是 backlog，见设计 §10.2）。
2. **单轮问答**：一次提问独立检索，不带会话历史 —— 追问"那前年呢"不会有上下文（设计决策 D10）。
3. **只支持 TXT + PDF，且不保存原始文件**：Word 是 backlog（设计 §10.1）；
   原件不落盘 ⇒ **改分块参数后必须重传文档**（§7.1 末尾）。

### 7.1 `POST /api/kb/documents` —— 上传文档

| 项 | 值 |
| --- | --- |
| URL | `/api/kb/documents`（前端 `baseURL='/api'` + `url='/kb/documents'`） |
| Method | `POST` |
| `consumes` | `multipart/form-data`（显式声明） |
| `produces` | `application/json`（显式声明） |
| 鉴权 | **必需** `Authorization: Bearer <token>` |
| 成功 | HTTP **200** + `code=0`，`data` 为 `KbDocumentVO`（§7.5） |

请求（multipart 表单，**只有一个字段**）：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `file` | file | 是 | 单个文件；扩展名 `.txt` / `.pdf`（**大小写不敏感**）；**≤ 10MB**（上限真源 = `spring.servlet.multipart.max-file-size`，超限 → `40003`） |

**响应不是"已受理"，而是"已入库"**：接口返回 200 时，解析 → 分块 → 向量化 → 入库**已经全部完成**
（设计决策 D7 的同步口径）。`data.chunkCount` 即本次写入的分块数 —— 前端刷新列表即可看到。

三条前端必须遵守的约定（踩了就是 `40001` 或一次超时误判）：

1. **字段名必须是 `file`**：写成 `upload` 之类 → 框架抛 `MissingServletRequestPartException` → **`40001`**。
2. **上传必须让请求头是带 boundary 的 `multipart/form-data`** —— ⚠️ **2026-09-22 实测订正**：
   本条曾写成"不要手工设置 `Content-Type`，axios 1.x 会主动删掉该请求头"，**那句不成立**
   （走查时上传报 `HTTP 415 + 40002` 就是它导致的）。axios 1.20 的真实行为：`request.ts` 的**实例默认头**
   `Content-Type: application/json` 会让 axios 把 `FormData` **转成 JSON 字符串**
   （`lib/defaults/index.js`：`isFormData(data)` 为真时 `return hasJSONContentType ?
   JSON.stringify(formDataToJSON(data)) : data` ⇒ body 变成 `{"file":{}}`，**文件字节根本不会发出**），
   于是"`data` 是 `FormData` 就删掉该头"那段（`lib/helpers/resolveConfig.js`）**永远不会执行**
   ⇒ 请求头留在 `application/json` ⇒ 后端按 `consumes` 在**映射阶段**拒收 ⇒ 用户读到
   "请求格式不支持，请使用 **application/json**"，而正确动作其实是"让浏览器带上 boundary"。正确做法：
   ① **上传请求显式声明 `headers: { 'Content-Type': 'multipart/form-data' }`** —— axios 的浏览器适配器
   随后会把该头删掉、由浏览器补 `; boundary=…`（`xhr` 走 `resolveConfig.js`；`fetch` 见
   `lib/adapters/fetch.js` 的 "delete it so fetch can set it correctly with the boundary"）；
   ② 或在拦截器里对 `FormData` 请求摘掉该头。
   **自己拼 `; boundary=…` 同样是错的**（那个值只有浏览器知道）；本版 axios 会连它一起摘掉，
   所以"没炸"是 axios 兜底、不是写法正确 —— 绝不要这么写。
   （用 `el-upload` 时另有一条：它的默认 XHR **不走 axios 拦截器**，必须用 `:http-request` 自定义，
   否则 401 不跳登录页、`Result` 不解包 —— 见设计 §5.1-1。）
3. **超时 ≠ 失败**：上传在**请求线程**上同步完成（数十秒量级，取决于文件大小与 CPU），
   前端必须逐请求覆盖超时（**上传 300s**、问答 120s —— 实测验收夹具 587 块 ≈ 80~90 秒，120s 太贴边；
   `timeout` 的取值以设计 §5.1-4 为准），且超时文案要写成
   **"请求超时，服务端可能仍在处理，请刷新列表确认后再重试"** ——
   直接重传会在列表里留下两份同名文档（重名不去重是**已知行为**，设计 §10.6）。

⚠️ **改分块参数后必须重传文档**：本轮不保存原始文件（决策 D8），已入库的文档无法重新分块 ——
改 `nexus.ai.rag.chunk-size` / `chunk-overlap` 只对**之后上传**的文档生效。

### 7.2 `GET /api/kb/documents` —— 文档列表

| 项 | 值 |
| --- | --- |
| URL | `/api/kb/documents` |
| Method | `GET` |
| `produces` | `application/json` |
| 鉴权 | **必需** |
| 成功 | HTTP 200 + `code=0`，`data` = `{ "items": [KbDocumentVO...], "total": <int> }`（§7.5） |

- **只返回当前租户的文档**：`tenant_id` 条件由 `TenantLineHandler` 自动注入，业务 SQL 里不手写（设计决策 D11）。
  跨租户的表现是"看不见"，不是报错 —— 用 `demo` 账号看不到 `admin` 上传的文档，这是**正确行为**。
- `total` 与 `items.length` 本轮**恒等**（无分页）；仍然保留 `total` 字段：将来加分页时不必改契约（backlog）。
- **排序未约定**：契约不保证 `items` 的顺序，前端不要依赖（需要固定顺序时自行排序）。

### 7.3 `DELETE /api/kb/documents/{documentId}` —— 删除文档

| 项 | 值 |
| --- | --- |
| URL | `/api/kb/documents/{documentId}`（`documentId` 为路径参数，int64） |
| Method | `DELETE` |
| `produces` | `application/json` |
| 鉴权 | **必需** |
| 成功 | HTTP 200 + `code=0` + `data=null`（文档行与其**全部分块**一并删除） |
| 失败 | 文档不存在 / **不属于当前租户** → HTTP **200** + `10202` |

**两个刻意的约定**：

1. **不存在时返回 `10202` 而不是 404**：本项目的 HTTP 状态码只承载传输/可用性语义（§1.2），
   业务拒绝一律 200 + 业务码。且**另一个租户的文档在本接口里与"不存在"完全同形** —— 这是有意的：
   区分开就等于给出一个"某 id 是否存在"的探测口。
2. **删文档 = 删分块**：`t_kb_chunk.document_id` 上带 `ON DELETE CASCADE`，级联由数据库保证，
   业务代码不做两次删除（少一处"忘了删分块"的可能）。
   ⚠️ 删除**无法恢复**（本轮不保存原始文件）—— 前端二次确认的文案要写明这一点。

### 7.4 `POST /api/kb/ask` —— 知识库问答

| 项 | 值 |
| --- | --- |
| URL | `/api/kb/ask` |
| Method | `POST` |
| `consumes` | `application/json`（显式声明） |
| `produces` | `application/json`（显式声明） |
| 鉴权 | **必需** |
| 成功 | HTTP 200 + `code=0`，`data` 为 `KbAnswerVO`（§7.6） |

请求体：

```json
{ "question": "去年利润是多少", "topK": 5 }
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `question` | string | 是 | 非空、去空白后非空；**≤ 500 字符**（按**字符**计，不是字节；超出 → `40001`） |
| `topK` | integer | **否** | 缺省 / `null` = 用配置值（`nexus.ai.rag.top-k`，默认 5）；取值域 **`1..20`**（越界 → `40001`） |

**三条补充约束**：

1. **`topK` 越界返回的是 `40001` + 可读文案**，不是 Bean Validation 的英文消息：后端刻意用 `Integer`
   承接、在业务侧判边界（`@Min/@Max` 的默认消息 `must be less than or equal to 20` 会被拦截器直接弹给用户）。
2. **`question` 的上限按字符计（500），与对话接口的 8KB 字节口径不同是刻意的**：那个口径防的是
   "误贴大段文本撑爆上游上下文"，而问题天然是短的 —— 500 字符 ≈ 500 个汉字，简单可读即可。
3. **问题不带任何"文档范围"参数**：本轮检索范围 = 当前租户的全部文档（见本节开头的边界 1、2）。

**同步口径**（设计决策 D6）：返回 200 时答案**已经生成完**（一次提问约 3~10s，云端比本地 CPU 快得多）。
前端要有 loading 态（按钮禁用 + "检索并生成中…"），超时文案同 §7.1 第 3 条（"服务端可能仍在处理"）。
**不要按流式实现**：本接口的响应体是一次性 JSON；将来若改流式为 backlog（设计 §10.4），那是一次契约变更。

### 7.5 `KbDocumentVO`（上传响应与列表项**同构**）

```json
{
  "documentId": 7,
  "fileName": "公司年报.pdf",
  "fileType": "PDF",
  "fileSize": 1048576,
  "charCount": 12480,
  "chunkCount": 28,
  "createdAt": "2026-09-22T10:30:00+08:00"
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `documentId` | integer (int64) | 是 | 文档 ID（删除接口的路径参数） |
| `fileName` | string | 是 | 原始文件名（**仅回显，不落盘**，见本节边界 3） |
| `fileType` | string | 是 | `TXT` / `PDF`（大写，由扩展名归一化而来） |
| `fileSize` | integer (int64) | 是 | 上传字节数 |
| `charCount` | integer | 是 | 解析出的正文字符数（**排查解析质量的第一眼数据**：与预期量级差太远就是解析出了问题） |
| `chunkCount` | integer | 是 | 入库的分块数（即 `3000` 上限判据的实测值） |
| `createdAt` | string (date-time) | 是 | 入库时间，格式与 `/api/health` 的 `timestamp` **同款**：`yyyy-MM-dd'T'HH:mm:ssXXX`（秒级、无小数秒；偏移量随服务端时区）。⚠️ **零偏移渲染成 `Z` 而不是 `+00:00`**（2026-09-22 实测）：容器内是 `2026-09-22T02:30:00Z`，Windows 本地直跑是 `+08:00` —— `Z` 与 `+00:00` 是等价的 ISO-8601 写法，**判据不要写死 `+00:00`**（前端 `new Date(...)` 两种都能解析） |

列表接口的外层结构是 `KbDocumentListVO`：`{ "items": [KbDocumentVO...], "total": 3 }`（§7.2）。

### 7.6 `KbAnswerVO`（问答响应）

```json
{
  "answer": "根据资料，去年（2025 年）净利润为 1.23 亿元 [资料1]。",
  "grounded": true,
  "sources": [
    {
      "documentId": 7,
      "fileName": "公司年报.pdf",
      "chunkIndex": 12,
      "score": 0.8241,
      "content": "……（该分块的原文）"
    }
  ],
  "retrieval": { "topK": 5, "hits": 1, "threshold": 0.5, "durationMs": 48 },
  "generation": { "modelType": "DEEPSEEK", "model": "deepseek-chat", "durationMs": 4034 }
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `answer` | string | 是 | 模型生成的答案；`grounded=false` 时是**固定文案**（见下） |
| `grounded` | boolean | 是 | 答案是否**基于检索到的资料**。`false` = 检索（阈值筛完后）0 条 ⇒ **未调用大模型** |
| `sources` | array | 是 | 引用片段，按 `score` **降序**；`grounded=false` 时为空数组（**不是 `null`**） |
| `sources[].documentId` | integer (int64) | 是 | 来源文档 ID |
| `sources[].fileName` | string | 是 | 来源文件名（展示用） |
| `sources[].chunkIndex` | integer | 是 | 该分块在文档内的序号，**0 起**；前端展示「第 N 段」时用 `chunkIndex + 1` |
| `sources[].score` | number | 是 | 余弦相似度 = `1 - (embedding <=> query)`，**保留 4 位小数**；取值域 `[-1, 1]` |
| `sources[].content` | string | 是 | 该分块的**原文**（引用即原文，不做二次摘要；段落内的换行原样保留 ⇒ 渲染要 `white-space: pre-wrap`） |
| `retrieval` | object | 是 | 检索侧观测值：`topK`（本次生效值）/ `hits`（过阈值条数）/ `threshold`（本次生效阈值）/ `durationMs` |
| `generation` | object \| **null** | 是（可为 `null`） | 生成侧观测值：`modelType` / `model` / `durationMs`；**`grounded=false` 时为 `null`**（没调模型） |

**`grounded=false` 时的 `answer` 固定文案**（后端常量，前端**不必自己拼**）：

```
知识库中未找到相关内容，请换一种问法，或先上传相关文档。
```

> 契约里**不写**"答案 ≤ 200 字"这类生成侧约束 —— 那是 prompt 的事（设计 §4.6），不是接口的事；
> 写进契约就成了后端必须校验的规则，而它其实拦不住模型。
> `retrieval` / `generation` 两个观测块**必须有**：它们是验收（"答案不对"到底是检索的问题还是生成的问题）
> 与现场排查的唯一可见证据。
> ⚠️ `sources[].content` 与 `answer` 都是**用户上传的外部文本**，前端必须用插值渲染（`{{ }}` / `v-text`），
> **绝不用 `v-html`** —— PDF/TXT 里出现 `<script>` 就是一次 XSS（设计 §5.1-3）。

### 7.7 失败形态总表（**全同步口径**）

本链路全部同步（无工作线程、无 `SseEmitter`），所以每条失败都有确定的载体与状态码 —— 这正是决策 D6/D7 换来的一致性：

| 失败点 | 时机 | 载体 | HTTP | code |
| --- | --- | --- | --- | --- |
| 无 token / 过期 / 伪造 | 进控制器前 | `Result` | 401 | `40100` / `40102` / `40101`（过滤器出口，见 §1.2） |
| 请求不是 multipart（缺 `Content-Type` / 缺 boundary） | 参数绑定前 | `Result` | **200** | `40001` |
| 未带 `file` 字段 / 文件为空 | 参数绑定 | `Result` | **200** | `40001` |
| 请求体校验失败（`question` 空 / 超长；`topK` 越界）/ JSON 畸形 | 进控制器前 | `Result` | **200** | `40001` |
| **`file` 的文件名超过 255 字符** | 业务（写库前拦下） | `Result` | **200** | `40001` |
| 文件超过 10MB | multipart 解析 | `Result` | **200** | `40003`（**待实测**：也可能表现为连接被重置，见设计 §6.2） |
| 扩展名不在白名单 / 扩展名与内容不符 | 业务 | `Result` | **200** | `10201` |
| 解析不出文本（扫描版 PDF、空文件、编码不可识别） | 业务 | `Result` | **200** | `10203` |
| 分块数超过 3000 | 业务（**向量化之前**） | `Result` | **200** | `10204` |
| 向量化时 Ollama 不可达 / 超时 / 报错 | 业务 | `Result` | **503** | `20100`（事务回滚，文档不落库） |
| 生成时上游模型不可达 / 超时 / 报错 | 业务 | `Result` | **503** | `20100`（检索结果已拿到，但答案生成失败 ⇒ **整个请求失败**） |
| 删除不存在的文档 | 业务 | `Result` | **200** | `10202` |
| 未预期的内部异常 | 兜底 | `Result` | 500 | `50000` |
| 客户端中途断开（上传/问答进行中） | — | 无（连接已没了） | — | 服务端**不会**察觉：入库照常完成。前端超时 ≠ 后端失败（§7.1 第 3 条） |

> **为什么"生成失败"不降级为"返回引用片段、答案留空"**：那样会造出一个**看着成功其实没答**的响应，
> 而"答案为空"的前端处置与"检索不到"（`grounded=false`）长得一样，会把排查方向带偏。
> 宁可整体失败（503 + `20100`）让用户重试 —— 重试成本是几秒钟，误诊成本是半小时。

### 7.8 错误码增量（**不复用**既有值）

**通用码**（已在 §1.3 的表里，此处为完整复述 —— 它不限知识库接口）：

| code | 常量（后端 `ResultCode`） | 配套 HTTP | `msg` | 归属 |
| --- | --- | --- | --- | --- |
| 40003 | `FILE_TOO_LARGE` | **200** | 文件过大，最大支持 10MB | 4xxxx 请求侧（**通用**） |

**知识库业务码**：

| code | 常量（后端 `ResultCode`） | 配套 HTTP | `msg` | 归属 |
| --- | --- | --- | --- | --- |
| 10201 | `KB_FILE_TYPE_UNSUPPORTED` | 200 | 不支持的文件类型，仅支持 TXT / PDF | 1xxxx 业务 |
| 10202 | `KB_DOCUMENT_NOT_FOUND` | 200 | 文档不存在或已被删除 | 1xxxx 业务 |
| 10203 | `KB_PARSE_EMPTY` | 200 | 未能从文件中解析出文本（可能是扫描版 PDF 或空文件） | 1xxxx 业务 |
| 10204 | `KB_CONTENT_TOO_LARGE` | 200 | 文档内容过长，超出单文档分块上限，请拆分后上传 | 1xxxx 业务 |

**复用的既有码**（不新增，避免同一件事有两个码）：
`40001`（参数不合法：请求不是 multipart、缺 `file`、文件为空、JSON 畸形、`topK` 越界、问题超长）、
`20100`（模型/依赖不可用：向量化或生成时上游不可达 —— 知识库链路里**只有 503 一种载体**，
不像对话接口那样还有 `error` 帧，因为本链路全程同步）、`50000`、`40100`/`40101`/`40102`。

> **`40002`（415）为什么不用于上传**：它的 `msg` 写死是"请求格式不支持，请使用 **application/json**"，
> 贴到上传接口上是**反向误导**（用户会去改 JSON 头）。故 multipart 相关失败一律走 `40001`，
> 由后端日志说明具体是哪一种（缺头 / 缺 boundary / 缺 part）。
>
> ⚠️ **一处"文案写死、配置可改"的已知成本**（本轮接受，记在明处）：
> `40003` 的 `msg` 写死"10MB"，真源是 `spring.servlet.multipart.max-file-size` —— 改配置时
> 文案要一起改，否则前端展示的限制值与实际行为不一致。
> `10201` 的"仅支持 TXT / PDF"**没有**这个问题：白名单是后端常量（`TikaDocumentParser` 里的
> `Set.of("txt", "pdf")`），与文案同源（初版设计曾想做配置键，实施时改掉 —— 可配的白名单会让
> 写死的文案说谎，而"支持哪几种格式"本就是范围决策，不是运维旋钮）。

### 7.9 curl 验证

> ⚠️ **端口必须从唯一真源取，不要写死**（`docker-compose/.env` 的 `BACKEND_PORT`）。
> 夹具在 `qa/fixtures/rag/`（**清单与用途见设计 §7.2**，其余夹具由 qa-engineer 按需生成）；
> **验收用例与判据见 `docs/test-cases/TC-03.md`**。
> 下面这组命令用现成的 `公司年报.pdf`（阶段验收夹具）—— 上传它再问"去年利润"才是闭环。

```bash
# 前置：进入仓库根目录
cd /c/wp/nexus-agent-workbench
BACKEND_PORT=$(grep -E '^BACKEND_PORT=' docker-compose/.env | cut -d= -f2)
TOKEN=$(curl -s -X POST "http://localhost:${BACKEND_PORT}/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

# ① 上传（-F 即 multipart；curl 会自动带上 boundary —— 这正是前端不能手写 Content-Type 的原因）
curl -s -X POST "http://localhost:${BACKEND_PORT}/api/kb/documents" \
  -H "Authorization: Bearer ${TOKEN}" \
  -F "file=@/mnt/c/wp/nexus-agent-workbench/qa/fixtures/rag/公司年报.pdf"

# ② 列表
curl -s "http://localhost:${BACKEND_PORT}/api/kb/documents" -H "Authorization: Bearer ${TOKEN}"

# ③ 问答（阶段验收的那一问）
curl -s -X POST "http://localhost:${BACKEND_PORT}/api/kb/ask" \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d '{"question":"去年利润是多少"}'

# ④ 删除（documentId 换成②里的值）
curl -s -X DELETE "http://localhost:${BACKEND_PORT}/api/kb/documents/1" -H "Authorization: Bearer ${TOKEN}"
```

**判据**：① 返回 `code=0` 且 `chunkCount ≥ 1`；② 列表里出现刚上传的文档（跨租户看不到，见 §7.2）；
③ `grounded=true` + `sources` 非空 + 答案里的数字与资料一致；④ 返回 `data=null`，再查列表已消失。

> 四条**探针**（embedding 维度 / 批量入参 / 模型版本 / pgvector 版本）与相似度阈值的**标定步骤**不在契约范围内，
> 见设计 §3.9。四条探针已于 2026-09-22 实测：`/api/embed` 存在且**维度 = 768**（故 `vector(768)` 定稿）、
> **支持批量**、`nomic-embed-text` 的 `num_ctx 8192`（500 字块远小于窗口）、pgvector **0.8.6**（HNSW 可用）。

---

## 8. 变更记录

| 日期 | 变更 | 说明 |
| --- | --- | --- |
| 2026-09-20 | **订正 §6.3 失败形态表**：拆开「上游中途报错 / 服务端软上限截断」这一格 | **原表自相矛盾**：它与 §6.2 冲突 —— §6.2 规定 `done` 帧的 `finishReason` 有 `timeout`（**服务端软上限**截断），而 §6.3 那一格把"软上限截断"与"上游中途报错"并列写成 `error` 帧（20100）。后端口径以 §6.2 为准（v1 实现即如此）：软上限截断 = `done` + `finishReason=timeout` + `code=0`，**不是失败**；只有"上游中途报错"才走 `error` 帧。这一格若留着，前端会把一次正常的主动收尾渲染成故障 |
| 2026-09-20 | §6.2 补一条边界说明（`meta` 的「必有」只对成功路径成立） | 上游一个字都没吐就失败时，这条流只有一帧 `error`（没有 `meta`）—— 这符合时序规则 1（`meta` 卡在"首次取到上游响应"之后）。**前端不要断言"第一帧一定是 meta"**，否则"上游不可用"这种正常失败会变成解析异常 |
| 2026-09-11 | 首版（阶段0 / 子任务 0.4） | `GET /api/health` + 统一响应体 + 错误码表 + 状态码策略 |
| 2026-09-11 | 追加 §4 健康检查判据（面向 0.3 脚本） | 两个探活口分工、判定规则表、轮询策略、503 造法、模型就绪独立判据、不可用检查项、复核偏差 |
| 2026-09-13 | 追加 §5 认证接口（阶段1） | `login` / `logout` / `me` 三个接口 + 6 个错误码（10100~10102、40100~40102）+ 401 状态码语义 + 演示账号；§1.4 的 `Authorization` 由"不需要"改为"除白名单外必需"。**§4 编号保持不变**（`scripts/` 三个脚本按编号引用），新章节追加在其后 |
| 2026-09-17 | 订正 §5.3 一处的用例引用 | 「这是验收项 1.2-3 的判据」→「验收项『登出即失效』（`TC-01-1.2-5`）」。原因：`1.2-3` 在本仓库有**两套编号**（design §9 / task.2 指「task 第 3 条验收标准 = 登出即失效」，TC-01.md 指「第 3 条用例 = 过期 token」），只写 `1.2-3` 会指到错的那条。对照表见 `docs/test-cases/TC-01.md` 头部 |
| 2026-09-20 | 追加 §6 对话接口（阶段2） | `POST /api/chat/stream`：请求契约（三条补充约束）+ 四种事件帧 + 三条时序规则 + 失败形态总表（**全异步口径**）+ 3 个新错误码（`40001`/`10200`/`20100`）+ curl 验证。**§1~§5 编号保持不变**（§4 被 `scripts/` 三个脚本按编号引用），原「变更记录」顺延为 §7。⚠️ 流式接口是本仓库第一个「响应体不是一次性 JSON」的接口，但**每帧 `data` 仍是 `Result<T>`** |
| 2026-09-20 | §6.2 补「帧表 ↔ schema 对应关系」；`openapi.yaml` 补齐 `delta` 帧 schema | **缺口来源（前端实施时提出）**：`openapi.yaml` 只有 meta/done/error 三个 schema，缺 `ChatStreamDelta`，前端只好手工补了一个类型 —— 机器可读版与本节帧表不一致，按 openapi 生成类型的人会漏掉 `delta`。本次：① openapi 新增 `ChatStreamDelta`（`content`，注明是**增量**、不是累积全文）；② 四种帧 ↔ 四份 schema 的对应关系在 §6.2 与 openapi 的接口描述里各写一份（OpenAPI 3.0.3 无法把 schema 挂到 SSE 事件上）；③ `data.servedBy` 由紧 enum 放宽为 `string` —— 取值集合会随自动路由扩大（设计 §10.1 的 `fallback`），纯展示字段的容错优先于严格 |
| 2026-09-22 | 追加 §7 知识库接口（阶段3） | `POST /api/kb/documents`（上传）/ `GET /api/kb/documents`（列表）/ `DELETE /api/kb/documents/{documentId}` / `POST /api/kb/ask`（问答）四个**同步**接口 + `KbDocumentVO` / `KbAnswerVO` 两个响应结构 + **全同步口径**的失败形态总表 + 5 个新错误码（`40003` 为通用码，`10201`~`10204` 为知识库业务码）+ curl 验证；含三条边界（一个租户一个知识库 / 单轮问答 / 只支持 TXT + PDF 且不保存原件）。**§1~§6 编号与内容保持不变**（§4 被 `scripts/` 三个脚本按编号引用），原「变更记录」顺延为 §8。⚠️ 与 §6 的流式接口相反：本组接口**每个失败都有确定的 HTTP 载体** |
| 2026-09-22 | §1.3 增通用码 `40003`；§4.6 的 pgvector 判据升级 | ① `40003`（`FILE_TOO_LARGE`，HTTP 200）列入 §1.3 通用表 —— 它约束的是 multipart 请求体本身，任何上传接口都会撞上，与"知识库"这个业务域无关。② §4.6 那一行订正为「**阶段3 起 `installed_version IS NOT NULL` 为硬判据**」：`CREATE EXTENSION vector` 已由 `db-patch/202609131000` 执行，而 RAG 的建表补丁依赖 `vector` 类型 ⇒ 扩展没装上时建表 / 入库 / 检索全挂，"扩展可用"不再够用（这是设计 §0.1 末尾预告的收口，连带任务是 devops 升级 `check-env.sh`） |
| 2026-09-22 | **补齐 `openapi.yaml` 缺失的 6 个认证 schema**（阶段1 技术债） | **缺口来源（阶段3 实施复核实测发现，非本阶段引入）**：`LoginRequest` / `ResultLoginResponse` / `ResultVoid` / `ResultUserInfoVO` 这 4 个 `$ref` **从阶段1 起就没有对应的 schema 定义**（悬空引用），任何按 `openapi.yaml` 生成 TS 类型或导入 Postman 的人都会在这 4 处失败 —— 而本文件 §5 的字段表一直是完整的，即**两份契约从阶段1 起就不同步**。本次补上 6 个 schema：`LoginRequest` / `UserInfoVO` / `LoginResponse` + 三个 `Result*` 包装体（沿用 `ResultHealthReport` 的 `allOf` 写法；认证组用包装体、知识库组用内联，两种写法并存是既成事实，见 openapi 内对应注释）。字段以 §5.2 / §5.4 为准，并与 `nexus-module-system` 的 `LoginRequest` / `LoginResponse` / `UserInfoVO` 三个 DTO 逐一核对。判据：`openapi.yaml` 可解析且**全部 `$ref` 可解析**（本次自检：8 path / 22 schema / 20 ref / **0 悬空**） |
| 2026-09-22 | **阶段3 第二段 b 的契约侧收口（5 处）+ §4.2.2 判据订正** | ① §7.5 `createdAt` 补"**零偏移渲染成 `Z`**"（实测 `2026-09-22T02:30:00Z`；原写"容器内通常为 `+00:00`"，会在容器内误导判据）；② §7.7 失败形态表补一行 **`file` 文件名超 255 字符 → 200 + `40001`**（原先会落到 DB 的 `VARCHAR(255)` 报错 ⇒ 500「系统繁忙」，把客户端的输入问题报成服务端故障）；③ §7.7 里"向量化/生成时上游不可达 → **503** + 20100"两行**原先只有契约、实现给的是 200** —— 本次在 `GlobalExceptionHandler` 加**按码分流**（`code=20100` ⇒ 503）**修的是代码、契约未动**（已逐点复核不影响阶段2：池满走自己的出口、provider 的 20100 在工作线程被转成 `error` 帧）；④ **§4.2.2 加 `data.timestamp` 专条** —— 原判据正则只认 `±HH:MM`，而容器内实测是 `Z` 结尾 ⇒ 照它实现会**假失败**（`scripts/check-health.sh` 本来就按"不断言时区"实现，**脚本没错、是本节文字写窄了**）；⑤ `openapi.yaml` 的健康 timestamp 描述同步订正 |

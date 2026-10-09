# qa/e2e —— pytest + Playwright 冒烟集合（阶段5 / 5.4）

> **这里是什么**：PR 部署完测试环境之后跑的**冒烟**（S1~S5 五条，不再多），在 **测试发行版宿主上直跑**、
> **不容器化**（设计 05 §1 的时序图）。它按设计产出三样东西：报告产物（Allure/trace/截图）、
> 结构化结果（`run-results.json`）、以及给失败用的现场。
>
> - **定位写法的唯一真源 = [`docs/design/05-自动化测试.md` §7.2](../../docs/design/05-自动化测试.md)** ——
>   本文件**不复制定位表**（task.6 5.3 明写：约定表只能有一处）。要加/改定位，先改那一节，再改 `lib/ui.py`。
> - **用例与判据的唯一真源 = [`docs/test-cases/TC-05.md`](../../docs/test-cases/TC-05.md)** ——
>   每条用例的步骤、预期、出处都在那里；本文件只讲"怎么跑、产物落哪"。
> - 三条铁律（`qa/README.md`）：判据不藏脚本里、夹具用完即销、脚本自己也得无害 —— 本工程沿用。

## 目录

| 路径 | 是什么 |
| --- | --- |
| `conftest.py` | 环境读取、登录/收尾夹具、失败留痕与结果文件的装配（**不放判据**） |
| `lib/` | 机械动作库：`ui.py`（定位值 + 登录/登出）、`api.py`（发请求）、`kb.py`（列表指纹/删文档）、`results.py`（结果记账） |
| `smoke/` | S1~S5，一条用例一个文件；文件名与 TC-05 的编号一一对应 |
| `pytest.ini` | `testpaths` / 标记 / 失败留痕的两个开关（trace、截图） |
| `requirements.txt` | 四个依赖，**版本 pin** |
| `artifacts/` | 运行产物（**不入库**，见该目录下的 `.gitignore`） |

## 跑一次

### 前置（两条都是"跑之前先满足"）

1. **被测环境已就绪**：测试环境已按 5.1 拉起、`deploy.py --profile test` 的八步表全绿（含 `/api/health` 三项 UP）。
   本工程**不启动、不部署、不重建**任何容器 —— 那是 `deploy.py` 的事。
2. **依赖与浏览器已装**（下面两条命令各面只做一次）。

### 安装（两个运行面，差别都在这张表里）

| | A. 测试发行版宿主（**CI 实际跑的地方**） | B. Windows 本地（调试用） |
| --- | --- | --- |
| 发行版/系统 | `nexus-agent-workbench-test`（不挂 `/mnt/c`） | Windows 11 |
| Python | 系统 `python3`（⚠️ **版本未核**，需实测） | **3.14.4**（已实测：四个包都有轮子） |
| 建 venv | `python3 -m venv .venv` | `py -3.14 -m venv .venv` |
| 装依赖 | `.venv/bin/pip install -r requirements.txt` | `.venv\Scripts\python -m pip install -r requirements.txt` |
| 装浏览器 | `.venv/bin/python -m playwright install --with-deps chromium` ⚠️ 需实测（`--with-deps` 要 sudo 装系统库） | `.venv\Scripts\python -m playwright install chromium` ⚠️ 需实测 |
| 跑 | `.venv/bin/python -m pytest` | `.venv\Scripts\python -m pytest` |
| 到被测环境的链路 | 同宿主，`127.0.0.1:12006` 直达 | 走 WSL2 的 `localhost` 转发 ⚠️ **需实测**（设计 05 §6-9） |

> ⚠️ 浏览器**不进依赖**：`playwright install` 会把 Chromium 下到用户缓存目录（约 150MB+），
> 首跑之前装一次即可（本工程交付时**刻意没装**）。离线/受限网络下这条会失败，属环境问题、不是用例问题。

### 常用命令

```bash
# 全部五条（默认打测试环境 http://127.0.0.1:12006）
cd <CHECKOUT>/qa/e2e && .venv/bin/python -m pytest

# 只跑最慢的那条（RAG）
cd <CHECKOUT>/qa/e2e && .venv/bin/python -m pytest smoke/test_s5_kb_upload_ask_answer.py -v

# 对着别的环境跑（例：开发环境 8088；**只在调试时这么用**，别拿它当验收）
cd <CHECKOUT>/qa/e2e && E2E_BASE_URL=http://127.0.0.1:8088 .venv/bin/python -m pytest

# 看得到界面 / 成功的用例也留 trace 与截图（默认只留失败的）
cd <CHECKOUT>/qa/e2e && .venv/bin/python -m pytest --headed --tracing=on --screenshot=on
```

`<CHECKOUT>` = 仓库根。在测试发行版里它是 runner 的工作区（首次运行由 M7 确定实际路径）；
在 Windows 本地是 `C:\wp\nexus-agent-workbench`。

## 可配项（环境变量）

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `E2E_BASE_URL` | `http://127.0.0.1:12006` | 被测前端地址（测试环境前端端口，口径 B 下已发布） |
| `E2E_USERNAME` / `E2E_PASSWORD` | `admin` / `admin123` | 冒烟账号（与 `deploy.py` 的 `--login-user/--login-password` 同口径） |
| `E2E_TIMEOUT_MS` | `15000` | 普通期望（元素出现、跳转）的上限 |
| `E2E_LLM_TIMEOUT_MS` | `180000` | **需要模型生成的两段**（S4 的回答、S5 的上传+提问）—— ⚠️ **上界待首次实跑确定**，跑过一次再收紧，别照抄 |
| `E2E_EXPECT_CHAT_MODEL` | `qwen2.5:7b` | S4 断言气泡上的模型名（= 后端 `nexus.ai.default-model` 指到的模型） |
| `E2E_KB_FIXTURE` | `qa/fixtures/rag/知识库说明.txt` | S5 上传的夹具（3KB、3 块，别换成那份 1.6MB 的年报 —— 冒烟要快） |
| `E2E_RUN_ID` | UTC 时间戳 `YYYYMMDDTHHMMSSZ` | 覆盖 run_id（同一批次重跑时用） |
| `E2E_TRIGGER` | `local` | 只进结果文件（5.2 的 workflow 里传 `ci`） |

## 产物路径（交给 5.5 挂卷）

本地一律落在 `qa/e2e/artifacts/`（conftest 已把 pytest 的两处相对路径改写成绝对路径，
所以**从哪个目录发起 pytest 都不影响落点**）：

| 本地产物 | 谁生成 | 卷内建议目标 | 说明 |
| --- | --- | --- | --- |
| `artifacts/allure-results/` | `allure-pytest` | `/allure-results/` | **原始 JSON**（每次运行前先清空）；`allure generate` 出 `/allure-report/index.html` —— 那是报告首页，由 5.5 生成 |
| `artifacts/playwright/<用例目录>/trace.zip` | pytest-playwright | `/playwright/<用例目录>/` | **仅失败时保留**（`--tracing=retain-on-failure`） |
| `artifacts/playwright/<用例目录>/test-failed-1.png` | pytest-playwright | 同上 | **仅失败时保留**，整页截图（`--screenshot=only-on-failure --full-page-screenshot`） |
| `artifacts/run-results.json` | `conftest.py` | `/run-results.json` | 最近一次运行的结构化结果 |
| `artifacts/runs/<run_id>.json` | `conftest.py` | `/runs/<run_id>.json` | 同上的历史副本 |

- 卷 = 设计 §2.2 的 `nexus-test-report`（**宿主侧写、report-nginx 只读挂**）。"本地产物怎么进卷"（bind 挂载 / 拷贝）
  由 **5.5** 定：本工程只负责**落点固定**，不管传输。
- 报告卷要能被 `ls -lR` 出 `index.html` / `trace.zip` / `*.png`（设计 §5.5 的验收动作）—— 上面这张表就是它的来源。
- `artifacts/` 下的东西**全部不入库**（目录里有一份 `.gitignore`）：阶段验收有一条
  「跑前跑后 `git status --porcelain` 一致」的无害性判据，产物出现在 git status 里会把它淹掉。

## 结果文件（`run-results.json` / `runs/<run_id>.json`）

JSON，UTF-8，**LF**，`indent=2`；一次运行一份。5.5 落库就读它。

```jsonc
{
  "schema": "qa-e2e/run-results@1",
  "run_id": "20261009T144607Z",          // E2E_RUN_ID 可覆盖
  "started_at": "…+00:00", "finished_at": "…+00:00",
  "exit_code": 0,                         // pytest 的退出码（0=全过；1=有用例没过；其它=运行本身出错）
  "environment": { "base_url": "…", "python": "3.14.4", "pytest": "…", "playwright": "…", … },
  "revision": { "sha": "d448d91", "source": "git|GITHUB_SHA|unknown" },   // 被测代码的版本
  "trigger": { "kind": "local|ci", "github_run_id": "…", "run_url": "…" },// 只记录，不参与判定
  "totals": { "total": 5, "passed": 4, "failed": 1, "skipped": 0, "error": 0 },
  "cases": [
    {
      "tc_id": "S1", "title": "登录成功 → 落到控制台", "source_tc": "TC-01-1.5-1",
      "nodeid": "smoke/test_s1_…py::test_s1_…[chromium]",
      "status": "passed|failed|skipped|error|unknown",   // error = 夹具/收尾阶段出的问题
      "duration_ms": 1234,
      "phases": { "setup": "passed", "call": "failed", "teardown": "passed" },
      "message": "…失败原文（截断 4000 字符）…",          // 仅非通过时有
      "artifacts": { "trace": "artifacts/playwright/…/trace.zip",
                     "screenshot": "artifacts/playwright/…/test-failed-1.png" },
      "cleanup": { "login": { "status": "ok|warn|none", "detail": "…" },   // 收尾与自证
                   "kb":    { "status": "ok|warn|none", "detail": "…" } }
    }
  ]
}
```

- **状态口径**：`failed` = 用例断言没过；`error` = 夹具或收尾出问题（含登录前置不成立）；
  `skipped` = 环境前置不满足（见下节）—— 三者别混着看。
- **`cleanup`** 是**无害性自证**的落点：`ok` = 跑完环境回到原样并有观察为证；`warn` = 自证未达成
  （处置写在 `detail` 里，一般等 TTL 自净即可）；`none` = 本次没有产生需要清理的东西。
  **它不改变用例主结论** —— 主结论只由断言决定。

## 失败留痕策略

- **只留失败的**：`pytest.ini` 里 `--tracing=retain-on-failure --screenshot=only-on-failure`（+ 整页截图）。
  成功的用例不留 trace/截图（省体积、省时间）；要留就临时加 `--tracing=on --screenshot=on`。
- 失败时同时有：**trace.zip**（可用 `playwright show-trace <file>` 打开）、**整页截图**、pytest 的失败原文
  （进 `run-results.json` 的 `message`），以及 Allure 报告里的同一条记录。
- 失败发生在**夹具阶段**（例如登录拿不到 token）同样会留痕 —— 那时截图是登录页，正是要看的东西。

## 已知前置：S5 需要把 RAG 回答模型指向本地（**开口项，需 devops 一行配置**）

`nexus.ai.rag.answer-model-type` 的默认值是 `DEEPSEEK`，而测试环境按 **M4-③ 不配云端密钥** ⇒
`/api/kb/ask` 会以 **HTTP 503 + `code=20100`** 失败，S5 拿不到答案。

- **本用例的处置**：S5 见 `code=20100` 即记 **SKIP**（环境前置不满足，不记失败 —— 沿用 TC-03 §0 的口径
  "别把它记成本用例失败"），skip 理由里写清怎么分辨"环境没配"与"模型真挂了"。
- **修法（任选一处，都在测试档，别碰开发环境）**：
  - `.env.test` 加一行 `NEXUS_AI_RAG_ANSWER_MODEL_TYPE=OLLAMA`，并让 `docker-compose.test.yml` 的
    `nexus-backend.environment` 把 `NEXUS_AI_RAG_ANSWER_MODEL_TYPE` 透传进容器（**两处都要**：
    现在 compose 里没有这个键，光写 env 文件到不了容器里）；
  - 或给测试环境配云端密钥 —— 与 M4-③ 冲突，**不推荐**。
- 修好之后 S5 会真的跑起来（不再 SKIP）；届时若还见 20100，那就是**真的上游故障**，按失败处理。
- ⚠️ **反过来也别配**：给测试环境塞云端密钥（与 M4-③ 冲突）会让本条每次跑都真发一次**计费**调用，
  而且把"测试不依赖外部网络"这条判据破掉。

## 无害性（Test Harmlessness 的落点）

| 写什么 | 为什么非写不可 | 清理与自证 |
| --- | --- | --- |
| Redis 白名单 1 个键（`nexus:auth:token:{jti}`，TTL 7200s） | 交互用例必须先登录（知识库/对话接口都不在白名单） | `cleanup_login`：跑完登出 + 自证（同一 token 调 `/api/auth/me` 期望 **401 + 40101**），结果记进 `cleanup.login`。最坏情况 = 该键等 TTL 自净，不阻断任何后续用例 |
| S5 上传的一份文档（`知识库说明.txt`，3 块） | 本条的验收对象就是"上传 → 入库 → 检索 → 出答案" | `kb_doc_registry`：跑前/跑后各取一次**列表指纹**（documentId/fileName/chunkCount），用例内删掉自建的那份；结果记进 `cleanup.kb`。最坏情况 = 库里多一份 3KB 测试文档（列表里带文件名，手工删：`DELETE /api/kb/documents/<id>`） |

**一律不碰**：业务表数据（`t_user` / `t_tenant` / 业务文档的既有行）、`db-patch/`、共享卷
`build-artifacts`、`scripts/`、前端/后端源码、已合入的契约文档。**不删库、不重建容器、不清卷。**

## ⚠️ 需实测确认（本文档的"未实测"清单，一条都别当成既成事实）

| # | 待确认项 | 验证动作 |
| --- | --- | --- |
| 1 | 五条用例在真环境上跑得通、耗时是多少 | 首跑后把耗时填进 `E2E_LLM_TIMEOUT_MS` 的口径（必要时收紧） |
| 2 | 测试发行版的 `python3` 版本与 `--with-deps` 能否装上浏览器（要 sudo） | 按「安装」表 A 面做一遍 |
| 3 | Windows 侧 `127.0.0.1:12006` 是否可达（WSL2 `localhost` 转发） | Windows 上 `curl -s -o NUL -w "%{http_code}" http://127.0.0.1:12006/` 期望 200（设计 §6-9） |
| 4 | `set_input_files` 对**隐藏**文件框（`kb-upload` 内层 `input[type=file]`）生效 | S5 首跑（设计 §7.2.4 第 5 行） |
| 5 | 密码框的 Role 定位取不到（本工程已按 §7.2.3 一律走定位属性，故只是核对口径） | 设计 §7.2.4 第 3 行 |
| 6 | 各 tid 在真实 DOM 里的落层与 §7.2.2 一致（16 个） | 设计 §7.2.4 第 1/2 行的 DevTools 片段；5.4 首跑时顺带核对 |
| 7 | 报告卷里"一个容器写、另一个容器读"的属主/权限 | 5.5 交付后 `ls -lR` + 浏览器实开（设计 §6-3） |

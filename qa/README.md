# qa —— 测试资产

> **这个目录是什么**：测试用的**夹具（fixtures）与辅助脚本**的家。它**不属于生产链路** —— `up.sh`、镜像构建、db-patch 迁移都不会读这里。
>
> **为什么单独一个顶层目录**：测试要注入的东西，既不能塞进 `db-patch/`（正式补丁目录，会被真实迁移应用），也不能塞进 `scripts/`（`up.sh` 会调用、用户天天跑）—— 那些是**被生产链路消费的正式目录**。测试资产必须与它们物理隔离，这是 Test Harmlessness 的落点（纪律全文见 `.claude/agents/qa-engineer/CLAUDE.md`）。
>
> **建立时间**：2026-09-17。起因：TC-00 §2 的 db-patch 用例把探针 SQL 造在容器内 `/workspace/repo/db-patch/` 里，**忘了删会让下一次真实迁移判乱序 `exit 3`**，`up.sh` 当场中止 —— 一个延迟爆发、且没人会联想到测试的坑。

## 目录约定

| 路径 | 放什么 | 不放什么 |
|---|---|---|
| `fixtures/` | 用例步骤里要**注入到被测环境**的东西：探针补丁、种子 SQL、清理 SQL、造故障用的小文件 | 任何会被正式流程读取的文件 |
| `fixtures/db-patch/` | **探针补丁**（`YYYYMMDDHHmm_描述.sql`，与正式补丁同命名规则，供 `NEXUS_PATCH_DIR` 指向） | 真实业务补丁（那些在 `/db-patch`） |
| `fixtures/sql/` | 种子 / 清理 / 取指纹的 SQL 片段 | 表结构变更（那些走 db-patch） |
| `scripts/` | 用例的**辅助 shell**：多步机械动作（建沙箱 → 注入 → 跑 → 断言 → 清理） | 环境脚本（那些在 `/scripts`） |
| `e2e/` | Playwright（**待 `docs/design/01-多租户与认证.md` §D8 解禁后再落**） | — |

**单测不在本目录**：后端 `backend/<module>/src/test/java`、前端 `frontend/src/**/__tests__` —— 位置由 Maven / Vitest 的约定决定，**没得选**，必须跟被测代码同模块。`qa/` 装的是「跨模块、可注入」的那类资产。

## 三条铁律

1. **夹具不是生产物**：这里的东西**永远不会**被直接应用到真实环境 —— 需要应用时，是把它拷进**一次性作用域**（容器 `/tmp`、沙箱库）再跑，跑完连作用域一起销毁。
2. **判据不藏脚本里**：TC 的「预期」栏必须人眼可读；`scripts/` 里只放**机械动作**（建、注入、清理），断言与判据永远写在 `docs/test-cases/TC-XX.md` 里。
3. **脚本自己也得无害**：幂等、自带清理、失败后可重跑 —— 与用例同一条标准（Test Harmlessness §2）。

## 在 TC 步骤栏里怎么引用

- **引用夹具**：写**完整路径**，并说明它是什么。
  ✅ `docker compose exec -T builder bash -c 'cp -r /workspace/repo/qa/fixtures/db-patch /tmp/tc00-patches'`
- **引用脚本**：同样写完整路径 **+ 一句话说明它做什么** —— 执行者不看脚本内容也应知道这一步在干什么（否则违反「步骤栏可被无损复现」）。
  ✅ `bash /mnt/c/wp/nexus-agent-workbench/qa/scripts/tc00-sandbox-up.sh   # 建沙箱库 + 把探针拷进容器 /tmp`
- ⚠️ 注意容器视角：`qa/` 在宿主是 `C:\wp\nexus-agent-workbench\qa`，在 WSL 是 `/mnt/c/wp/nexus-agent-workbench/qa`，**在 builder 容器内是 `/workspace/repo/qa`**（容器内是 git clone 的工作区，受「必须先 push」约束）。

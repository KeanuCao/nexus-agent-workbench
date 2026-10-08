---
name: deploy
description: |
  nexus 一键部署：把已 push/merge 的代码走完「拉代码 → 配置对账 → db-patch 校验 → 打包 → 容器 → health → 冒烟」，
  输出一张 PASS/FAIL 表。一次跑完、零提示、不派活、失败即停等人。
trigger: |
  用户说「部署」「#部署#」「deploy」「上部署」「发一版」「跑一次部署」等类似表达时触发。
  前置：前后端代码已完成并**人工 push/merge** 完毕。
---

# 部署（deploy）

## 这个 skill 存在的理由（2026-09-23 立）

此前部署是「主会话派 devops-engineer → 它来回取证 → 主会话顺手改文档 → 再派活」的多轮过程：
一次部署要用户反复过目、看大量命令输出、烧 token，**而且用户本来就能自己验的活被不断打断**。
现在的约定是：

| 约束 | 内容 |
| --- | --- |
| 一次跑完 | 一个脚本、一条命令、一张表；**零交互**（脚本不提问、不确认） |
| 时间 | 目标 **≤ 1 分钟**（构建缓存热时）；超了脚本会自己标出来 |
| 交互 | **≤ 3 次**（下发 → 看表 → 决定下一步） |
| ★ 部署期间**禁止派活** | 不派任何子代理、不提交代码、不改文档。**跑完再说话。** |
| 失败即停 | **不自动重试、不自动修**。把脚本输出给用户 → **人工定方案** → 再干活 |
| devops-engineer 的定位 | 收窄为**按需调查环境问题**（跑特殊脚本），日常部署不再经它 |
| CI 档（2026-10-09 新增） | 在**测试发行版**内跑 `scripts/py/deploy_test.py`：失败即非 0 退出 + `--summary-json` 机器可读摘要；"失败即停等人"里"**等人**"由 workflow 的失败态承担（见下方「CI 档」小节） |

## 调用时机

前后端代码完成 → **用户人工 push + merge** → 主会话下发本 skill。
⚠️ builder 容器**只能拿到已 push 的提交**，所以顺序不能颠倒（用 `--expect-sha` 可以硬性防呆）。

## 执行

```bash
MSYS_NO_PATHCONV=1 wsl -d nexus-agent-workbench -- python3 /mnt/c/wp/nexus-agent-workbench/scripts/py/deploy.py
```

★ **`MSYS_NO_PATHCONV=1` 不能省**：Git Bash 会把 `/mnt/c/...` 改写成 `C:/Program Files/Git/mnt/c/...`
（见 `wsl.abc.md` §3.8，实测报 `No such file or directory`）。

可选参数（默认全都不带）：

| 参数 | 用途 |
| --- | --- |
| `--expect-sha <前缀>` | 断言 git-sync 拉到的提交前缀。**防"没 push/没 merge 就部署"**——这是本项目最常见的自伤 |
| `--no-build` | 跳过打包，只用上次产物做部署与验证（改配置/改 nginx 时用） |
| `--rebuild` | 强制 rebuild 运行镜像（脚本平时自己按"烘进镜像的文件是否变过"判定） |
| `--profile dev\|test` | 档位（默认 dev，行为与以前一致）；test = 切到 `docker-compose.test.yml` + `.env.test`（**成对**，见「CI 档」） |
| `--ref <refspec>` | git-sync 的 refspec（CI 拉 PR head：`refs/pull/<N>/head`）；省略 = 分支模式，行为不变 |
| `--login-user` / `--login-password` | 冒烟登录账号（默认 admin/admin123；输出不打口令明文） |
| `--summary-json <path>` | 把机器可读摘要（每步状态 + 拉到的 SHA + 结论）写到该路径（CI 档） |
| `--ci` | CI 档：关颜色（"失败即非 0 退出"本就成立） |

## CI 档（测试环境 / 自动化，2026-10-09 新增）

> 依据：`docs/design/05-自动化测试.md` §4.2（"CI 里没有'人'"）。**本小节是 CI 档的口径真源。**

- **入口**：`python3 scripts/py/deploy_test.py <参数…>` —— 薄包装，等价于 `deploy.py --profile test`。
  包装层**只组装参数、不含任何判据**（判据只在 `deploy.py` 里写一份，见设计 §5.1-(b) 的硬规则）。
- **执行位置**：**测试发行版 `nexus-agent-workbench-test` 内**（独立 dockerd + `nexus-test-*` 一整套）。
  ⚠️ **不要**在开发发行版里跑 test 档 —— 引擎是隔离的，跑错地方会在开发引擎上建出一套 `nexus-test-*`。
- **"停"的承担者变了**：人工档是"失败即停、**等人**定方案"；CI 档**没有人**，由两件东西承担：
  1. **失败即非 0 退出**（脚本本就如此）；
  2. **`--summary-json <path>`**：失败路径也会写出机器可读摘要（每步 step/status/summary + 拉到的
     SHA + 结论），workflow 的邮件正文 / Actions 摘要从这里读"红在哪一步"。
  ⚠️ "**不自动重试、不自动修**"在 CI 档**同样成立** —— 红了就是红了，人工看摘要定方案。
- **拉 PR head 的固定组合**：`--ref refs/pull/<N>/head` + `--expect-sha <PR head SHA 前缀>`，两个一起用才闭环
  （只拉不断言 = 无法发现"拉错 ref"；只断言不拉 = 拿不到 PR 代码）。
- **判据档**：test 档是 lenient —— "前置物不存在 ⇒ WARN；存在但不一致 ⇒ 仍 FAIL"（设计 §4.1-⑨）。
- **测试档的步骤差异**（开发档完全不变）：第 1 步先把基础设施拉起（postgres / postgres-results / redis / ollama / ollama-init，
  模型拉取与后续构建并行）；第 6 步是全栈 `up -d`，并判"**冷启动 / 热环境**"决定要不要 `restart` 后端
  （热环境不 restart 就会拿旧 jar 跑 —— 这正是本项目反复踩过的"静默旧产物"类）；第 3 / 4 步把"前置物不存在"
  的判据降级为 WARN（如全新库还没有 `t_db_patch`）。
- **耗时**：CI **首跑缓存全冷**（镜像要拉、builder 缓存空、模型 ≈6GB 重下）⇒ 远超 1 分钟目标，属预期。

## 脚本做的八步（与用户给的清单一一对应）

1. **前置**：docker 可用、compose 文件在、builder 容器在（不在就拉起来）
2. **拉最新代码**：容器内 `git-sync`，打印拉到的 SHA 与分支（**人工核对这个 SHA 就是你刚 merge 的**）
3. **配置对账**（只读，先做）：分支真源链（容器 env vs `.env`）、模型清单（compose 的 `ollama-init` 为真源 vs `ollama list`）、
   nginx `client_max_body_size`（判据取**运行中容器内那份**）、向量维度（代码 `EXPECTED_DIMENSION` vs DB 列）、
   以及**镜像是否需要 rebuild**（比对烘进镜像的文件 sha256 与上次部署，状态存 `.tmp/deploy-state.json`）
4. **db-patch 校验**：先只读对账（宿主 `db-patch/*.sql` 个数 vs `t_db_patch` 行数）——
   **相等就跳过迁移**（省掉每次跑的 mvn 开销）；不等才跑 `db-patch-migrate`，并复核「扫描/应用/跳过」三个数与宿主一致
   （对不上就是**有补丁没 push/没 merge**）
5. **打包**：builder 内 `build-all`（后端 `mvn clean install` → `app.jar`；前端 `npm ci && npm run build` → dist）
6. **容器**：需要 rebuild 就**直接 rebuild**（按用户要求，不做校验）；`up -d` 应用配置变更 + `restart nexus-backend` 加载新 jar
   （前端 dist 走**卷挂载**、逐请求读取 ⇒ **不需要重启**）
7. **容器 health**：5 个常驻容器 running + healthy（轮询到超时）
8. **冒烟**：`/api/health`（经 nginx 与直连后端各试一次，报 `checks` 三项）、
   `POST /api/auth/login`（admin/admin123，只报 token 长度不打明文）、
   前端首页 200 **且首页引用的 `assets/*.js` 也 200**（证明 served 的是最新 dist，而不是"新 index + 旧 assets"）

## 刻意不做的事

- **不校验 rebuild 结果**（用户明确：需要 rebuild 就直接 rebuild）
- **不自动修任何东西**：失败只报告 + 给下一步线索
- **不打印密钥**：`.env.local` 只报存在/缺失（`DEEPSEEK_API_KEY` 的值永不出现在输出里）
- **不写业务数据**：只读 SQL + 一次登录（登录只写一个 Redis token 键）
- **不派活**：脚本跑完，主会话把表念给用户听，然后停下

## 失败时怎么办（写死在这个 skill 里）

★ **第一步不是找人，是分辨 FAIL 的来源**：这是**被测对象**（代码/环境/配置）的问题，还是**判据自己**的问题？
自研判据照样会造假警报 —— 2026-09-23 首跑的三条里两条是脚本自造的假警报，只有一条是真缺陷
（见 `docs/agent-log/20260923-首次实跑deploy失败.md`）。分辨不出就把"判据原文 + 原始输出"一起贴给用户，**别先下结论**。

1. **不要**自动重试、**不要**换个参数再跑、**不要**派子代理去"顺手查一下"。
2. 把 **FAIL 行 + 它上面的原始输出**贴给用户（脚本已经把关键行截出来了）。
3. 等用户定方案。若用户判断需要调查环境（端口、镜像源、卷、DNS 之类），
   **才**按需派 `devops-engineer` 跑专项脚本 —— 这是它现在的主要职责。

## 输出的读法

```
[PASS] 2 拉取最新代码   19709d7  Merge pull request #15 …
[FAIL] 4 db-patch 校验   补丁数对不上：宿主 5 / 容器扫到 4 / 应用+跳过 4
       宿主有而容器没扫到的补丁 ⇒ 它**没 push/没 merge**（容器只能看到远端）
```

- `[PASS]` 绿 / `[FAIL]` 红（决定退出码，**必须人工处理**）/ `[WARN]` 黄（不阻断，看一眼）/ `[SKIP]` 灰。
- 末尾有「总耗时」；> 60s 会标注（缓存冷或后端启动慢属正常）。

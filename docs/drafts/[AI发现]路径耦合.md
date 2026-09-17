# [AI发现] 路径耦合：绝对路径 `/mnt/c/wp/nexus-agent-workbench` 散落在文档与提示文案里

- **发现时间**：2026-09-17 ｜ **发现场景**：用户提出「请在 README 里请想测试的人把 repo clone 到 C 盘的 wp 目录」时，顺带全仓库排查 ｜ **状态**：待定（未修，(b) 已随 README 落地）
- **另注**：用户原始表述是「去除 hard code」。排查后**建议改名并收窄范围** —— 理由见「为什么不是『去硬编码』」。

## 现象

仓库里 `/mnt/c/wp/nexus-agent-workbench`（及其 Windows 视角 `C:\wp\nexus-agent-workbench`）出现在约 25 个文件中。仓库一旦克隆到别处，其中一部分会失效。

## 定位（按严重度分三类，**前两类的严重度都低于字面印象**）

**① 生产脚本的逻辑 —— 零处，且已从根上解决。**
`scripts/lib/probe.sh:50-52` 已从 `BASH_SOURCE` 派生，不依赖仓库位置：

```bash
NEXUS_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NEXUS_REPO_ROOT="$(cd "${NEXUS_LIB_DIR}/../.." && pwd)"
NEXUS_COMPOSE_DIR="${NEXUS_REPO_ROOT}/docker-compose"
```

`scripts/` 与 `qa/scripts/` 里的 `/mnt/c/wp` 出现**全部在注释或提示文案里**。

**② 逻辑行里仅剩 2 处，且都是「打印给人看的字符串」—— 真缺陷，但属"提示失真"而非"跑不起来"：**

| 位置 | 内容 | 换位置后的后果 |
|---|---|---|
| `scripts/check-env.sh:103` | `_info` 里那句「下一步执行 …」的建议命令 | 打印出来的建议命令指错地方；**脚本自身照跑** |
| `qa/scripts/tc01-expired-token.sh:52` | `--help`/用法示例字符串 | 同上 |

**③ 文档里的绝对路径 —— 不能一概删。**
`README.md`、`docs/test-cases/`、`docs/design/`、`.claude/agents/` 里的 `cd /mnt/c/wp/...` 是「**步骤必须可直接复制粘贴执行**」这条硬要求的产物（见 `docs/核心任务.md` 的测试案例约定、`qa-engineer` charter 编写纪律 §1）。换成占位符等于把可复现性打掉。

## 为什么不是「去硬编码」

字面执行「去除 hard code」会**要求删掉 ③**，而那与项目自己的可执行纪律**直接冲突**。所以本任务的正确定义是：

> **把「仓库在 `C:\wp\nexus-agent-workbench`」这条假设收口到一处显式声明，并把少数失真文案改成派生值。**

## 建议方向（未拍板）

- **(a) 提示文案改用派生值** —— 上面 ② 的两处，用 `$NEXUS_REPO_ROOT`（或 `$0` 所在目录）拼出来。**真缺陷，建议做。**
- **(b) 路径假设单点声明** —— **已于 2026-09-17 随 README 落地**：README 开头新增「请克隆到 `C:\wp\nexus-agent-workbench`」块，并写清换位置后什么照跑、什么会错。其余文档**暂不加引用**（避免又一处需要同步的文案）。
- **(c) 防回归检查** —— 加一条检查让新脚本不再写死。**建议先不做**：全仓库仅 2 处、且是人写文案时的疏忽，加检查的成本高于收益。若将来 ② 类问题复发，再补。

## 关联

- `README.md` 开头的克隆位置声明（本次已落地的那半）
- `qa/README.md` 的「三个视角」换算（宿主 `C:\` ↔ WSL `/mnt/c/` ↔ builder 容器 `/workspace/repo`）
- `docs/design/00-环境与部署.md` §5.1（版本号单一真源）—— 同一类问题的另一种形态：**同一事实存在多处副本时，迟早分叉**

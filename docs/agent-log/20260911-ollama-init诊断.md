# ollama-init 诊断

- **Agent**：devops-engineer ｜ **耗时**：约 3 分钟 ｜ **task-id**：a5c2e72e2d3a396be

## 做了什么

- 用 `docker compose config --services` 确认 `ollama-init` **没有 `profiles:` 键 → 在默认 `up -d` 服务图内**（对照：`builder` 有 `profiles: ["build"]`，只在显式 profile 下出现）。这是"干净机器一条命令自举"能力所在。
- 判定"启动不了"**最可能是正常现象**：`docker compose ps` 默认只列运行中容器，而它是 `restart: "no"` 的一次性容器，跑完 `Exited (0)` 就被过滤掉。
- 提出真故障候选 **F5**：裸跑 `docker compose up -d` 时默认图含 `nexus-backend`/`nexus-frontend`，两者 `FROM nexus-builder:dev`，而 builder 镜像尚未构建（`backend/`、`frontend/` 为空目录）→ 整个 up 在构建阶段失败，ollama-init 根本没机会跑。
- 判定幂等判断 bug **成立**，并**修正了量级**（见下）。
- 结论：**不建议删除**，给出 1 行修复 diff + 6 条验证命令清单，未动手改任何文件。

## 遇到的坑

- **`ollama list` 的 NAME 列 `:latest` 陷阱**：脚本写 `awk '{print $1}' | grep -qx "nomic-embed-text"`，但列表里该模型显示为 `nomic-embed-text:latest`，`-x` 要求整行相等 → **永不命中** → 跳过分支对第二个模型失效，与注释"重复 up -d 幂等、不重复下载"矛盾。第一个模型 `qwen2.5:7b` 自带 tag，与列表行一致，所以它的跳过是有效的 —— 这也是首次 `up -d` 时看不出问题的原因。
- **量级误判（重要）**：起初判断为"每次 `up -d` 重拉 274MB"，实际 `ollama pull` 是内容寻址的，blob 按 digest 已在共享卷里会被复用，真实动作只是多发一次 manifest 请求（秒级）。**真正的危害不是流量，而是凭空给 `up -d` 加了一次外网依赖** —— registry 不通时这次多余 pull 失败 → `set -e` → 容器 `Exited (1)`，即**制造一个假故障**。
- **静态分析的边界**：无法离线确认 ollama 0.33.2 CLI 是否主动剥掉 `:latest`，"CLI 渲染格式"这一步是推断而非实测 → 已在报告中标注残余不确定性，并指定一条命令（`ollama list`）即可定论。
- **纪律约束下的取舍**：用户的 docker 纪律只允许 `docker compose config` 静态校验，不得跑 up/pull/build 及探测类命令。因此"启动不了"的真实原因**未实测**，只能给排序后的假设 + 验证命令，由用户批准后执行。

## 下次怎么改进

- **一次性容器排障第一步永远是 `ps -a`**，不是 `ps`。`ps` 默认过滤已退出容器，会把"设计上就该退出"误读成"起不来"。
- **凡结论依赖"某条命令的输出格式"，先找一条实测证据再下判断**，不要从脚本写法反推。这次的 `:latest` 问题本可以用一条 `ollama list` 当场定论。
- **判断"要不要删"时，先算干净机器的代价**，不要只看当前这台热机器。"现在什么都没干"≠"用不上" —— 本例中删掉的真实后果是：新环境 `up -d` 后 ollama 健康检查**照样绿**（零模型时 `ollama list` 退出码也是 0），故障推迟到运行期以深层 500 暴露，是比启动期报错**更差**的失败模式。
- **修 1 行 < 删一个服务**：一次性 init 容器的保留成本约等于零（跑完即退、复用已有镜像），不要为了"清爽"牺牲可复现性。
- 判定幂等逻辑时，**在列表侧做归一化，不要在匹配侧赌显示格式**：`awk 'NR>1 {sub(/:latest$/, "", $1); print $1}'` 优于 `grep -qxE "${model}(:latest)?"`（后者在模型名含正则元字符时会误伤）。

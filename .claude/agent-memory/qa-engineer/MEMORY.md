# qa-engineer 记忆索引

- [机械多步命令要脚本化](script-mechanical-steps.md) — 用户口径：要人手工拼命令拿判据的步骤一律收进 qa/scripts/；抽公共库的判据与时机
- [夹具清理承诺必须自证](fixture-cleanup-claims.md) — EXIT trap 在信号下不执行、后台 bash 抓不到 SIGINT、curl -o 连不上时不建文件（均已实测）
- [判据可证伪性](criteria-falsifiability.md) — 问「失效时这条会不会照样通过」；40101 同码多义 + 按 jti 分流日志拆开它；「预期」里点的观察必须真在屏幕上出现（零 delta 失败走整轮回滚）
- [重建容器会打断 nginx 上游](recreate-breaks-nginx-upstream.md) — `--force-recreate` 换 IP ⇒ 8088 变 502，跑前/还原后都要验链路
- [桩要复刻契约语义](stub-faithfulness.md) — 桩少一条判定就等于开后门（logout 未过过滤器那次的教训）；干跑输出要逐行读
- [TC 增删的记录纪律](tc-edit-record-discipline.md) — 删用例必须登记（附录写"评估过"、编号留空号不重排）；「手工形态跑过」≠「脚本形态跑过」
- [本地自编自跑单测的路线](local-test-compile-route.md) — 宿主无 mvn：JDK17 + `.tmp` 的 jar 缓存 + javac argfile（`-encoding UTF-8` 必加）+ junit console standalone；59 条 4 秒跑完
- [8088 的 1MB 体量墙](nginx-1mb-body-wall.md) — 走 nginx 上传 >1MB 被 413 挡（实测），验收夹具 1.6MB 受影响；只读探针写法 + 修好后删这条

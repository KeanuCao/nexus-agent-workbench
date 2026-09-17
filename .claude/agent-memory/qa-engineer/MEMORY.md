# qa-engineer 记忆索引

- [机械多步命令要脚本化](script-mechanical-steps.md) — 用户口径：要人手工拼命令拿判据的步骤一律收进 qa/scripts/；抽公共库的判据与时机
- [夹具清理承诺必须自证](fixture-cleanup-claims.md) — EXIT trap 在信号下不执行、后台 bash 抓不到 SIGINT、curl -o 连不上时不建文件（均已实测）
- [判据可证伪性](criteria-falsifiability.md) — 问「失效时这条会不会照样通过」；40101 同码多义 + 按 jti 分流日志拆开它
- [桩要复刻契约语义](stub-faithfulness.md) — 桩少一条判定就等于开后门（logout 未过过滤器那次的教训）；干跑输出要逐行读

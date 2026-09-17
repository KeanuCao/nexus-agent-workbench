# qa-engineer 记忆索引

- [机械多步命令要脚本化](script-mechanical-steps.md) — 用户口径：要人手工拼命令拿判据的步骤，一律收进 qa/scripts/（脚本不打 PASS/FAIL）
- [夹具清理承诺必须自证](fixture-cleanup-claims.md) — EXIT trap 在信号下不执行、后台 bash 抓不到 SIGINT、curl -o 连不上时不建文件（均已实测）
- [判据可证伪性](criteria-falsifiability.md) — 问「失效时这条会不会照样通过」；40101 同码三义 + 夹具前提必须能当场证伪

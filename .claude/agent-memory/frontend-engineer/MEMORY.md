# frontend-engineer 记忆索引

- [接口契约来源](api-contract-source.md) — 唯一事实源已是 `docs/api/README.md`；503 保留 `data`、`timestamp` 是 `Z` 后缀且不由前端管
- [前端依赖版本基线](dependency-version-baseline.md) — 按设计文档主版本落地（Vite 5/Pinia 2/vue-router 4）而非 npm 最新大版本，含理由与升级路径
- [鉴权链路验收要点](auth-skeleton-verification.md) — 白名单接口不会 401、改 token 需刷新、restore 失败必须清 token 的防环理由
- [第三方库行为要源码实证](library-behavior-verify-by-source.md) — 415 事故的教训：断言漏前置条件=返工一轮；含浏览器环境探针的桩法
- [估算数字 vs 实测数字](estimated-vs-measured-numbers.md) — 估算被实测推翻就地订正（含 UI 文案）+ 留作废痕迹；耗时只当参考值、不当判据

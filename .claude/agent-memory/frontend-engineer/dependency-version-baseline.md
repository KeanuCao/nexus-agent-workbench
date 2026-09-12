---
name: dependency-version-baseline
description: 前端依赖按设计文档 §5.2 的主版本落地（Vite 5 / Pinia 2 / vue-router 4），未取 npm 最新大版本及其理由
metadata:
  type: project
---

0.4 前端骨架落地时，依赖**按 `docs/design/00-环境与部署.md` §5.2 指定的主版本**取该主版本内的最新补丁版，而不是 npm 上的最新大版本：

| 包 | 落地版本 | npm 最新稳定（2026-09-11 实测） |
|----|----------|--------------------------------|
| vue | 3.5.42 | 3.5.42（一致） |
| vite | 5.4.21 | **8.3.0** |
| @vitejs/plugin-vue | 5.2.4 | 6.0.8 |
| pinia | 2.3.1 | **4.0.3** |
| vue-router | 4.6.4 | **5.3.1** |
| element-plus | 2.14.5 | 2.14.5（一致） |
| axios | 1.20.0 | 1.20.0（一致） |
| typescript | 5.9.3 | **7.0.2** |

**Why:** ① 设计文档 §5.2 明确写 Vite 5.x / Pinia 2.x / vue-router 4，任务指令也写明 vue-router 4 —— 偏离已批准的选型需要先说明原因；② 构建容器 `nexus-builder` 是 **Node 20**（nodesource `setup_20.x`），而 vite 8 / @vitejs/plugin-vue 6 的 engines 要求 `^20.19.0 || >=22.12.0`；③ vue-router 5.3.1 的 peerDependencies 要求 `vite: ^7.3.0 || ^8.0.0`、`pinia: ^3.0.4 || ^4.0.2`，取下它就得把 vite/pinia 一起升大版本，连锁升级未经验证。

**How to apply:** 用户尚未确认是否升到最新大版本；若后续要求升级（例如为了面试展示新技术栈），必须**整套一起升**（vue-router 5 ↔ vite 7.3+/pinia 3+），并重新验证 `npm ci` 在 Node 20 构建容器内可跑通。升级前先确认构建容器的 Node 补丁版本 ≥ 20.19.0。相关：[[api-contract-source]]

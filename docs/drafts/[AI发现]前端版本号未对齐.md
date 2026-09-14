# [AI发现] 前端 `package.json` 版本号与后端不联动

- **发现时间**：2026-09-15 ｜ **发现场景**：排查后端版本号分散问题时顺带发现 ｜ **状态**：待定（用户当日决定本次不改）

## 现象

| 位置 | 值 |
|---|---|
| `frontend/package.json:3` | `"version": "0.1.0"` |
| `frontend/package-lock.json:3` | `"version": "0.1.0"` |
| 后端 `backend/pom.xml` 的 `<revision>` | `0.2.1` |

两者各走各的，从项目开始就没联动过。

## 已核实的事实

该字段**目前没有任何地方读它** —— Vite 构建出的是一堆静态文件；页面上显示的版本号来自后端 `/api/health` 返回的 `data.version`（见 `frontend/src/components/HealthCheckPanel.vue:119`）。所以「对齐」纯粹是数字上的一致，不影响任何行为。

## 为什么当时不改

单独对齐会使「对外发布方便」这个目标**反向恶化**：每次发版要改 2 处（后端 `<revision>` 一行 + 前端 `version` 一行），而能让两处自动同步的 `release.sh` 恰好本次不做。

## 建议方向

与 `scripts/release.sh` 一起做（脚本一次改两处），届时再决定前端版本是「跟随后端」还是「独立但同批发布」。

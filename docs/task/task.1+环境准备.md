# task.1 环境准备与检查（阶段0）

> 任务级别：L1 阶段级（跨模块、影响架构） ｜ 状态：⏳ 待开始
> 设计文档：`docs/design/00-环境与部署.md` ｜ 测试案例：`docs/test-cases/TC-00.md`

## 任务目标
一键拉起整套环境

## 执行要求
本任务为 L1 阶段级任务：先产出设计文档并经确认后再编码；完成后更新 `docs/核心任务.md` 进度标记并生成 TC-00。

## 子任务拆解

### 0.1 基础设施编排（docker-compose.yml）— 第一步交付物
- **输入**：技术选型约束（PostgreSQL 16 + pgvector、Redis 7、Ollama）
- **输出**：`docker-compose/docker-compose.yml`
- **依赖**：无
- **验收标准**：
  - 配置国内镜像加速（registry-mirrors）
  - 一次 `docker compose up -d` 拉起 PG / Redis / Ollama
  - Ollama 就绪并拉取 `qwen2.5:7b`、`nomic-embed-text`
  - 后端、前端独立容器；
  - 单独的专门用来打包的独立容器: builder 构建前后端包、运行期不常驻（前后端打包共用构建阶段，酌情拆分）

### 0.2 db-patch 数据库补丁工作流
- **输入**：补丁命名规则 `YYYYMMDDHHmm_描述.sql`
- **输出**：补丁记录表 `t_db_patch` + 启动扫描执行逻辑 + 首条补丁
- **依赖**：0.1
- **验收标准**：
  - 未执行补丁按文件名排序依次应用
  - 已执行补丁再次执行必须报错终止（记录表 + checksum 幂等保护）
  - 涉及表结构变更一律新增补丁，严禁修改已发布历史补丁

### 0.3 环境检查与一键启动脚本
- **输入**：无
- **输出**：`scripts/check-env.sh`、`scripts/up.sh`
- **依赖**：0.1
- **验收标准**：
  - `check-env.sh` 自检 Docker / WSL / 端口占用 / 镜像源 / 模型就绪
  - `up.sh` 一键拉起全套环境
  - `wsl.abc.md` 记录 WSL 排查经验：遇到问题用日志 + 官方文档交叉验证，不照抄 AI 文档

### 0.4 前后端项目骨架
- **输入**：目录结构约定（backend 五模块 / frontend）
- **输出**：backend Maven 多模块骨架（含可启动的 nexus-start）+ frontend Vite 骨架（Vue 3 setup + Element Plus + Pinia）
- **依赖**：无（可与 0.1 并行）
- **验收标准**：
  - `mvn clean install -DskipTests` 通过
  - 后端健康检查接口返回 `Result<T>` 统一格式
  - `npm run dev` 可启动

## 🚩 阶段交付物
`docker-compose.yml`、db-patch 工作流、`check-env.sh` / `up.sh` 、前后端容器

## ✅ 阶段验收标准
拿到仓库后执行 `./scripts/up.sh` 即可拉起全部环境，后端健康检查、前端页面均可达

## 完成状态
- [x] 0.1 基础设施编排
- [ ] 0.2 db-patch 补丁工作流
- [ ] 0.3 环境检查与启动脚本
- [ ] 0.4 前后端项目骨架

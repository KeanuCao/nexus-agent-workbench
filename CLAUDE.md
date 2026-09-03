# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 📌 项目核心定位
这是一个用于求职面试的**Java全栈展示项目**，目标是复刻"AI 数字员工平台"的核心能力。我的背景是 Python 转 Java，此项目用于展示我从架构设计到编码交付的完整工程能力。

## 🎯 核心目标
1. **技术栈精准命中**：严格使用 Java 17 + Spring Boot 3.x + Vue 3 (setup) + Element Plus。
2. **解决真实痛点**：实现一个轻量级的"统一 AI 网关"和"RAG 知识库问答"。
3. **产出工程化代码**：代码必须遵循阿里巴巴/Google Java Style，包含完善的异常处理和日志。

--
## 📚 文档体系（必读）

本项目采用"规范 + 任务"分离的文档结构，Claude 在开始工作前必须理解：

| 文档 | 职责 | 何时查阅 |
|------|------|---------|
| **CLAUDE.md**（本文档） | 权威规范：技术栈约束、编码规范、架构约定、工作流程 | 每次对话开始时自动加载 |
| **docs/核心任务.md** | 任务清单：阶段目标、交付物、验收标准、当前进度 | 接到任务、状态汇报、阶段推进时 |

### ⚠️ 关键约定
- **CLAUDE.md 是"宪法"**：编码规范、技术选型、禁止事项以本文档为准
- **核心任务.md 是"作战地图"**：阶段划分、具体任务、交付物清单以该文档为准
- **禁止重复维护**：CLAUDE.md 中不再保留"核心功能开发清单"章节，统一引用 `docs/核心任务.md`
- **进度同步**：每次阶段完成后，更新 `docs/核心任务.md` 中的进度标记，CLAUDE.md 仅保留状态更新协议

---

## 🏗️ 项目架构与目录结构（约定）
请按以下目录结构生成代码，保持模块清晰：

```
/backend                    # Spring Boot 后端 (Maven 多模块)
├── pom.xml                 # 父 POM (Spring Boot 3.3.x)
├── nexus-common            # 公共模块 (工具类、异常、统一响应)
├── nexus-infrastructure    # 基础设施 (多租户拦截器、JWT、数据库配置)
├── nexus-module-system     # 系统模块 (用户、角色、菜单)
├── nexus-module-ai         # AI 核心模块 (统一网关、RAG、Agent编排)
└── nexus-start             # 启动模块 (包含 Application 主类)
/frontend                   # Vue 3 前端 (Vite)
├── src
│   ├── api                 # 后端接口封装 (Axios)
│   ├── views               # 页面 (登录、控制台、知识库管理、Agent编排)
│   └── components          # 公共组件
/docker-compose             # 环境依赖 (PostgreSQL+pgvector, Redis, Ollama, 构建容器)
/db-patch                   # 数据库补丁 (命名规则见 db-patch 工作流章节)
/scripts                    # 环境检查与一键启动脚本 (check-env.sh / up.sh)
/docs/test-cases            # 每个阶段交付后的测试案例 (TC-00, TC-01, ...)
```

---

## ⚙️ 技术选型与版本约束（严格遵守）
- **后端基础**：Java 17, Spring Boot 3.3.x, Maven 3.9+
- **ORM/数据层**：MyBatis-Plus 3.5.x (便于多租户实现)
- **数据库**：PostgreSQL 16 + pgvector 扩展 (用于向量检索)
- **缓存/会话**：Redis 7.x (用于存储 JWT 和租户信息)
- **AI 集成**：Spring AI 或 OkHttp + 策略模式 (对接 Ollama/DeepSeek)
- **前端**：Vue 3 (script setup) + TypeScript, Vite, Element Plus, Pinia

---

## 📝 编码规范与设计原则（必须遵守）
1. **统一返回体**：所有 API 返回 `Result<T>` 格式 (`code`, `msg`, `data`)。
2. **全局异常处理**：使用 `@RestControllerAdvice` 捕获业务异常，不返回堆栈信息给前端。
3. **多租户强制隔离**：使用 MyBatis-Plus 的 `TenantLineHandler` 自动注入 `tenant_id`，任何查询都必须带上租户条件（除非用 `@IgnoreTenant` 注解跳过）。
4. **日志规范**：关键流程（调用大模型、向量入库）必须打印 `log.info`，方便面试时展示调用链路。
5. **Git 提交**：请按功能点分批提交，commit message 使用 `feat: 添加统一AI网关` 格式。

---

## 🚫 针对"Python转Java"的特别限制（避免露怯）
1. **不要出现 Python 语法**：严禁在 Java 代码中使用 `_` 作为变量名，严禁滥用 `var`（只在类型明确且不可变时使用，如 `var list = new ArrayList<String>()`，否则必须显式声明类型）。
2. **不要使用 `*` 导入**：Java 代码必须明确写出导入的类名（如 `import java.util.List;`，严禁 `import java.util.*;`）。
3. **Controller 层注解规范（Spring Boot 3.x 现代风格）**：
   - 统一使用 `@GetMapping`、`@PostMapping`、`@PutMapping`、`@DeleteMapping` 等组合注解，不要使用 `@RequestMapping(method = RequestMethod.GET)`。
   - 必须显式指定 `produces` 和 `consumes` 属性（如 `@PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)`），以明确接口契约，便于 Swagger/OpenAPI 文档生成。
4. **异常处理要细**：不要所有异常都 `catch Exception`，至少要分 `BusinessException`（业务异常，前端可展示）和 `SystemException`（系统异常，记录日志并返回通用错误）。

---

## 🔧 常用命令

```bash
# ── 环境准备与启动（Windows + WSL，Docker 使用国内镜像源）──
./scripts/check-env.sh                        # 环境自检：Docker/WSL/端口/镜像源/模型是否就绪
./scripts/up.sh                               # 一键启动 docker compose 全套环境
docker compose -f docker-compose/docker-compose.yml up -d   # 或手动拉起基础设施

# ── 后端（Maven 多模块）──
mvn clean install -DskipTests                 # 全量构建
java -jar nexus-start/target/*.jar            # 本地启动（依赖环境容器已就绪）
mvn test -pl nexus-module-ai                  # 只跑单个模块的全部测试
mvn test -pl nexus-module-ai -Dtest=XxxServiceTest#methodName   # 跑单个测试方法

# ── 前端 ──
cd frontend && npm install && npm run dev     # 开发模式
npm run build                                 # 生产构建（正常由构建容器执行）

# ── 数据库变更 ──
# 不要手工改库！新增 <db-patch>/YYYYMMDDHHmm_描述.sql，按 db-patch 工作流执行
```

---

## 🐳 环境与部署约定
1. **演示环境**：Windows + WSL。整理并维护 `wsl.abc.md` 作为 WSL 参考文档；注意 AI 时代的文档可能不正确，遇到问题时要自己排查解决（用日志、官方文档交叉验证），不要照抄。
2. **容器化**：前端、后端、PostgreSQL、Redis、Ollama 各自独立容器部署，全部容器化。
3. **国内镜像加速**：镜像一律通过 Dockerfile `FROM` / compose `image:` 前缀走 `docker.m.daocloud.io` 加速（官方镜像走 `/library/` 路径），**禁止修改 `/etc/docker/daemon.json`**；这是第一步要生成的交付物。
4. **构建容器（builder）**：docker compose 中配置专门的打包容器 `nexus-builder`（git + mvn + npm + postgresql-client），负责前后端打包与 db-patch 迁移执行；采用多阶段构建（multi-stage build）：前后端以 `nexus-builder` 为共用构建阶段（stage 1），后端产物复制到 JRE 运行镜像、前端产物由 Nginx 镜像承载。**尽量节约资源**：构建容器只在打包/迁移时使用，运行期不常驻。

---

## 🗂️ db-patch 数据库补丁工作流
- 补丁目录 `/db-patch`，命名规则 `YYYYMMDDHHmm_描述.sql`（如 `202609030900_初始化多租户表.sql`），按文件名排序依次执行。
- 数据库内置补丁记录表（如 `t_db_patch`，含 patch_id、文件名、执行时间、checksum 等字段）。
- 由 builder 容器执行迁移（`PatchCli`，见 `docs/design/00-环境与部署.md` §3）：扫描补丁目录，未执行过的补丁按顺序应用；**已执行过的补丁再次执行必须报错终止**（通过记录表 + checksum 校验实现幂等保护）。
- 涉及表结构变更时新增补丁文件，严禁修改已发布的历史补丁。

---
## 🧭 任务执行协议（Task Execution Protocol）

为避免"接到需求就写代码"导致的架构失控，所有任务按粒度分级执行：

### 📐 任务分级标准

| 级别 | 判定标准 | 典型例子 | 交付物 |
|------|---------|---------|--------|
| **L1 阶段级** | 跨模块、影响架构、>1天工作量 | 多租户实现、RAG 整体设计、Agent 编排 | ① 设计文档 ② 子任务拆解 ③ 代码 ④ 测试案例 |
| **L2 模块级** | 单模块内、涉及多文件 | 统一 AI 网关策略模式、JWT 过滤器 | ① 简要设计说明（接口契约+类图） ② 代码 ③ 测试案例 |
| **L3 任务级** | 单文件、明确需求 | 新增一个 DTO、修复一个 bug、调整配置 | ① 代码 ② 必要时补测试 |

### 📝 L1/L2 任务的标准流程

**Step 1 - 任务分解**（先输出，等我确认）
- 把大任务拆成 ≤5 个子任务，每个子任务明确：输入/输出/依赖/验收标准
- 输出到 `docs/design/<阶段>-<模块>.md`

**Step 2 - 关键设计决策**（写在设计文档里）
- 为什么选这个方案？备选方案是什么？trade-off 在哪？
- 接口契约（URL、请求体、响应体、错误码）
- 核心类图 / 时序图（用 Mermaid）

**Step 3 - 我确认后再写代码**
- 未经确认不要直接生成代码
- 代码必须与设计文档一致，偏离时要说明原因

**Step 4 - 同步更新测试案例**
- 按 `docs/test-cases/TC-XX.md` 规范补充

### 🚀 L3 任务直接执行
无需设计文档，但 commit message 必须清晰，代码必须带注释说明意图。

### ⚠️ 特别约定
- **禁止"边想边写"**：复杂逻辑必须先有设计再动手
- **禁止"文档滞后"**：代码改完必须同步文档
- **面试友好**：设计文档控制在 1-2 页 A4，重点是架构图和决策理由，不要写成论文

---

### 🚀 环境与运行指令
- 提供 `docker-compose.yml`，包含：
  - PostgreSQL 16 (带 pgvector 插件)
  - Redis 7
  - Ollama (拉取 `qwen2.5:7b` 和 `nomic-embed-text`)
- 后端启动命令：`mvn clean install -DskipTests && java -jar nexus-start/target/*.jar`
- 前端启动命令：`npm install && npm run dev`
- **要求**：编写 `README.md` 时，必须包含上述一键启动指令。

## 🔄 状态更新协议

每当我输入 `Status` 或 `继续下一个阶段`，请你：
1. 阅读 `docs/核心任务.md`，回顾当前已完成的功能点和下一阶段目标
2. 阅读现有代码，核对交付物是否齐全
3. 更新 `docs/核心任务.md` 中的进度标记
4. 给出下一步具体的开发指令
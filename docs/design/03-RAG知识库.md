# 03 RAG 知识库 — 阶段3 设计文档

> 任务级别：**L1 阶段级** ｜ 状态：✅ **已确认（2026-09-22）**，实施中 —— 后端第一段（契约 / DB 补丁 / 配置）已交付，见 `docs/agent-log/20260922-阶段3后端实施第一段.md` ｜ 对应任务：`docs/task/task.4+RAG知识库.md`
> 前置设计：`docs/design/01-多租户与认证.md`（租户上下文 / fail-closed 注入 / 错误码分段）、
> `docs/design/02-统一AI网关.md`（`AiModelService` 端口 / 工厂 + 策略 / 超时口径）—— 本文档**不重开**已确认的决策，只做增量。
> 编写依据：`docs/drafts/RAG建议.md`（选型与范围）+ `docs/task/task.4+RAG知识库.md`（交付物与验收标准）。
> 约定速记（沿用 01/02 的纪律）：
> 1. **未经实测的判据一律标注「待实测确认」**，不写成结论；
> 2. **契约先落**（`docs/api/README.md` + `openapi.yaml`）**再写代码**；
> 3. **文档自相矛盾必须上报**，不得自行挑一条照做（本阶段的三处矛盾与处置见 §0.1）。

## 0. 本轮范围

| 范围 | 来源 | 交付物 |
| --- | --- | --- |
| 3.1 向量存储层 | task.4 | `t_kb_document` / `t_kb_chunk` 两张表 + HNSW 索引（db-patch 补丁）+ 向量检索 SQL |
| 3.2 文件上传与解析 | task.4 | `POST /api/kb/documents` + `DocumentParser` 端口（TXT 直读 / PDF 走 Tika） |
| 3.3 分块向量化入库 | task.4 | `TextChunker`（500 / 50，可配）+ `nomic-embed-text` 向量化 + 单事务入库（带 `tenant_id`） |
| 3.4 知识库问答接口 | task.4 | `POST /api/kb/ask`：问题 → 向量 TopK → 拼 Prompt → 复用阶段2 网关生成 → 答案 + 引用片段 |
| 3.5 知识库前端页面 | task.4 | 知识库页：上传 / 文档列表 / 删除 + 问答入口（答案 + 引用卡片） |

**阶段交付物**：知识库页面 + RAG 问答完整链路
**阶段验收标准**（task.4 原文）：上传「公司年报.pdf」，提问「去年利润是多少」能准确回答。

### 0.1 与 `RAG建议.md` / task.4 的三处矛盾及处置（**2026-09-22 用户拍板**）

两份文档在三个点上互相打架。按 CLAUDE.md 的纪律：**指出来、不自行挑一条照做**。三处已当场问、当场定：

| # | 矛盾点 | 两份文档的说法 | 处置（用户拍板） |
| --- | --- | --- | --- |
| 1 | 文件格式范围 | `task.4` 3.2 验收标准："**三种格式**均解析出纯文本"（PDF / Word / TXT）；`RAG建议.md`："上传 TXT 或 PDF……多格式记 backlog" | **本轮只做 TXT + PDF**。Word 的开启成本 = 多一行依赖（Tika 的 Microsoft 模块）+ 一个用例，路径写进 §10.1，**不是删除，是排期**。⚠️ 因此 task.4 3.2 的验收标准本轮按"**两种格式**"执行，`docs/核心任务.md` 与 TC-03 要按此口径写，避免验收时才发现两边理解不一致 |
| 2 | 表结构 | `task.4` 3.1 输出："**知识库** / 文档 / 分块表"（三张）；`RAG建议.md`："多知识库……记 backlog" | **两张表**：`t_kb_document` + `t_kb_chunk`，**知识库维度 = `tenant_id`**（一个租户一个知识库）。理由：一张本轮没有任何接口/页面能创建、且永远只有一行的表，是 DB 形态的死代码。多知识库的演进路径见 §10.2（加 `kb_id` 列 + 新增补丁，不改前端契约） |
| 3 | 向量库接入方式 | `task.4` 3.1："spring-ai-pgvector-store 集成**或**手写 pgvector 入库逻辑"；`RAG建议.md`："手写，不引框架" | **手写**（决策 D1/D2）。与阶段2 的 D2（不引 Spring AI）同源：本阶段要展示的是"分块 / 检索 / 拼 prompt 的每一步"，引框架等于把要展示的东西换成框架自带实现 |

> **另一处**（非矛盾，是既有文档的**预告**）：`docs/api/README.md` §4.6 已写明"`installed_version IS NOT NULL` 才算已安装（**阶段3 RAG 落地后才作为硬判据**）"。
> ⇒ 本阶段落地后，`scripts/check-env.sh` 的 pgvector 检查项应从"扩展可用"升级为"扩展已安装"硬判据。**这是 devops 的连带任务**，列在 §6.6。

### 0.2 各角色照着哪一节干活（本节的唯一目的：让颗粒度可执行）

| 角色 | 照哪几节 | 产出 |
| --- | --- | --- |
| `backend-engineer` | §3（契约）+ §4（实现结构）+ §6.1/§6.2/§6.4 | 后端类 + DB Patch + `docs/api/README.md` §7 + `openapi.yaml` |
| `frontend-engineer` | §3（契约）+ §5（前端结构） | `KnowledgeView.vue` 重写 + `api/kb.ts` |
| `devops-engineer` | §6（配置与部署）+ §6.6（连带任务） | db-patch 迁移执行 + builder 构建 + `check-env.sh` pgvector 判据升级 |
| `qa-engineer` | §7（测试）+ §8（验收对照）+ §3.5（失败形态） | `docs/test-cases/TC-03.md` + §7 的单测 |
| `task-decomposer` | §0.2 + §10 | 无需再拆：task.4 的 3.1~3.5 已是原子级，本表只标注归属 |

---

---

## 0.3 ★ 2026-09-22 晚：embedding 模型由 `nomic-embed-text` 换为 `bge-m3`（**先读这一节**）

**起因是端到端验收的一条判据不成立**（devops 实测，非推断）：上传一份中文年报（587 块）后问「去年利润是多少」，
接口返回 `grounded=true` 但答案是"知识库中未找到相关内容" —— 引用的 5 条与问题无关。

**四个替代假设都被数字排除**：① 不是前缀不对称（`cos(库内向量, 带前缀重算) = 1.000000`）；
② 不是生成坏了（换问"受益计划的服务年限"→ 答 27.4 年，与原文一致）；③ 不是解析（`84.1 亿元` 与
`8,408,057 千元` 都在正文里，Tika 抽出 263,910 字）；④ 不是阈值筛掉（反了：阈值 0.5 **一条都没筛掉**）。

**真正的数字（这就是换模型的全部理由）**：含答案的分块在 587 块中的排名 ——
问「去年利润是多少」= **第 27 名**、问「归属于上市公司股东的净利润是多少」= **第 47 名**、
问「报告期内公司实现的营业总收入是多少」= 第 10 名；而**完全无关**问题的 top1 就有 **0.6929**，
5 条无关块拿 **0.71~0.75** ⇒ 阈值标定救不了（`(S_miss+S_hit)/2` 落在无关块的分数带里）。
定性：`nomic-embed-text` 在**中文细粒度检索**上的区分度不足。

**换模型牵动的四处（一次改全，缺一处就是"不报错、只是变差"）**：

| # | 位置 | 改动 | 为什么必须同改 |
| --- | --- | --- | --- |
| 1 | `OllamaEmbeddingService.EMBED_MODEL` | `nomic-embed-text` → `bge-m3` | 模型本身 |
| 2 | `OllamaEmbeddingService.EXPECTED_DIMENSION` | `768` → `1024` | 与 DB 的 `vector(N)` 是同一条真源；只改一个会在**第一次入库**时以类型错现形 |
| 3 | DB 补丁 `202609221100` | 重建两表为 `vector(1024)`（**清空数据**） | 存量向量与新模型不在同一空间；列上已有 768 维数据 ⇒ 不能 `ALTER COLUMN TYPE` |
| 4 | `nexus.ai.rag.document-prefix` / `query-prefix` | 由 `search_document: ` / `search_query: ` 改为**空串** | 那两个前缀是 **nomic 的模型卡建议**（D14 的由来）；bge-m3 不期望它们 ⇒ 保留等于给模型硬塞英文任务前缀 |

**连带要做**：`docker-compose.yml` 的 `ollama-init` 模型清单、`scripts/lib/probe.sh` 的期望模型名
（都属 **devops**）；`docs/api/README.md` §4.5 的模型就绪判据（属**契约**，本节同步改）。
**重灌**：换模型后**必须重传文档**（本轮不保存原文件 ⇒ 只能重传；验收夹具约 90 秒）。

**仍然没解决的（记 backlog，本轮不做）**：混合检索（关键词 + 向量，对"精确词/数字"类问题最稳）、
rerank、分块粒度调参、`nomic` 前缀的 A/B（模型已换，该实验作废）。
另有一条**规划器观察**（devops 实测）：生产那条带 tenant JOIN + score 列的 SQL，在 587 行时规划器选
`Bitmap Index Scan(tenant_id) + Sort`（cost 119 vs HNSW 761）—— 小表下是正确取舍，量级上去后要复看。

---

## 1. 总体架构

```mermaid
graph TD
    subgraph FE[前端 nexus-frontend]
        KV[KnowledgeView 知识库页<br/>上传 / 列表 / 删除 / 问答] --> KBAPI[api/kb.ts<br/>axios FormData + 逐请求超时]
        KBAPI --> RQ[api/request.ts<br/>拦截器：鉴权 / 解包 / 401 处置]
    end

    subgraph RAG[后端 nexus-module-ai · com.nexus.module.ai.rag]
        DC[KbDocumentController<br/>上传 / 列表 / 删除] --> DS[KbDocumentService<br/>编排：解析→分块→向量化→入库]
        AC[KbAskController<br/>POST /api/kb/ask] --> AS[KbAskService<br/>检索→拼 prompt→生成]
        DS --> DP[DocumentParser<br/>TXT 直读 / PDF 走 Tika]
        DS --> TC[TextChunker<br/>固定窗口 + 重叠]
        DS --> EMB[EmbeddingService<br/>OllamaEmbeddingService]
        DS --> CM[KbChunkMapper<br/>自定义 SQL]
        AS --> CM
        AS --> PB[PromptBuilder]
        AS --> GEN[ModelAnswerGenerator]
    end

    subgraph GW[后端 nexus-module-ai · com.nexus.module.ai.gateway 阶段2 既有]
        GEN --> FAC[AiModelFactory]
        FAC --> DSK[DeepSeekService]
        FAC --> OLL[OllamaService]
    end

    subgraph INFRA[nexus-infrastructure 既有]
        JF[JwtAuthenticationFilter] --> TCTX[TenantContext ThreadLocal]
        TCTX --> TLH[TenantLineHandlerImpl<br/>fail-closed 注入 tenant_id]
    end

    RQ -->|Authorization: Bearer| JF
    JF --> DC
    JF --> AC
    CM -->|自定义 SQL + 自动注入 tenant_id| PG[(PostgreSQL 16<br/>pgvector · HNSW)]
    EMB -->|POST /api/embed| OL[(Ollama 容器<br/>bge-m3 · 1024 维<br/>2026-09-22 前为 nomic-embed-text)]
    DSK -->|SSE /chat/completions| CLOUD[(DeepSeek 云端<br/>deepseek-chat)]
```

**依赖方向**（父 POM 硬约束）：`common ← infrastructure ← module-ai ← start`。本阶段新增的类一律落在
`nexus-module-ai` 的 `com.nexus.module.ai.rag` 包（`nexus-module-ai/README.md` 的阶段计划表已约定该包名），
子包 `config / controller / dto / entity / mapper / parse / chunk / embedding / prompt / generate / service` 都挂在它下面，**不新增模块**。

**模块内的方向也是单向的**：`rag → gateway`（RAG 复用阶段2 的模型端口），**反过来不成立** ——
`gateway` 里的任何类都不得 import `rag` 里的东西。判据：`grep -rn "module.ai.rag" backend/nexus-module-ai/src/main/java/com/nexus/module/ai/gateway/`
必须无输出。

> ⚠️ **RAG 链路全程在请求线程上**（无工作线程、无 `SseEmitter`）。这直接决定了三件事，都是本阶段的红利：
> ① **不会**踩到阶段2 D10 那个"工作线程没有租户上下文"的坑（`TenantContext` 在请求线程上就是好的）；
> ② 失败可以给**真正的 HTTP 状态码**（阶段2 的 `20100` 有两种载体，本阶段只有 `503` 一种）；
> ③ 代价是**每个上传/提问占一个 Tomcat 请求线程直到结束**（见 D7 的取舍）。

---

## 2. 关键设计决策

| # | 决策 | 备选 | 理由 / trade-off |
| --- | --- | --- | --- |
| **D1** | **手写 RAG 链路，不引 Spring AI / LangChain4j 的 RAG 组件** | `spring-ai-pgvector-store` + `QuestionAnswerAdvisor` | 与阶段2 的 D2 同源：本阶段要展示的正是"分块策略 / 检索 SQL / prompt 拼装"这三件框架替你做的事。**代价**：解析、分块、类型对接、prompt 全自己写（核心逻辑约 200 行 + 一个 TypeHandler）。**这不是省事，是选考点** |
| **D2** | **向量存取的 SQL 自己写**（MyBatis 注解语句），`embedding` 列的类型对接用一个 `VectorTypeHandler` | ① pgvector-java 官方库（`PGvector` 类型）② JPA / MP 的通用 CRUD | 检索语句是本阶段唯一"必须被看懂"的 SQL（`ORDER BY embedding <=> ?` 是否走索引见 §4.4）。**代价**：`vector` 不是 JDBC 标准类型 —— 用 `setObject(i, text, Types.OTHER)` 让服务端按 `vector_in` 解析（§4.5 有全部细节与退路）。引入 pgvector-java 只是把这 15 行换成一个依赖，还得为它多讲一层 |
| **D3** | **固定窗口分块：500 字 + 50 重叠，均可配**（`nexus.ai.rag.chunk-size` / `chunk-overlap`） | 语义分块 / 按句切 / 递归切 | `RAG建议.md` 明确"先不做语义分块"；task.4 3.3 的验收标准点名"分块大小 / 重叠**可配置**"。**代价**：边界会切在句子中间（记入 §10.3），"分块太大检索不准、太小丢上下文"正是面试要讲的调参经历 |
| **D4** | **Embedding 自成一个端口** `EmbeddingService`，Ollama 实现走 `POST /api/embed`（批量） | ① 复用 `AiModelService`（那是**生成**端口）② 引 `spring-ai-ollama` | 生成与向量化是两种调用（批量、无流、返回数组），塞进同一个端口会让两条路径互相污染。HTTP 客户端仍用阶段2 既定的 `RestClient`（**不引第二套客户端**）。**待实测确认**：`/api/embed` 的批量入参与响应形状（§3.7 探针 A/B） |
| **D5** | **生成侧复用阶段2 网关**：`AiModelFactory` 取实现 + `AiModelService.stream(...)` 累积成整段文本，由一个**新组件** `ModelAnswerGenerator` 适配 | ① 给 `AiModelService` 加一个非流式方法 ② 另写一套 DeepSeek 客户端 | ① 是**动已确认端口**（阶段2 的签名是回调式、有取消语义，为 RAG 加一个同步方法等于把"流何时消费"这个被刻意钉死的问题重新打开）；② 是重复实现。**新增一个 40 行的适配器是最小改动**，且它天然复用阶段2 的超时、异常（`20100`）与日志口径 |
| **D6** | **问答同步返回 JSON**（`POST /api/kb/ask` → `Result<{answer, sources[]}>`） | 复用阶段2 的 SSE 帧协议（打字机） | **2026-09-22 用户拍板**。理由：`RAG建议.md` 的闭环写的是"返回答案 + 引用片段"；引用必须先于答案可见，用流式就得给**已确认的**帧协议加第五种帧（`sources`）+ 改 README §6 + 改 openapi + 改 `sse.ts`。**代价**：提问后等 3~10s（loading 态），没有打字机效果。演进路径见 §10.4 |
| **D7** | **入库同步执行**：上传请求在**请求线程**上完成"解析 → 分块 → 向量化 → 入库"，**单事务**（要么全成、要么全不成） | 异步入库 + 状态列 + 轮询 | 最小闭环：同步不需要状态机、不需要轮询接口、不需要处理"任务丢了"。**代价有两个，都记在明处**：① 请求线程被占住（10MB PDF 约 10~60s，**待实测**）；② **事务包含网络调用**（embedding），事务持续数十秒 —— 演示规模可接受，并发上传会占住 Hikari 连接（§9 风险 8）。演进见 §10.5 |
| **D8** | **不保存原始文件**：只存解析后的分块 | 落对象存储 / 挂载卷，存原件 + 支持重新解析 | task.4 与 `RAG建议.md` 都没要求下载或重解析原文件；存原件要多一个卷 + 生命周期 + 清理逻辑。**代价**：改分块参数后**必须重传文档**才能生效（写进 §4.4 的配置注意事项）；`model_answer` 的引用只能给文字片段，给不了"原文件第 3 页" |
| **D9** | **检索 = 纯向量 TopK（余弦）**，不做 rerank / 混合检索 / MMR；**"检索不到就说不知道"用三道闸**（相似度阈值 → 空结果短路 → prompt 约束） | ① 混合检索（BM25 + 向量）② rerank 模型 ③ 只靠 prompt 约束 | ① ② 是 `RAG建议.md` 明确 backlog 的项。三道闸的分工：**阈值**挡住"全都低分"、**空结果短路**连大模型都不调（省成本且结构上不可能胡说）、**prompt** 挡住"检索到但答不上"。阈值初值是**待标定**的（§3.7 探针 D），不写成"0.35 就是对的" |
| **D10** | **单轮问答**：一次提问独立检索，不带会话历史 | 多轮（把上几轮问答拼进 prompt） | task.4 的问答是"检索 → 拼 → 生成"，无会话维度；无状态也让契约不必定义会话 id。**代价**：追问"那前年呢"不管用（前端给一句提示，§5.2） |
| **D11** | **一个租户一个知识库**，隔离完全交给既有的 `TenantLineHandler`（SQL 里**不手写** `tenant_id`） | 三张表（含 `t_kb`） | **2026-09-22 用户拍板**（§0.1 第 2 条）。**为什么 SQL 里不手写 `tenant_id`**：手写等于把隔离交给"每个人每次都写对"，而拦截器的注入是**结构化保证**。判据：mapper debug 日志里能看到注入后的最终 SQL（§4.7） |
| **D12** | **文件类型白名单 = TXT + PDF**，写成 `TikaDocumentParser` 里的**常量**（`Set.of("txt","pdf")`），**不做成配置键**；按**扩展名 + 内容检测**双重判断 | ① 只看扩展名 ② 只做内容探测 ③ 做成配置键 `nexus.ai.rag.allowed-extensions` | 只看扩展名：改个后缀就能骗过；只做内容探测：`.txt` 的探测结果是 `text/plain`，区分不出内容是否真是文本。**两头都看，冲突时报 `10201`**（§4.3）。**为什么不做成配置键**（初版设计写了这个键，实施时改掉）：白名单一旦可配，`10201` 里写死的文案"仅支持 TXT / PDF"就会**说谎** —— 除非把文案也做成动态的，而那会把"接口文案"变成运行期可变的东西；而"支持哪几种格式"本来就是**范围决策**（加 Word 要同时加 Tika 模块 + 用例，§10.1），不是运维旋钮。常量 + 写死文案，两处同源、改一次全对。<br>⚠️ **实施时再订正一处**：**内容检测只对 PDF 成立** —— TXT 的"内容是不是文本"由**编码探测**回答（UTF-8 与 GBK 解出来都超标 ⇒ `10203`）；**不要**对 `.txt` 跑 Tika 的内容探测：合法 GBK 文件很可能被判成 `application/octet-stream`，拿它做交叉校验会把**正路文件误杀成 `10201`**（"两个探针都做过头、反而更差"的一例） |
| **D13** | **解析器收口成 `DocumentParser` 端口**：TXT 用 JDK 直读（**带编码探测** UTF-8 → GBK），PDF 才用 Tika | ① 全部走 Tika ② 全部自己写（PDFBox 直用） | TXT 不需要框架（`new String(bytes, UTF_8)` 就是全部），而**编码才是 TXT 真正的坑**（GBK 文件按 UTF-8 解 = 全文乱码，见 §4.3）；PDF 这类二进制格式才是 Tika 的用武之地。**代价**：多一个 15 行的编码探测；换来的是把"乱码"从"入库一堆垃圾"变成"要么正确解码、要么明确报错" |
| **D14** | ~~`nomic-embed-text` 检索前缀~~ **已随模型更换作废**（2026-09-22 晚，见 §0.3）：改为 `bge-m3`，两个前缀配置**置空** | 不加前缀（即是现在的选择） | 原决策的理由是"该模型卡建议检索侧加任务前缀（v1.5）"；换 `bge-m3` 后**它不期望这类前缀**，保留等于给模型硬塞英文任务前缀（同属"不报错、只是变差"的静默劣化）。**保留的教训**：改前缀必须重灌数据 —— 存量向量与新查询向量不在同一空间；这条警告仍在 `application.yml` 的注释里 |
| **D15** | **上限：单文件 10MB、单文档 3000 块**；分块数上限**在向量化之前**检查 | 不限 / 只在最后统计 | 10MB 的 TXT ≈ 2 万块 ≈ 上万次 embedding 调用 —— 演示机上就是一次"跑到天荒地老"的事故。**先分块、再判上限、再向量化**：失败在几秒内发生，而不是几分钟后（`10204`）。10MB 这个数字的唯一真源是 `spring.servlet.multipart.max-file-size`（§6.2），业务侧不重复判 |

---

## 3. 接口契约（**先改 `docs/api/README.md` §7 + `openapi.yaml`，再改代码**）

> 纪律同 01/02：`docs/api/README.md` 是前后端唯一事实源。本节为其增量摘要 —— **摘要不等于契约**，两者必须一致。
> 本阶段新增 **§7 知识库接口**（追加在 §6 之后，**§1~§6 编号一律不动** —— §4 被 `scripts/` 按编号引用，原「变更记录」由 §7 顺延为 §8）。

### 3.1 `POST /api/kb/documents` —— 上传文档

| 项 | 值 |
| --- | --- |
| URL | `/api/kb/documents`（前端 `baseURL='/api'` + `url='/kb/documents'`） |
| Method | `POST` |
| `consumes` | `multipart/form-data`（显式声明） |
| `produces` | `application/json`（显式声明） |
| 鉴权 | **必需** `Authorization: Bearer <token>` |
| 成功 | HTTP **200** + `code=0`，`data` 为 `KbDocumentVO`（§3.5） |

请求（multipart 表单，**只有一个字段**）：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `file` | file | 是 | 单个文件；扩展名 `.txt` / `.pdf`（大小写不敏感）；≤ 10MB |

**响应不是"已受理"，而是"已入库"**：接口返回 200 时，解析、分块、向量化、入库**已经全部完成**（D7 同步口径）。
`data.chunkCount` 即本次写入的分块数 —— 前端刷新列表即可看到。

### 3.2 `GET /api/kb/documents` —— 文档列表

| 项 | 值 |
| --- | --- |
| URL | `/api/kb/documents` |
| Method | `GET` |
| `produces` | `application/json` |
| 鉴权 | **必需** |
| 成功 | HTTP 200 + `data = { "items": [KbDocumentVO...], "total": <int> }` |

- **只返回当前租户的文档**（`tenant_id` 由拦截器注入，见 D11）。
- `total` 与 `items.length` 本轮恒等（无分页）；**仍然保留 `total` 字段**：将来加分页时不必改契约（分页是 §10.6 的 backlog）。

### 3.3 `DELETE /api/kb/documents/{documentId}` —— 删除文档

| 项 | 值 |
| --- | --- |
| URL | `/api/kb/documents/{documentId}`（`documentId` 为路径参数，long） |
| Method | `DELETE` |
| `produces` | `application/json` |
| 鉴权 | **必需** |
| 成功 | HTTP 200 + `code=0` + `data=null`（文档行与其全部分块一并删除） |
| 失败 | 文档不存在 / 不属于当前租户 → HTTP **200** + `10202` |

**两个刻意的约定**：
1. **不存在时返回 `10202` 而不是 404**：本项目的 HTTP 状态码只承载传输/可用性语义（`docs/api/README.md` §1.2），业务拒绝一律 200 + 业务码；且 404 出口需要新造一套异常类型（`40400` 目前只由"路径不存在"触发），收益不抵成本。**另一个租户的文档在本接口里与"不存在"完全同形** —— 这是有意的：区分开就等于给出"某 id 是否存在"的探测口。
2. **删文档 = 删分块**：`t_kb_chunk.document_id` 上带 `ON DELETE CASCADE`，级联由数据库保证，业务代码不做两次删除（少一处"忘了删分块"的可能）。

### 3.4 `POST /api/kb/ask` —— 知识库问答

| 项 | 值 |
| --- | --- |
| URL | `/api/kb/ask` |
| Method | `POST` |
| `consumes` | `application/json`（显式声明） |
| `produces` | `application/json`（显式声明） |
| 鉴权 | **必需** |
| 成功 | HTTP 200 + `code=0`，`data` 为 `KbAnswerVO`（§3.6） |

请求体：

```json
{ "question": "去年利润是多少", "topK": 5 }
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `question` | string | 是 | 非空、去空白后非空；**≤ 500 字符**（超出 → `40001`） |
| `topK` | integer | **否** | 缺省 / `null` = 用配置值（`nexus.ai.rag.top-k`，默认 5）；取值域 `1..20`（越界 → `40001`） |

**三条补充约束**：

1. **`topK` 用 `Integer` 承接、业务侧判边界**，不用 `@Min/@Max`：契约要的是"越界 → `40001` + 可读文案"，
   而 Bean Validation 的默认消息是英文的 `must be less than or equal to 20`，会直接展示给用户（拦截器弹 `msg`）。
2. **`question` 的上限按字符计（500），不是字节**：对话接口用字节口径是因为它防的是"误贴大段文本撑爆上下文"，
   而问题天然是短的，500 字符 ≈ 500 个汉字，口径简单可读即可（差异是有意的，写在契约里）。
3. **问题不带任何"文档范围"参数**：本轮检索范围 = 当前租户的全部文档（D10/D11）。按文档过滤属于多知识库/多文档选择的范畴，记 §10.2。

### 3.5 `KbDocumentVO`（上传响应与列表项**同构**）

```json
{
  "documentId": 7,
  "fileName": "公司年报.pdf",
  "fileType": "PDF",
  "fileSize": 1048576,
  "charCount": 12480,
  "chunkCount": 28,
  "createdAt": "2026-09-22T10:30:00+08:00"
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `documentId` | integer (int64) | 是 | 文档 ID（删除接口的路径参数） |
| `fileName` | string | 是 | 原始文件名（**仅回显，不落盘**，见 D8） |
| `fileType` | string | 是 | `TXT` / `PDF`（大写，扩展名归一化而来） |
| `fileSize` | integer (int64) | 是 | 上传字节数 |
| `charCount` | integer | 是 | 解析出的正文字符数（**排查解析质量的第一眼数据**：与预期量级差太远就是解析出了问题） |
| `chunkCount` | integer | 是 | 入库的分块数（= `3000` 上限判据的实测值） |
| `createdAt` | string (date-time) | 是 | 入库时间，格式与 `/api/health` 的 `timestamp` 同款：`yyyy-MM-dd'T'HH:mm:ssXXX`（秒级、无小数秒）。⚠️ **零偏移渲染成 `Z` 而不是 `+00:00`**（已实测）—— 容器内是 `2026-09-22T02:30:00Z`，Windows 本地直跑是 `+08:00`；`Z` 与 `+00:00` 是等价的 ISO-8601 写法，**前端的判据不要写死 `+00:00`** |

### 3.6 `KbAnswerVO`（问答响应）

```json
{
  "answer": "根据资料，去年（2025 年）净利润为 1.23 亿元 [资料1]。",
  "grounded": true,
  "sources": [
    {
      "documentId": 7,
      "fileName": "公司年报.pdf",
      "chunkIndex": 12,
      "score": 0.8241,
      "content": "……（该分块的原文，最多 500 字）"
    }
  ],
  "retrieval": { "topK": 5, "hits": 1, "threshold": 0.5, "durationMs": 48 },
  "generation": { "modelType": "DEEPSEEK", "model": "deepseek-chat", "durationMs": 4034 }
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `answer` | string | 是 | 模型生成的答案；`grounded=false` 时是**固定文案**（见下） |
| `grounded` | boolean | 是 | 答案是否**基于检索到的资料**。`false` = 检索为空（阈值筛完后 0 条）⇒ **未调用大模型** |
| `sources` | array | 是 | 引用片段，按 `score` 降序；`grounded=false` 时为空数组（**不是 null**） |
| `sources[].documentId` | integer (int64) | 是 | 来源文档 ID |
| `sources[].fileName` | string | 是 | 来源文件名（展示用） |
| `sources[].chunkIndex` | integer | 是 | 该分块在文档内的序号，**0 起**；前端展示为「第 N 段」时用 `chunkIndex + 1` |
| `sources[].score` | number | 是 | 余弦相似度 = `1 - (embedding <=> query)`，保留 4 位小数；取值域 `[-1, 1]` |
| `sources[].content` | string | 是 | 该分块的**原文**（引用即原文，不做二次摘要；段落内的换行原样保留） |
| `retrieval` | object | 是 | 检索侧观测值：`topK`（本次生效值）、`hits`（过阈值条数）、`threshold`（本次生效阈值）、`durationMs` |
| `generation` | object \| **null** | 是（可为 null） | 生成侧观测值：`modelType` / `model` / `durationMs`；**`grounded=false` 时为 `null`**（没调模型） |

**`grounded=false` 时的 `answer` 固定文案**（后端常量，前端不必自己拼）：

```
知识库中未找到相关内容，请换一种问法，或先上传相关文档。
```

> 契约里**不写**"答案 ≤ 200 字"这类生成侧约束 —— 那是 prompt 的事（§4.6），不是接口的事；写进契约就成了后端必须校验的规则，而它其实拦不住模型。

### 3.7 失败形态总表

本接口**全部同步**，所以每条失败都有确定的载体与状态码（这正是 D7/D6 换来的一致性）：

| 失败点 | 时机 | 载体 | HTTP | code |
| --- | --- | --- | --- | --- |
| 无 token / 过期 / 伪造 | 进控制器前 | `Result` | 401 | `40100` / `40102` / `40101`（过滤器出口，既有） |
| 请求不是 multipart（缺 `Content-Type` / 缺 boundary） | 参数绑定前 | `Result` | **200** | `40001` |
| 未带 `file` 字段 / 文件为空 | 参数绑定 | `Result` | **200** | `40001` |
| 请求体校验失败（`question` 空 / 超长；`topK` 越界）/ JSON 畸形 | 进控制器前 | `Result` | **200** | `40001` |
| **文件名超过 255 字符**（实施时补：precheck 段拦下，不让 DB 的 `VARCHAR(255)` 报错） | 业务（写库前） | `Result` | **200** | `40001` |
| 文件超过 10MB | multipart 解析 | `Result` | **200** | `40003`（**待实测**：也可能表现为连接被重置，见 §6.2） |
| 扩展名不在白名单 / 扩展名与内容不符 | 业务 | `Result` | **200** | `10201` |
| 解析不出文本（扫描版 PDF、空文件、编码不可识别） | 业务 | `Result` | **200** | `10203` |
| 分块数超过 3000 | 业务（**向量化之前**） | `Result` | **200** | `10204` |
| 向量化时 Ollama 不可达 / 超时 / 报错 | 业务 | `Result` | **503** | `20100`（事务回滚，文档不落库） |
| 生成时上游模型不可达 / 超时 / 报错 | 业务 | `Result` | **503** | `20100`（**检索结果已拿到，但答案生成失败 ⇒ 整个请求失败**，见下） |
| 删除不存在的文档 | 业务 | `Result` | **200** | `10202` |
| 未预期的内部异常 | 兜底 | `Result` | 500 | `50000` |
| 客户端中途断开（上传/问答进行中） | — | 无（连接已没了） | — | 服务端**不会**察觉：入库照常完成。前端超时 ≠ 后端失败（§5.1-4） |

> **为什么"生成失败"不降级为"返回引用片段、答案留空"**：那样会造出一个**看着成功其实没答**的响应，
> 而"答案为空"的前端处置与"检索不到"（`grounded=false`）长得一样，会把排查方向带偏。
> 宁可整体失败（503 + `20100`），让用户重试 —— 重试成本是几秒钟，误诊成本是半小时。

### 3.8 错误码增量（**不复用**既有值）

**通用码**（进 `docs/api/README.md` §1.3，与 `40001`/`40002` 同表 —— 它不限知识库接口，任何上传接口都会用到）：

| code | 常量 | HTTP | `msg` | 归属 |
| --- | --- | --- | --- | --- |
| 40003 | `FILE_TOO_LARGE` | **200** | 文件过大，最大支持 10MB | 4xxxx 请求侧 |

**知识库业务码**（进 §7.8）：

| code | 常量 | HTTP | `msg` | 归属 |
| --- | --- | --- | --- | --- |
| 10201 | `KB_FILE_TYPE_UNSUPPORTED` | 200 | 不支持的文件类型，仅支持 TXT / PDF | 1xxxx 业务 |
| 10202 | `KB_DOCUMENT_NOT_FOUND` | 200 | 文档不存在或已被删除 | 1xxxx 业务 |
| 10203 | `KB_PARSE_EMPTY` | 200 | 未能从文件中解析出文本（可能是扫描版 PDF 或空文件） | 1xxxx 业务 |
| 10204 | `KB_CONTENT_TOO_LARGE` | 200 | 文档内容过长，超出单文档分块上限，请拆分后上传 | 1xxxx 业务 |

**复用的既有码**（不新增，避免同一件事有两个码）：`40001`（参数）、`40002`（媒体类型，**上传接口不适用**，见下）、
`20100`（模型/依赖不可用）、`50000`、`40100`/`40101`/`40102`。

> **`40002`（415）为什么不用于上传**：它的 `msg` 写死是"请求格式不支持，请使用 **application/json**" ——
> 贴到上传接口上是**反向误导**（用户会去改 JSON 头）。故 multipart 相关失败一律走 `40001`，
> 并在 §4.7 的异常出口里给 warn 日志写明"缺 multipart 头或 boundary"。

### 3.9 curl 验证（含三条**探针**，编码前先跑）

> 端口一律从唯一真源取（`docker-compose/.env` 的 `BACKEND_PORT`），不要写死。

```bash
# 前置：进入仓库根目录
cd /c/wp/nexus-agent-workbench
BACKEND_PORT=$(grep -E '^BACKEND_PORT=' docker-compose/.env | cut -d= -f2)
TOKEN=$(curl -s -X POST "http://localhost:${BACKEND_PORT}/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

# ① 上传（-F 即 multipart，curl 会自动带上 boundary；前端侧对应"必须让请求头带 boundary"，见 §5.1-2）
curl -s -X POST "http://localhost:${BACKEND_PORT}/api/kb/documents" \
  -H "Authorization: Bearer ${TOKEN}" \
  -F "file=@/mnt/c/wp/nexus-agent-workbench/qa/fixtures/rag/公司年报.pdf"

# ② 列表
curl -s "http://localhost:${BACKEND_PORT}/api/kb/documents" -H "Authorization: Bearer ${TOKEN}"

# ③ 问答（阶段验收的那一问）
curl -s -X POST "http://localhost:${BACKEND_PORT}/api/kb/ask" \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d '{"question":"去年利润是多少"}'

# ④ 删除（documentId 换成②里的值）
curl -s -X DELETE "http://localhost:${BACKEND_PORT}/api/kb/documents/1" -H "Authorization: Bearer ${TOKEN}"
```

**四条探针**（**在写代码之前**跑，把"待实测确认"变成事实；命令里的 `$` 决定了它们必须在 WSL 内直跑，不能经 `wsl -d … -- bash -c "…"` 包装）：

> ✅ **四条探针已于 2026-09-22 实测**（栈由 Docker Desktop 开机自启，五个容器均 `healthy`；`builder` 不在其中，属预期 —— 它走 `profiles: [build]`）：
>
> | 探针 | 实测结果 | 影响的决策 |
> | --- | --- | --- |
> | A | `/api/embed` **存在**，响应形状 `{"model":...,"embeddings":[[...]]}`；**维度 = 768** | `vector(768)` 定稿（§4.2 / §6.3）；§9 风险 3 消除 |
> | B | **支持批量**：3 条 input → 3 个向量数组 | `embed-batch-size: 16` 可用，**不需要**退化为逐条调用（§4.5） |
> | C | `nomic-embed-text`：architecture `nomic-bert`、**embedding length 768**、`num_ctx 8192`（架构 context 2048） | 500 字块 ≈ 500~800 token，**远小于**该窗口 ⇒ 不存在"分块被 embedding 截断"的风险。⚠️ `ollama show` **看不出 v1.0 / v1.5** ⇒ D14 的前缀开/关由 §7 的 A/B 用例给结论，不预先断言 |
> | D | pgvector **0.8.6** | ≥ 0.5.0 ⇒ **HNSW 可用**（§4.2 的索引选型成立） |
>
> ⚠️ 探针 D 的落点已订正为 **`nexus-postgres` 容器**（它自带 `psql`，且不必先把 builder 拉起来）；原稿写的 builder 容器也可用，但要求 builder 处于运行态。
>
> ⚠️ **本节是 nomic 时代的记录**（维度 768）。**2026-09-22 晚模型换为 `bge-m3`**，探针 A/C 的取值随之改变
> （维度应为 1024）—— 换模型时**先跑探针 A 确认新维度**再落补丁，纪律见 §0.3。

```bash
# 探针 A：embedding 维度（决定 t_kb_chunk.embedding 的 vector(N) —— 写错就是建表就错）
curl -s http://localhost:11434/api/embed \
  -H 'Content-Type: application/json' \
  -d '{"model":"nomic-embed-text","input":["测试"]}' | grep -o '\[-\?[0-9.,e-]*\]' | head -1 | tr ',' '\n' | wc -l
# 期望 768；若报 404 说明该 Ollama 版本没有 /api/embed → 改用旧的 /api/embeddings（单条入参）
#（旧端点：{"model":...,"prompt":"..."} → {"embedding":[...]}），设计相应改为逐条调用（§4.5 有说明）

# 探针 B：批量入参是否被接受（决定 embed-batch-size 能不能 >1）
curl -s http://localhost:11434/api/embed -H 'Content-Type: application/json' \
  -d '{"model":"nomic-embed-text","input":["甲","乙","丙"]}' | grep -c '\[\-\?[0-9]'
# 期望 3（三个数组）

# 探针 C：模型版本与上下文长度（D14 的前缀建议按 v1.5 口径；顺带确认 500 字远小于上下文）
docker exec nexus-ollama ollama show nomic-embed-text

# 探针 D：pgvector 版本（HNSW 需 ≥ 0.5.0）—— nexus-postgres 容器自带 psql，无需先拉起 builder
docker exec nexus-postgres psql -U nexus -d nexus -P pager=off \
  -c 'SELECT extname, extversion FROM pg_extension'
```

**阈值标定步骤**（D9 的初值 0.5 只是起点，必须实测校准；跑完写进 TC-03 的备注）：

1. 上传一份**已知内容**的文档（如 §7 的 fixture）；
2. 问一个**文档里一定有**的问题 → 记下 `sources[0].score`（记为 `S_hit`）；
3. 问一个**文档里一定没有**的问题（如"今天杭州天气怎么样"）→ 记下 `sources[0].score`（记为 `S_miss`）；
4. `threshold` 取 `(S_miss + S_hit) / 2` 附近的值，改 `application.yml` → 重打包 + 重启后端（配置打进 jar，§6.2）；
5. 判据：改完后第 3 步的问题应返回 `grounded=false`，第 2 步的问题仍 `grounded=true`。

---

## 4. 后端实现结构

### 4.1 新增 / 改动文件清单

```
backend/
├── pom.xml                                          [改] +<tika.version> + dependencyManagement（§6.4）
├── nexus-common/
│   └── result/ResultCode.java                       [改] +§3.8 的 5 个码（40003 + 10201~10204）
├── nexus-module-ai/
│   ├── pom.xml                                      [改] ★依赖增量（§6.4）：tika-core、tika-parser-pdf-module、
│   │                                                    mybatis-plus-spring-boot3-starter（显式声明，理由见 §6.4）
│   └── src/main/java/com/nexus/module/ai/rag/
│       ├── config/RagProperties.java                [新] @Component + @ConfigurationProperties("nexus.ai.rag")
│       ├── controller/KbDocumentController.java     [新] POST/GET /api/kb/documents、DELETE /{id}
│       ├── controller/KbAskController.java          [新] POST /api/kb/ask
│       ├── dto/KbAskRequest.java                    [新] question + topK（可变 POJO + 校验注解，同 ChatRequest 风格）
│       ├── dto/KbAnswerVO.java                      [新] answer/grounded/sources/retrieval/generation（record）
│       ├── dto/KbSourceVO.java                      [新] 引用片段（record）
│       ├── dto/KbDocumentVO.java                    [新] 上传响应与列表项同构（record）
│       ├── dto/KbDocumentListVO.java                [新] {items,total}（record）
│       ├── entity/KbDocument.java                   [新] MP 实体（@TableName/@TableId）
│       ├── entity/KbChunk.java                      [**不建**] ★ 实施时裁定**不建**（原设计写的是"只映射非向量字段"）：全链路自定义 SQL —— 插入走参数、检索返回 `ChunkHit` ⇒ 实体没有任何消费者，属死代码。设计里"`t_kb_chunk` 的向量不经实体映射面"这条契约，改由"压根没有实体"直接保证
│       ├── mapper/KbDocumentMapper.java             [新] extends BaseMapper<KbDocument>（列表 / 按 id 查 / 级联删除）
│       ├── mapper/KbChunkMapper.java                [新] ★ 不继承 BaseMapper，**两条**自定义语句（insertChunk / search —— `deleteByDocumentId` 实施时裁定不建：删文档走 DB 级联，零调用方即死代码）
│       ├── mapper/VectorTypeHandler.java            [新] String → setObject(Types.OTHER)（§4.5）
│       ├── parse/DocumentParser.java                [新] 端口：ParsedDocument parse(byte[] content, String fileName)
│       ├── parse/TikaDocumentParser.java            [新] 实现：TXT 直读 + 编码探测；PDF 走 Tika
│       ├── chunk/TextChunker.java                   [新] 纯函数：String → List<String>（§4.4）
│       ├── embedding/EmbeddingService.java          [新] 端口：List<float[]> embed(List<String> texts)
│       ├── embedding/OllamaEmbeddingService.java    [新] POST /api/embed（RestClient，同阶段2 的客户端取舍）
│       ├── prompt/PromptBuilder.java                [新] 纯函数：问题 + 命中分块 → List<ChatMessage>（§4.6）
│       ├── generate/ModelAnswerGenerator.java       [新] 复用 gateway：流式端口 → 整段文本（§4.7）
│       ├── service/KbDocumentService.java           [新] 端口（上传 / 列表 / 删除）
│       ├── service/KbAskService.java                [新] 端口（问答）
│       ├── service/impl/KbDocumentServiceImpl.java  [新] 编排 + @Transactional（§4.8）
│       └── service/impl/KbAskServiceImpl.java       [新] 检索 → 拼 → 生成（§4.9）
└── nexus-start/
    ├── resources/application.yml                    [改] +nexus.ai.rag.* +nexus.ai.ollama.embed-path
    │                                                     +spring.servlet.multipart.* +rag mapper debug（§6.2）
    └── handler/GlobalExceptionHandler.java          [改] +4 个出口（§4.10）

docs/
├── api/README.md                                    [改] +§7（§3 的全部内容），原「变更记录」顺延为 §8
├── api/openapi.yaml                                 [改] +4 个 path + Kb* schemas
├── test-cases/TC-03.md                              [新] qa-engineer 按 §7/§8 产出
└── 核心任务.md                                       [改] 阶段3 进度标记

db-patch/
└── 202609221000_初始化知识库表.sql                    [新] 两张表 + 索引（§4.2 / §6.3）

backend/nexus-module-ai/README.md                    [改] 阶段3 段落（落地后补"已完成"）
```

> `nexus-start` 的 `scanBasePackages = "com.nexus"` 与 `@MapperScan("com.nexus.**.mapper")` 已就位
> ⇒ `rag.mapper` 包会被自动扫描，**无需改动** `MybatisPlusConfig` 或启动类。

### 4.2 表结构（db-patch **新增**补丁，历史补丁只读）

表清单见 §6.3 的补丁文件。字段与索引的**设计理由**：

| 表 / 字段 | 设计理由 |
| --- | --- |
| `t_kb_document.tenant_id` | 多租户隔离列（既有拦截器按此注入）。索引 `idx_kb_document_tenant_id` —— 列表查询每次都带它 |
| `t_kb_document.char_count` / `chunk_count` | **审核与排查的落点**：解析质量差（`char_count` 离谱地小）与分块异常（`chunk_count` 为 1）在列表页就能看出来，不必翻日志 |
| `t_kb_document.created_by` | 记上传者（`TenantContext.getUserId()`）。**不加外键**：与 `t_user` 的耦合没有收益，本轮也没有"按人过滤"的需求 |
| `t_kb_chunk.embedding vector(768)` | 维度 = 当时模型（`nomic-embed-text`）的输出维度（**✅ 2026-09-22 探针 A 实测 = 768**）。写错的表现是建表成功、插入报错。⚠️ **2026-09-22 晚已换 `bge-m3` ⇒ 维度 1024**（新增补丁 `202609221100` 重建表；见 §0.3） |
| `t_kb_chunk.chunk_index` + `UNIQUE (document_id, chunk_index)` | 引用展示需要序号（0 起）；唯一约束挡住"同一文档重灌出重复块"这类缺陷（成本一个索引） |
| `t_kb_chunk.document_id ... ON DELETE CASCADE` | 删文档即删分块（§3.3 第 2 条）。**没有它就得靠业务代码记得删两次** |
| `idx_kb_chunk_embedding_hnsw`（`vector_cosine_ops`） | task.4 3.1 验收点名"检索有索引"。**为什么是 HNSW 而不是 IVFFlat**：HNSW 不需要训练/指定 list 数，小数据量上召回更稳，写入慢一点对本项目无所谓（离线入库）。**待实测确认**：pgvector ≥ 0.5.0（探针 D）；建表后 `\d t_kb_chunk` 应能看到该索引 |
| 不给 `t_kb_chunk.content` 建全文索引 | 本轮检索只用向量（D9）；混合检索是 §10.3 的 backlog。**不要顺手加**：没有消费者的索引只是写入负担 |

> ⚠️ **HNSW + 租户过滤的一个真实取舍**（面试可讲）：pgvector 的近似索引是**先取近似 TopK、再过滤**，
> 所以当"全表跨租户数据量"远大于"单租户数据量"时，过滤后可能不足 topK 条（召回下降）。
> 本轮演示规模（单租户几百块）无影响；规模化方案（按租户分区 / `tenant_id` 前缀的部分索引 / 后置过滤改前过滤）记 §10.3。

### 4.3 解析：`DocumentParser` 端口与 Tika 实现

```java
public interface DocumentParser {
    /**
     * @param content  文件字节（调用方已校验类型与大小）
     * @param fileName 原始文件名（用于取扩展名与日志；不用于任何落盘）
     * @return 纯文本 + 解析元数据（实际走的解析路径）
     */
    ParsedDocument parse(byte[] content, String fileName);
}
```

实现 `TikaDocumentParser` 的三条规则：

1. **扩展名判定在前**（`txt` / `pdf`，大小写不敏感；不在白名单 → `BusinessException(10201)`）；
2. **TXT 直读 + 编码探测**（D13）：
   - 先按 UTF-8 解码；若解码结果里 `U+FFFD`（替换字符）占比 > 1% → **按 GBK 重解一次**；仍超标 → `10203`；
   - 理由：GBK 编码的 TXT 按 UTF-8 解出来是"一屏乱码"，直接入库等于把垃圾灌进向量库，且**检索时才发现**；
   - 归一化：统一换行为 `\n`、行尾空白 strip、连续 3 个以上空行压成 2 个。**不做**去空格、不做列对齐（表格错位属 §10.3）。
3. **PDF 走 Tika**：`AutoDetectParser` + `BodyContentHandler`（限制输出长度，防 PDF 炸弹），
   **且用 Tika 的检测结果与扩展名交叉校验**（D12：`.pdf` 扩展名但内容检测为 `text/plain` 之类 → `10201`）；
   - Tika 解析出的文本仍按第 2 条归一化；
   - 若解析结果 trim 后为空 → `10203`（**扫描版 PDF 的典型归宿**：它有页面、没有文本层）；
   - ⚠️ **Tika 的异常不要原样抛给用户**：`TikaException` / `SAXException` 统一转成 `10203`（加密 PDF、损坏文件都归这一档），
     并在日志里留 `type` 与 message（**不打印正文**）。

> **为什么 Tika 只用于 PDF 而不是全部**（D13）：TXT 用框架不产生任何价值，而 TXT 的编码问题框架也不替我们解决。
> 这个分工让 `Word 支持` 的成本降到**一行依赖**：Tika 的 Microsoft 模块进来后，第 3 条那段代码不用改（§10.1）。

### 4.4 分块：`TextChunker`（纯函数，最容易测、也最值得测）

规则（全部由 `nexus.ai.rag.chunk-size` / `chunk-overlap` 控制，默认 500 / 50）：

1. 输入是**归一化后**的文本（§4.3 第 2 条），输出是 `List<String>`，**顺序即 `chunk_index`**（0 起）；
2. 步长 = `chunk-size - chunk-overlap`（默认 450）；窗口不足 `chunk-size` 时直接取到末尾；
3. **尾块合并**：若最后一块的长度 ≤ `chunk-overlap`，并入前一块（避免产生一个几十字的尾巴 —— 那种块即使被检索到也没有信息量）。
   ⚠️ **实施时订正**：这条在 `步长 = size - overlap` 下**不可达** —— 尾块长度恒 > overlap（子代理用 7920 组
   `size × overlap × 长度` 的网格穷举，触发 **0 次**）。分支**保留**（代码注释已写明"防御性，将来改语义分块即生效"），
   但**不要**把它当成需要覆盖的用例：TC-03 不必为它造数据，`TextChunkerTest` 也不必断言它被触发过；
4. 文本长度 ≤ `chunk-size` 时**产出 1 块**（不特判成 0 块：短文档同样要能问答）；
5. 空文本（trim 后为空）不由本类处理 —— 那是 `10203`，在解析层就拦掉了；
6. 计数口径：Java `String.length()`（UTF-16 单元）。中文 BMP 字符与"字数"1:1；emoji 等增补平面字符按 2 计
   —— **已知偏差**，写进类注释，不为此引入 code point 计数（演示文档里几乎不出现）。

⚠️ **两处配置注意事项**（写进 `application.yml` 的注释与 jar 内配置的说明）：

- **改分块参数后必须重传文档**（D8 不存原文件 ⇒ 无法重新分块）；
- **改 D14 的前缀后必须重灌数据**，否则存量向量与新查询向量不在同一空间 —— **不报错、只是检索变差**。

### 4.5 向量化：`EmbeddingService` 端口与 `VectorTypeHandler`

```java
public interface EmbeddingService {
    /** 批量向量化；返回顺序与入参严格一一对应；维度由实现保证（见实现类日志） */
    List<float[]> embed(List<String> texts);
}
```

`OllamaEmbeddingService` 的实现要点：

| 关注点 | 做法 |
| --- | --- |
| 上游端点 | `POST {nexus.ai.ollama.base-url}{nexus.ai.ollama.embed-path}`（默认 `/api/embed`）。**✅ 2026-09-22 探针 A/B 实测**：端点存在、响应形状 `{"embeddings":[[...]]}`、支持批量入参 ⇒ **不需要**退化到旧的 `/api/embeddings`（那条退路留在本行备查：将来换版本只改实现内部，端口签名不变 —— 这正是把 embedding 做成端口的价值） |
| 请求体 | `{"model":"nomic-embed-text","input":["...","..."]}`；**前缀在这里加**（D14）：入库侧 `search_document: `，查询侧 `search_query: `（两个前缀由**实现内部**持有，端口只暴露 `embedDocuments` / `embedQuery` 两个方法，不把前缀泄漏给调用方） |
| ★ 三个**常量**（实施时订正；2026-09-22 晚更新） | `EMBED_MODEL="bge-m3"` / `EMBED_BATCH_SIZE=16` / `EXPECTED_DIMENSION=1024` 都是**实现类里的常量，不是配置键** —— yml 里从来没有这三个键（设计 §6.2 的清单也没有）。尤其注意：**`nexus.ai.ollama.model` 是对话模型**（`qwen2.5:7b`），拿它做向量化是错的，两者必须分开。`EXPECTED_DIMENSION` 的真源是 DB 补丁的 `vector(N)`：换 embedding 模型时**两处必须同改**（改常量 + 新增补丁 + 重灌数据）—— **2026-09-22 晚由 `nomic-embed-text`(768) 换成 `bge-m3`(1024) 就是这条的第一次实战**（起因见 §0.3） |
| 超时 | 复用 `nexus.ai.read-timeout-ms`（60s，阶段2 既定口径）。**不要**沿用 `probe-timeout-ms`（那是探活口径，会误杀） |
| 错误处理 | 与阶段2 的 provider 完全同口径：网络类失败 / 非 2xx / 上游错误报文 → `BusinessException(20100)`；**非网络类**（如序列化失败）原样上抛按 `50000` 处置（"上游真的挂了"与"我们写挂了"必须分得开） |
| 响应校验 | `embeddings` 数组长度必须 == 入参长度、每个向量长度必须 == 表定义维度；不符 → **抛系统异常**（`50000`，属本服务/上游配置缺陷，不是用户问题），日志带上"期望 vs 实际" |
| 日志 | `[kb] 向量化: model=nomic-embed-text texts=16 dims=768 durationMs=812`（每批一条 debug） |

**`VectorTypeHandler`（`vector` 列与 JDBC 的对接，约 15 行）**：

- 写入：`ps.setObject(i, "[0.1,0.2,...]", Types.OTHER)` —— 让服务端按上下文推断类型（`CAST($1 AS vector)` → 走 `vector_in`）；
- **为什么不用 `setString`**：PostgreSQL JDBC 会把它标成 `varchar`，是否可隐式转 `vector` 取决于 pgvector 是否定义了该 cast，
  **不确定 ⇒ 不赌**（备选是给连接串加 `stringtype=unspecified`，但那会改变整个连接的行为，代价大得多）；
- **为什么不需要"读取"方向**：检索永不返回向量（只返回 `content` 与算好的 `score`）⇒ 不需要 `vector → float[]` 的反向处理。
  这也是 `KbChunk` 实体**不映射 `embedding` 字段**的原因（D2 的代价落地处）。

### 4.6 检索 SQL 与 Prompt 模板

**检索语句**（`KbChunkMapper.search`，注解语句；**用 `#{}` 而不是 `${}`**）：

> ★ **实施时按实测改写过一次**（详见 §9 风险 4）：pgvector 的 `<=>` 让 MP 3.5.9 内置的 jsqlparser 5.0
> **解析失败**（它把 `<=>` 切成 `<=` + 一个孤立的 `>`），而租户拦截器是"**先解析、后注入**"
> ⇒ 只要语句里有 `<=>`，**每条问答请求都会抛 `MybatisPlusException`**。
> 因此本语句**标 `@InterceptorIgnore(tenantLine = "true")` 绕开拦截器，并手写 `tenant_id`**（两处，两张表都判）。

```sql
SELECT c.document_id, d.file_name, c.chunk_index, c.content, c.char_count,
       1 - (c.embedding <=>
            CAST(#{queryVector, typeHandler=com.nexus.module.ai.rag.mapper.VectorTypeHandler} AS vector)) AS score
FROM t_kb_chunk c
JOIN t_kb_document d ON d.document_id = c.document_id
-- ★ 这两行 tenant_id 是【手写】的 —— 全项目唯一的例外（理由见上：拦截器在本语句上被短路）。
--   两张表都判：只判一张等于给跨租户留一条窄缝。
WHERE c.tenant_id = #{tenantId}
  AND d.tenant_id = #{tenantId}
ORDER BY c.embedding <=>
         CAST(#{queryVector, typeHandler=com.nexus.module.ai.rag.mapper.VectorTypeHandler} AS vector)
LIMIT #{topK}
```

**SELECT 里没有 `chunk_id`**（实施时订正）：`ChunkHit` 只有 6 个组件，多选一列没有消费者。
**`JOIN` 只为取 `file_name`**。

**三条必须写进注释的理由**：

1. **`ORDER BY` 必须是"操作符直接作用在列上"的形式**：不能写成 `ORDER BY score DESC`（别名）或包一层函数 ——
   那样 HNSW 索引**用不上**，退化成全表扫描 + 排序（数据量小时看不出差别，正是最危险的那种坑）。
   判据（★ 2026-09-22 沙箱实测订正，**必须带前提**）：先 `SET enable_seqscan = off` 再 `EXPLAIN`，
   断言出现 `Index Scan using idx_kb_chunk_embedding_hnsw`。**为什么必须关顺序扫描**：HNSW 是**近似**索引，
   表小的时候规划器会按代价选 Seq Scan（哪怕索引可用）⇒ 照字面断言会在真实数据量下**假失败**。
   实测三条对照：本写法 → `Index Scan`；`ORDER BY score DESC`（别名）→ `Sort` + `Seq Scan`；
   阈值进 `WHERE` → `Index Scan` **+ Filter**（索引照样可用 —— 见本节第 2 条的订正）；
2. **阈值不过 SQL 的 `WHERE`** —— ⚠️ **但初版给的理由是错的，已实测订正**：
   初版写"`WHERE 1 - (embedding <=> ?) >= ?` 同样会让索引失效"。**沙箱实测（2026-09-22）推翻了它**：
   该写法下规划器给出的仍是 `Index Scan using idx_kb_chunk_embedding_hnsw` **+ Filter**
   （索引照样用于**排序**，谓词只是加在索引扫描结果上）。
   ⇒ **仍然选择应用层过滤，但理由是语义而不是索引**：`LIMIT topK` 的含义是"最相似的 K 条"，
   再按阈值收紧（可能少于 K 条）；若把阈值放进 `WHERE`，`LIMIT` 会在**过滤之后**取满 topK 条，
   语义变成"最多 K 条且都过阈值"—— 两者都合理，本项目选前者。
   **教训（"理由错、行为对"是最难发现的一类错）**：错的理由会被后来者当依据（比如据此去"优化"一处
   本来不需要优化的地方），所以订正理由与订正行为一样重要；
3. **向量参数出现两次是刻意的**（SELECT 里算 score、ORDER BY 里排序）：绑定同一个字符串两次，无副作用。
   **不要**为了"只绑一次"改成派生表 / CTE —— 那会让 `ORDER BY` 依赖派生列，重新引入第 1 条的索引问题。

**`PromptBuilder` 的输出**（`List<ChatMessage>`，两条消息）：

```
role = system
你是企业知识库问答助手。规则：
1) 只依据【资料】回答，不得使用资料之外的知识，不得猜测；
2) 若【资料】中没有答案，直接回答「知识库中未找到相关内容」；
3) 引用来源时在句末标注 [资料N]，N 是资料编号；
4) 使用简体中文，简洁作答（不超过 200 字）。

role = user
【资料】
[资料1] 来源：公司年报.pdf 第 12 段
<分块原文>

[资料2] 来源：公司年报.pdf 第 13 段
<分块原文>

【问题】去年利润是多少
```

- **`role = system` 说明**：契约 §6.1 的"不支持 `system`"是**对话接口入参**的约定（Bean Validation 在控制器上生效），
  不是 provider 的能力限制 —— 两个上游都接受 `system`，且本调用不经校验。
  **待实测确认**：若上游拒收 `system`（会以 `20100` 现形），退路是 `PromptBuilder` 把系统指令并入唯一一条 user 消息（一个开关）。
- **上下文预算**：`topK=5` × 500 字 ≈ 2500 字 ≈ 3~4k token，两个上游的上限（`deepseek-chat` 64k / `qwen2.5:7b` 32k）都远超，
  **不需要截断逻辑**。若把 topK 调到 20，仍只有约 12k token，安全。
- 答案长度约束只写在 prompt 里（§3.6 的备注），不做后端校验。

### 4.7 生成：`ModelAnswerGenerator`（把阶段2 的流式端口适配成"一次性"）

```java
@Component
public class ModelAnswerGenerator {
    /** 调一次模型、拿回整段答案（内部把流式分片累积起来） */
    public GeneratedAnswer generate(List<ChatMessage> messages);
}
```

实现要点：

1. **取 provider 与自述**：`AiModelFactory.provider(type)` / `descriptor(type)`，`type = ModelType.parse(ragProperties.getAnswerModelType())`
   —— 复用既有工厂（找不到实现时它已经会给出 `10200` 与"已注册清单"的日志）；
2. **累积**：`provider.stream(messages, descriptor, chunk -> { if (chunk.content() 非空) buffer.append(...) }, new CancelToken())`；
   `Chunk.finished(...)` 的 `finishReason` 记录到返回值（`length` 说明被 token 上限截断，值得进日志）；
3. **`CancelToken` 的用法**：本链路没有"客户端断开就取消上游"的通道（同步请求），传一个**不被取消**的实例即可；
   **不要**为了"支持取消"去监听请求线程中断 —— 那是一条没有消费者的复杂度（§10.4 才需要）；
4. **异常**：`BusinessException(20100)` 原样上抛（由全局处理器出 503），其余异常按 `50000`（与阶段2 的分工一致）；
5. **日志**：`[kb] 生成完成: provider=deepseek model=deepseek-chat finishReason=stop chars=86 durationMs=4034`
   （**实施时订正**：初版这条写的是 `sources=3`，而 `generate(messages)` 的签名里没有这个数 —— 不改签名，
   因为 `sources` 与上一条检索日志的 `hits` **是同一个数**，两行相邻、合起来就是全部信息，补第二行只是噪声）。
6. **空答案按 `20100` 失败**（**实施时定案**）：上游"成功但一个字都没给"时**不要**返回 `answer=""` + `grounded=true`
   —— 那会造出"看着成功其实没答"的响应，与 §3.7 的原则直接冲突（"答案为空"与"检索不到"的前端处置会混淆）。
   判定落在 `KbAskServiceImpl`（`answer` 去空白后为空 ⇒ 抛 `BusinessException(20100)`，HTTP 503 让用户重试）；
   `ModelAnswerGenerator` 只记一条 warn（它离上游最近，`provider` / `finishReason` 在那里才可见）。

### 4.8 `KbDocumentServiceImpl` 编排（上传链路）与事务边界

```mermaid
sequenceDiagram
    participant FE as 前端 KnowledgeView
    participant JF as JwtAuthenticationFilter
    participant DC as KbDocumentController
    participant DS as KbDocumentService
    participant P as TikaDocumentParser
    participant C as TextChunker
    participant E as OllamaEmbeddingService
    participant M as KbChunkMapper
    participant DB as PostgreSQL

    FE->>JF: POST /api/kb/documents（multipart, Bearer）
    JF->>DC: 放行 + set TenantContext（请求线程）
    DC->>DC: 扩展名/空文件预检（快速失败，读字节之前）
    DC->>DS: ingest(fileBytes, fileName, caller)
    DS->>P: parse(...)
    P-->>DS: 纯文本（或 10201/10203）
    DS->>C: chunk(text, size, overlap)
    C-->>DS: List<String>（空 → 10203）
    DS->>DS: 分块数 > 3000 ? → 10204（★ 在向量化之前）
    DS->>DS: 开事务（@Transactional, rollbackFor = Exception.class）
    DS->>M: insertDocument(...)（tenant_id 由拦截器注入）
    M->>DB: INSERT t_kb_document
    loop 每批 16 块
        DS->>E: embed(["search_document: " + chunk...])
        E-->>DS: List<float[]>（失败 → 20100，事务回滚）
        DS->>M: insertChunks(...)（批量 INSERT，tenant_id 由拦截器注入）
        M->>DB: INSERT t_kb_chunk
    end
    DS->>DS: 提交事务
    DS-->>DC: KbDocumentVO
    DC-->>FE: Result<KbDocumentVO>
```

**六条实现纪律**：

1. **预检放在最前面**（扩展名、空文件、大小）——"读字节之前"能挡掉的就别进后续流程；
2. **`10204` 在向量化之前判**（D15）：先分块、数一眼，再决定要不要调 embedding；
3. **事务只包住"写库 + 向量化"这一段**（实施时订正：用 `TransactionTemplate` **编程式**事务，不用 `@Transactional` 注解 ——
   需要事务的只是 `upload` 的最后一段，而注解事务靠代理生效、**同类内部调用会静默失效**），解析与分块放在事务外
   —— 事务越短越好，且解析失败时压根没有事务开销；
4. **`tenant_id` 一律不手写**（D11）：`insertDocument` / `insertChunks` 的 SQL 里没有 `tenant_id` 列，由拦截器补；
5. **日志**（宪法："向量入库必须打 `log.info`"）：见 §4.11；
6. **`caller` 的处理**：本链路全在请求线程上，`TenantContext` 直接可用。
   但 `created_by` 需要 `userId` —— **从 `TenantContext.getUserId()` 取**，不要从 DTO 里带（那是客户端可以伪造的输入）。

### 4.9 `KbAskServiceImpl` 编排（问答链路）

```mermaid
sequenceDiagram
    participant FE as 前端 KnowledgeView
    participant AC as KbAskController
    participant AS as KbAskService
    participant E as OllamaEmbeddingService
    participant M as KbChunkMapper
    participant PB as PromptBuilder
    participant GEN as ModelAnswerGenerator
    participant UP as DeepSeek / Ollama

    FE->>AC: POST /api/kb/ask {question, topK?}
    AC->>AS: ask(request)
    AS->>E: embed(["search_query: " + question])
    E-->>AS: 查询向量（失败 → 503 + 20100）
    AS->>M: search(queryVector, topK)
    M-->>AS: List<ChunkHit>（租户条件已注入）
    AS->>AS: score < threshold 的丢掉（★ 阈值在应用层过滤）
    alt 过滤后为空
        AS-->>FE: Result{grounded:false, sources:[], answer:固定文案}（★ 不调大模型）
    else 有命中
        AS->>PB: build(question, hits)
        PB-->>AS: List<ChatMessage>
        AS->>GEN: generate(messages)
        GEN->>UP: stream(...)（阶段2 的端口）
        UP-->>GEN: 分片累积
        GEN-->>AS: 整段答案
        AS-->>FE: Result{grounded:true, sources:[...], answer}
    end
```

**四条纪律**：

1. **空结果短路不调模型**（D9 第二道闸）：这一条是"检索不到就说不知道"的**结构性保证**，也是本阶段省成本最直接的一处；
2. **`score` 由 SQL 算出**（`1 - (embedding <=> q)`），应用层只做比较与格式化 —— 不要在 Java 里重算一遍距离；
3. **`sources[].content` 用分块原文**，不截断、不摘要（引用即原文是"可核对"的前提）；
4. **`retrieval` / `generation` 两个观测块必须有**：它们是 §8 验收与 §9 排查的**唯一可见证据**（否则"答案不对"只能靠猜）。

### 4.10 `GlobalExceptionHandler` 新增出口（4 个）

| 异常 | HTTP | code | 日志级别 | 说明 |
| --- | --- | --- | --- | --- |
| `MaxUploadSizeExceededException` | 200 | `40003` | warn | multipart 层抛出的超限。**必须排在 `MultipartException` 之前匹配**（它是其子类） |
| `MultipartException` | 200 | `40001` | warn | 缺 `Content-Type: multipart/form-data`、缺 boundary 等 |
| `MissingServletRequestPartException` | 200 | `40001` | warn | 表单里没有 `file` 这个 part |
| `MissingServletRequestParameterException` | 200 | `40001` | warn | 其它必需参数缺失（顺手补齐，避免落兜底成 500） |

> 四个出口都**不打堆栈**（与既有风格一致）——这类是客户端缺陷，不是服务端故障。
> 参考既有实况：阶段2 的 `HttpMediaTypeNotSupportedException` 曾经落进兜底被报成 500「系统繁忙」，
> 把排查方向整个带偏（`docs/api/README.md` §1.2 有完整记载）。这里的四个出口是同一类问题的**事前**处置。

### 4.11 日志（宪法要求：向量入库、大模型调用都要有链路日志）

```
[kb] 上传开始: userId=1 tenantId=1 fileName=公司年报.pdf size=1048576
[kb] 解析完成: fileName=公司年报.pdf fileType=PDF parser=tika chars=12480 durationMs=820
[kb] 分块完成: fileName=公司年报.pdf chunks=28 chunkSize=500 overlap=50
[kb] 向量化: model=nomic-embed-text texts=16 dims=768 durationMs=812     （每批一条，debug）
[kb] 向量化完成: fileName=公司年报.pdf chunks=28 batches=2 durationMs=3120
[kb] 入库完成: documentId=7 tenantId=1 chunkCount=28 durationMs=4300
[kb] 检索: questionLength=12 topK=5 hits=3 maxScore=0.8241 threshold=0.5 durationMs=48
[kb] 检索为空（未调用大模型）: questionLength=12 topK=5 maxScore=0.3120 threshold=0.5
[kb] 生成完成: provider=deepseek model=deepseek-chat finishReason=stop chars=86 durationMs=4034
```

> ⚠️ **两个判据细节**（实施时实测；qa 按字面核对会得出假结论）：
> ① **`durationMs` 含"问题的向量化"**（契约 §7.6 明写），不只是那条 SQL 的耗时；
> ② **`tenant_id` 在 mapper debug 日志里的形态两处不同** —— INSERT 的改写是**字面量**
> （`INSERT INTO t_kb_chunk (..., tenant_id) VALUES (..., 1)`），而**检索语句是绑定参数**
> （`WHERE c.tenant_id = ? AND d.tenant_id = ?` —— 它绕开了拦截器、由 SQL 自己绑定）。
> **拿一种形态去核对另一种，会误判成"没注入"。**

**不打印正文**（分块内容、问题、答案都不进日志）：与阶段2 §4.5 同一条纪律（隐私 + 日志体积）。
**例外**：解析失败时的异常类型与 message、上游错误报文可以进日志 —— 那是诊断信息，不是用户正文。

### 4.12 `RagProperties`（`nexus.ai.rag.*` 的类型化绑定）

- 注册方式与 `AiProperties` **完全一致**：`@Component + @ConfigurationProperties(prefix = "nexus.ai.rag")`
  ——靠 `scanBasePackages = "com.nexus"` 扫到，**不需要改 nexus-start**；
- 每个字段都给与 `application.yml` 一致的 Java 默认值（yml 片段缺失时行为不退化，单测里 `new RagProperties()` 即可用）；
- ⚠️ **嵌套类属性名的命名陷阱**：`AiProperties` 已踩过一次（`deepSeek` → `deep-seek` 静默不绑定）。
  本类若加嵌套块（如 `prefixes`），getter 必须写成 `getPrefixes()` 这种**全小写单词**形式，不要出现连续大写字母。

---

## 5. 前端实现结构

| 文件 | 动作 | 要点 |
| --- | --- | --- |
| `src/views/KnowledgeView.vue` | **改**（占位页 → 完整页面） | 三段式：上传区（`el-upload` + `:http-request`）+ 文档表格（`el-table` + `el-popconfirm` 删除）+ 问答区（输入 + `el-button` + 答案 + 引用卡片）。顶部一条 `el-alert` 说明本轮边界（§5.2） |
| `src/api/kb.ts` | **新** | 四个函数：`uploadDocument(file)` / `listDocuments()` / `deleteDocument(id)` / `askKb(req)`（实施时订正：初版这里写了 `onProgress?`，与同节的签名块不一致，且 §5.2 没有百分比 UI —— 按签名块实现，**不带进度回调**）。wire 类型（`KbDocumentVO` / `KbDocumentListVO` / `KbAskRequest` / `KbAnswerVO` / `KbSourceVO` / `KbRetrievalVO` / `KbGenerationVO`）定义在本文件，每个 interface 注明契约出处（对齐 `api/auth.ts` / `api/chat.ts` 的既有风格） |
| `src/router/index.ts` | **不改** | `/knowledge` 路由已存在且**已受守卫保护**（无 `public`）—— 上传/问答接口不在白名单，缺 token 时守卫会带 `redirect` 跳登录（阶段1 既定行为） |
| `src/components/AppNav.vue` | **不改** | 「知识库」菜单项已存在（始终显示）；与「对话」不同的是它没有 `v-if="userStore.isLoggedIn"` —— **本轮不动**（收藏夹式直链会被守卫拦到登录页，行为正确） |
| `src/api/request.ts` | **改**（2026-09-22 实测订正，原写"不改"） | ① 请求拦截器加一条：`if (config.data instanceof FormData) config.headers.delete('Content-Type')` —— 不这么写上传**必 415**（§5.1-2）；② 超时仍在 `api/kb.ts` 的**调用点**逐请求覆盖（§5.1-4），**不动**实例默认值 |

**四个函数的签名与要点**（`api/kb.ts`）：

```ts
export async function uploadDocument(file: File): Promise<KbDocumentVO>
// service.post('/kb/documents', formData, { timeout: 300_000 })   // ★ 300s：按实测放宽（§5.1-4）
//  - formData.append('file', file) —— 字段名必须是 file（契约 §3.1），写错就是 40001
//  - ★ 必须显式声明 headers: { 'Content-Type': 'multipart/form-data' }（见 §5.1-2；不写就是 415）

export async function listDocuments(): Promise<KbDocumentListVO>   // service.get('/kb/documents')

export async function deleteDocument(documentId: number): Promise<void>
// service.delete(`/kb/documents/${documentId}`)

export async function askKb(req: KbAskRequest): Promise<KbAnswerVO>
// service.post('/kb/ask', req, { timeout: 120_000 })
```

### 5.1 五条必须写进代码注释的坑

1. **`el-upload` 的默认上传会绕过 axios 拦截器。** 它的 `action` 自带一套 XHR：不走 `request.ts`
   ⇒ **401 不会跳登录页、`Result` 不会被解包、失败文案不会统一**。必须用 `:http-request` 自定义，
   在回调里调 `uploadDocument(file)`，再手工调用 `onSuccess` / `onError`（Element Plus 的类型要求）。
   **判据**：把 localStorage 里的 token 改坏再上传 → 应当跳登录页（而不是只在控制台里看到一个错误）。
2. **`Content-Type` 必须让请求头带上 boundary，而 axios 的实例默认头会把它做坏（2026-09-22 实测订正）。**
   `request.ts` 的实例默认头是 `application/json`，于是 axios 的 `transformRequest`
   （`lib/defaults/index.js`：`hasJSONContentType ? JSON.stringify(formDataToJSON(data)) : data`）
   会把 `FormData` **JSON 序列化**成 11 字节的 `{"file":{}}` —— **文件字节根本没发出**；
   适配器拿到 body 时 `data` 已经不是 `FormData` 了，`lib/helpers/resolveConfig.js` 里
   那段"是 `FormData` 就删掉该头"**永不触发** ⇒ 请求头留在 `application/json` ⇒
   后端在**映射阶段**以 `HttpMediaTypeNotSupportedException` 拒收，用户读到"请使用 application/json"。
   **正确做法**：上传时显式声明 `headers: { 'Content-Type': 'multipart/form-data' }` ——
   两个适配器都会把**这个不带 boundary 的值**删掉、由浏览器补 `; boundary=…`，所以这么写是安全的；
   等价写法是在拦截器里对 `instanceof FormData` 的请求删掉该头（`request.ts` 已加这一条，见 §5 文件表）。
   **绝不要自己拼 `; boundary=…`**：那个值只有浏览器知道；本版 axios 会连它一起摘掉，
   所以"没炸"是 axios 兜底、不是写法正确。
   ★ **本条初版写反了**（写成"不要写死 `multipart/form-data`…手工写死会丢 boundary"，还挂着"待实测确认"）
   —— 照初版实现就是把 415 原样写回来。订正 4 处：本条、§5 签名块注释、§3.9 探针①的注释、
   §5 文件表 `request.ts` 由"不改"改为"改"；经过与处置见 §12。
   **判据**：上传应得 `200`；若得 `40001`（修复前是 `415`）且后端日志出现
   `multipart 接口收到非 multipart 请求`，就是这条。
3. **引用片段是"用户上传的外部文本"，必须用插值渲染。**
   `{{ source.content }}`（或 `v-text`），**绝不用 `v-html`** —— PDF/TXT 里出现 `<script>` 或 `<img onerror=...>`
   在 `v-html` 下就是一次 XSS。答案文本同理。
4. **上传/问答必须逐请求覆盖超时，且"超时 ≠ 失败"。**
   实例默认 `timeout: 15_000`，而**实测的上传耗时是它的数十倍**：嵌入约 **2.1 s/批（16 块）**，
   验收夹具 `公司年报.pdf` 有 587 块 ⇒ 37 批 ≈ **80~90 秒**（另加 Tika 解析与写库）。
   前端若用默认值，就会在**后端其实已经入库成功**时判定失败 ⇒ 用户重传 ⇒ 列表里出现两份同名文档。
   故：`uploadDocument` 传 **`timeout: 300_000`**（留足余量，覆盖"机器更慢/文件更大"的情形）、
   `askKb` 传 `120_000`（问答约 3~10 s）；且超时/网络错误的提示要写清楚
   ——**"请求超时，服务端可能仍在处理，请刷新列表确认后再重试"**。
5. **答案与片段要 `white-space: pre-wrap` 渲染。** 分块原文里的换行是结构信息（引用要能对得上原文），
   CSS 默认会把它们压成一整行，看起来像"解析坏了"。

### 5.2 页面结构与交互细节

| 区域 | 交互 | 细节 |
| --- | --- | --- |
| 顶部提示（`el-alert`，`closable=false`） | 静态 | "本轮：一个租户一个知识库；不做多轮对话；删除文档会同时删除其向量。" —— 一句话省掉一次误报（同阶段2 §5.5 的做法） |
| 上传区（`el-upload`，`drag` + `show-file-list`） | 选择/拖拽 → 上传 | `accept=".txt,.pdf"`；`limit=1`；`http-request` 走 axios；上传中禁用（`:disabled="uploading"`）并显示"解析并向量化中…" |
| 上传结果 | 成功 / 失败 | 成功：`ElMessage.success('已入库 N 段')` + **刷新列表** + 清空文件列表；失败：用后端 `msg`（`10201`/`10203`/`10204`/`40003`/`20100` 都已是可直接展示的文案，`request.ts` 的拦截器已经弹过一次 —— 页面内**不要**重复弹） |
| 文档表格（`el-table`） | 查看 | 列：文件名 / 类型 / 大小（格式化为 KB/MB）/ 字符数 / 分块数 / 入库时间 / 操作 |
| 删除（`el-popconfirm`） | 二次确认 → 删除 | 文案写清后果："删除后其向量一并移除，且**无法恢复**（本轮不保存原文件）"。成功后刷新列表 |
| 问答区 | 输入 + 提问 | `el-input`（`type=textarea`，`maxlength=500`，`show-word-limit`）+ `el-button`（loading 态："检索并生成中…"）。回车提交（`@keyup.enter`，shift+enter 换行） |
| 答案 | `grounded=true` | 答案正文 + 下方「引用片段」列表：每条显示 `文件名 · 第 N 段 · 相似度 0.82`（`score` 保留 2 位展示）+ 折叠的原文（`el-collapse`，`pre-wrap`） |
| 答案 | `grounded=false` | 用 `el-alert type="info"` 展示 `answer` 文案，**不显示引用卡片**（`sources` 是空数组） |
| 空态 | 无文档时 | 表格 `empty-text="还没有文档，先上传一份 TXT 或 PDF"`；问答区在无文档时也可用（会得到 `grounded=false`，这是正确的行为，不要在前端拦） |

---

## 6. 配置增量与部署（**逐个文件标明改哪里**）

### 6.0 要不要重建容器？（**先看这一节**）

沿用阶段0 的架构事实（`docs/design/01` §2.5）：**运行镜像里不含产物**，产物由 builder 写进命名卷 `build-artifacts`。

| 改动类型 | 重建镜像？ | 重建/执行产物？ | 容器动作 | 生效判定 |
| --- | --- | --- | --- | --- |
| Java 源码（§4） | ❌ | ✅ builder `build-backend` | **`restart nexus-backend`** | 日志出现 `[kb]` 链路（§4.11） |
| `application.yml`（§6.2） | ❌ | ✅（yml 打进 jar） | **`restart nexus-backend`** | 上传一份 2MB 的 PDF：**不报 40003** ⇒ multipart 上限生效 |
| **`db-patch/` 新补丁**（§6.3） | ❌ | ✅ builder `db-patch-migrate` | 无需动容器（但要先跑迁移，否则上传报 500） | `SELECT to_regclass('public.t_kb_chunk') IS NOT NULL` → `t` |
| `pom.xml` 依赖增量（§6.4） | ❌ | ✅ `build-backend`（★ **首次会多下载 Tika 依赖**） | `restart nexus-backend` | 构建日志里 Tika 构件解析成功；启动日志出现 `TikaDocumentParser 就绪` |
| 前端源码（§5） | ❌ | ✅ builder `build-frontend` | **不用动容器**（nginx 逐请求读共享卷） | 浏览器强刷后页面变成知识库页 |
| `docker-compose.yml` / `.env` | — | — | **本阶段零改动** | 不需要新增环境变量、不需要新端口、不需要新卷 |
| **`nginx.conf`** | ✅ **`build nexus-frontend`** | ❌ | **`up -d nexus-frontend`** | ★ **实施时发现必须改**（初版写"零改动"是错的）：nginx 默认 `client_max_body_size` = **1m** ⇒ 走 8088 上传 >1MB 被 **413** 挡下（**1.68MB 的验收夹具必挂**），而直连后端端口一切正常。加了 `client_max_body_size 12m;`（与后端 `max-request-size` 对齐）。**该文件是 COPY 进镜像的 ⇒ 必须重建镜像**，只 `restart` 不生效 |

> 💡 **本阶段不动 compose、不动 nginx、不动 Dockerfile** —— 三个"不需要改"本身是阶段0/2 那套架构（产物走共享卷 + `env_file`）的红利，值得在面试里提一句。

### 6.1 改动清单（仓库相对路径）

| # | 文件 | 动作 | 内容 |
| --- | --- | --- | --- |
| 1 | `backend/pom.xml` | 改 | `<tika.version>` + dependencyManagement 两条（§6.4） |
| 2 | `backend/nexus-module-ai/pom.xml` | 改 | +tika-core +tika-parser-pdf-module +mybatis-plus-spring-boot3-starter |
| 3 | `backend/nexus-common/.../ResultCode.java` | 改 | +5 个码（§3.8） |
| 4 | `backend/nexus-start/src/main/resources/application.yml` | 改 | +`nexus.ai.rag.*` +`nexus.ai.ollama.embed-path` +`spring.servlet.multipart.*` +rag mapper debug（§6.2） |
| 5 | `backend/nexus-start/.../GlobalExceptionHandler.java` | 改 | +4 个出口（§4.10） |
| 6 | `db-patch/202609221000_初始化知识库表.sql` | **新建** | 两张表 + 索引 + 注释（§6.3） |
| 7 | `docs/api/README.md` + `docs/api/openapi.yaml` | 改 | +§7 契约（**先落这两份，再写代码**） |
| 8 | `backend/nexus-module-ai/README.md` | 改 | 阶段3 段落（落地后） |
| 9 | `docker-compose/frontend/nginx.conf` | 改 | ★ **实施时发现**：加 `client_max_body_size 12m;`（默认 1m 会挡住 >1MB 的上传 ⇒ 验收夹具 413）。证据、探针与复核见 §6.0 的表 |
| 10 | `docs/核心任务.md` | 改 | 阶段3 进度标记 |

### 6.2 `application.yml` 的改法（**三处插入，别整块替换**）

```yaml
nexus:
  ai:
    # ── 以下为节选：rag: 插在既有 ai: 块内（ollama / deepseek / executor 之后）──
    rag:
      # 分块（task.4 3.3 验收点名"可配置"）
      # ⚠️ 改这两个值之后**必须重传文档**才生效 —— 本轮不保存原文件，无法重新分块（设计 D8）
      chunk-size: 500
      chunk-overlap: 50
      # 文档上限（**不是**上传大小上限 —— 那个的唯一真源是 spring.servlet.multipart.max-file-size）
      # 先分块再判上限，超限报 10204，避免"跑到一半才发现太大"
      max-chunks-per-document: 3000
      # 检索
      top-k: 5
      max-top-k: 20
      # 相似度阈值（余弦）：低于它的检索结果一律丢弃；全部低于 → grounded=false（不调大模型）
      # 初值 0.5 是**起点不是结论** —— 标定步骤见设计文档 §3.9 探针 D
      score-threshold: 0.5
      # 问答生成用哪个模型（取值即 ModelType 枚举名）。默认云端：中文问答质量与速度都更好（约 4s vs CPU 上 30s+）
      # 无 DEEPSEEK_API_KEY 时选 DEEPSEEK 会直接 20100（阶段2 既定行为）；离线演示可改成 OLLAMA
      answer-model-type: DEEPSEEK
      max-question-length: 500
      # embedding（检索侧任务前缀；**2026-09-22 晚已随模型更换置空**，见设计 §0.3）
      # ⚠️ 改这两个前缀之后**必须重灌全部文档**：存量向量与新查询向量会不在同一空间，
      #    而且**不报错**，只是检索质量静默劣化 —— 这类"改了没生效/生效了变差"是本仓库最防的失效形态
      document-prefix: ""
      query-prefix: ""
    ollama:
      # ── 既有 ollama: 块内**新增一行**（chat-path / model 都不动）──
      embed-path: /api/embed

spring:
  # ── 以下同样为节选：新增 servlet 块（既有 spring: 块下没有它）──
  servlet:
    multipart:
      # ★ 必须显式配：Spring Boot 的默认 max-file-size 是 **1MB**，
      #   不配的话一份 2MB 的年报 PDF 会直接失败（表现为 40003 或连接被重置）
      max-file-size: 10MB
      # 要留出 multipart 信封（boundary/头）的余量，故比 file-size 大一档
      max-request-size: 12MB

logging:
  level:
    # ── 在既有 logging.level 块内追加一行 ──
    # 判据同阶段1 的 com.nexus.module.system.mapper：这是"检索 SQL 真的被注入了 tenant_id"
    # 唯一可见、可截图、可演示的证据（设计 D11 的判据）
    com.nexus.module.ai.rag.mapper: debug
```

⚠️ **这些键都不在 compose 的 `nexus-backend.environment` 里**（与阶段2 §6.2 的同一类坑）：
`OLLAMA_EMBED_MODEL`（若用环境变量写）等变量**改 `.env` 容器收不到**，只能改本 yml 的默认值 = **重打包 + 重启后端**。

**`server.tomcat.max-swallow-size`（待实测确认）**：Tomcat 默认只"吞掉"约 2MB 的超限请求体，
超出的部分会让容器**直接断连**而不是把异常交给应用 ⇒ 症状是"上传 11MB 文件时连接被重置"而不是 `40003`。
**判据**：故意上传一个 11MB 的文件，看拿到的是 `40003` 还是连接错误；
若为后者，在 `spring:` 同级加 `server.tomcat.max-swallow-size: 12MB`（或 `-1` 表示不限制）。

### 6.3 `db-patch/202609221000_初始化知识库表.sql`（**新增补丁，不得改历史补丁**）

命名遵循既有规则（`YYYYMMDDHHmm_描述.sql`，字典序即时间序；本文件名 > 已应用的最大文件名 `202609131010`）。
内容要点（完整 SQL 由 backend-engineer 按 §4.2 的理由写，此处给结构与判据）：

```sql
-- ① 文档表
CREATE TABLE t_kb_document (
    document_id BIGSERIAL    PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES t_tenant (tenant_id),
    file_name   VARCHAR(255) NOT NULL,
    file_type   VARCHAR(16)  NOT NULL,          -- TXT / PDF（大写）
    file_size   BIGINT       NOT NULL,
    char_count  INTEGER      NOT NULL,
    chunk_count INTEGER      NOT NULL,
    created_by  BIGINT,                          -- 上传者 user_id（不加外键，见 §4.2）
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_kb_document_tenant_id ON t_kb_document (tenant_id);

-- ② 分块表（含向量）
CREATE TABLE t_kb_chunk (
    chunk_id    BIGSERIAL    PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES t_tenant (tenant_id),
    document_id BIGINT       NOT NULL REFERENCES t_kb_document (document_id) ON DELETE CASCADE,
    chunk_index INTEGER      NOT NULL,           -- 0 起
    content     TEXT         NOT NULL,
    char_count  INTEGER      NOT NULL,
    embedding   vector(768)  NOT NULL,           -- ✅ 2026-09-22 探针 A 实测 = 768
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_kb_chunk_doc_index UNIQUE (document_id, chunk_index)
);
CREATE INDEX idx_kb_chunk_tenant_id ON t_kb_chunk (tenant_id);
CREATE INDEX idx_kb_chunk_document_id ON t_kb_chunk (document_id);
CREATE INDEX idx_kb_chunk_embedding_hnsw ON t_kb_chunk USING hnsw (embedding vector_cosine_ops);

COMMENT ON TABLE t_kb_document IS '…';
COMMENT ON TABLE t_kb_chunk    IS '…（tenant_id 由 TenantLineHandler 自动注入查询条件）';
```

**执行方式**（builder 容器内，`/mnt/c/...` 内不可用）：

```bash
wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench/docker-compose && docker compose exec -T builder db-patch-migrate"
```

**判据**：输出里出现本补丁文件名、`t_db_patch` 多一行、`to_regclass('public.t_kb_chunk')` 非空。
⚠️ **2026-09-22 晚：本节的 `vector(768)` 已是历史** —— 模型换成 `bge-m3` 后，由**新增补丁
`202609221100`** 把两表重建为 `vector(1024)`（并清空数据，理由见 §0.3）。下面这段"维度必须在建表前确认"
的纪律**依然成立**，而且这次换模型正是它的第二次实战。

**`vector(768)` 的维度必须在建表前用探针 A 确认** —— 维度写错时**建表是成功的**，第一次入库才报错（且报的是类型错误，容易误判成代码 bug）。

### 6.4 依赖增量（**两个 pom**）

`backend/pom.xml`（父 POM，第三方版本集中管理 —— 遵循"BOM 未覆盖的第三方在本文件集中声明，子模块不写 `<version>`"）：

```xml
<properties>
    <!-- ★ 待核实（写入前用 Maven 解析一次，不可用则回退 2.9.2）：
         核实命令（builder 容器内）：mvn -B dependency:get -Dartifact=org.apache.tika:tika-core:3.2.3 -->
    <tika.version>3.2.3</tika.version>
</properties>
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.apache.tika</groupId>
            <artifactId>tika-core</artifactId>
            <version>${tika.version}</version>
        </dependency>
        <!-- PDF 解析模块（含 PDFBox）。只引 PDF 模块而不是 tika-parsers-standard-package：
             后者会带进 Office / 图片 / 音频等一大批用不到的解析器，构建与镜像都变重 -->
        <dependency>
            <groupId>org.apache.tika</groupId>
            <artifactId>tika-parser-pdf-module</artifactId>
            <version>${tika.version}</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

`backend/nexus-module-ai/pom.xml`：

| 依赖 | scope | 为什么需要 / 备选 |
| --- | --- | --- |
| `org.apache.tika:tika-core` | compile | `AutoDetectParser` / `BodyContentHandler` / `Metadata`（**直接 import** ⇒ 显式声明，遵循本模块既有原则） |
| `org.apache.tika:tika-parser-pdf-module` | runtime | PDF 解析器实现（运行期由 Tika 的服务加载机制发现）。**待核实**：若该 artifact 在选定版本不存在，回退 `org.apache.tika:tika-parsers-standard-package`（确定存在，代价是体积） |
| `com.baomidou:mybatis-plus-spring-boot3-starter` | compile | `BaseMapper` / `@TableName` / `@TableId`（**直接 import**）。它已由 `nexus-infrastructure` 传递进来，显式声明是遵循本模块 pom 的既定原则（"自己 import 的类型自己声明"）。⚠️ 现存不一致：`nexus-module-system` 未显式声明同类依赖 —— **对齐它属阶段1 的技术债，本轮不动**（记 §10.7） |

> **首构建提示**：builder 的 Maven 走阿里云源 + `builder-m2` 卷缓存（`docker-compose/builder/settings.xml`），
> Tika 及其传递依赖（PDFBox / commons-* 等，约 30~60MB）**只在首次构建下载一次**；
> 若该版本在镜像源上不存在，构建会以"依赖解析失败"响亮报错（不会静默），此时按上表回退版本。

### 6.5 完整执行顺序（本阶段首次上线）

```bash
# 0. 宿主侧两步，都必须在切进 WSL 之前做完：
#    ① commit + push（builder 只能拉到【已 push】的提交）
#    ② ★ 确保该提交在 NEXUS_REPO_BRANCH（当前 = main）上！
#       git-sync 执行的是 `git checkout --force --detach origin/${NEXUS_REPO_BRANCH}`；
#       代码若只在未合并的特性分支上，第 2 步拉到的是 main（不含本阶段代码），
#       而第 3 步照样 exit 0、日志正常、产物却是旧的。

# 1. 起 builder（不常驻，用完可停）
wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench/docker-compose && docker compose up -d builder"

# 2. 拉代码 —— ★ 判据：输出里 `[git-sync] 同步完成，当前提交：` == 刚 push 的 commit
wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench/docker-compose && docker compose exec -T builder git-sync"

# 3. ★ 执行 db-patch 迁移（本阶段唯一新增的结构变更；必须在使用新接口之前完成）
wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench/docker-compose && docker compose exec -T builder db-patch-migrate"

# 4. 重建后端产物（含 rag 全部新类 + 新 application.yml；首次会多下载 Tika 依赖）
wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench/docker-compose && docker compose exec -T builder build-backend"

# 5. 重建前端产物
wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench/docker-compose && docker compose exec -T builder build-frontend"

# 5.5 ★ 重建前端**镜像**并重建容器 —— 只有改了 nginx.conf 才需要（它被 COPY 进镜像，restart 不生效）。
#     ⚠️ 别漏：漏了的话走 8088 上传 >1MB 仍是 413，而你会以为"改过了怎么还不行"
wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench/docker-compose && docker compose build nexus-frontend"
wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench/docker-compose && docker compose up -d nexus-frontend"

# 6. 重启后端（jar 变了 → restart 就够；本阶段只改了 nginx.conf，没动 compose 的 environment/env_file）
wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench/docker-compose && docker compose restart nexus-backend"

# 7. 核对新表与索引（★ 只在 builder 容器内有 psql）
wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench/docker-compose && docker compose exec -T builder psql postgresql://nexus:nexus123@postgres:5432/nexus -c \"SELECT indexname FROM pg_indexes WHERE tablename='t_kb_chunk'\""
```

**WSL 内直跑**（含 `$` 的命令不能经 `wsl -d … -- bash -c "…"` 包装 —— 双层包装会把 `$` 吃掉，本项目已两次踩到）：

```bash
# 进 WSL 后：cd /mnt/c/wp/nexus-agent-workbench
BACKEND_PORT=$(grep -E '^BACKEND_PORT=' docker-compose/.env | cut -d= -f2)
# 然后跑 §3.9 的四条探针与四条 curl
```

**三条反直觉点**（沿用阶段2 的结论，别重新踩）：

1. **jar 变 → `restart`；配置（compose/env）变 → `up -d`**。本阶段只改 jar（含 yml）⇒ `restart` 正确；
2. **前端改动连容器都不用重启**（nginx 逐请求读共享卷），`build-frontend` 后浏览器强刷即可；
3. **`nexus-backend` 镜像本阶段一次都不用重建**（只改了 Java 代码 / yml / pom → 全走"builder 重建 jar + 重启容器"）。

### 6.6 devops 连带任务（不属 3.1~3.5，但本阶段落地后必须收口）

| # | 任务 | 判据 |
| --- | --- | --- |
| 1 | `scripts/check-env.sh` 的 pgvector 检查升级：由"扩展可用"（`pg_available_extensions` 有行）升为"**已安装**"（`installed_version IS NOT NULL`）硬判据 | `docs/api/README.md` §4.6 已预告该升级时机；改后跑一次，应 PASS |
| 2 | `check-health.sh` 增加模型就绪判据的**下界**：`nomic-embed-text` 从"WARN + 继续"改为**硬判据**（本阶段起它是必需依赖，缺了上传/问答全挂） | 该脚本已判 `qwen2.5:7b` + `nomic-embed-text`（§4.5），确认它现在是否只是 WARN；是则升级 |
| 3 | `qa/fixtures/rag/` 放测试夹具（§7 的 PDF/TXT），**不放** `scripts/`、`db-patch/`、`docker-compose/`（那些会被生产链路消费） | 目录约定见 `qa/README.md` |

---

## 7. 测试案例与自动化测试

### 7.1 单元测试（JUnit 5 + Mockito，放 `nexus-module-ai/src/test/java/.../rag/`）

> ⚠️ **测试类的包路径要精确**（实施时的可见性约束）：`TextChunker.chunk(text, size, overlap)` 与
> `TikaDocumentParser.normalize(...)` 是**包内可见**的（刻意留的测试缝，与阶段2 的 `OllamaService.parseStream` 同款），
> 所以 `TextChunkerTest` 必须放在 `com.nexus.module.ai.rag.chunk`、`TikaDocumentParserTest` 放在
> `com.nexus.module.ai.rag.parse` —— 一律丢在 `...rag` 下会因为可见性**编不过**。

> 选型理由同阶段2 §7：**纯函数与协议解析这类"喂样本断言输出"的单测成本低、防回归价值高**，
> 本阶段真正值得机器钉住的就是下面 6 组。真实连通性交给 TC-03 的实机用例。

| 测试类 | 喂什么 | 断言什么（即它防的缺陷） |
| --- | --- | --- |
| `TextChunkerTest` | 空串 / 短文本（< 500）/ 恰好 500 / 501 / 1000 / 中文长文本 | 块数正确；相邻块重叠恰好 50；**尾块不出现 ≤50 的碎块**（被并入前一块）；短文本产出 1 块 |
| `TikaDocumentParserTest` | UTF-8 TXT / **GBK TXT**（`getBytes("GBK")` 造）/ 最小 PDF（`src/test/resources/rag/sample.pdf`，ASCII 文本，无嵌入字体）/ 空文件 / `.docx` 扩展名 | GBK 能被正确解码（**这是"乱码"那个坑的机器判据**）；PDF 抽出预期文本；空内容 → `10203`；不支持的类型 → `10201` |
| `OllamaEmbeddingServiceTest` | 固定 JSON 响应体（成功 / `{"error":...}` / 数量不匹配 / 维度不匹配），走 `FragmentInputStream` 那种"被 TCP 切碎"的喂法 | 向量条数与维度解析正确；上游错误 → `20100`；**数量/维度不匹配 → 系统异常**（不是静默截断） |
| `PromptBuilderTest` | 3 条命中 / 1 条命中 / **0 条命中** | 资料编号连续；含问题原文；含"只依据资料"的约束；0 条时**不进模型**（由 service 测）且文案不空 |
| `ModelAnswerGeneratorTest` | 假 `AiModelService`（回调 3 个 `text` + 1 个 `finished`） | 文本按序拼成整段；`finishReason` 被带出；provider 抛 `BusinessException(20100)` 时**原样上抛**（不吞、不改码） |
| `KbAskServiceImplTest` | mock `EmbeddingService` / `KbChunkMapper` / `ModelAnswerGenerator` | **检索为空（或全部低于阈值）→ `grounded=false` 且 `ModelAnswerGenerator` 零调用**（"检索不到就说不知道"的机器判据）；命中 → `sources` 按 score 降序、`generation` 非 null |

> `TextChunker` / `PromptBuilder` 做成**无依赖的纯函数**（静态方法或只接配置的组件）就是为了这个：
> 不 mock、不起 Spring 上下文，测试是毫秒级的。

### 7.2 测试夹具（`qa/fixtures/rag/`，qa-engineer 维护）

| 夹具 | 用途 | 备注 |
| --- | --- | --- |
| `公司年报.pdf` | **阶段验收**：上传后问"去年利润是多少" | ✅ **2026-09-22 用户已提供**（1.6MB、`%PDF-1.7`、**含文本层**：`/Font` 266 处 / `/Image` 3 处 ⇒ 不是扫描件，`10203` 那条负路径不会误伤它）。⚠️ **刻意不入库**（`.gitignore` 末段：`qa/fixtures/rag/*.pdf` —— 第三方公开材料 + 体积），复现时自备一份中文含财务数字的 PDF、放同目录同名即可。<br>★ **期望答案（已由主会话用 `pdftotext -layout` 抽出正文核对，两处措辞一致）**：<br>· "报告期内，公司实现营业总收入 **897 亿元**，同比增长 14.4%；归属于上市公司股东的净利润 **84.1 亿元**，同比大幅上升 **41.2%**"（正文段）<br>· "报告期间实现归属母公司净利润 **84.1 亿元**，同比大幅增长 **41.2%**，销售净利率达 9.5%"（经营讨论段）<br>· 财报表格里同一数字写作 **8,408,057 千元**（= 84.08 亿元）⇒ **TC-03 的判据要接受两种形态**（"84.1 亿元" 或 "8,408,057 千元"），否则会把一次正确回答判成错。<br>**交叉核对工具**：宿主侧 `pdftotext -layout <pdf> <txt>`（Git Bash 自带）可随时抽正文，用来判断"答案不对"是**解析问题**还是**检索/生成问题**。<br>★ **2026-09-22 晚补记（nomic 时代实测的排名，换 bge-m3 后必须重测）**：问「去年利润是多少」时，含答案的块在 587 块中**排第 27 名**（问"归母净利润"排第 47、问"营业总收入"排第 10），而无关块也有 0.71~0.75 分 —— 这组数字是"换模型"决策的全部依据（§0.3），复测时用 devops 的排名脚本（`.tmp/main/acc-d2-rank.py`）重跑同一组问题<br>★ **实测数字（第二段 a 用真实 Tika 3.2.3 跑出来的，TC-03 可直接当判据）**：本夹具解析出 **263,910 字符 → 587 块**（最长 500、最短 210），**远低于 3000 的 `max-chunks-per-document` 上限**（不会撞 `10204`）。⚠️ 判据建议留余量：`charCount` 落在 25 万~28 万、`chunkCount` 在 585~590 之间 —— 精确断言 `587` 也可，但 Tika/PDFBox 版本一动就会假失败。<br>★ **实测耗时**（2026-09-22，宿主直连 Ollama 实测）：嵌入约 **2.1 s/批（16 块）** ⇒ 本夹具 37 批 ≈ **80~90 秒**（含解析与写库）—— 前端的上传超时据此定 **300s**（§5.1-4），TC-03 要记录实测耗时是否落在这个区间 |
| `知识库说明.txt` | 3.2 的正路径 + 3.3 的分块判据 | 建议 ~1200 字（→ 3 块），内容里埋一个只有它才有的答案（用于验证"答案基于知识库"） |
| `gbk编码.txt` | 编码探测的正路径 | 用 `iconv -f UTF-8 -t GBK` 生成（生成命令写进 TC-03 的备注） |
| `扫描版.pdf` | 3.2 的负路径（`10203`） | 只有图片层、无文本层的 PDF；若一时造不出，用**空文件**替代并注明"未覆盖扫描版形态" |
| `超大.txt`（11MB） | 上限判据（`40003` / 连接重置，§6.2 待实测项） | 用 `head -c 11000000 /dev/urandom \| base64 > 超大.txt` 生成，**不要提交进仓库**（加进 `.gitignore` 或用生成脚本） |

### 7.3 `docs/test-cases/TC-03.md` 必须覆盖的用例（qa-engineer 按 §8 展开）

- **3.1**：① 两表与三个索引存在（`pg_indexes` 查询）；② **`EXPLAIN` 显示走 HNSW 索引** —— ★ **必须先 `SET enable_seqscan = off`**（表小时规划器会选 Seq Scan，照字面断言会假失败）：`EXPLAIN SELECT chunk_id FROM t_kb_chunk ORDER BY embedding <=> (SELECT embedding FROM t_kb_chunk LIMIT 1) LIMIT 5;`；③ 写入后可检索（问答命中）；④ 级联删除与唯一约束（沙箱脚本 `.tmp/main/sandbox-probe.sh` 是现成的机械动作实现，qa 可搬进 `qa/scripts/`）
- **3.2**：① TXT 正路径；② PDF 正路径；③ GBK TXT 不乱码；④ 扫描版/空文件 → `10203`；⑤ `.docx` → `10201`；⑥ 11MB → `40003`（或其真实形态）；⑦ 非 multipart 请求 → `40001`
- **3.3**：① `chunk_size=200` 重打包后同一文档块数变多（**可配置的判据**）；② 日志出现 §4.11 的六条；③ **跨租户不可见**（`demo` 账号的列表里看不到 `admin` 上传的文档；用 `demo` 的 token 删 `admin` 的 documentId → `10202`）；④ mapper debug 日志里能看到注入后的 `tenant_id = ?`
- **3.4**：① 命中问题 → `grounded=true` + `sources` 非空 + 答案含资料里的数字；② **无关问题 → `grounded=false` + `sources=[]` + 未调用大模型的日志**；③ `topK` 越界 → `40001`；④ 阈值标定记录（§3.9 探针 D 的步骤与结果）；⑤ ★ **跨租户提问**（用 `demo` 的 token 提问，**不得命中 `admin` 上传的文档**）—— 强制项，理由见 §9 风险 4：检索语句绕开了租户拦截器、`tenant_id` 是人手写的，必须有机器判据兜住
- **3.5**：① 上传 → 列表出现 → 删除 → 列表消失且分块一并删除（SQL 复核）；② 上传中/问答中的 loading 与按钮禁用；③ 上传失败文案可直接展示；④ 两条链路都走 **8088（nginx）**
- **回归**：`/api/health` 200 + 三项 UP；`/api/chat/stream` 仍能流式；登录/401 三态不受影响；`check-health.sh` 复跑无新增 FAIL
- **契约复核**：`docs/api/README.md` §7 与 `openapi.yaml` 与实际响应逐字段一致（含 `grounded=false` 时 `generation` 为 `null`）

> **本阶段沿用 01 的 D8：不引 Vitest / Playwright**，前端验收 = `npm run type-check` + TC-03 的手工用例。
> 解禁时机与 E2E 落点见 §10.8。

---

## 8. 验收对照表（逐条对应 task.4 原文）

| # | 验收标准（task.4 原文） | 验证方式 |
| --- | --- | --- |
| 3.1 | 向量写入后可检索；检索有索引（HNSW / IVFFlat） | ① 上传任意文档后问一个文档内的问题 → `sources` 非空；② `pg_indexes` 里有 `idx_kb_chunk_embedding_hnsw`；③ `EXPLAIN` 走索引（TC-03 §3.1） |
| 3.2 | ~~三种~~**两种**格式均解析出纯文本（**口径已订正**，见 §0.1 第 1 条）；大小 / 类型限制与异常处理完善 | TXT + PDF 各一次正路径；`10201` / `10203` / `40003` / `40001` 四条负路径 |
| 3.3 | 分块大小 / 重叠可配置；向量入库有 `log.info` 链路日志；数据租户隔离 | ① 改 `chunk-size=200` → 重打包 → 块数变化；② 日志出现 §4.11 六条；③ 跨租户用例（TC-03 §3.3-③） |
| 3.4 | 检索 TopK 相关分块并拼接 Prompt，答案基于知识库内容生成 | ① `retrieval.topK/hits` 与日志一致；② `sources[].content` 是命中的分块原文；③ 答案里的数字与资料一致（**不能答对非资料里的问题**：用"资料里没有的问题"验 `grounded=false`） |
| 3.5 | 上传"公司年报.pdf"后提问"去年利润是多少"能准确回答 | `qa/fixtures/rag/公司年报.pdf` 走 **8088** 完整跑一遍（上传 → 列表 → 提问 → 答案 + 引用 → 删除） |
| **阶段** | 知识库页面 + RAG 问答完整链路 | 上述 3.1~3.5 的组合实测 |
| 0/1/2 回归 | **不得破坏既有交付物** | `check-health.sh` 复跑；`/api/chat/stream` 两模型各跑一次；登录/401 三态；前端四个页面可打开 |
| 契约 | 契约文档先于代码 | `docs/api/README.md` §7 + `openapi.yaml` 先落，含 §3.7 失败形态表与 §3.8 错误码 |

> **实机验证排在 18:00 之后**（重活：镜像产物重建 + Maven 全量构建 + 一次真实上传）；编码与静态检查不受此限。

---

## 9. 风险清单

| # | 风险 | 影响 | 应对 |
| --- | --- | --- | --- |
| 1 | **PDF 文本提取质量**（表格错位、多列串行、页眉页脚混入） | 答案答不准，且**看起来像"检索坏了"** | ① `char_count` 落在列表页，一眼看出解析是否成功；② §4.3 的空白归一化；③ **排查顺序写死在 TC-03 里**：先看 `sources[].content` 本身乱不乱 → 乱=解析问题，不乱=检索/阈值问题（**这一步省掉半小时**）；④ 版面分析/OCR 记 §10.3 |
| 2 | 扫描版 PDF（无文本层） | 用户以为上传成功，实际 0 块 | `10203` 明确报错（**不做 OCR**，记 §10.3）。判据：`char_count` 接近 0 时必然报错 |
| 3 | ~~embedding 维度与 `vector(768)` 不符~~ **已消除** | — | ✅ 2026-09-22 探针 A 实测维度 = 768，与表定义一致。**处置保留**：维度仍写进启动期日志，将来换 embedding 模型时第一个发现 |
| 4 | ~~jsqlparser 改写不了 `<=>`~~ **已实测发生并处置** | ✅ 元凶**只有** `<=>`；`CAST(? AS vector)` **单独出现时解析与注入都正常** ⇒ 原退路①（换 `?::vector`）**不对症，已作废**。失败形态不是"条件没注入"，而是**每条请求都抛 `MybatisPlusException`** ⇒ 不处理则问答链路全挂 | **实测处置**：`search` 标 MP 的 `@InterceptorIgnore(tenantLine = "true")`（它在 `willIgnoreTenantLine` 处**短路在解析之前**）+ 手写 `tenant_id`（`where` 里两处、两张表都判）。⚠️ **项目自己的 `@IgnoreTenant` 救不了这里**：它是 AOP，只让 `ignoreTable()` 返 true，而 MP 是"先 `parserSingle` 解析、后咨询 `ignoreTable`"（已实测：`ignoreTable` 恒真照样 FAIL）。**连带强制项**：TC-03 必须有一条 **"B 租户提问不得命中 A 租户文档"** 的用例 —— 这条退路把结构化保证降级成"人手写对"，必须有机器判据兜住 |
| 5 | HNSW 近似 + 租户后置过滤导致召回下降 | 跨租户数据量大时"检索不到" | 演示规模无影响；规模化方案（分区 / 部分索引 / 提高 `hnsw.ef_search`）记 §10.3 |
| 6 | Tika 版本/坐标在镜像源上不存在 | 构建失败（响亮，不静默） | §6.4 的回退链（`tika-parsers-standard-package` / 2.9.2）+ 首次构建后核对版本 |
| 7 | **multipart 默认上限 1MB** + Tomcat `max-swallow-size` | 大文件上传表现成"连接被重置"而不是 `40003` | §6.2 显式配 `max-file-size`；`max-swallow-size` 按实测决定是否加 |
| 8 | **长事务占 Hikari 连接**（并发上传） | 池满 → 其它接口全部排队 | 池大小 10、演示规模无并发；日志里事务耗时可见；异步化记 §10.5 |
| 9 | 前端超时 ≠ 后端失败（D7 同步口径的必然结果） | 用户重传 → 重复文档 | §5.1-4 的文案 + 逐请求超时（**上传 300s / 问答 120s** —— 实测夹具上传 ≈ 80~90 秒，原定的 120s 太贴边）；重名不去重是**已知行为**（§10.6） |
| 10 | 无 `DEEPSEEK_API_KEY` 时问答默认走云端会 20100 | 演示现场翻车 | 与阶段2 同款、同处置：`printenv \| wc -c` 先验；离线演示把 `answer-model-type` 改成 `OLLAMA`（改配置要重打包） |
| 11 | **答案幻觉 / 引用与答案不符** | 面试被追问时站不住 | 三道闸（D9）+ `sources` 原文可见（**可当场核对**）+ prompt 明确"只依据资料"；把"如何量化幻觉率"记 §10.9 |
| 12 | 同名文档重复上传 | 列表里两份同名、检索被稀释 | 本轮**允许**（不去重）；`content_hash` 去重记 §10.6 |
| **13** | **检索召回不足（中文语料）—— 已实测发生并处置** | ✅ 端到端验收时**真的踩到了**：问「去年利润是多少」，答案所在的块在 587 块里**排第 27 名**（top5 取不到），而 5 条无关块拿 0.71~0.75 分 ⇒ 答案被生成侧如实判为"未找到"（**我们的三道闸工作正常，是召回没把资料送上去**） | **处置**：换中文检索专长的 `bge-m3`（1024 维）+ 前缀置空 + 重建表 + 重灌（§0.3）。**仍未解决**：混合检索（关键词 + 向量）/ rerank / 分块粒度调参 —— 记 §10.3，**下一次复测若仍不进前 3，就按这个顺序上** |

---

## 10. 演进方向与本轮明确不做（防范围蔓延）

### 10.1 Word 支持（**一行依赖的距离**）
加 `org.apache.tika:tika-parser-microsoft-module` + 白名单加 `docx`/`doc` + 一个用例。
**代码零改动**（`DocumentParser` 的第 3 条分支不动，Tika 自己按内容选解析器）—— 这是 D13 把解析收口成端口的直接收益。

### 10.2 多知识库
契约加 `knowledgeBaseId`（可空，缺省=默认库）+ 两张表加 `kb_id` 列 + 新增补丁回填 + 前端选择器。
**本轮不做**（§0.1 第 2 条）；`tenant_id` 隔离已能支撑"每租户一个库"的真实用法。

### 10.3 检索质量（本阶段最值得继续做的方向）
语义分块 / 按句切、rerank 模型、混合检索（BM25 + 向量）、`hnsw.ef_search` 调参、
按租户分区或部分索引、PDF 版面分析、OCR、答案质量评测集（问题 → 期望答案，跑一遍算命中率）。

### 10.4 流式问答（把 D6 的取舍换回来）
让 `POST /api/kb/ask` 复用阶段2 的 SSE 帧协议：**先发一帧 `sources`**（引用先定），再发 `delta` 打字机。
代价：改 README §6 + openapi + `sse.ts` + 页面（这是本轮**刻意省掉**的那块工）。

### 10.5 异步入库
`t_kb_document.status`（PROCESSING / READY / FAILED）+ 提交到线程池 + 轮询接口。
**必须同时做 D10 那件事**：工作线程上重建 `TenantContext`（阶段2 已经留好了 `CallerContext` 的搬运模式）。

### 10.6 其它 backlog
原始文件留存与重新解析、按文档过滤检索（`documentIds`）、`content_hash` 去重、分页、
embedding / 答案缓存（同一问题重复问会重复调用上游）、批量上传、进度条。

### 10.7 技术债（本轮记录，不在本阶段处理）
`nexus-module-system` 未显式声明 mybatis-plus（`nexus-module-ai` 本轮显式声明了，两模块口径不一致）。

### 10.8 前端测试框架解禁
沿用 01 的 D8（不引 Vitest / Playwright）。解禁时 E2E 落 `qa/e2e/`（**不放在** `frontend/src/__tests__/`，也不与组件单测混放）。

### 10.9 面试可讲的三个"为什么"（本阶段真正的产出）
1. **为什么手写不用框架**（D1）—— 每一行都能讲；
2. **为什么阈值要标定而不是抄一个数**（D9 + §3.9 探针 D）—— 讲的是方法论；
3. **为什么 `ORDER BY` 的写法决定索引是否生效**（§4.6）—— 讲的是"懂数据库"。

---

## 11. 决策记录

| # | 事项 | 定案 | 时间 / 方式 |
| --- | --- | --- | --- |
| 1 | 文件格式范围 | **TXT + PDF**（Word 记 backlog，路径见 §10.1） | **2026-09-22 用户拍板** |
| 2 | 表结构 | **两张表**，知识库维度 = `tenant_id`（多知识库记 backlog，§10.2） | **2026-09-22 用户拍板** |
| 3 | 问答响应形态 | **同步 JSON**（流式记 backlog，§10.4） | **2026-09-22 用户拍板** |
| 4 | 向量库接入 | **手写 SQL + 自定义 TypeHandler**，不引 `spring-ai-pgvector-store` / pgvector-java（D1/D2） | 依据 `RAG建议.md`，与阶段2 D2 同源 |
| 5 | 入库时机 | **同步、单事务**（异步记 backlog，§10.5） | 依据"最小闭环"，代价见 D7 |
| 6 | 原始文件 | **不保存**（D8） | 依据 task.4 交付物范围 |
| 7 | 生成模型 | `answer-model-type` 可配，**默认 `DEEPSEEK`**（离线演示改 `OLLAMA`） | 建议即定案（配置化，可改） |
| 8 | 相似度阈值初值 | `0.5`，**必须标定**（§3.9 探针 D） | **待实测**，标定结果回写 TC-03 |
| 9 | embedding 前缀 | **机制保留、默认置空**（2026-09-22 晚随换模型订正：原为"默认开启"）—— `search_document:` / `search_query:` 是 nomic 模型卡的建议，当前模型 `bge-m3` 不需要；改它/换模型都必须重灌数据 | 建议即定案（有 A/B 步骤，可开可关；见 D14 与 §0.3） |
| 10 | 分块参数 | 500 / 50（可配） | 依据 `RAG建议.md` |

**无未决项** —— 上表 10 条中 3 条已由用户拍板、7 条为"依据文档 + 可配置"的定向选择，本阶段可以开工。
唯一**阻塞项**是 §7.2 的 `公司年报.pdf` 夹具（阶段验收的硬前提，**待用户准备**）。

---

## 12. 变更记录

| 日期 | 变更 | 说明 |
| --- | --- | --- |
| 2026-09-22 | 首版 | 阶段3 全量设计：D1~D15 决策、四个接口契约、两张表 + HNSW、四类错误码增量、TXT 编码探测 + Tika(PDF) 分工、`ModelAnswerGenerator` 适配阶段2 网关、同步 JSON 问答、四条探针 + 阈值标定步骤、各角色领取章节（§0.2） |
| 2026-09-22 | §0.1 记录三处文档矛盾与拍板 | 格式范围（三格式 → 两格式）、表结构（三张 → 两张）、向量库接入（二选一 → 手写）；均由用户在编写前拍板，避免矛盾沉进代码 |
| 2026-09-22 | **四条探针实测**（§3.9） | A: `/api/embed` 存在且**维度 = 768**（`vector(768)` 定稿、§9 风险 3 消除）；B: **支持批量**（`embed-batch-size` 可用）；C: `num_ctx 8192`（500 字块远小于窗口）、v1.0/v1.5 待 A/B 用例判定；D: pgvector **0.8.6**（HNSW 可用）。探针 D 的落点订正为 `nexus-postgres` 容器 |
| 2026-09-22 | 状态置「已确认」+ 夹具落定 + 编号收口 | ① 用户确认设计并开工（后端第一段已交付）；② **契约 §7.N 与本文档 §3.N 收成一比一** —— 原 §3.8 的"（进 §7.4）"是从阶段2 的"§6.4"顺手抄来的笔误（阶段2 的 §3.4 恰好是错误码，阶段3 不是），子代理上报后由主会话按阶段2 惯例收口，并把该约定写进契约 §7 开头；③ `公司年报.pdf` 由用户提供（含文本层）且**刻意不入库**（§7.2 已更新，`.gitignore` 有对应规则） |
| 2026-09-22 | **D12 订正**：文件白名单由"配置键"改为**常量** | 初版 D12 写的是配置键 `nexus.ai.rag.allowed-extensions`（而 §6.2 的 yml 清单里**从来没有这个键** —— 子代理上报了这个缺口）。实施时定案：**白名单是 `TikaDocumentParser` 里的常量**，因为可配的白名单会让 `10201` 里写死的文案"仅支持 TXT / PDF"**说谎**，而"支持哪几种格式"本就是范围决策（加 Word 要同时加 Tika 模块与用例，§10.1）。连带订正 3 处引用：`ResultCode` 的 `KB_FILE_TYPE_UNSUPPORTED` javadoc、契约 §7.8 的"已知成本"提示、`openapi.yaml` 的错误码说明 —— 三处原先都写着"白名单可配、文案要一起改" |
| 2026-09-22 | **第二段 a 交付后的设计订正（6 处）** | 子代理交付 11 个类（**含真实 jar 的 `javac` 编译自检 + 59/15 项行为探针**）并上报 11 条偏离，主会话逐条裁定后订正本设计：① embedding 的**三个常量**（§4.5 —— 含"`nexus.ai.ollama.model` 是对话模型、不能拿来向量化"这条警告）；② **TXT 不做 Tika 内容检测**（D12 —— 合法 GBK 文件会被判成 `octet-stream` 而误杀成 10201）；③ **尾块合并不可达**（§4.4 —— 7920 组穷举 0 次触发，保留分支但不再当用例）；④ `TextChunker` 构造期 fail-fast（§4.4 —— `overlap ≥ size` 会让首个上传请求死循环）；⑤ **空答案按 `20100` 失败**（§4.7 —— 由 `KbAskServiceImpl` 判，绝不给"看着成功其实没答"的响应）；⑥ **`entity/KbChunk` 不建**（§4.1 —— 全链路自定义 SQL ⇒ 实体无消费者，属死代码）。另：不因一条日志给 `generate(...)` 加参数（§4.7 第 5 条），因此 §4.11 的"生成完成"行去掉 `sources=N`（与检索行的 `hits=N` 同源） |
| 2026-09-22 | **第二段 b 交付后的设计订正（4 处）+ 一条实测数字** | 子代理交付 15 个类（编译自检 40 源文件 exit 0 + 4 项行为实测）并上报 9 条偏离，主会话逐条裁定：① **§4.6 的检索 SQL 换成实况** —— pgvector 的 `<=>` 让 jsqlparser **解析失败**、租户拦截器"先解析后注入" ⇒ 每条问答都会抛 `MybatisPlusException`；处置是 `@InterceptorIgnore(tenantLine="true")` + **手写 `tenant_id`（两张表都判）**，§9 风险 4 据此从"待实测"改为"**已实测发生并处置**"，原退路①（换 `?::vector`）作废；② §4.1 `KbChunkMapper` 两条语句（`deleteByDocumentId` 不建）；③ §4.8 事务改 `TransactionTemplate`（同类内部调用的注解事务会静默失效）；④ §4.11 补两个判据细节（`durationMs` 含问题向量化；`tenant_id` 在 INSERT 是**字面量**、在检索是**绑定参数**，别混着核对）；⑤ §3.5 `createdAt` 零偏移渲染成 `Z`；⑥ §3.7/§7.7 补"文件名超 255 → `40001`"；⑦ **契约 §7.7 的 503 由代码修齐**（`GlobalExceptionHandler` 加 `code=20100` ⇒ 503 的按码分流，契约一字未改）；⑧ §7.3 把**跨租户提问**升为强制用例。★ **实测数字**：嵌入 ≈ **2.1 s/批（16 块）** ⇒ 夹具 587 块 ≈ **80~90 秒**上传 ⇒ §5 的上传超时由 120s 改 **300s** |
| 2026-09-22 | **沙箱库重放补丁 + 索引/级联/约束实测；订正 §4.6 第 2 条的理由** | 用一次性沙箱库（`nexus_patch_probe`，真实 `nexus` 库**零写操作**）重放「补丁1 + 补丁2」→ 两条 **exit 0**（新补丁**首次被真正执行**，此前是"没人跑过"的风险）。实测五点：① 本项目的 `ORDER BY 嵌入列 <=> 常量` → **`Index Scan using idx_kb_chunk_embedding_hnsw`**；② `ORDER BY score DESC`（别名）→ **`Sort` + `Seq Scan`**（§4.6 第 1 条成立）；③ **阈值进 `WHERE` → `Index Scan` + `Filter` —— 索引照样可用** ⇒ **§4.6 第 2 条初版"会让索引失效"的理由被实测推翻**，已改为按**语义**（TopK 的定义）选择应用层过滤；④ 删文档 → 分块**级联清零**；⑤ 唯一约束 `uk_kb_chunk_doc_index` 如期拦住重复块。**连带订正 3 处 `EXPLAIN` 判据**（§4.6 / §7.3 / 补丁注释）：**必须先 `SET enable_seqscan = off`**，否则表小时规划器选 Seq Scan 会让判据**假失败**。⚠️ 复现时注意：`wsl … bash -s < 脚本` 会让脚本与 `docker exec -i` 争用同一个 stdin（症状是输出为空、脚本静默半途而废、**沙箱库残留**）—— 落成文件再执行 |
| 2026-09-22 | **测试段交付：修掉一个阻塞验收的产品缺陷（nginx 1MB 体量墙）** | qa-engineer 交付 6 个单测类（**59 条，59/59 绿**）+ TC-03（**33 条 / 7 组**）+ 两条 qa 脚本（都实跑过）+ 5 件夹具（含**真·无文本层**的 `扫描版.pdf` 与 GBK 夹具）；其只读探针发现**走 8088 上传 >1MB 被 413 挡下**（`nginx.conf` 缺 `client_max_body_size`，nginx 默认 **1m**；**1.68MB 的验收夹具必挂**，而直连 8089 正常、响应体还不是 `Result`）。主会话复核后修：加 `client_max_body_size 12m;` + **重建前端镜像** → 复测 1.68MB 走 8088 得 **`401` + 标准 `Result`** ✅。⇒ **§6.0 / §6.1 / §6.5 三处"本阶段零改动"的表述同步订正**（初版判断错了，且这是"只有走 nginx 才现形"的**第二例**，前一例是阶段2 的 `proxy_buffering`）。另：qa 对共享库 `tc-common.sh` 的改动经复核**向后兼容**（三个 HTTP 动作各加**可选**基地址参数，静态核对全部调用点 + 实跑 `tc01-login-then-me.sh` 退出码 0） |
| 2026-09-22 | ★ **换 embedding 模型：`nomic-embed-text`(768) → `bge-m3`(1024)** | 起因：**端到端验收有一条判据不成立** —— 问「去年利润是多少」返回 `grounded=true` 但答"未找到"，引用的 5 条与问题无关。devops 用数字排除了四个替代假设（前缀/生成/解析/阈值），定位到**召回不足**：含答案的块在 587 块中**排第 27 名**，而无关块也拿 0.71~0.75 分（阈值标定救不了）。处置见新增的 **§0.3**：改 `EMBED_MODEL`/`EXPECTED_DIMENSION` 两个常量 + 前缀置空（`search_*:` 是 nomic 的模型卡建议，bge-m3 不期望）+ **新增补丁 `202609221100` 重建两表为 `vector(1024)`（清空数据）** + **重灌文档**。连带同步：`docker-compose.yml` 的 `ollama-init` 模型清单、`scripts/lib/probe.sh`、契约 §4.5 的模型就绪判据（**均属"活配置"**，历史记录只标注不改写）。**仍未解决**：混合检索 / rerank / 分块粒度（§10.3，下一次复测若仍不进前 3 就按此顺序上）。另新增 §9 风险 13 记录本事件 |
| 2026-09-22 | ★ **修掉一个阻塞手动验收的 415：FormData 被 JSON 化，文件字节从未发出** | 手动走查上传报"请求格式不支持，请使用 application/json"。**按纪律派 `backend-engineer` 复现定位**（诊断归角色，主会话不动手）。根因：`request.ts` 实例默认头 `application/json` ⇒ axios `transformRequest` 把 `FormData` JSON 序列化成 **11 字节 `{"file":{}}`**，**文件字节根本没离开浏览器**；"`data` 是 `FormData` 就删头"那段因 `data` 已非 `FormData` **永不触发**。⇒ **本文档 §5.1-2 初版写的正是相反的口径**（"不要写死 `multipart/form-data`…会丢 boundary"），照初版实现就是把 415 写回来 —— 已按实测订正 **4 处**：§5.1-2 正文、§5 签名块注释、§3.9 探针①注释、§5 文件表（`request.ts` 由"不改"改"改"）。**处置**：前端 2 处（`kb.ts` 显式声明 `multipart/form-data`；`request.ts` 拦截器对 `FormData` 删头 —— 实测修前 body=字符串 `{"file":{}}`、修后=真 `FormData`）；后端把 **multipart 接口的 415 收成 `200 + 40001`**（按 `ex.getSupportedMediaTypes()` 判端点；非 multipart 仍是 `415 + 40002`，4 格行为探针全过）⇒ 契约 §7.1 与 openapi 的"415 响应体不是 `Result`"表述同步作废。★ **教训**：凡"库会自动帮我做 X"的设计断言，**必须实测**——本文档唯一挂着"待实测确认"的一条，恰恰就是错的。 |

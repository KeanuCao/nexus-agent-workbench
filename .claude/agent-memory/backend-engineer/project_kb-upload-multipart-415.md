---
name: kb-upload-multipart-415
description: （2026-09-22）知识库上传 415/40002 的根因与"axios 会主动删 Content-Type"这句假断言所在的 5 处文件
metadata:
  type: project
---

**2026-09-22 事实**：用户走查知识库页上传必失败 = HTTP 415 + `code 40002`（msg"请求格式不支持，请使用 application/json"）。

- 前端根因（源码级已证）：`request.ts` 的**实例默认头** `Content-Type: application/json`。axios 1.20 `lib/defaults/index.js:56-58` 在 `hasJSONContentType` 时把 FormData **转成 JSON 字符串**（实测 body = `{"file":{}}`，文件字节根本没发出）；于是 `lib/helpers/resolveConfig.js:65-73` 那段"data 是 FormData → 删掉该头"的分支**永远不会触发**（它的前置是 data 到那时仍是 FormData）。
- 后端根因：`consumes=multipart/form-data` 与请求头不匹配 ⇒ `RequestMappingInfoHandlerMapping.handleNoMatch` 抛 `HttpMediaTypeNotSupportedException`（**不是** `MultipartException`）⇒ 落进阶段2 给 JSON 接口开的 415/40002 出口，与契约（上传的这类失败应走 **200 + 40001**）矛盾。后端日志实证：`请求媒体类型不受支持：contentType=application/json`。修复判据：该异常自带 `getSupportedMediaTypes()`（6.1 的 handleNoMatch 用 `new ArrayList<>(getConsumableMediaTypes())` 构造），含 multipart 即分流。

**Why**：契约/设计里那句"axios 1.x 在 `data` 是 FormData 时会主动删掉该请求头"**有前置条件**，写成无条件断言后在"实例默认头是 application/json"的配置下就是假的 —— 而它恰好被抄进了 5 处，会反复误导下一个改这块的人。

**How to apply**：动这块代码前先读这几处，别把它们当依据 ——
`docs/api/README.md` §7.1 约定 2、`docs/api/openapi.yaml` 上传 operation description 第 2 条、`docs/design/03-RAG知识库.md` §5.1-2（原话还写着"待实测确认"）、`frontend/src/api/kb.ts` 的 `uploadDocument` 注释、`KbDocumentController` 类注释。
两条修复（前端声明 multipart / 后端按 supported media types 分流）**都落地才算契约一致**；只改后端的话上传仍失败（只是把 415 换成 40001）。

**2026-09-22 落地状态（未提交，在工作区）**：后端 `GlobalExceptionHandler` 已加 `isMultipartEndpoint` 分流（编译 + 离线探针 4 格通过）；前端 kb.ts 显式头 + request.ts 拦截器对 FormData 摘头（两处都生效，`config.headers` 在拦截器阶段确是 AxiosHeaders，见 `Axios.js:162`）；文档订正已做 README §7.1/§6.1、openapi 上传 description、`KbDocumentController` 类注释。**`docs/design/03-RAG知识库.md` §5.1-2 与 `docs/agent-log/20260922-阶段3前端知识库页.md` 里那两处同源断言仍未改**（前者归协调者、后者是历史留档）。相关：[[agent-log-cross-session-lessons]]。

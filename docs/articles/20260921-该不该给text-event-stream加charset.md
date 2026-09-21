# 该不该给 text/event-stream 加 charset

> 一次从**实测**到**规范**、再定下 **6 处订正**的完整排查。顺带记下同日撞见的两个静默失效 —— 它们都"看着像跑通了"。

---

## 01 现场：那三行响应头

一次流式对话的验收里，我把响应头**原样**抓了下来（带到达时刻，不加工）：

```
[09:17:45.522] HTTP/1.1 200
[09:17:45.525] Content-Type: text/event-stream
[09:17:45.527] Transfer-Encoding: chunked
[09:17:45.529] Date: Mon, 21 Sep 2026 01:17:45 GMT
```

问题在第二行：**没有 `;charset=UTF-8`**。

而我们自己的测试用例判据里写的是 `Content-Type: text/event-stream;charset=UTF-8` —— 用例和实测对不上。于是有了这次排查。

## 02 先判"该不该"，再判"谁错了"

顺序很重要。如果一上来就去翻代码找"是谁漏了 charset"，很快会掉进"谁改谁对"的扯皮 —— 而这类问题的答案，通常**不在代码里，在规范里**。

SSE 的 IANA 注册里，`charset` 是**可选**参数，原文：

> **serves no purpose; it is only allowed for compatibility with legacy servers.**

（它不服务于任何目的，只为兼容遗留服务端而保留。）

WHATWG HTML 标准里更直接：

> **Event streams are always decoded as UTF-8. There is no way to specify another character encoding.**

（事件流恒按 UTF-8 解码，无法指定其它编码。）

⇒ 加它**不改变任何行为**。而本项目的接口契约 §6.1 声明的就是裸 `text/event-stream`，实现与契约一致。

**结论：不加。** 保持裸值，前端也无需依赖它 —— 它自己 `TextDecoder('utf-8')` 解码、按 `includes('application/json')` 分流。

## 03 那么错的是理由，不是结论

文档里原来那句话是这样的（两处文档 + 契约机器版里都有）：

> 判断响应类型要用 `includes('application/json')`，**因为 Spring 会给 `text/event-stream` 带上 `;charset=UTF-8`**，全等比较会误判。

实测把这句因果拆开了：**SSE 那半不带**，而**失败形态的 `application/json` 带**（`application/json;charset=UTF-8`，同一天另一次实测）。

也就是说 —— **"别用全等比较"这个结论是对的，理由却指错了对象**：两边形态**本就不一致**，这才是不能用全等比较的原因。

再补一个能讲"为什么"的机制。失败侧是**手写响应**：

```java
// JwtAuthenticationFilter.writeUnauthorized()
response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
response.setContentType(MediaType.APPLICATION_JSON_VALUE);
response.setCharacterEncoding(StandardCharsets.UTF_8.name());   // ← 就是这句让容器补上 charset
```

成功侧走 `SseEmitter` + `produces = text/event-stream`，**没有任何人设置编码**。

**两类不同的写入者 ⇒ 形态不一致是必然，不是偶发。** 这比"Spring 会怎样怎样"精确得多，也是能拿去讲的那种解释。

## 04 一句错理由，复制到了 6 处

订正范围不是按"文档几处"定的，而是按**这句错理由被复制到了几处**定的：

| 位置 | 性质 |
|---|---|
| `docs/api/README.md` §6.3 | 说明文字 |
| `docs/design/02-统一AI网关.md` §5.1-2 | 说明文字 |
| `docs/api/openapi.yaml` **两处** | 其中一处是**直接断言成功响应头带 charset** —— 与实测正面冲突，最重 |
| `frontend/src/api/chat.ts` 注释 | **代码注释**：理由错、行为对 |
| `ChatController.java` javadoc | **代码注释**：理由错、行为对 |

**"理由错、行为对"是最难发现的一类错。** 行为正确（用 `includes`）会让人以为整句话都对，错的理由就顺着注释 → 文档 → 契约一路复制。**行为正确不构成理由正确的证据。**

订正的分寸：**只换理由半句，结论一字未动**（`includes('application/json')`、"必须自己判 `response.ok`" 都原样留着）；`application/json` 那半**实测存在**的 charset 也一个没删 —— 动了就是造假。

## 05 同一天撞见的两个静默失效

### A. 测试工具漏了一个 `return`

我们的流式捕获工具会把每一行**原样**打出来（带到达时刻）。跑完一看，那一整段 1065 行**全是 `None`** —— 连响应头都看不见。

而与此同时：**汇总统计完全正确、工具自检全绿。**

原因：`feed_line()` 少了一句 `return`，调用方拿到的永远是 `None`。

> 教训：**自检只覆盖统计，挡不住"人看到的东西是坏的"。** 已补两项"逐行回显"检查，并用"故意删掉 `return` 的副本"验证过它真的会报不通过。

### B. 环境变量文件里的同名键

`.env.local` 里有两行同名的 API key，而 compose 的 `env_file` 是**后面的行覆盖前面的行** ⇒ 容器里生效的是第二个（一个 14 字符的占位值），真密钥被**静默遮蔽**。

更隐蔽的是：**启动日志照样打 `apiKey已配置=true`** —— 它只判"非空"。直到第一次云端请求，才以 `401 → 20100` 的形式现形，而现场看起来像"上游挂了"。

> 教训：**"非空"不等于"有效"。** 判环境态时别只看"有没有"，要看"值是什么"。

## 06 可复用的五条

1. **先判"该不该"，再判"谁错了"** —— 规范 → 契约 → 依赖方，最后才轮到代码。
2. 文档里每个"**因为…**"都是**待验证的断言**，不是背景。被多处引用的那句尤其要核。
3. 发现"结论对、理由错"：**只换理由、不动结论**，并且**一次改完所有副本** —— 漏一处，下次还会有人从那处抄回去。
4. **判据的措辞必须能追到"实测值"或"规范条文"**。追不到的别写进判据 —— 本文里那条判据 ① 就是"照抄文档"的反例：它测的是文档自洽，不是系统行为。
5. 日志说"已配置"、自检说"通过"、统计说"正确"，**都不等于**"人看到的是对的"。

---

*以上都发生在一个真实项目的一次验收里：改动只涉及措辞与注释，**代码零行为变更**。日期化留档里的同款说法按"不改历史留档"原样保留 —— 改了才是篡改记录。*

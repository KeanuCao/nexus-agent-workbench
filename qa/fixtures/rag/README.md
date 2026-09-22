# qa/fixtures/rag —— 阶段3（RAG 知识库）的测试夹具

> 用途、期望答案、判据见 [`docs/test-cases/TC-03.md`](../../../docs/test-cases/TC-03.md)（本文只记**夹具本身**：
> 它是什么、怎么来的、怎么重新生成）。
> 纪律：本目录**不属于任何生产链路**（`up.sh` / 镜像构建 / db-patch 迁移都不读它）；夹具要在**一次性作用域**里
> 使用 —— 上传到知识库的那一份**必须在用例内删掉**（Test Harmlessness）。

| 文件 | 是什么 | 期望结果（实测值，2026-09-22） | 怎么重新生成 |
|---|---|---|---|
| `公司年报.pdf` | **阶段验收夹具**：一份中文、含财务数字的真实年报（用户提供） | 1,682,689 字节；解析出 263,910 字符 → 587 块（Tika 3.2.3）；上传耗时 ≈ **130 秒**（2026-09-22 换 bge-m3 后实测 **129.75 秒**：37 批 × 3.21 s/批 ≈ 119 秒 + 解析写库 ≈ 11 秒；nomic 时代是 80~90 秒） | **不入库**（`.gitignore` 末段）。自备一份中文、含财务数字的 PDF，放本目录、保持同名即可 |
| `知识库说明.txt` | TXT 正路径 + "只有它才有的答案"（内部代号「星桥计划」、试点部门「客户成功部」、口令「砚台十七」） | 3,199 字节 / 1231 字符 → **3 块**（按 500/50 切） | 直接编辑：**保持 951~1350 字符**即可稳定切成 3 块（边界见 `TextChunker` 的规则 2） |
| `gbk编码.txt` | GBK 编码探测（UTF-8 解出来是一屏替换字符，必须按 GBK 重解）+ 跨租户用例的对照内容（试点部门「财务共享中心」，与说明.txt 的答案不同） | 623 字节 / 361 字符 → **1 块**；日志 `parser=gbk` | `iconv -f UTF-8 -t GBK 原文.txt > gbk编码.txt`（原文先存成 UTF-8，再用 iconv 转） |
| `扫描版.pdf` | **无文本层**的 PDF（整页只有一张 16×16 灰度图）→ 解析不出文本 | 915 字节；`TikaDocumentParser.parse(...)` → `BusinessException(10203)` | 见下方「最小 PDF 的造法」 |
| `../../scripts/tc03-kb-sandbox.sh`（不在本目录） | 表/索引/约束/级联的一次性沙箱探针 | 见 TC-03 §3.1 | — |

## 最小 PDF 的造法（`扫描版.pdf` 与单测夹具 `sample.pdf` 同源）

两份 PDF 都是用 **Python 手写字节**生成的（不引第三方库，也不从别处复制）：`%PDF-1.4` 头 + 对象体 + 正确的
`xref` 偏移表 + `trailer`。区别只在于**页面内容**：

- **有文本层的**（`backend/nexus-module-ai/src/test/resources/rag/sample.pdf`，683 字节）：
  页面内容流是 `BT /F1 18 Tf 72 700 Td (Nexus KB sample 2026) Tj ET`（Type1 标准字体 Helvetica，无嵌入字体）；
- **无文本层的**（本目录的 `扫描版.pdf`，915 字节）：页面资源里挂一个 `/XObject` 图像（`/Subtype /Image`，
  16×16、`DeviceGray`、未压缩的 256 字节像素），内容流只有 `q … cm /Im1 Do Q` —— **一个字符都没有**。

生成脚本（可照抄；`xref` 偏移量必须按实际字节数算，否则 PDFBox 要走"重建 xref"的兜底路径）：

```python
import pathlib

objs = []
objs.append(b"<< /Type /Catalog /Pages 2 0 R >>")
objs.append(b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
objs.append(b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            b"/Resources << /XObject << /Im1 4 0 R >> >> /Contents 5 0 R >>")
pixels = bytes([(i * 7) % 256 for i in range(256)])
objs.append(b"<< /Type /XObject /Subtype /Image /Width 16 /Height 16 /ColorSpace /DeviceGray "
            b"/BitsPerComponent 8 /Length 256 >>\nstream\n" + pixels + b"\nendstream")
content = b"q 300 0 0 300 150 400 cm /Im1 Do Q\n"
objs.append(b"<< /Length " + str(len(content)).encode() + b" >>\nstream\n" + content + b"endstream")

out = bytearray(b"%PDF-1.4\n")
offsets = []
for i, body in enumerate(objs, start=1):
    offsets.append(len(out))
    out += str(i).encode() + b" 0 obj\n" + body + b"\nendobj\n"
xref_pos = len(out)
out += b"xref\n0 " + str(len(objs) + 1).encode() + b"\n0000000000 65535 f \n"
for off in offsets:
    out += ("%010d 00000 n \n" % off).encode()
out += (b"trailer\n<< /Size " + str(len(objs) + 1).encode() + b" /Root 1 0 R >>\nstartxref\n"
        + str(xref_pos).encode() + b"\n%%EOF\n")
pathlib.Path("扫描版.pdf").write_bytes(bytes(out))
```

**验证它真的没有文本层**（而不是"我们以为没有"）：把字节喂给 `TikaDocumentParser.parse(...)`，
应当抛 `10203`；用 `pdftotext -layout 扫描版.pdf -` 抽出来应当是空的。

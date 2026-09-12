# nexus-common

公共模块：**统一响应体 / 异常体系 / 通用工具**。全项目的"最稳底座"。

## 硬约束

**零第三方依赖**（设计文档 §5.1）：只用 JDK + 必要注解。任何模块都可依赖本模块，
而本模块不依赖任何模块 —— 一旦引入 Spring / Jackson / Lombok，底座不再稳定，
并会把框架版本被动带到所有上层模块。

因此：

- `Result` 上**不能**加 `@JsonInclude` / `@JsonIgnore` 等 Jackson 注解（序列化行为由上层配置决定）；
- 不使用 Lombok，getter/setter 手写（多 20 行换取零依赖与可读的显式代码）；
- 工具类按需追加，禁止提前塞入"以后可能用得上"的抽象。

## 内容（阶段0）

| 包 | 类 | 说明 |
| --- | --- | --- |
| `result` | `Result<T>` | 统一响应体 `{code, msg, data}`，静态工厂 `success()` / `failure(...)` |
| `result` | `ResultCode` | 响应码枚举：0 成功、1xxxx 业务、2xxxx 可用性、4xxxx 请求侧、5xxxx 系统 |
| `exception` | `BusinessException` | 业务异常：`msg` 可展示给前端，处理器按 warn 记录、不打堆栈 |
| `exception` | `SystemException` | 系统异常：只进日志，前端统一收通用话术 |

## 注意

`Result` 刻意**不提供** `isSuccess()` —— Bean 序列化会把 `isXxx()` 暴露成 JSON 字段，
多出的 `success` 字段会破坏前端解包契约。判定成功请用 `getCode() == ResultCode.SUCCESS.getCode()`。

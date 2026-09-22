package com.nexus.module.ai.rag.embedding;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.result.ResultCode;

import java.util.List;

/**
 * 向量化端口：<b>文本 → 向量</b>（决策 D4）。
 *
 * <h2>为什么自成一个端口，而不复用 {@code AiModelService}</h2>
 * 那个端口是<b>生成</b>端口（流式、有回调与取消语义），而向量化是另一种调用：
 * 批量入参、无流、返回定长数组。塞进同一个端口会让"两条路径"互相污染 ——
 * 比如 {@code CancelToken} 对 embedding 毫无意义，而 {@code Chunk} 也表达不了上千个浮点数
 * （维度见实现类的 {@code EXPECTED_DIMENSION}，随模型变）。
 * HTTP 客户端仍然复用阶段2 既定的 {@code RestClient}（<b>不引第二套客户端</b>）。
 *
 * <h2>两个方法为什么刻意分开（而不是一个方法 + 方向参数）</h2>
 * 入库与查询要加<b>不同的</b>任务前缀（决策 D14 的机制：入库 {@code search_document: }、
 * 查询 {@code search_query: }；<b>当前模型 {@code bge-m3} 不需要它们，两侧配置默认都是空串</b>，
 * 见实现类），而"该加哪个前缀"是实现内部的事：拆成两个方法后，调用方
 * （{@code KbDocumentServiceImpl} / {@code KbAskServiceImpl}）拿到的是一句自解释的调用，
 * 前缀策略不会泄漏到业务代码里，也不存在"传错方向"这种参数。
 * ⚠️ 前缀当前是关闭的，但这两个方法<b>不合并</b>：预处理策略本就该收在实现里，
 * 端口不该随一个配置值的取值而变形状（将来换回需要前缀的模型、或两侧改用不同预处理，都靠这条边界）。
 *
 * <h2>实现要遵守的失败约定（与阶段2 的 provider 完全同口径）</h2>
 * <ul>
 *     <li><b>网络类失败 / 非 2xx / 上游错误报文</b>：抛 {@link BusinessException}
 *         （{@link ResultCode#CHAT_UPSTREAM_UNAVAILABLE}，20100）——
 *         对使用者而言"向量化服务不可用"与"模型服务不可用"是同一件事（同一个 Ollama 容器），
 *         复用同一个码才不会让前端为同一件事写两个分支；</li>
 *     <li><b>其余异常</b>（本服务侧的序列化失败、响应形状不符等）：不要包装成 20100 ——
 *         "上游真的挂了"与"我们写挂了"必须在日志里分得开（见 {@code AiModelService} 的同段注释）。</li>
 * </ul>
 *
 * <h2>调用方要遵守的一条</h2>
 * {@code texts} 里的元素<b>不可为 {@code null}</b>，且返回值的<b>顺序与入参严格一一对应</b>
 * （实现不得重排、不得去重）—— 顺序错了会让分块与向量错位，检索出来的引用与内容对不上，
 * 而那种缺陷在日志里完全看不出来。
 *
 * @author nexus
 */
public interface EmbeddingService {

    /**
     * 批量向量化（入库侧）：返回顺序与入参严格一一对应。
     *
     * @param texts 待向量化的文本（调用方已保证元素非 null），可为空列表（实现应直接返回空列表，
     *              不发无谓的上游往返）
     * @return 向量列表，条数与 {@code texts} 相等；每个向量的维度由实现保证（见实现类日志）
     * @throws BusinessException 上游不可达 / 超时 / 报错（code = 20100）
     */
    List<float[]> embedDocuments(List<String> texts);

    /**
     * 单条向量化（查询侧）：内部会加上查询专用的任务前缀。
     *
     * @param question 用户问题（调用方已校验非空白，见契约 {@code 40001}）
     * @return 查询向量
     * @throws BusinessException 上游不可达 / 超时 / 报错（code = 20100）
     */
    float[] embedQuery(String question);
}

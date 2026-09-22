package com.nexus.module.ai.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 知识库配置：绑定 {@code nexus.ai.rag.*}（见 nexus-start 的 {@code application.yml}）。
 *
 * <p><b>注册方式与 {@link com.nexus.module.ai.gateway.config.AiProperties} 完全一致</b>：
 * {@code @Component + @ConfigurationProperties} 自注册，靠 {@code NexusApplication} 的
 * {@code scanBasePackages = "com.nexus"} 扫到 —— <b>不需要改 nexus-start</b>（理由见 AiProperties
 * 的类注释：换成 {@code @EnableConfigurationProperties} 就得在下游模块登记，那是反向依赖）。
 *
 * <p>字段与 {@code application.yml} 里的 {@code nexus.ai.rag} 块<b>逐键一致</b>（10 个键 = 10 个字段），
 * 且每个字段的 Java 默认值都与 yml 的取值相同 —— yml 片段缺失时行为不退化
 * （单测里 {@code new RagProperties()} 就能得到可用的配置）。
 *
 * <p>⚠️ <b>两处"改了不生效"的坑，落在配置侧</b>（与 yml 里的注释同源，此处只留指针）：
 * <ol>
 *     <li>改 {@link #chunkSize} / {@link #chunkOverlap} 之后<b>必须重传文档</b> ——
 *         本轮不保存原始文件，无法对已入库的文档重新分块（决策 D8）；
 *     <li>改 {@link #documentPrefix} / {@link #queryPrefix}（D14 的检索侧前缀机制，<b>当前默认置空</b>）
 *         之后<b>必须重灌全部文档</b>
 *         —— 存量向量与新查询向量会不在同一个空间，而且<b>不报错、只是检索质量静默劣化</b>。
 *         两个前缀必须成对同源地改：只改一侧等于把两侧的向量按比例拉偏，同样不报错。
 * </ol>
 *
 * <p>⚠️ <b>命名陷阱（本类只有一个嵌套块的余量，故刻意不设嵌套块）</b>：Spring Boot 把 JavaBean
 * 属性名转成"中划线形式"时会在<b>大写字母前插连字符</b> —— 属性名 {@code documentPrefix} 就绑
 * {@code document-prefix}（本类全部字段都是这个形状，安全）；而 {@code AiProperties} 曾踩过
 * {@code deepSeek → deep-seek} 那次（yml 写的是 {@code deepseek}）<b>静默不绑定</b>。
 * 将来若要加嵌套块，getter 必须写成 {@code getPrefixes()} 这种全小写单词形式。
 *
 * @author nexus
 */
@Component
@ConfigurationProperties(prefix = "nexus.ai.rag")
public class RagProperties {

    /**
     * 分块窗口大小（字符，Java {@code String.length()} 口径）。
     *
     * <p>task.4 3.3 的验收标准点名"分块大小 / 重叠<b>可配置</b>"，故这两个值是配置而不是常量。
     */
    private int chunkSize = 500;

    /**
     * 相邻分块的重叠字符数；步长 = {@link #chunkSize} - 本值。
     *
     * <p>重叠的作用是避免"答案正好被切在块边界上"时两边都检索不到。
     */
    private int chunkOverlap = 50;

    /**
     * 单文档分块数上限，超过即 {@code BusinessException(10204)}。
     *
     * <p><b>它判在向量化之前</b>（决策 D15）：先分块、数一眼、再决定要不要调 embedding ——
     * 10MB 的 TXT ≈ 2 万块 ≈ 上万次 embedding 调用，超限在几秒内失败，
     * 而不是"跑到一半才发现太大"（那时用户看到的形态是一次像上游故障的超时，排查方向是错的）。
     *
     * <p>注意它与上传<b>字节数</b>上限是两回事：那个的唯一真源是
     * {@code spring.servlet.multipart.max-file-size}（10MB），业务侧不重复判。
     */
    private int maxChunksPerDocument = 3000;

    /**
     * 默认检索条数（请求体缺省 / {@code null} 时生效）。
     *
     * <p>topK 的定义是"最相似的 K 条"；相似度阈值是在这 K 条<b>之后</b>再收紧的一道闸
     * （阈值不下推成 SQL 的 {@code WHERE}，那会让 HNSW 索引失效，见设计 §4.6）。
     */
    private int topK = 5;

    /**
     * 请求侧 {@code topK} 的取值上界（越界 → {@code 40001}）。
     *
     * <p>上界防的是"一次检索把整个知识库捞回来"——它同时也是一道上下文预算闸
     * （topK=20 × 500 字 ≈ 12k token，两个上游都远超）。
     */
    private int maxTopK = 20;

    /**
     * 相似度（余弦）阈值：低于它的检索结果一律丢弃；全部低于 → {@code grounded=false} 且<b>不调大模型</b>。
     *
     * <p>⚠️ 初值 {@code 0.5} 是<b>起点不是结论</b>：标定步骤见设计 §3.9（问一个文档里一定有的问题记
     * {@code S_hit}、问一个一定没有的问题记 {@code S_miss}，阈值取两者中点附近），跑完写进 TC-03 备注。
     * 直接抄一个数字，等于把"检索不到就说不知道"这条产品行为的成败交给运气。
     */
    private double scoreThreshold = 0.5;

    /**
     * 问答生成用哪个模型：取值即 {@code ModelType} 枚举名（{@code DEEPSEEK} / {@code OLLAMA}）。
     *
     * <p>默认云端：中文问答质量与速度都更好（约 4s vs CPU 上 30s+）。
     * <b>无 {@code DEEPSEEK_API_KEY} 时选 {@code DEEPSEEK} 会直接 20100</b>（阶段2 既定行为，
     * provider 不发无谓的网络请求）；离线演示把本值改成 {@code OLLAMA} 即可。
     *
     * <p>非法取值会让 {@code ModelType.parse(...)} 在<b>第一次提问时</b>抛 10200
     * （不是启动期 fail-fast：本轮没有"启动时校验一遍配置"的消费者，为一个可配旋钮加一整个
     * 启动期校验器不划算；代价是配置写错要到第一个请求才响，记在明处）。
     */
    private String answerModelType = "DEEPSEEK";

    /**
     * 问题长度上限，<b>按字符计</b>（不是字节），超出 → {@code 40001}。
     *
     * <p>与对话接口的 8KB <b>字节</b>口径不同是<b>有意的</b>（契约 §7.4）：那条防的是"误贴大段文本
     * 撑爆上游上下文"，而问题天然是短的，500 字符 ≈ 500 个汉字，口径简单可读即可。
     */
    private int maxQuestionLength = 500;

    /**
     * 入库侧（document）给 embedding 文本加的前缀（决策 D14 的机制，<b>当前默认关闭</b>）。
     *
     * <p><b>为什么默认是空串</b>（2026-09-22 晚换 embedding 模型时定）：
     * ① <b>机制保留</b> —— 有些模型的模型卡建议检索侧加任务前缀（{@code nomic-embed-text} 的 v1.5
     * 口径就是入库 {@code search_document: }、查询 {@code search_query: }），换回那类模型时把这两个
     * 值填回去即可，不必改代码；
     * ② <b>默认置空</b> —— 当前模型 {@code bge-m3} <b>不需要</b>任务前缀（设计 §0.3），留着等于给一个
     * 不期望它们的模型硬塞英文任务前缀，属"不报错、只是检索变差"的静默劣化。
     * ⚠️ 置空的理由是"<b>当前模型不需要</b>"，<b>不是"这个机制没用"</b> —— 不要顺手把机制删掉。
     *
     * <p>⚠️ 改它必须重灌数据（见类注释第 2 条）；留空字符串表示<b>关闭前缀</b>
     * —— TC-03 的 A/B 用例正是靠"填上 / 清空这两个值 → 重灌 → 比检索质量"来给结论的。
     */
    private String documentPrefix = "";

    /** 查询侧（query）的前缀。与 {@link #documentPrefix} <b>必须成对同源</b>，理由见类注释。 */
    private String queryPrefix = "";

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public int getMaxChunksPerDocument() {
        return maxChunksPerDocument;
    }

    public void setMaxChunksPerDocument(int maxChunksPerDocument) {
        this.maxChunksPerDocument = maxChunksPerDocument;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getMaxTopK() {
        return maxTopK;
    }

    public void setMaxTopK(int maxTopK) {
        this.maxTopK = maxTopK;
    }

    public double getScoreThreshold() {
        return scoreThreshold;
    }

    public void setScoreThreshold(double scoreThreshold) {
        this.scoreThreshold = scoreThreshold;
    }

    public String getAnswerModelType() {
        return answerModelType;
    }

    public void setAnswerModelType(String answerModelType) {
        this.answerModelType = answerModelType;
    }

    public int getMaxQuestionLength() {
        return maxQuestionLength;
    }

    public void setMaxQuestionLength(int maxQuestionLength) {
        this.maxQuestionLength = maxQuestionLength;
    }

    public String getDocumentPrefix() {
        return documentPrefix;
    }

    public void setDocumentPrefix(String documentPrefix) {
        this.documentPrefix = documentPrefix;
    }

    public String getQueryPrefix() {
        return queryPrefix;
    }

    public void setQueryPrefix(String queryPrefix) {
        this.queryPrefix = queryPrefix;
    }
}

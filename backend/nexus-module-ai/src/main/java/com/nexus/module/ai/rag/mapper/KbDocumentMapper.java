package com.nexus.module.ai.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexus.module.ai.rag.entity.KbDocument;

/**
 * 知识库文档 Mapper（{@code t_kb_document}）。
 *
 * <p>被 {@code NexusApplication} 的 {@code @MapperScan("com.nexus.**.mapper")} 扫描后由 MyBatis
 * 生成 JDK 动态代理实现，无需（也没有）实现类 —— 与 {@code UserMapper} 同一形态。
 *
 * <h2>为什么本接口<b>一行自定义 SQL 都没有</b>（与 {@link KbChunkMapper} 的分工）</h2>
 * 文档链路的三件事（列表 / 按 id 查 / 删除）都是<b>单表、无条件或主键条件</b>的通用操作：
 * 通用 CRUD 生成出来的 SQL 恰好就是要写的那一条，而租户条件由拦截器补 —— 没有一处需要表达力。
 * 分块链路的检索是另一回事（{@code ORDER BY embedding <=> ?} 与 {@code CAST(... AS vector)}），
 * 只能用自定义语句表达。这个分界不是风格偏好：<b>凡是通用 CRUD 能忠实表达的就不要手写</b>
 * （手写多一份需要维护、也躲开了 MP 的方言/字段策略），凡是通用 CRUD 表达不了的就必须手写。
 *
 * <h2>三条方法各自依赖的既有机制（都不需要在此声明）</h2>
 * <ul>
 *     <li>{@code selectList} → {@code SELECT ... FROM t_kb_document WHERE tenant_id = ?}
 *         （拦截器注入，契约 §7.2 "另一个租户看不见" 的落地处）；</li>
 *     <li>{@code selectById} / {@code deleteById} → 主键条件 + 同一注入；</li>
 *     <li>{@code insert} → 语句里<b>没有</b> {@code tenant_id} 列，由拦截器补列与值
 *         （形态与理由见 {@link com.nexus.module.ai.rag.entity.KbDocument} 的类注释）。</li>
 * </ul>
 * ⚠️ 跨租户删除的表现是 {@code deleteById} 返回 <b>0 行</b>，不是异常 —— 那是正确行为
 * （"别的租户的文档"与"不存在"刻意同形，见契约 §7.3 第 1 条）。
 *
 * @author nexus
 */
public interface KbDocumentMapper extends BaseMapper<KbDocument> {
}

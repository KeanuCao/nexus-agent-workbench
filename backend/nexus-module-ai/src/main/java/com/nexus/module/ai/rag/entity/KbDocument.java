package com.nexus.module.ai.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 知识库文档表实体（{@code t_kb_document}）。
 *
 * <p>字段与 {@code db-patch/202609221000_初始化知识库表.sql} 一一对应；列名转驼峰由
 * {@code mybatis-plus.configuration.map-underscore-to-camel-case: true} 完成，
 * 因此除主键外无需逐个标注 {@code @TableField}（同 {@code User} 的取舍）。
 *
 * <p><b>主键必须显式 {@code IdType.AUTO}</b>：表用的是 {@code BIGSERIAL}（数据库自增），
 * 而 MyBatis-Plus 默认策略是<b>雪花算法</b>在应用侧生成 ID —— 两者冲突时表现为插入的 ID
 * 与序列完全不搭。自增表一律配 AUTO（同 {@code User.userId}）。
 * AUTO 的另一个收益是<b>插入后主键会被回填到本实体</b>：上传响应里的
 * {@code documentId} 就取自 {@link #getDocumentId()}，不需要额外查一次库。
 *
 * <h2>{@link #tenantId} 的生长方式（决策 D11 的落地处，值得逐字读）</h2>
 * <ul>
 *     <li><b>查 / 删</b>：本类字段不参与拼条件 —— {@code TenantLineHandler} 会把
 *         {@code tenant_id = <当前租户>} 注入最终 SQL（改写的对象是 SQL，不是实体）；</li>
 *     <li><b>插</b>：业务代码<b>不设</b>本字段（保持 {@code null}）。MP 的字段策略默认
 *         {@code NOT_NULL}，为 {@code null} 的字段<b>不会出现在 INSERT 语句里</b>；
 *         随后租户拦截器给语句补上 {@code tenant_id} 列与当前租户的值。
 *         于是"写入行的 tenant_id 正确"是<b>结构上</b>成立的（拦不到就 fail-closed 抛异常，
 *         见 {@code TenantLineHandlerImpl}），而不是靠每处业务代码记得赋值。
 *         <b>已实测</b>（离线探针：真实 MP 装配 + 真实 {@code TenantLineHandlerImpl} + H2）：
 *         改写后的语句是 {@code INSERT INTO t_kb_document (file_name, …, tenant_id) VALUES (?, …, 1)}，
 *         落库行的 {@code tenant_id} 为当前租户，且实体里的 {@code tenantId} 始终是 {@code null}
 *         （回填的只有自增主键 {@code documentId}）。</li>
 * </ul>
 * ⇒ 全链路<b>看不到</b>一行手写的 {@code tenant_id}，这正是设计 §4.8 纪律 4 要的形态。
 *
 * <h2>时间字段为什么在应用侧赋值，而不是交给 DDL 的 {@code DEFAULT now()}</h2>
 * 交给数据库的代价是：插入之后实体里 {@code createdAt} 仍是 {@code null}，而契约
 * （{@code docs/api/README.md} §7.5）把 {@code createdAt} 列为<b>必填</b> —— 那就必须再
 * {@code SELECT} 一次把值读回来。多一次查询换一个"更权威的时钟"，在本项目里不划算
 * （演示环境单库、无跨库对时需求）。DDL 的 {@code DEFAULT now()} 保留：它是补丁 / 手工 SQL
 * 插入时的安全网，不是本类的依赖。
 *
 * @author nexus
 */
@TableName("t_kb_document")
public class KbDocument {

    /** 主键，BIGSERIAL 自增（插入后由 MP 回填，见类注释）。 */
    @TableId(value = "document_id", type = IdType.AUTO)
    private Long documentId;

    /** 租户隔离列：不手写、不赋值，由租户拦截器注入（见类注释）。 */
    private Long tenantId;

    /** 原始文件名，仅回显与引用来源标注用（本轮不落盘，决策 D8）。 */
    private String fileName;

    /** 文件类型 {@code TXT} / {@code PDF}（大写，由解析器按扩展名归一化并交叉校验）。 */
    private String fileType;

    /** 上传字节数。 */
    private Long fileSize;

    /** 解析出的正文字符数 —— 排查解析质量的第一眼数据。 */
    private Integer charCount;

    /** 入库分块数 —— 同时也是"单文档 3000 块上限"这条规则的实际取值。 */
    private Integer chunkCount;

    /** 上传者 {@code user_id}，取自 {@code TenantContext}（不是请求体 —— 那是客户端可伪造的输入）。 */
    private Long createdBy;

    /** 入库时间（应用侧赋值，见类注释）。 */
    private OffsetDateTime createdAt;

    /** 更新时间。本轮没有更新路径（改分块参数要重传文档），插入时与 {@link #createdAt} 取同一时刻。 */
    private OffsetDateTime updatedAt;

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Integer getCharCount() {
        return charCount;
    }

    public void setCharCount(Integer charCount) {
        this.charCount = charCount;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * 只输出定位用的元数据，<b>不含正文</b>（本实体没有正文字段，故无需像 {@code User} 那样
     * 特意屏蔽某个字段；写上这条注释是为了让"为什么这里可以放心 toString"留在代码里）。
     *
     * @return 简要描述
     */
    @Override
    public String toString() {
        return "KbDocument{documentId=" + documentId + ", tenantId=" + tenantId
                + ", fileName='" + fileName + "', fileType='" + fileType + "', fileSize=" + fileSize
                + ", charCount=" + charCount + ", chunkCount=" + chunkCount + '}';
    }
}

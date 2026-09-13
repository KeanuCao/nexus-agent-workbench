package com.nexus.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 租户表实体（{@code t_tenant}）。
 *
 * <p>字段与 db-patch/202609131000_初始化多租户基础表.sql 一一对应。
 *
 * <p><b>注意 {@code t_tenant} 没有 {@code tenant_id} 列</b>：它的主键列名虽然叫 {@code tenant_id}，
 * 但那正是"租户自己的 ID"。因此本表在 {@code TenantLineHandlerImpl} 里被列为
 * <b>结构性豁免表</b> —— 给它注入 {@code tenant_id = ?} 会变成"用自己筛自己"。
 *
 * <p>主键同样是 {@code BIGSERIAL}，故 {@code IdType.AUTO}（理由见 {@link User} 的类注释）。
 *
 * @author nexus
 */
@TableName("t_tenant")
public class Tenant {

    /** {@code status} 取值：启用。 */
    public static final int STATUS_ENABLED = 1;

    /** {@code status} 取值：停用。 */
    public static final int STATUS_DISABLED = 0;

    /** 主键，BIGSERIAL 自增。 */
    @TableId(value = "tenant_id", type = IdType.AUTO)
    private Long tenantId;

    /** 人类可读租户标识（{@code default} / {@code demo}），全局唯一。 */
    private String tenantCode;

    /** 租户名称（前端展示）。 */
    private String tenantName;

    /** 1=启用 0=停用（登录时校验）。 */
    private Integer status;

    /** 创建时间（审计字段）。 */
    private OffsetDateTime createdAt;

    /** 更新时间（审计字段）。 */
    private OffsetDateTime updatedAt;

    /**
     * 租户是否处于启用状态。
     *
     * @return {@code true} 表示启用
     */
    public boolean enabled() {
        return status != null && status == STATUS_ENABLED;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getTenantCode() {
        return tenantCode;
    }

    public void setTenantCode(String tenantCode) {
        this.tenantCode = tenantCode;
    }

    public String getTenantName() {
        return tenantName;
    }

    public void setTenantName(String tenantName) {
        this.tenantName = tenantName;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
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

    @Override
    public String toString() {
        return "Tenant{tenantId=" + tenantId + ", tenantCode='" + tenantCode
                + "', tenantName='" + tenantName + "', status=" + status + '}';
    }
}

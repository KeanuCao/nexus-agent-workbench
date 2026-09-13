package com.nexus.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 用户表实体（{@code t_user}）。
 *
 * <p>字段与 db-patch/202609131010_初始化用户表.sql 一一对应；
 * 列名转驼峰由 {@code mybatis-plus.configuration.map-underscore-to-camel-case: true} 完成，
 * 因此除主键外无需逐个标注 {@code @TableField}。
 *
 * <p><b>主键必须显式 {@code IdType.AUTO}</b>：表用的是 {@code BIGSERIAL}（数据库自增），
 * 而 MyBatis-Plus 默认策略是<b>雪花算法</b>在应用侧生成 ID —— 两者冲突时表现为
 * 插入的 ID 与序列完全不搭（且 BIGSERIAL 的自增值被白白浪费）。自增表一律配 AUTO。
 *
 * <p>本类只做数据载体，不放业务规则；"账号是否可用"这类判断由 {@link #enabled()} 表达意图、
 * 由 Service 决定如何处置（失败话术与错误码属于业务语义）。
 *
 * @author nexus
 */
@TableName("t_user")
public class User {

    /** {@code status} 取值：启用。 */
    public static final int STATUS_ENABLED = 1;

    /** {@code status} 取值：停用。 */
    public static final int STATUS_DISABLED = 0;

    /** 主键，BIGSERIAL 自增。 */
    @TableId(value = "user_id", type = IdType.AUTO)
    private Long userId;

    /** 租户 ID —— 多租户隔离字段，由 TenantLineHandler 自动注入查询条件。 */
    private Long tenantId;

    /** 登录名，<b>全局唯一</b>（登录的唯一检索键，见设计决策 D2）。 */
    private String username;

    /** BCrypt 哈希，明文一律不入库。 */
    private String password;

    /** 展示名。 */
    private String nickname;

    /** 1=启用 0=停用。 */
    private Integer status;

    /** 创建时间（审计字段）。 */
    private OffsetDateTime createdAt;

    /** 更新时间（审计字段）。 */
    private OffsetDateTime updatedAt;

    /**
     * 账号是否处于启用状态。
     *
     * <p>方法名刻意不用 {@code isEnabled()}：{@code isXxx()} 会被 Bean 序列化当成属性暴露
     * （与 {@code Result} 类注释里记录的同一条经验），而异名方法不会 ——
     * 实体迟早会出现在某个响应里，先把口子堵上。
     *
     * @return {@code true} 表示启用
     */
    public boolean enabled() {
        return status != null && status == STATUS_ENABLED;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
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
        // 刻意不输出 password：日志/异常里一旦带上哈希，就是一次凭据泄露
        return "User{userId=" + userId + ", tenantId=" + tenantId + ", username='" + username
                + "', nickname='" + nickname + "', status=" + status + '}';
    }
}

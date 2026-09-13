package com.nexus.module.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexus.infrastructure.tenant.IgnoreTenant;
import com.nexus.module.system.entity.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户 Mapper（{@code t_user}）。
 *
 * <p>被 {@code @MapperScan("com.nexus.**.mapper")} 扫描后由 MyBatis 生成 JDK 动态代理实现，
 * 无需（也没有）实现类。
 *
 * @author nexus
 */
public interface UserMapper extends BaseMapper<User> {

    /**
     * 按登录名查用户（登录流程的第一步）。
     *
     * <p><b>必须标 {@link IgnoreTenant}</b>：登录发生在<b>还没有租户上下文</b>的时候 ——
     * 用户连自己属于哪个租户都还没告诉服务端（登录只收 username + password）。
     * 少了这个豁免，多租户拦截器会 fail-closed 直接抛异常，登录不可用
     * （这是设计上刻意的"暴露得早"：宁可登录 500，也不要一条静默漏租户的查询）。
     *
     * <p>SQL 手写（而非用 Wrapper 拼）的两个理由：
     * ① 显式列出列名，不依赖 {@code SELECT *} 的隐式契约；
     * ② 使 {@code logging.level.com.nexus.module.system.mapper: debug} 打出的 SQL
     * <b>肉眼可见地不含 {@code tenant_id} 条件</b> —— 这正是验收项 1.3-3 的判据。
     *
     * <p>注意 {@code username} 是<b>全局唯一</b>（不是 (tenant_id, username) 联合唯一）：
     * 检索键必须全局唯一，否则同一次查询可能命中多个租户的同名用户。
     * 取舍见设计决策 D2。
     *
     * @param username 登录名
     * @return 用户；不存在时返回 {@code null}
     */
    @IgnoreTenant("登录时尚无租户上下文，必须按全局唯一的 username 先把用户查出来，才知道它属于哪个租户")
    @Select("SELECT user_id, tenant_id, username, password, nickname, status, created_at, updated_at"
            + " FROM t_user WHERE username = #{username}")
    User selectByUsername(@Param("username") String username);
}

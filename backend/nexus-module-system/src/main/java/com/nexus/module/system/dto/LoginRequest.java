package com.nexus.module.system.dto;

/**
 * 登录请求体（契约见 docs/api/README.md §5.1）。
 *
 * <p>用可变 POJO 而非 record：<b>入参</b>是 Jackson 反序列化的目标，
 * setter 注入是各类序列化配置下最不需要额外约定的写法（record 依赖
 * {@code -parameters} 编译参数与 ParameterNamesModule 的配合）。
 * 出参（{@code LoginResponse} / {@code UserInfoVO}）反过来用 record —— 由本项目自己构造，
 * 不可变更安全，也没有反序列化的顾虑。
 *
 * <p>本类不做字段级校验注解：用户名/密码的合法性判断（是否为空）放在 Service 里，
 * 与"用户不存在""密码错误"共用同一个错误码 —— 校验注解会提前抛 400，
 * 破坏"登录失败一律 200 + 10100"的契约。
 *
 * @author nexus
 */
public class LoginRequest {

    /** 登录名（全局唯一，见设计决策 D2）。 */
    private String username;

    /** 明文口令（仅在请求体中出现，服务端只做 BCrypt 比对，绝不落库、绝不打日志）。 */
    private String password;

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

    @Override
    public String toString() {
        // 不输出 password：入参一旦被打进日志（如参数绑定失败的调试日志），就是明文口令泄露
        return "LoginRequest{username='" + username + "'}";
    }
}

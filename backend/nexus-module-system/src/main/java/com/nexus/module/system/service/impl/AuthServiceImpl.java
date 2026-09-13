package com.nexus.module.system.service.impl;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.SystemException;
import com.nexus.common.exception.UnauthorizedException;
import com.nexus.common.result.ResultCode;
import com.nexus.infrastructure.security.JwtUtil;
import com.nexus.infrastructure.security.TokenStore;
import com.nexus.infrastructure.tenant.TenantContext;
import com.nexus.module.system.dto.LoginRequest;
import com.nexus.module.system.dto.LoginResponse;
import com.nexus.module.system.dto.UserInfoVO;
import com.nexus.module.system.entity.Tenant;
import com.nexus.module.system.entity.User;
import com.nexus.module.system.mapper.TenantMapper;
import com.nexus.module.system.mapper.UserMapper;
import com.nexus.module.system.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 认证服务实现。
 *
 * <p>登录的完整链路（顺序即语义，改动前先想清楚为什么是这个顺序）：
 * <ol>
 *     <li>空值校验 —— 与"密码错"同码，不额外泄露信息；</li>
 *     <li>按全局唯一 username 查用户（{@code @IgnoreTenant}，此刻还没有租户上下文）；</li>
 *     <li><b>先</b>验口令，<b>后</b>看账号状态 —— 顺序反过来会泄露"这个账号存在"，
 *         给攻击者一个账号枚举的探测口；</li>
 *     <li>校验租户状态（租户停用则整个租户都登不进）；</li>
 *     <li>签发 token（带 jti）+ 登记 Redis 白名单（TTL = token 有效期）。</li>
 * </ol>
 *
 * <p>为什么读当前用户/登出都从 {@link TenantContext} 取，而不是让 Controller 传参：
 * 登录态是<b>请求级</b>的事实，由过滤器一次解析、全链路共享；
 * 若改成逐层传参，等于把"谁在调用"这件基础设施的职责泄漏进业务签名。
 *
 * @author nexus
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    /** 契约固定值：{@code data.tokenType}。 */
    private static final String TOKEN_TYPE_BEARER = "Bearer";

    private final UserMapper userMapper;

    private final TenantMapper tenantMapper;

    private final PasswordEncoder passwordEncoder;

    private final JwtUtil jwtUtil;

    private final TokenStore tokenStore;

    public AuthServiceImpl(UserMapper userMapper,
                           TenantMapper tenantMapper,
                           PasswordEncoder passwordEncoder,
                           JwtUtil jwtUtil,
                           TokenStore tokenStore) {
        this.userMapper = userMapper;
        this.tenantMapper = tenantMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.tokenStore = tokenStore;
    }

    /**
     * 登录（契约见 docs/api/README.md §5.1）。
     *
     * @param request 登录请求
     * @return token 与当前用户信息
     */
    @Override
    public LoginResponse login(LoginRequest request) {
        String username = trimToNull(request.getUsername());
        String rawPassword = request.getPassword();
        if (username == null || rawPassword == null || rawPassword.isEmpty()) {
            // 与"用户名或密码错误"同码同文案：连"你没填"都不区分，就没有可被利用的差异
            log.warn("登录失败：用户名或密码为空");
            throw new BusinessException(ResultCode.LOGIN_FAILED);
        }

        User user = userMapper.selectByUsername(username);
        if (user == null) {
            log.warn("登录失败：用户不存在，username={}", username);
            throw new BusinessException(ResultCode.LOGIN_FAILED);
        }

        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            log.warn("登录失败：密码错误，userId={} username={}", user.getUserId(), username);
            throw new BusinessException(ResultCode.LOGIN_FAILED);
        }

        if (!user.enabled()) {
            log.warn("登录失败：账号已停用，userId={} username={}", user.getUserId(), username);
            throw new BusinessException(ResultCode.ACCOUNT_DISABLED);
        }

        Tenant tenant = tenantMapper.selectById(user.getTenantId());
        if (tenant == null || !tenant.enabled()) {
            log.warn("登录失败：租户不存在或已停用，userId={} tenantId={}", user.getUserId(), user.getTenantId());
            throw new BusinessException(ResultCode.TENANT_DISABLED);
        }

        String tokenId = UUID.randomUUID().toString();
        String token = jwtUtil.issue(user.getUserId(), tenant.getTenantId(), user.getUsername(), tokenId);
        long expiresIn = jwtUtil.getExpireSeconds();
        tokenStore.save(tokenId, buildTokenValue(user), expiresIn);

        log.info("登录成功：userId={} username={} tenantId={} tenantCode={} jti={} expiresIn={}s",
                user.getUserId(), user.getUsername(), tenant.getTenantId(), tenant.getTenantCode(),
                tokenId, expiresIn);

        return new LoginResponse(token, TOKEN_TYPE_BEARER, expiresIn, toUserInfo(user, tenant));
    }

    /**
     * 登出：删掉 Redis 白名单里的 {@code jti}（同一个 token 立刻失效）。
     */
    @Override
    public void logout() {
        String tokenId = TenantContext.getTokenId();
        if (tokenId == null) {
            // 正常流程不可达：能进到这里说明过滤器已判定登录态有效，必然写过上下文。
            // 真发生了就是过滤器/上下文被绕过，按未登录处理，不静默放过
            throw new UnauthorizedException(ResultCode.UNAUTHENTICATED);
        }
        tokenStore.remove(tokenId);
        log.info("登出成功：userId={} jti={}", TenantContext.getUserId(), tokenId);
    }

    /**
     * 取当前用户（{@code GET /api/auth/me}）。
     */
    @Override
    public UserInfoVO getCurrentUser() {
        Long userId = TenantContext.getUserId();
        Long tenantId = TenantContext.getTenantId();
        if (userId == null || tenantId == null) {
            throw new UnauthorizedException(ResultCode.UNAUTHENTICATED);
        }

        // 这里顺带是一次隔离性的自检：查询会自动带上 tenant_id = 当前租户，
        // 若 token 里的租户与用户实际所属租户不一致，查不到 → 401（而不是"查到别人的数据"）
        User user = userMapper.selectById(userId);
        if (user == null) {
            log.warn("取当前用户失败：token 有效但用户不存在或不属于该租户，userId={} tenantId={}", userId, tenantId);
            throw new UnauthorizedException(ResultCode.TOKEN_INVALID);
        }

        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            // 有外键约束兜着，正常不该发生 —— 属数据完整性问题，按系统异常记日志
            throw new SystemException("租户不存在：tenantId=" + tenantId + "（用户 " + userId + " 引用了不存在的租户）");
        }

        return toUserInfo(user, tenant);
    }

    /**
     * 组装出参 VO。
     *
     * @param user   用户实体
     * @param tenant 租户实体
     * @return 用户信息 VO
     */
    private static UserInfoVO toUserInfo(User user, Tenant tenant) {
        return new UserInfoVO(
                user.getUserId(),
                user.getUsername(),
                user.getNickname(),
                tenant.getTenantId(),
                tenant.getTenantCode(),
                tenant.getTenantName());
    }

    /**
     * Redis 白名单里存的 value —— {@code redis-cli get} 时能一眼认出是谁，仅此而已。
     *
     * <p>鉴权判据是"键是否存在"，不解析 value（见 {@link TokenStore} 类注释），
     * 因此这里<b>不含任何凭据</b>，即使 Redis 被 dump 也不会多泄露东西。
     *
     * @param user 用户实体
     * @return 形如 {@code userId=1,tenantId=1,username=admin} 的描述串
     */
    private static String buildTokenValue(User user) {
        return "userId=" + user.getUserId()
                + ",tenantId=" + user.getTenantId()
                + ",username=" + user.getUsername();
    }

    /**
     * 去空白并归一化空串。
     *
     * @param value 原值
     * @return 去空白后的值；原值为 {@code null} 或全空白时返回 {@code null}
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

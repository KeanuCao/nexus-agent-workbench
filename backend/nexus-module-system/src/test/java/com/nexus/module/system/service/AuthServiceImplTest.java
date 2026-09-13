package com.nexus.module.system.service;

import com.nexus.common.exception.BusinessException;
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
import com.nexus.module.system.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AuthServiceImpl} 单元测试（Mockito）。
 *
 * <p>覆盖设计文档 §8 点名的五条：用户不存在 / 密码错 / 账号停用 / 租户停用 / 正常登录，
 * 外加空入参、取当前用户与登出三组边界。
 *
 * <p><b>口令编码器用真实实现</b>（{@link BCryptPasswordEncoder}）而非 mock：
 * "登录能过"这件事的价值有一大半在于"BCrypt 真的在比对" ——
 * 若把 {@code matches} 打桩成恒 true，测试全绿也证明不了什么（这与风险清单里
 * "种子密码哈希与口令不匹配 → 登录必失败"是同一件事的两面）。
 *
 * <p>Mock 掉的是外部边界：两个 Mapper（数据库）、{@link JwtUtil}（时间与签名）、
 * {@link TokenStore}（Redis）。这样本用例不依赖任何外部服务，可随时重跑。
 *
 * @author nexus
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final String USERNAME = "admin";

    private static final String RAW_PASSWORD = "admin123";

    private static final long USER_ID = 1L;

    private static final long TENANT_ID = 10L;

    private static final long EXPIRE_SECONDS = 7200L;

    /** 真实 BCrypt 实例：与生产同一算法、同一强度（10 轮、随机盐）。 */
    private static final PasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @Mock
    private UserMapper userMapper;

    @Mock
    private TenantMapper tenantMapper;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private TokenStore tokenStore;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(userMapper, tenantMapper, PASSWORD_ENCODER, jwtUtil, tokenStore);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("正常登录：签发 token、把同一 jti 写进 Redis 白名单、返回用户与租户信息")
    void shouldLoginSuccessfully() {
        when(userMapper.selectByUsername(USERNAME)).thenReturn(enabledUser());
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(enabledTenant());
        when(jwtUtil.issue(eq(USER_ID), eq(TENANT_ID), eq(USERNAME), anyString())).thenReturn("token-value");
        when(jwtUtil.getExpireSeconds()).thenReturn(EXPIRE_SECONDS);

        LoginResponse response = authService.login(loginRequest(USERNAME, RAW_PASSWORD));

        assertEquals("token-value", response.token());
        assertEquals("Bearer", response.tokenType());
        assertEquals(EXPIRE_SECONDS, response.expiresIn());
        assertEquals(USER_ID, response.user().userId());
        assertEquals(USERNAME, response.user().username());
        assertEquals(TENANT_ID, response.user().tenantId());
        assertEquals("default", response.user().tenantCode());
        assertEquals("默认租户", response.user().tenantName());

        // 三个捕获：签发的 jti、登记的 jti、登记的 TTL
        ArgumentCaptor<String> issuedJti = ArgumentCaptor.forClass(String.class);
        verify(jwtUtil).issue(eq(USER_ID), eq(TENANT_ID), eq(USERNAME), issuedJti.capture());

        ArgumentCaptor<String> savedJti = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> savedTtl = ArgumentCaptor.forClass(Long.class);
        verify(tokenStore).save(savedJti.capture(), anyString(), savedTtl.capture());

        assertDoesNotThrow(() -> UUID.fromString(issuedJti.getValue()), "jti 应为 UUID");
        assertEquals(issuedJti.getValue(), savedJti.getValue(),
                "签发的 jti 与登记进 Redis 的 jti 必须一致，否则登出永远删不掉自己的登录态");
        assertEquals(EXPIRE_SECONDS, savedTtl.getValue(), "白名单 TTL 必须与 token 有效期一致");
    }

    @Test
    @DisplayName("用户不存在：10100，且不签发 token、不写 Redis")
    void shouldFailWhenUserNotFound() {
        when(userMapper.selectByUsername(USERNAME)).thenReturn(null);

        BusinessException ex = assertThrowsBusinessException(loginRequest(USERNAME, RAW_PASSWORD));

        assertEquals(ResultCode.LOGIN_FAILED.getCode(), ex.getCode());
        verifyNoInteractions(jwtUtil, tokenStore);
    }

    @Test
    @DisplayName("密码错误：10100（与「用户不存在」同码，防账号枚举）")
    void shouldFailWhenPasswordWrong() {
        when(userMapper.selectByUsername(USERNAME)).thenReturn(enabledUser());

        BusinessException ex = assertThrowsBusinessException(loginRequest(USERNAME, "wrong-password"));

        assertEquals(ResultCode.LOGIN_FAILED.getCode(), ex.getCode());
        verifyNoInteractions(jwtUtil, tokenStore);
    }

    @Test
    @DisplayName("账号停用：密码正确才判定，返回 10101")
    void shouldFailWhenAccountDisabled() {
        User disabled = enabledUser();
        disabled.setStatus(User.STATUS_DISABLED);
        when(userMapper.selectByUsername(USERNAME)).thenReturn(disabled);

        BusinessException ex = assertThrowsBusinessException(loginRequest(USERNAME, RAW_PASSWORD));

        assertEquals(ResultCode.ACCOUNT_DISABLED.getCode(), ex.getCode());
        verifyNoInteractions(jwtUtil, tokenStore);
    }

    @Test
    @DisplayName("租户停用：账号正常但所属租户不可用，返回 10102")
    void shouldFailWhenTenantDisabled() {
        Tenant disabled = enabledTenant();
        disabled.setStatus(Tenant.STATUS_DISABLED);
        when(userMapper.selectByUsername(USERNAME)).thenReturn(enabledUser());
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(disabled);

        BusinessException ex = assertThrowsBusinessException(loginRequest(USERNAME, RAW_PASSWORD));

        assertEquals(ResultCode.TENANT_DISABLED.getCode(), ex.getCode());
        verifyNoInteractions(jwtUtil, tokenStore);
    }

    @Test
    @DisplayName("租户查不到（数据不一致）：同样返回 10102，不放过登录")
    void shouldFailWhenTenantMissing() {
        when(userMapper.selectByUsername(USERNAME)).thenReturn(enabledUser());
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(null);

        BusinessException ex = assertThrowsBusinessException(loginRequest(USERNAME, RAW_PASSWORD));

        assertEquals(ResultCode.TENANT_DISABLED.getCode(), ex.getCode());
        verifyNoInteractions(jwtUtil, tokenStore);
    }

    @Test
    @DisplayName("用户名为空/全空白：10100，且不查库")
    void shouldFailWhenUsernameBlank() {
        BusinessException ex = assertThrowsBusinessException(loginRequest("   ", RAW_PASSWORD));

        assertEquals(ResultCode.LOGIN_FAILED.getCode(), ex.getCode());
        verifyNoInteractions(userMapper, jwtUtil, tokenStore);
    }

    @Test
    @DisplayName("取当前用户：无租户上下文 → 40100")
    void shouldRejectCurrentUserWithoutContext() {
        UnauthorizedException ex = assertThrows(UnauthorizedException.class, () -> authService.getCurrentUser());

        assertEquals(ResultCode.UNAUTHENTICATED.getCode(), ex.getCode());
        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("取当前用户：正常返回（用户与租户均来自库）")
    void shouldReturnCurrentUser() {
        TenantContext.set(USER_ID, TENANT_ID, "jti-1");
        when(userMapper.selectById(USER_ID)).thenReturn(enabledUser());
        when(tenantMapper.selectById(TENANT_ID)).thenReturn(enabledTenant());

        UserInfoVO vo = authService.getCurrentUser();

        assertEquals(USER_ID, vo.userId());
        assertEquals(USERNAME, vo.username());
        assertEquals(TENANT_ID, vo.tenantId());
        assertEquals("default", vo.tenantCode());
    }

    @Test
    @DisplayName("取当前用户：token 有效但用户查不到（越权/已删除）→ 40101 登录态无效")
    void shouldRejectCurrentUserWhenUserMissing() {
        TenantContext.set(USER_ID, TENANT_ID, "jti-1");
        when(userMapper.selectById(USER_ID)).thenReturn(null);

        UnauthorizedException ex = assertThrows(UnauthorizedException.class, () -> authService.getCurrentUser());

        assertEquals(ResultCode.TOKEN_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("登出：按上下文里的 jti 删除 Redis 白名单")
    void shouldLogoutByJti() {
        TenantContext.set(USER_ID, TENANT_ID, "jti-1");

        authService.logout();

        verify(tokenStore).remove("jti-1");
        assertNotNull(TenantContext.getTokenId(), "登出本身不负责清上下文（那是过滤器的 finally 职责）");
    }

    @Test
    @DisplayName("登出：无登录态 → 40100（防御性分支，正常流程不可达）")
    void shouldRejectLogoutWithoutContext() {
        UnauthorizedException ex = assertThrows(UnauthorizedException.class, () -> authService.logout());

        assertEquals(ResultCode.UNAUTHENTICATED.getCode(), ex.getCode());
        verifyNoInteractions(tokenStore);
    }

    /**
     * 断言登录抛出业务异常并返回它（供进一步核对 code）。
     *
     * @param request 登录请求
     * @return 抛出的业务异常
     */
    private BusinessException assertThrowsBusinessException(LoginRequest request) {
        return assertThrows(BusinessException.class, () -> authService.login(request));
    }

    /**
     * 构造登录请求。
     *
     * @param username 用户名
     * @param password 明文口令
     * @return 登录请求
     */
    private static LoginRequest loginRequest(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    /**
     * 构造启用状态的用户（口令为 {@code admin123} 的真实 BCrypt 哈希）。
     *
     * @return 用户实体
     */
    private static User enabledUser() {
        User user = new User();
        user.setUserId(USER_ID);
        user.setTenantId(TENANT_ID);
        user.setUsername(USERNAME);
        user.setPassword(PASSWORD_ENCODER.encode(RAW_PASSWORD));
        user.setNickname("默认租户管理员");
        user.setStatus(User.STATUS_ENABLED);
        return user;
    }

    /**
     * 构造启用状态的租户。
     *
     * @return 租户实体
     */
    private static Tenant enabledTenant() {
        Tenant tenant = new Tenant();
        tenant.setTenantId(TENANT_ID);
        tenant.setTenantCode("default");
        tenant.setTenantName("默认租户");
        tenant.setStatus(Tenant.STATUS_ENABLED);
        return tenant;
    }
}

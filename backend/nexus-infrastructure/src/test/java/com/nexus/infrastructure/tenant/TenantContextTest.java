package com.nexus.infrastructure.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TenantContext} 单元测试 —— 重点不是"能存能取"，而是<b>不泄漏</b>。
 *
 * <p>为什么把"配平"当主要测试对象：本类是 ThreadLocal 持有者，一旦泄漏，
 * 表现是"上一个请求的租户串到下一个请求"，且<b>不报错</b>（设计文档把这条列为多租户最隐蔽的事故形态）。
 * 因此这里逐条覆盖：嵌套压弹、异常路径、未压即弹、clear 连带清豁免深度。
 *
 * <p>测试与被测类同线程执行（JUnit 默认同线程），ThreadLocal 状态会在方法间残留 ——
 * 故 {@code @AfterEach} 必须清理，否则用例互相污染，失败现象会随执行顺序变化。
 *
 * @author nexus
 */
class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("set 之后能原样读回 userId / tenantId / tokenId")
    void shouldReadBackWhatWasSet() {
        TenantContext.set(1L, 10L, "jti-1");

        assertEquals(1L, TenantContext.getUserId());
        assertEquals(10L, TenantContext.getTenantId());
        assertEquals("jti-1", TenantContext.getTokenId());
        assertTrue(TenantContext.hasContext());
    }

    @Test
    @DisplayName("未 set 时全部为 null，hasContext 为 false")
    void shouldReturnNullWhenNotSet() {
        assertNull(TenantContext.getUserId());
        assertNull(TenantContext.getTenantId());
        assertNull(TenantContext.getTokenId());
        assertFalse(TenantContext.hasContext());
        assertFalse(TenantContext.isIgnored());
    }

    @Test
    @DisplayName("clear 之后登录态与豁免深度一并归零")
    void shouldClearBothSnapshotAndIgnoreDepth() {
        TenantContext.set(1L, 10L, "jti-1");
        TenantContext.pushIgnore();

        TenantContext.clear();

        assertFalse(TenantContext.hasContext());
        assertNull(TenantContext.getTenantId());
        assertFalse(TenantContext.isIgnored());
    }

    @Test
    @DisplayName("豁免嵌套压弹：内层弹栈后外层仍处于豁免中")
    void shouldKeepOuterIgnoreWhenInnerPopped() {
        TenantContext.pushIgnore();
        TenantContext.pushIgnore();

        TenantContext.popIgnore();
        assertTrue(TenantContext.isIgnored(), "内层弹栈不应影响外层豁免");

        TenantContext.popIgnore();
        assertFalse(TenantContext.isIgnored(), "深度归零后必须回到未豁免");
    }

    @Test
    @DisplayName("异常路径下 finally 弹栈后不残留豁免状态")
    void shouldNotLeakIgnoreAfterException() {
        assertThrows(IllegalStateException.class, () -> {
            TenantContext.pushIgnore();
            try {
                throw new IllegalStateException("模拟 Mapper 执行期间的异常");
            } finally {
                TenantContext.popIgnore();
            }
        });

        assertFalse(TenantContext.isIgnored(), "异常路径同样必须配平");
    }

    @Test
    @DisplayName("未压栈即弹栈不抛异常（popIgnore 位于 finally 路径，抛异常会盖掉主故障）")
    void shouldToleratePopWithoutPush() {
        assertDoesNotThrow(TenantContext::popIgnore);
        assertFalse(TenantContext.isIgnored());
    }

    @Test
    @DisplayName("clear 会清掉未配平的豁免深度（请求结束时的兜底）")
    void shouldResetEvenWhenIgnoreNotBalanced() {
        TenantContext.pushIgnore();
        TenantContext.pushIgnore();

        TenantContext.clear();

        assertFalse(TenantContext.isIgnored(), "线程池复用前必须回到初始态");
    }
}

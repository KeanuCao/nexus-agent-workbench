/**
 * db-patch 数据库补丁引擎（子任务 0.2 填充）。
 *
 * <p>设计依据：docs/design/00-环境与部署.md §3.1 / §3.2 / §3.3。
 *
 * <p>计划内容：
 * <ul>
 *     <li>补丁记录表 {@code t_db_patch}（DDL 落在 {@code /db-patch} 首个补丁文件中，
 *         由引擎先行引导建表：{@code CREATE TABLE IF NOT EXISTS}）；</li>
 *     <li>{@code PatchCli}（Java main 类）：扫描补丁目录 → 按文件名字典序排序 →
 *         逐条在事务内执行 + 记录 SHA-256 checksum；</li>
 *     <li>幂等与防篡改：未执行则执行、已执行且 checksum 一致则跳过、
 *         checksum 不一致则报错终止（退出码非 0，up.sh 据此中止后续步骤）。</li>
 * </ul>
 *
 * <p>执行方式：由 builder 容器经 mvn 触发（{@code mvn -pl nexus-infrastructure exec:java}），
 * <b>不在后端启动流程中执行</b> —— 迁移先于后端启动完成，后端因此无需任何"补丁未执行"门控。
 *
 * <p>阶段0 状态：仅占位包结构，不含实现。
 *
 * @author nexus
 */
package com.nexus.infrastructure.dbpatch;

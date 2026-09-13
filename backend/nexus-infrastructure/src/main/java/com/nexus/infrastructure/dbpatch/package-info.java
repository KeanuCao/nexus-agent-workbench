/**
 * db-patch 数据库补丁引擎（子任务 0.2，2026-09-13 已实现）。
 *
 * <p>设计依据：docs/design/00-环境与部署.md §3.1 / §3.2 / §3.3。
 *
 * <p>内容：
 * <ul>
 *     <li>补丁记录表 {@code t_db_patch}：DDL 同时落在 {@code /db-patch} 首个补丁文件中，
 *         并由引擎先行引导建表（{@code CREATE TABLE IF NOT EXISTS}）—— 两者互为冗余保险；</li>
 *     <li>{@link com.nexus.infrastructure.dbpatch.PatchCli}（Java main 类）：扫描补丁目录 →
 *         按文件名字典序排序 → 逐条在事务内执行 + 记录 SHA-256 checksum；</li>
 *     <li>幂等与防篡改：未执行则执行、已执行且 checksum 一致则跳过、
 *         checksum 不一致则报错终止（退出码非 0，up.sh 据此中止后续步骤）；</li>
 *     <li>乱序保护：新补丁文件名早于已应用的最大文件名时终止，防止时间戳回退打乱时序。</li>
 * </ul>
 *
 * <p><b>执行方式（2026-09-13 修订）</b>：由 builder 容器内的
 * {@code db-patch-migrate} 入口脚本启动（{@code docker compose exec builder db-patch-migrate}）。
 * 脚本先用 Maven 编译本模块并解析运行时 classpath，再 {@code java -cp … PatchCli} 执行。
 *
 * <p><b>修订说明</b>：本文件原写"由 builder 容器经 mvn 触发（{@code mvn -pl nexus-infrastructure
 * exec:java}）"。该写法已弃用 —— {@code exec:java} 与 {@code System.exit} 的交互会让退出码
 * 不可靠，而 up.sh 完全依赖退出码决定"后端是否拉起"。现改为 {@code java -cp} 直跑，退出码由
 * {@code exec} 原样透传。
 *
 * <p><b>不在后端启动流程中执行</b>：迁移先于后端启动完成，后端因此无需任何"补丁未执行"门控。
 *
 * @author nexus
 */
package com.nexus.infrastructure.dbpatch;

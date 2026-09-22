package com.nexus.infrastructure.dbpatch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * db-patch 迁移引擎 —— 扫描补丁目录，按文件名顺序把未执行的补丁应用到数据库。
 *
 * <p><b>为什么是自研而不是 Flyway/Liquibase</b>：本项目的核心需求只有"排序执行 + checksum 防篡改"，
 * 两者都功能过剩（节点锁、多 schema、changelog DSL 全用不上），且执行时机需要完全自控
 * ——迁移必须发生在后端启动<strong>之前</strong>，失败时后端根本不该被拉起。
 * 命名规则 {@code YYYYMMDDHHmm_描述.sql} 里的纯数字时间戳同时是合法的 Flyway 版本号，
 * 团队规模化时可平替。完整取舍见 docs/design/00-环境与部署.md §3.4。
 *
 * <p><b>执行语义</b>（docs/design/00-环境与部署.md §3.3 的落地口径）：
 * <table border="1">
 *     <caption>决策表</caption>
 *     <tr><th>场景</th><th>行为</th><th>退出码</th></tr>
 *     <tr><td>补丁未执行过</td><td>单事务内执行 SQL + 写入记录</td><td>0</td></tr>
 *     <tr><td>已执行、checksum 一致</td><td>静默跳过（重跑迁移的幂等行为）</td><td>0</td></tr>
 *     <tr><td>已执行、checksum 不一致</td><td>报错终止（历史补丁被篡改，严禁二次执行）</td><td>2</td></tr>
 *     <tr><td>文件名不符命名规则（限 .sql 文件）</td><td>警告并跳过（目录允许放说明性文件）</td><td>0</td></tr>
 *     <tr><td>非 .sql 文件</td><td>扫描阶段即被过滤，静默忽略（不计入扫描数、不告警）</td><td>0</td></tr>
 *     <tr><td>时间戳早于已应用的最大时间戳</td><td>报错终止（乱序保护）</td><td>3</td></tr>
 * </table>
 *
 * <p><b>退出码就是判据</b>：{@code scripts/sh/up.sh} 第 5 步完全依赖它 ——
 * 非 0 即中止，后端不会被拉起。因此本类不吞任何异常，一律翻译成明确的退出码。
 *
 * <p><b>依赖约束</b>：本类只 import {@code java.*}，<strong>没有任何第三方编译期依赖</strong>，
 * 也不做 Spring 容器装配。PostgreSQL JDBC 驱动由运行时 classpath 提供
 * （见 docker-compose/builder/db-patch-migrate 用 Maven 解析出的 classpath 启动本类）。
 * 这么约束的好处是：引擎的行为不依赖任何框架的隐式约定，可以直接读、直接测。
 *
 * <p><b>checksum 的一个刻意选择</b>：哈希前会把 CRLF 归一化为 LF。
 * 原因是本项目的开发在 Windows、执行在 Linux 容器，行尾差异极易被误判成"补丁被篡改"，
 * 而它并不是语义变更。归一化后任何<strong>语义</strong>改动仍会改变哈希，检测力不减。
 * 复现方式：{@code tr -d '\r' < 补丁.sql | sha256sum}
 *
 * @author nexus
 */
public final class PatchCli {

    /** 补丁记录表名（结构契约见 docs/design/00-环境与部署.md §3.1）。 */
    private static final String PATCH_TABLE = "t_db_patch";

    /** 命名规则：12 位时间戳 + 下划线 + 描述 + .sql。 */
    private static final Pattern PATCH_FILE_PATTERN = Pattern.compile("^(\\d{12})_.+\\.sql$");

    /** 十六进制字符表（自实现避免依赖 String.format 的格式化开销与本地化风险）。 */
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    // ── 退出码：up.sh 据此判断迁移成败，每个码对应一类可以分别处置的故障 ──
    private static final int EXIT_OK = 0;
    private static final int EXIT_USAGE = 1;
    private static final int EXIT_CHECKSUM_MISMATCH = 2;
    private static final int EXIT_OUT_OF_ORDER = 3;
    private static final int EXIT_SQL_FAILED = 4;
    private static final int EXIT_CONNECT_FAILED = 5;
    private static final int EXIT_UNEXPECTED = 9;

    private PatchCli() {
        // 工具类入口，不允许实例化
    }

    /**
     * 程序入口。
     *
     * @param args 命令行参数，支持 {@code --patch-dir=<目录>}
     */
    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = run(args);
        } catch (PatchException ex) {
            logError(ex.getMessage());
            exitCode = ex.getExitCode();
        } catch (Exception ex) {
            // 兜底：任何未预期异常也必须有明确退出码，绝不能让进程以"看起来成功"的方式结束
            logError("未预期异常：" + ex);
            exitCode = EXIT_UNEXPECTED;
        }
        System.out.flush();
        System.err.flush();
        System.exit(exitCode);
    }

    /**
     * 迁移主流程。
     *
     * @param args 命令行参数
     * @return 退出码
     */
    private static int run(String[] args) {
        Path patchDir = parsePatchDir(args);
        logInfo("补丁目录：" + patchDir.toAbsolutePath());

        if (!Files.isDirectory(patchDir)) {
            throw new PatchException(EXIT_USAGE, "补丁目录不存在或不是目录：" + patchDir.toAbsolutePath());
        }

        try (Connection connection = openConnection()) {
            List<Path> patchFiles = listPatchFiles(patchDir);
            logInfo("扫描到 " + patchFiles.size() + " 个 .sql 文件（按文件名字典序排序）");
            logInfo("已连接数据库：" + connection.getMetaData().getURL());
            bootstrapPatchTable(connection);

            String maxAppliedFileName = findMaxAppliedFileName(connection);
            if (maxAppliedFileName == null) {
                logInfo("此前没有任何已应用的补丁（首次迁移）");
            } else {
                logInfo("已应用的最大补丁文件名：" + maxAppliedFileName);
            }

            int appliedCount = 0;
            int skippedCount = 0;
            for (Path patchFile : patchFiles) {
                String fileName = patchFile.getFileName().toString();
                Matcher matcher = PATCH_FILE_PATTERN.matcher(fileName);
                if (!matcher.matches()) {
                    // 能走到这里的只有「以 .sql 结尾但名字不合规」的文件 —— 非 .sql 的说明性文件
                    // （README.md 之类）在 listPatchFiles 的扫描阶段就被过滤掉了，根本进不来，
                    // 因此也不会打印这行 WARN。实测见 docs/test-cases/TC-00.md 的 TC-00-0.2-6。
                    logWarn("命名不符 " + PATCH_FILE_PATTERN.pattern() + "，跳过：" + fileName);
                    continue;
                }

                String checksum = sha256OfPatchFile(patchFile);
                String appliedChecksum = findAppliedChecksum(connection, fileName);
                if (appliedChecksum != null) {
                    if (appliedChecksum.equals(checksum)) {
                        logInfo("跳过（已应用且 checksum 一致）：" + fileName);
                        skippedCount++;
                        continue;
                    }
                    // 篡改只能被检出，绝不能被重放 —— 这里必须终止，且要说清新旧 checksum
                    throw new PatchException(EXIT_CHECKSUM_MISMATCH,
                            "已应用补丁的内容与记录不一致，迁移终止（历史补丁不得修改）：" + fileName
                                    + System.lineSeparator()
                                    + "  记录中的 checksum：" + appliedChecksum
                                    + System.lineSeparator()
                                    + "  当前文件的 checksum：" + checksum);
                }

                // 乱序保护：只对**未应用**的补丁判 —— 已应用的补丁文件名必然不大于最大值，判了会误报
                if (maxAppliedFileName != null && fileName.compareTo(maxAppliedFileName) < 0) {
                    throw new PatchException(EXIT_OUT_OF_ORDER,
                            "补丁文件名早于已应用的最大文件名，迁移终止（时间戳回退会打乱执行时序）："
                                    + fileName + " < " + maxAppliedFileName);
                }

                long elapsedMillis = applyPatch(connection, patchFile, fileName, checksum);
                logInfo("已应用：" + fileName + "（耗时 " + elapsedMillis + " ms）");
                appliedCount++;
            }

            logInfo("迁移完成：本次应用 " + appliedCount + " 个，跳过 " + skippedCount + " 个，共 " + patchFiles.size() + " 个");
            return EXIT_OK;
        } catch (SQLException ex) {
            throw new PatchException(EXIT_CONNECT_FAILED, "数据库操作失败：" + ex.getMessage(), ex);
        } catch (IOException ex) {
            throw new PatchException(EXIT_USAGE, "读取补丁文件失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * 解析 {@code --patch-dir} 参数。
     *
     * @param args 命令行参数
     * @return 补丁目录
     */
    private static Path parsePatchDir(String[] args) {
        String patchDirValue = null;
        for (String arg : args) {
            if (arg.startsWith("--patch-dir=")) {
                patchDirValue = arg.substring("--patch-dir=".length());
            }
        }
        if (patchDirValue == null || patchDirValue.isBlank()) {
            throw new PatchException(EXIT_USAGE, "缺少参数 --patch-dir=<补丁目录>");
        }
        return Path.of(patchDirValue);
    }

    /**
     * 建立数据库连接。连接参数全部来自环境变量，与 docker-compose/.env 同源。
     *
     * @return 数据库连接
     */
    private static Connection openConnection() {
        String host = readEnv("NEXUS_PG_HOST", null);
        String port = readEnv("NEXUS_PG_PORT", "5432");
        String database = readEnv("NEXUS_PG_DB", null);
        String user = readEnv("NEXUS_PG_USER", null);
        String password = readEnv("NEXUS_PG_PASSWORD", null);

        if (host == null || database == null || user == null || password == null) {
            throw new PatchException(EXIT_USAGE,
                    "缺少数据库连接环境变量：需要 NEXUS_PG_HOST / NEXUS_PG_DB / NEXUS_PG_USER / NEXUS_PG_PASSWORD"
                            + "（NEXUS_PG_PORT 可选，默认 5432）");
        }

        String url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        // 连接超时必须显式设置：默认值会让"数据库没起"表现为长时间静默卡住，
        // 而 up.sh 侧看到的就是脚本不动了 —— 很难判断是慢还是死了
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "60");

        try {
            return DriverManager.getConnection(url, properties);
        } catch (SQLException ex) {
            throw new PatchException(EXIT_CONNECT_FAILED,
                    "无法连接数据库 " + url + "：" + ex.getMessage()
                            + System.lineSeparator() + "  排查：postgres 容器是否 healthy？凭据是否与 .env 一致？",
                    ex);
        }
    }

    /**
     * 引导建表：让记录表自身也先存在，才能谈"记录了哪些补丁"。
     *
     * @param connection 数据库连接
     * @throws SQLException 建表失败
     */
    private static void bootstrapPatchTable(Connection connection) throws SQLException {
        String ddl = "CREATE TABLE IF NOT EXISTS " + PATCH_TABLE + " ("
                + "patch_id     BIGSERIAL    PRIMARY KEY,"
                + "file_name    VARCHAR(255) NOT NULL UNIQUE,"
                + "checksum     CHAR(64)     NOT NULL,"
                + "applied_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),"
                + "applied_by   VARCHAR(64)  NOT NULL DEFAULT 'nexus-builder',"
                + "exec_time_ms INTEGER      NOT NULL"
                + ")";
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    /**
     * 取已应用补丁里的最大文件名（乱序保护的基准）。
     *
     * @param connection 数据库连接
     * @return 最大文件名；一个都没有时返回 {@code null}
     * @throws SQLException 查询失败
     */
    private static String findMaxAppliedFileName(Connection connection) throws SQLException {
        String sql = "SELECT file_name FROM " + PATCH_TABLE + " ORDER BY file_name DESC LIMIT 1";
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    /**
     * 查某个补丁已记录的 checksum。
     *
     * @param connection 数据库连接
     * @param fileName   补丁文件名
     * @return 已记录的 checksum；未执行过时返回 {@code null}
     * @throws SQLException 查询失败
     */
    private static String findAppliedChecksum(Connection connection, String fileName) throws SQLException {
        String sql = "SELECT checksum FROM " + PATCH_TABLE + " WHERE file_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, fileName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    /**
     * 在单事务内执行补丁 SQL 并写入记录。
     *
     * <p>事务边界刻意包住"执行 SQL + 写记录"两件事：只写了一半的迁移记录比没有记录更危险
     * （下次迁移会以为它执行过了）。任一步失败即整体回滚，补丁等同于从未执行。
     *
     * @param connection 数据库连接
     * @param patchFile  补丁文件
     * @param fileName   补丁文件名
     * @param checksum   补丁内容的 SHA-256
     * @return 执行耗时（毫秒）
     * @throws SQLException 执行或写记录失败
     * @throws IOException  读取补丁文件失败
     */
    private static long applyPatch(Connection connection, Path patchFile, String fileName, String checksum)
            throws SQLException, IOException {
        // 注意：必须用 Statement 而不是 PreparedStatement —— 一个补丁文件里通常有多条 SQL，
        // 而 JDBC 的扩展查询协议只允许单条语句；Statement.execute 走简单查询协议，支持多语句。
        String sql = Files.readString(patchFile, StandardCharsets.UTF_8);

        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        long startMillis = System.currentTimeMillis();
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            long elapsedMillis = System.currentTimeMillis() - startMillis;
            insertPatchRecord(connection, fileName, checksum, elapsedMillis);
            connection.commit();
            return elapsedMillis;
        } catch (SQLException ex) {
            // rollback 自身也可能失败（连接已断）；此时**不能**让它的异常盖掉原始故障 ——
            // 真正要报告的是"补丁 SQL 为什么错"，不是"回滚也没成功"
            rollbackQuietly(connection, fileName);
            throw new PatchException(EXIT_SQL_FAILED,
                    "补丁执行失败并已回滚：" + fileName + System.lineSeparator() + "  " + ex.getMessage(), ex);
        } finally {
            // 恢复 autoCommit：后续补丁与查询都依赖它，不恢复会让下一条 SQL 悬在事务里
            try {
                connection.setAutoCommit(originalAutoCommit);
            } catch (SQLException ex) {
                logWarn("恢复 autoCommit 失败（连接可能已中断）：" + ex.getMessage());
            }
        }
    }

    /**
     * 回滚并吞掉回滚自身的异常。
     *
     * <p>刻意吞异常：调用点此刻正在处理"补丁执行失败"这个主故障，
     * 回滚失败只是它的次生现象，不该抢走异常链。
     *
     * @param connection 数据库连接
     * @param fileName   补丁文件名（仅用于日志定位）
     */
    private static void rollbackQuietly(Connection connection, String fileName) {
        try {
            connection.rollback();
        } catch (SQLException rollbackEx) {
            logWarn("回滚失败（连接可能已中断），补丁 " + fileName + "：" + rollbackEx.getMessage());
        }
    }

    /**
     * 写入补丁执行记录。
     *
     * @param connection    数据库连接
     * @param fileName      补丁文件名
     * @param checksum      补丁内容的 SHA-256
     * @param elapsedMillis 执行耗时（毫秒）
     * @throws SQLException 写入失败
     */
    private static void insertPatchRecord(Connection connection, String fileName, String checksum, long elapsedMillis)
            throws SQLException {
        String sql = "INSERT INTO " + PATCH_TABLE
                + " (file_name, checksum, applied_by, exec_time_ms) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, fileName);
            statement.setString(2, checksum);
            // 执行来源：容器内可用 NEXUS_PATCH_APPLIED_BY 覆盖，缺省与 DDL 默认值一致
            statement.setString(3, readEnv("NEXUS_PATCH_APPLIED_BY", "nexus-builder"));
            statement.setLong(4, elapsedMillis);
            statement.executeUpdate();
        }
    }

    /**
     * 列出目录下的 .sql 文件并按文件名字典序排序（命名规则保证字典序即时间序）。
     *
     * @param patchDir 补丁目录
     * @return 排序后的补丁文件列表
     * @throws IOException 目录读取失败
     */
    private static List<Path> listPatchFiles(Path patchDir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(patchDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return files;
    }

    /**
     * 计算补丁文件的 SHA-256（十六进制小写，64 字符）。
     *
     * <p>CRLF 先归一化为 LF，理由见类注释。
     *
     * @param patchFile 补丁文件
     * @return 64 字符的十六进制摘要
     * @throws IOException 读取失败
     */
    private static String sha256OfPatchFile(Path patchFile) throws IOException {
        byte[] rawBytes = Files.readAllBytes(patchFile);
        byte[] normalizedBytes = normalizeLineEndings(rawBytes);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(normalizedBytes));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 强制实现的算法，走到这里说明运行环境被裁剪过
            throw new PatchException(EXIT_UNEXPECTED, "运行环境缺少 SHA-256 实现：" + ex.getMessage(), ex);
        }
    }

    /**
     * 把 CRLF 归一化为 LF。
     *
     * @param bytes 原始字节
     * @return 归一化后的字节
     */
    private static byte[] normalizeLineEndings(byte[] bytes) {
        int writeIndex = 0;
        byte[] buffer = new byte[bytes.length];
        for (int readIndex = 0; readIndex < bytes.length; readIndex++) {
            byte current = bytes[readIndex];
            if (current == '\r' && readIndex + 1 < bytes.length && bytes[readIndex + 1] == '\n') {
                continue;
            }
            buffer[writeIndex] = current;
            writeIndex++;
        }
        if (writeIndex == bytes.length) {
            return bytes;
        }
        byte[] result = new byte[writeIndex];
        System.arraycopy(buffer, 0, result, 0, writeIndex);
        return result;
    }

    /**
     * 字节数组转小写十六进制字符串。
     *
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    private static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            chars[i * 2] = HEX_DIGITS[value >>> 4];
            chars[i * 2 + 1] = HEX_DIGITS[value & 0x0F];
        }
        return new String(chars);
    }

    /**
     * 读环境变量。
     *
     * @param key          变量名
     * @param defaultValue 缺省值（可为 {@code null}，表示必填）
     * @return 变量值
     */
    private static String readEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    private static void logInfo(String message) {
        System.out.println("[db-patch] " + message);
    }

    private static void logWarn(String message) {
        System.out.println("[db-patch] WARN " + message);
    }

    private static void logError(String message) {
        System.err.println("[db-patch] ERROR " + message);
    }

    /**
     * 携带退出码的内部异常：让故障类型一路传到 {@link #main} 而无须层层判断返回值。
     */
    private static final class PatchException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int exitCode;

        PatchException(int exitCode, String message) {
            super(message);
            this.exitCode = exitCode;
        }

        PatchException(int exitCode, String message, Throwable cause) {
            super(message, cause);
            this.exitCode = exitCode;
        }

        int getExitCode() {
            return exitCode;
        }
    }
}

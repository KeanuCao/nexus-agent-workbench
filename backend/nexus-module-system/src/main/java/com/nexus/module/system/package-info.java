/**
 * 系统模块：用户、角色、菜单（阶段1 填充）。
 *
 * <p>规划内的包结构（阶段1 落码时按需创建）：
 * <pre>
 * com.nexus.module.system
 *  ├── controller   对外 HTTP 接口（如 /api/auth/login）
 *  ├── service      业务编排（接口 + impl 子包）
 *  ├── mapper       MyBatis-Plus Mapper
 *  ├── entity       数据库实体（与表结构一一对应）
 *  └── model        入参 DTO / 出参 VO
 * </pre>
 *
 * <p>依赖方向：本模块 → nexus-common + nexus-infrastructure，禁止反向依赖。
 *
 * <p>阶段0 状态：空壳模块 —— 本文件（package-info）是包结构的占位，
 * 作用是让 {@code mvn clean install -DskipTests} 的全模块链路先跑通
 * （Git 不跟踪空目录，Java 包也需至少一个文件才能进版本库）。
 *
 * @author nexus
 */
package com.nexus.module.system;

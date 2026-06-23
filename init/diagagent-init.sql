-- 初始化 diagagent 数据库
CREATE DATABASE IF NOT EXISTS diagagent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

USE diagagent;

-- 诊断记录表
CREATE TABLE IF NOT EXISTS `diagnosis_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `query` VARCHAR(500) NOT NULL COMMENT '用户查询',
    `conclusion` TEXT COMMENT '诊断结论',
    `root_cause` TEXT COMMENT '根因分析',
    `suggestions` TEXT COMMENT '建议',
    `confidence` DOUBLE COMMENT '置信度',
    `engine` VARCHAR(50) COMMENT '使用的引擎 (LLM/Orchestrator)',
    `verified` TINYINT(1) DEFAULT 0 COMMENT '是否验证通过',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_query` (`query`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='诊断记录表';

-- 诊断会话表
CREATE TABLE IF NOT EXISTS `diagnosis_session` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `session_id` VARCHAR(100) NOT NULL COMMENT '会话唯一标识',
    `title` VARCHAR(200) COMMENT '会话标题',
    `engine` VARCHAR(50) COMMENT '使用的引擎',
    `user_id` BIGINT COMMENT '用户ID',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `idx_session_id` (`session_id`),
    INDEX `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='诊断会话表';

-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `password` VARCHAR(100) NOT NULL COMMENT '密码',
    `salt` VARCHAR(50) NOT NULL COMMENT '盐值',
    `nickname` VARCHAR(50) COMMENT '昵称',
    `avatar` VARCHAR(255) COMMENT '头像',
    `status` TINYINT DEFAULT 9 COMMENT '状态 0禁用 9可用',
    `role` VARCHAR(20) DEFAULT 'USER' COMMENT '角色',
    `last_login_time` DATETIME COMMENT '最后登录时间',
    `created_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `idx_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 插入默认用户
-- 密码: 1, salt: abc123, hash: MD5(password)
INSERT INTO `user` (`username`, `password`, `salt`, `nickname`, `status`, `role`)
VALUES ('user1', 'c4ca4238a0b923820dcc509a6f75849b', 'abc123', 'User 1', 9, 'USER')
ON DUPLICATE KEY UPDATE `username` = `username`;

-- 密码: 2, salt: abc123
INSERT INTO `user` (`username`, `password`, `salt`, `nickname`, `status`, `role`)
VALUES ('user2', 'c81e728d9d4c2f636f067f89cc14862c', 'abc123', 'User 2', 9, 'USER')
ON DUPLICATE KEY UPDATE `username` = `username`;


-- 密码: admin123, salt: abc123
INSERT INTO `user` (`username`, `password`, `salt`, `nickname`, `status`, `role`)
VALUES ('admin', '0192023a7bbd73250516f069df18b500', 'abc123', 'Admin', 9, 'ADMIN')
ON DUPLICATE KEY UPDATE `username` = `username`;

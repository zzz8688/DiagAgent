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

-- 会话表
CREATE TABLE IF NOT EXISTS `diagnosis_session` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `session_id` VARCHAR(100) NOT NULL COMMENT '会话唯一标识',
    `title` VARCHAR(200) COMMENT '会话标题',
    `engine` VARCHAR(50) COMMENT '使用的引擎',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `idx_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='诊断会话表';

-- 会话消息表
CREATE TABLE IF NOT EXISTS `session_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `session_id` VARCHAR(100) NOT NULL COMMENT '会话唯一标识',
    `role` VARCHAR(20) NOT NULL COMMENT '消息角色 (USER/AI)',
    `content` TEXT NOT NULL COMMENT '消息内容',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话消息表';

-- MongoDB 集合会自动创建，无需手动创建

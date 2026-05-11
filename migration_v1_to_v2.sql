-- ============================================================
-- 学思伴行（LearnThink Companion）数据库迁移脚本
-- 从 v1.0 升级到 v2.0
-- 日期：2026-05-10
-- 说明：保留现有数据，升级表结构至最终版设计
-- ============================================================

USE learnthink;

START TRANSACTION;

-- ============================================================
-- 步骤 1: 禁用外键检查（避免字符集修改冲突）
-- ============================================================
SET FOREIGN_KEY_CHECKS = 0;


-- ============================================================
-- 步骤 2: 修改字符集为 utf8mb4_0900_ai_ci
-- ============================================================
ALTER DATABASE learnthink CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

ALTER TABLE users CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE courses CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE profiles CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE profile_versions CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE profile_chats CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE tasks CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE task_events CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE resource_packs CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE resource_items CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE review_records CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE learning_paths CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE learning_path_versions CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE quiz_attempts CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE learning_events CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE knowledge_documents CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ============================================================
-- 步骤 3: 创建缺失的 3 张新表
-- ============================================================

-- 3.1 创建 user_course_enrollments 表
CREATE TABLE IF NOT EXISTS user_course_enrollments (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    enrolled_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_uce_user_course (user_id, course_id),
    INDEX idx_uce_course (course_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 3.2 创建 agent_thinking_traces 表
CREATE TABLE IF NOT EXISTS agent_thinking_traces (
    id CHAR(36) PRIMARY KEY,
    task_id CHAR(36) NOT NULL,
    agent_name VARCHAR(50) NOT NULL COMMENT 'Agent 标识：Retriever / Planner / Generator / Reviewer',
    agent_role VARCHAR(50) COMMENT 'Agent 角色描述',
    phase VARCHAR(50) COMMENT '流水线阶段',
    context TEXT COMMENT '输入上下文摘要',
    observation TEXT COMMENT '观察',
    thought TEXT COMMENT '思考过程',
    decision TEXT COMMENT '决策结论',
    confidence_level VARCHAR(10) COMMENT 'high / medium / low',
    `trigger` VARCHAR(30) COMMENT 'autonomous / response_to_agent / system_prompt',
    in_response_to CHAR(36) COMMENT '回复目标 trace ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_att_task (task_id),
    INDEX idx_att_task_agent (task_id, agent_name),
    INDEX idx_att_task_created (task_id, created_at),
    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 3.3 创建 agent_messages 表
CREATE TABLE IF NOT EXISTS agent_messages (
    id CHAR(36) PRIMARY KEY,
    task_id CHAR(36) NOT NULL,
    from_agent VARCHAR(50) NOT NULL,
    to_agent VARCHAR(50) COMMENT 'NULL = 广播',
    agent_role VARCHAR(50),
    action VARCHAR(50) COMMENT 'coverage_report / plan_adjusted / revision_request / revision_applied / revision_approved / parallel_dispatch / fallback_decision',
    message TEXT,
    detail_json JSON COMMENT '结构化详情',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_am_task (task_id),
    INDEX idx_am_task_from (task_id, from_agent),
    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ============================================================
-- 步骤 4: 修改 profiles 表（添加 surrogate PK）
-- ============================================================
ALTER TABLE profiles ADD COLUMN id CHAR(36) FIRST;
UPDATE profiles SET id = UUID();
ALTER TABLE profiles DROP PRIMARY KEY, ADD PRIMARY KEY (id);
ALTER TABLE profiles ADD UNIQUE KEY uk_profiles_user_course (user_id, course_id);
ALTER TABLE profiles ADD INDEX idx_profiles_user (user_id), ADD INDEX idx_profiles_course (course_id);


-- ============================================================
-- 步骤 5: 修改 learning_paths 表（添加 surrogate PK + deleted_at）
-- ============================================================
ALTER TABLE learning_paths ADD COLUMN id CHAR(36) FIRST;
UPDATE learning_paths SET id = UUID();
ALTER TABLE learning_paths DROP PRIMARY KEY, ADD PRIMARY KEY (id);
ALTER TABLE learning_paths ADD UNIQUE KEY uk_lp_user_course (user_id, course_id);
ALTER TABLE learning_paths ADD INDEX idx_lp_user (user_id);
ALTER TABLE learning_paths ADD COLUMN deleted_at DATETIME COMMENT '软删除时间（NULL=未删除）' AFTER current_version;


-- ============================================================
-- 步骤 6: 修改 profile_versions 表
-- ============================================================
-- 添加索引（如果不存在）
SET @exist := (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='learnthink' AND table_name='profile_versions' AND index_name='idx_pv_user');
SET @sqlstmt := IF(@exist=0, 'ALTER TABLE profile_versions ADD INDEX idx_pv_user (user_id)', 'SELECT "Index idx_pv_user already exists"');
PREPARE stmt FROM @sqlstmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ============================================================
-- 步骤 7: 修改 profile_chats 表（添加 profile_version_id）
-- ============================================================
ALTER TABLE profile_chats ADD COLUMN profile_version_id CHAR(36) COMMENT '处理完成后关联的画像版本（NULL=未处理）' AFTER course_id;
ALTER TABLE profile_chats ADD INDEX idx_pc_user_course (user_id, course_id), ADD INDEX idx_pc_profile_version (profile_version_id);


-- ============================================================
-- 步骤 8: 修改 tasks 表（profile_version → profile_version_id）
-- ============================================================
ALTER TABLE tasks CHANGE COLUMN profile_version profile_version_id CHAR(36) COMMENT '生成时所依据的画像版本';
ALTER TABLE tasks ADD INDEX idx_task_user_created (user_id, created_at), ADD INDEX idx_task_stage (stage), ADD INDEX idx_task_status (status);


-- ============================================================
-- 步骤 9: 修改 task_events 表（添加索引）
-- ============================================================
ALTER TABLE task_events ADD INDEX idx_te_task_created (task_id, created_at);


-- ============================================================
-- 步骤 10: 修改 resource_packs 表
-- ============================================================
ALTER TABLE resource_packs ADD COLUMN deleted_at DATETIME COMMENT '软删除时间（NULL=未删除）' AFTER push_reason_json;
ALTER TABLE resource_packs ADD INDEX idx_rp_user_created (user_id, created_at);

-- 修复字段名：generated_from_profile_version → generated_from_profile_version_id
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='learnthink' AND TABLE_NAME='resource_packs' AND COLUMN_NAME='generated_from_profile_version');
SET @sqlstmt := IF(@exist>0, 'ALTER TABLE resource_packs CHANGE COLUMN generated_from_profile_version generated_from_profile_version_id CHAR(36) COMMENT ''生成时所依据的画像版本ID''', 'SELECT "Column already renamed"');
PREPARE stmt FROM @sqlstmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ============================================================
-- 步骤 11: 修改 resource_items 表（重大变更）
-- ============================================================
ALTER TABLE resource_items 
ADD COLUMN confidence_score DECIMAL(3,2) COMMENT '置信度 0.00~1.00' AFTER content_mime,
ADD COLUMN quality_score DECIMAL(3,2) COMMENT '质量评分 0.00~100.00' AFTER confidence_score,
ADD COLUMN deleted_at DATETIME COMMENT '软删除时间（NULL=未删除）' AFTER review_summary;

-- 迁移旧 confidence 数据到 confidence_score
UPDATE resource_items 
SET confidence_score = CASE 
    WHEN confidence = 'high' THEN 0.90
    WHEN confidence = 'medium' THEN 0.70
    WHEN confidence = 'low' THEN 0.50
    ELSE NULL
END
WHERE confidence IS NOT NULL;

-- 删除旧的 confidence 字段
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='learnthink' AND TABLE_NAME='resource_items' AND COLUMN_NAME='confidence');
SET @sqlstmt := IF(@exist>0, 'ALTER TABLE resource_items DROP COLUMN confidence', 'SELECT "Column confidence already dropped"');
PREPARE stmt FROM @sqlstmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE resource_items 
ADD INDEX idx_ri_pack_type_status (pack_id, type, status),
ADD INDEX idx_ri_status (status),
ADD INDEX idx_ri_type_status (type, status);


-- ============================================================
-- 步骤 12: 修改 review_records 表
-- ============================================================
ALTER TABLE review_records ADD COLUMN resource_pack_id CHAR(36) NOT NULL COMMENT '冗余字段，便于按包查询审校结果' AFTER resource_item_id;

-- 为现有记录填充 resource_pack_id
UPDATE review_records rr JOIN resource_items ri ON rr.resource_item_id = ri.id
SET rr.resource_pack_id = ri.pack_id;

ALTER TABLE review_records ADD INDEX idx_rr_pack (resource_pack_id);


-- ============================================================
-- 步骤 13: 修改 learning_path_versions 表
-- ============================================================
ALTER TABLE learning_path_versions CHANGE COLUMN generated_from_profile_version generated_from_profile_version_id CHAR(36);
ALTER TABLE learning_path_versions ADD INDEX idx_lpv_user (user_id), ADD INDEX idx_lpv_user_course (user_id, course_id);


-- ============================================================
-- 步骤 14: 修改 quiz_attempts 表
-- ============================================================
ALTER TABLE quiz_attempts ADD INDEX idx_qa_user_course_created (user_id, course_id, created_at);


-- ============================================================
-- 步骤 15: 修改 learning_events 表（添加 course_id）
-- ============================================================
ALTER TABLE learning_events ADD COLUMN course_id CHAR(36) COMMENT '可为空：非课程特定事件（如登录）不关联课程' AFTER user_id;
ALTER TABLE learning_events ADD INDEX idx_le_user_course_created (user_id, course_id, created_at);


-- ============================================================
-- 步骤 16: 修改 knowledge_documents 表
-- ============================================================
ALTER TABLE knowledge_documents 
ADD COLUMN doc_hash VARCHAR(64) COMMENT 'SHA-256 文件哈希，用于增量索引去重' AFTER file_path,
ADD COLUMN chunk_count INT DEFAULT 0 COMMENT '切分后的 chunk 数量' AFTER doc_hash,
ADD COLUMN updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at;

ALTER TABLE knowledge_documents 
ADD INDEX idx_kd_course_type (course_id, source_type),
ADD UNIQUE KEY uk_kd_course_hash (course_id, doc_hash);


-- ============================================================
-- 步骤 17: 重新启用外键检查并添加所有外键约束
-- ============================================================
SET FOREIGN_KEY_CHECKS = 1;

-- profiles 表
ALTER TABLE profiles 
ADD FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
ADD FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT;

-- profile_versions 表
ALTER TABLE profile_versions 
ADD CONSTRAINT fk_pv_profiles FOREIGN KEY (user_id, course_id)
    REFERENCES profiles(user_id, course_id) ON DELETE RESTRICT,
ADD FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
ADD FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT;

-- profile_chats 表
ALTER TABLE profile_chats 
ADD FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
ADD FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT,
ADD FOREIGN KEY (profile_version_id) REFERENCES profile_versions(id) ON DELETE SET NULL;

-- tasks 表
ALTER TABLE tasks 
ADD CONSTRAINT fk_tasks_profiles FOREIGN KEY (user_id, course_id)
    REFERENCES profiles(user_id, course_id) ON DELETE RESTRICT,
ADD FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
ADD FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT,
ADD FOREIGN KEY (profile_version_id) REFERENCES profile_versions(id) ON DELETE SET NULL;

-- task_events 表
ALTER TABLE task_events 
ADD FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE;

-- agent_thinking_traces 表
ALTER TABLE agent_thinking_traces 
ADD FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE;

-- agent_messages 表
ALTER TABLE agent_messages 
ADD FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE;

-- resource_packs 表
ALTER TABLE resource_packs 
ADD CONSTRAINT fk_rp_profiles FOREIGN KEY (user_id, course_id)
    REFERENCES profiles(user_id, course_id) ON DELETE RESTRICT,
ADD FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
ADD FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT,
ADD FOREIGN KEY (generated_from_profile_version_id) REFERENCES profile_versions(id) ON DELETE SET NULL,
ADD FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE SET NULL;

-- resource_items 表
ALTER TABLE resource_items 
ADD FOREIGN KEY (pack_id) REFERENCES resource_packs(id) ON DELETE CASCADE,
ADD FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE RESTRICT;

-- review_records 表
ALTER TABLE review_records 
ADD FOREIGN KEY (resource_item_id) REFERENCES resource_items(id) ON DELETE CASCADE,
ADD FOREIGN KEY (resource_pack_id) REFERENCES resource_packs(id) ON DELETE CASCADE,
ADD FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE RESTRICT;

-- learning_paths 表
ALTER TABLE learning_paths 
ADD CONSTRAINT fk_lp_profiles FOREIGN KEY (user_id, course_id)
    REFERENCES profiles(user_id, course_id) ON DELETE RESTRICT,
ADD FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
ADD FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT;

-- learning_path_versions 表
ALTER TABLE learning_path_versions 
ADD CONSTRAINT fk_lpv_paths FOREIGN KEY (user_id, course_id)
    REFERENCES learning_paths(user_id, course_id) ON DELETE RESTRICT,
ADD FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
ADD FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT,
ADD FOREIGN KEY (generated_from_profile_version_id) REFERENCES profile_versions(id) ON DELETE SET NULL;

-- quiz_attempts 表
ALTER TABLE quiz_attempts 
ADD FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
ADD FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT,
ADD FOREIGN KEY (pack_id) REFERENCES resource_packs(id) ON DELETE SET NULL;

-- learning_events 表
ALTER TABLE learning_events 
ADD FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
ADD FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE SET NULL;

-- knowledge_documents 表
ALTER TABLE knowledge_documents 
ADD FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE;


-- ============================================================
-- 完成迁移
-- ============================================================
COMMIT;

-- ============================================================
-- 验证迁移结果
-- ============================================================
SELECT 'Migration completed successfully!' AS status;
SELECT COUNT(*) AS total_tables FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'learnthink';
SELECT table_name FROM information_schema.tables WHERE table_schema='learnthink' ORDER BY table_name;

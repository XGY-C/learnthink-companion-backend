-- ============================================================
-- 学思伴行（LearnThink Companion）数据库完全重建脚本
-- 版本：v2.0
-- 日期：2026-05-11
-- 警告：此脚本会删除现有数据库并重建，请谨慎使用！
-- 用法：mysql -u root -p < sql/rebuild_database.sql
-- ============================================================

-- 1. 删除旧数据库（如果存在）
DROP DATABASE IF EXISTS learnthink;

-- 2. 创建新数据库
CREATE DATABASE learnthink DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE learnthink;


-- ============================================================
-- 3. 用户与鉴权
-- ============================================================
CREATE TABLE users (
    id CHAR(36) PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) DEFAULT 'student' COMMENT 'student / admin',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ============================================================
-- 4. 课程
-- ============================================================
CREATE TABLE courses (
    id CHAR(36) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- 用户-课程选课关系（M:N）
CREATE TABLE user_course_enrollments (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    enrolled_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_uce_user_course (user_id, course_id),
    INDEX idx_uce_course (course_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ============================================================
-- 5. 画像（版本化）
-- ============================================================
-- 画像主表：每个用户在每个课程下有一份画像，仅存最新指针
CREATE TABLE profiles (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    current_version INT DEFAULT 0,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_profiles_user_course (user_id, course_id),
    INDEX idx_profiles_user (user_id),
    INDEX idx_profiles_course (course_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- 画像版本快照（每次对话/行为触发更新时追加一行）
CREATE TABLE profile_versions (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    version INT NOT NULL,
    dimensions_json JSON COMMENT '7维画像数据（v2：不含 assessment_summary）',
    summary_json JSON COMMENT '供 Planner 使用的压缩摘要',
    source_chat_ids JSON COMMENT '关联对话记录 ID 列表',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pv_user_course_version (user_id, course_id, version),
    INDEX idx_pv_user (user_id),
    INDEX idx_pv_user_course (user_id, course_id),
    CONSTRAINT fk_pv_profiles FOREIGN KEY (user_id, course_id)
        REFERENCES profiles(user_id, course_id) ON DELETE RESTRICT,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- 画像对话记录
CREATE TABLE profile_chats (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    profile_version_id CHAR(36) COMMENT '处理完成后关联的画像版本（NULL=未处理）',
    messages_json JSON COMMENT '[{role, content, at}]',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_pc_user (user_id),
    INDEX idx_pc_user_course (user_id, course_id),
    INDEX idx_pc_profile_version (profile_version_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT,
    FOREIGN KEY (profile_version_id) REFERENCES profile_versions(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ============================================================
-- 6. 任务编排与事件（多智能体流水线）
-- ============================================================
-- 任务主表
CREATE TABLE tasks (
    id CHAR(36) PRIMARY KEY COMMENT 'task_id',
    user_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    task_type VARCHAR(50) COMMENT 'resource_generate / path_generate',
    topic VARCHAR(200),
    requested_resource_types JSON,
    profile_version_id CHAR(36) COMMENT '生成时所依据的画像版本',
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED',
    stage VARCHAR(30) COMMENT 'PROFILING / RETRIEVING / PLANNING / GENERATING / REVIEWING / PUBLISHING',
    percent INT DEFAULT 0,
    error_code VARCHAR(50),
    error_message TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    started_at DATETIME,
    finished_at DATETIME,
    INDEX idx_task_user_status (user_id, status),
    INDEX idx_task_user_created (user_id, created_at),
    INDEX idx_task_type (task_type),
    INDEX idx_task_stage (stage),
    INDEX idx_task_status (status),
    CONSTRAINT fk_tasks_profiles FOREIGN KEY (user_id, course_id)
        REFERENCES profiles(user_id, course_id) ON DELETE RESTRICT,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT,
    FOREIGN KEY (profile_version_id) REFERENCES profile_versions(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- 任务事件日志（可回放流水线）
CREATE TABLE task_events (
    id CHAR(36) PRIMARY KEY,
    task_id CHAR(36) NOT NULL,
    event_type VARCHAR(50) COMMENT 'task.accepted / task.stage / resource.ready / review.flag / agent.thought / agent.message / task.done / task.failed',
    payload_json JSON,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_te_task (task_id),
    INDEX idx_te_task_event (task_id, event_type),
    INDEX idx_te_task_created (task_id, created_at),
    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- Agent 思考链记录
CREATE TABLE agent_thinking_traces (
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


-- Agent 间协作消息
CREATE TABLE agent_messages (
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
-- 7. 资源包与资源项
-- ============================================================
-- 资源包主表
CREATE TABLE resource_packs (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    topic VARCHAR(200),
    generated_from_profile_version_id CHAR(36),
    task_id CHAR(36),
    push_reason_json JSON COMMENT '个性化推送原因标签',
    deleted_at DATETIME COMMENT '软删除时间（NULL=未删除）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_rp_user (user_id),
    INDEX idx_rp_user_created (user_id, created_at),
    INDEX idx_rp_task (task_id),
    CONSTRAINT fk_rp_profiles FOREIGN KEY (user_id, course_id)
        REFERENCES profiles(user_id, course_id) ON DELETE RESTRICT,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT,
    FOREIGN KEY (generated_from_profile_version_id) REFERENCES profile_versions(id) ON DELETE SET NULL,
    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- 资源项（5 类资源 + 视频脚本）
CREATE TABLE resource_items (
    id CHAR(36) PRIMARY KEY,
    pack_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    type VARCHAR(30) COMMENT 'doc / quiz / mindmap / reading / code / video_script',
    title VARCHAR(500),
    status VARCHAR(20) DEFAULT 'pending' COMMENT 'pending / ready / failed / rejected',
    content_ref VARCHAR(500) COMMENT '对象存储 key',
    content_mime VARCHAR(50) COMMENT 'text/markdown / application/json',
    confidence_score DECIMAL(3,2) COMMENT '置信度 0.00~1.00',
    quality_score DECIMAL(3,2) COMMENT '质量评分 0.00~100.00',
    metadata_json JSON COMMENT '难度、标签、估时等扩展字段',
    sources_json JSON COMMENT '证据列表 [{doc_id, title, chunk_id, quote, locator, relevance}]',
    review_status VARCHAR(20) DEFAULT 'pending' COMMENT 'pending / approved / rejected',
    review_summary TEXT,
    deleted_at DATETIME COMMENT '软删除时间（NULL=未删除）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ri_pack (pack_id),
    INDEX idx_ri_task (task_id),
    INDEX idx_ri_pack_type_status (pack_id, type, status),
    INDEX idx_ri_status (status),
    INDEX idx_ri_type_status (type, status),
    FOREIGN KEY (pack_id) REFERENCES resource_packs(id) ON DELETE CASCADE,
    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- 审校记录
CREATE TABLE review_records (
    id CHAR(36) PRIMARY KEY,
    resource_item_id CHAR(36) NOT NULL,
    resource_pack_id CHAR(36) NOT NULL COMMENT '冗余字段，便于按包查询审校结果',
    task_id CHAR(36) NOT NULL,
    result VARCHAR(20) COMMENT 'approved / rejected',
    reasons_json JSON COMMENT '结构化驳回原因 [{type, detail}]',
    citation_coverage DECIMAL(5,2) COMMENT '引用覆盖率 0.00~100.00',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_rr_resource (resource_item_id),
    INDEX idx_rr_pack (resource_pack_id),
    INDEX idx_rr_task (task_id),
    FOREIGN KEY (resource_item_id) REFERENCES resource_items(id) ON DELETE CASCADE,
    FOREIGN KEY (resource_pack_id) REFERENCES resource_packs(id) ON DELETE CASCADE,
    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ============================================================
-- 8. 学习路径（版本化）
-- ============================================================
-- 路径主表（当前指针）
CREATE TABLE learning_paths (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    current_version INT DEFAULT 0,
    deleted_at DATETIME COMMENT '软删除时间（NULL=未删除）',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_lp_user_course (user_id, course_id),
    INDEX idx_lp_user (user_id),
    CONSTRAINT fk_lp_profiles FOREIGN KEY (user_id, course_id)
        REFERENCES profiles(user_id, course_id) ON DELETE RESTRICT,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- 路径版本快照
CREATE TABLE learning_path_versions (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    version INT NOT NULL,
    generated_from_profile_version_id CHAR(36),
    path_json JSON COMMENT '{nodes: [], edges: [], adjustments: []}',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_lpv_user_course_version (user_id, course_id, version),
    INDEX idx_lpv_user (user_id),
    INDEX idx_lpv_user_course (user_id, course_id),
    CONSTRAINT fk_lpv_paths FOREIGN KEY (user_id, course_id)
        REFERENCES learning_paths(user_id, course_id) ON DELETE RESTRICT,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT,
    FOREIGN KEY (generated_from_profile_version_id) REFERENCES profile_versions(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ============================================================
-- 9. 做题与学习行为
-- ============================================================
-- 做题记录
CREATE TABLE quiz_attempts (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    topic VARCHAR(200),
    node_id VARCHAR(50) COMMENT '关联路径节点',
    pack_id CHAR(36),
    answers_json JSON COMMENT '用户作答',
    score DECIMAL(5,2),
    weak_tags JSON COMMENT '错误标签列表',
    duration_seconds INT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_qa_user_topic (user_id, topic),
    INDEX idx_qa_user_created (user_id, created_at),
    INDEX idx_qa_user_course_created (user_id, course_id, created_at),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT,
    FOREIGN KEY (pack_id) REFERENCES resource_packs(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- 学习行为事件
CREATE TABLE learning_events (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    course_id CHAR(36) COMMENT '可为空：非课程特定事件（如登录）不关联课程',
    event_type VARCHAR(30) COMMENT 'resource_opened / quiz_submitted / node_completed / resource_shared',
    payload_json JSON,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_le_user_event (user_id, event_type),
    INDEX idx_le_user_created (user_id, created_at),
    INDEX idx_le_user_course_created (user_id, course_id, created_at),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ============================================================
-- 10. 知识库元数据
-- ============================================================
CREATE TABLE knowledge_documents (
    id CHAR(36) PRIMARY KEY,
    course_id CHAR(36) NOT NULL,
    title VARCHAR(500),
    source_type VARCHAR(50) COMMENT 'lecture / glossary / exercise_bank / reading',
    file_path VARCHAR(500),
    doc_hash VARCHAR(64) COMMENT 'SHA-256 文件哈希，用于增量索引去重',
    chunk_count INT DEFAULT 0 COMMENT '切分后的 chunk 数量',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_kd_course (course_id),
    INDEX idx_kd_course_type (course_id, source_type),
    UNIQUE KEY uk_kd_course_hash (course_id, doc_hash),
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ============================================================
-- 完成提示
-- ============================================================
SELECT '✅ 数据库表结构创建完成！' AS message;
SELECT COUNT(*) AS total_tables FROM information_schema.tables WHERE table_schema = 'learnthink';


-- ============================================================
-- 11. 插入测试数据（从 learnthink.sql 提取）
-- ============================================================

-- 11.1 用户数据
INSERT INTO users VALUES ('5465d59c825f1c7eb811d6665872611d', 'xgy', '3791158284@qq.com', '$2a$10$RXVCiXd8lHMo0LCOnzSJF.CPhRcOlaV8YUs1pPvn3IlyfbynA6zey', 'student', '2026-05-07 22:05:13', '2026-05-07 22:05:13');
INSERT INTO users VALUES ('user-admin-001', 'admin', 'admin@learnthink.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'admin', '2026-05-10 19:15:25', '2026-05-10 19:15:25');
INSERT INTO users VALUES ('user-demo-001', 'zhangsan', 'zhangsan@example.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'student', '2026-05-10 19:15:25', '2026-05-10 19:15:25');
INSERT INTO users VALUES ('user-test-001', 'testuser', 'test@learnthink.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'student', '2026-05-10 19:15:25', '2026-05-10 19:15:25');

-- 11.2 课程数据
INSERT INTO courses VALUES ('course-ai-001', '人工智能导论', '大学本科 AI 入门课程，涵盖搜索、机器学习、深度学习基础', '2026-05-10 19:15:25');
INSERT INTO courses VALUES ('course-db-001', '数据库原理', '关系型数据库与 SQL 基础课程', '2026-05-10 19:15:25');

-- 11.3 用户-课程选课关系
INSERT INTO user_course_enrollments VALUES ('uce-001', 'user-test-001', 'course-ai-001', '2026-05-10 19:15:25');
INSERT INTO user_course_enrollments VALUES ('uce-002', 'user-demo-001', 'course-ai-001', '2026-05-10 19:15:25');
INSERT INTO user_course_enrollments VALUES ('uce-003', 'user-demo-001', 'course-db-001', '2026-05-10 19:15:25');

-- 11.4 画像主表
INSERT INTO profiles VALUES ('prof-001', 'user-test-001', 'course-ai-001', 3, '2026-05-10 19:15:25');
INSERT INTO profiles VALUES ('prof-002', 'user-demo-001', 'course-ai-001', 1, '2026-05-10 19:15:25');

-- 11.5 画像版本快照
INSERT INTO profile_versions VALUES ('pv-001', 'user-test-001', 'course-ai-001', 1, '[{"key": "major_context", "label": "专业上下文", "layer": "auxiliary", "value": {"major": "计算机", "course": "AI导论", "current_chapter": "第1章"}, "source": "explicit", "confidence": 0.95, "updated_at": "2026-05-10T09:00:00+08:00"}, {"key": "knowledge_basis", "label": "知识基础", "layer": "core", "value": {"weak": ["贝叶斯公式", "A*搜索"], "strong": ["概率基础"]}, "source": "explicit", "confidence": 0.85, "updated_at": "2026-05-10T09:00:00+08:00"}, {"key": "learning_goal", "label": "学习目标", "layer": "core", "value": {"target": "期末85分", "deadline": "2026-06-30", "sub_goals": ["掌握搜索算法", "理解贝叶斯分类器"]}, "source": "explicit", "confidence": 0.9, "updated_at": "2026-05-10T09:00:00+08:00"}, {"key": "cognitive_style", "label": "认知偏好", "layer": "style", "value": {"avoid": ["纯公式推导"], "style": ["代码实例", "可视化"]}, "source": "inferred", "confidence": 0.7, "updated_at": "2026-05-10T09:00:00+08:00"}, {"key": "learning_pace", "label": "学习节奏", "layer": "style", "value": {"urgency": "high", "days_per_week": 5, "minutes_per_day": 60}, "source": "explicit", "confidence": 0.8, "updated_at": "2026-05-10T09:00:00+08:00"}, {"key": "interest_direction", "label": "兴趣方向", "layer": "auxiliary", "value": {"topics": ["推荐系统", "计算机视觉"], "applications": ["医疗AI"]}, "source": "inferred", "confidence": 0.6, "updated_at": "2026-05-10T09:00:00+08:00"}, {"key": "error_pattern", "label": "错误模式", "layer": "auxiliary", "value": {"tags": ["概念混淆", "贝叶斯公式"]}, "source": "inferred", "confidence": 0.75, "updated_at": "2026-05-10T09:00:00+08:00"}]', '{"goal": "期末85分", "style": ["代码实例", "可视化"], "weak_top": ["贝叶斯公式", "A*搜索"], "minutes_per_day": 60}', '["chat-001", "chat-002"]', '2026-05-10 19:15:25');
INSERT INTO profile_versions VALUES ('pv-002', 'user-test-001', 'course-ai-001', 2, '[{"key": "major_context", "label": "专业上下文", "layer": "auxiliary", "value": {"major": "计算机", "course": "AI导论", "current_chapter": "第3章"}, "source": "explicit", "confidence": 0.95, "updated_at": "2026-05-10T09:15:00+08:00"}, {"key": "knowledge_basis", "label": "知识基础", "layer": "core", "value": {"weak": ["贝叶斯公式"], "strong": ["概率基础", "线性回归"]}, "source": "explicit", "confidence": 0.88, "updated_at": "2026-05-10T09:15:00+08:00"}, {"key": "learning_goal", "label": "学习目标", "layer": "core", "value": {"target": "期末85分", "deadline": "2026-06-30", "sub_goals": ["掌握搜索算法", "理解贝叶斯分类器"]}, "source": "explicit", "confidence": 0.9, "updated_at": "2026-05-10T09:15:00+08:00"}, {"key": "cognitive_style", "label": "认知偏好", "layer": "style", "value": {"avoid": ["纯公式推导"], "style": ["代码实例", "可视化", "动画演示"]}, "source": "inferred", "confidence": 0.72, "updated_at": "2026-05-10T09:15:00+08:00"}, {"key": "learning_pace", "label": "学习节奏", "layer": "style", "value": {"urgency": "high", "days_per_week": 5, "minutes_per_day": 60}, "source": "explicit", "confidence": 0.8, "updated_at": "2026-05-10T09:15:00+08:00"}, {"key": "interest_direction", "label": "兴趣方向", "layer": "auxiliary", "value": {"topics": ["推荐系统", "计算机视觉"], "applications": ["医疗AI"]}, "source": "inferred", "confidence": 0.6, "updated_at": "2026-05-10T09:15:00+08:00"}, {"key": "error_pattern", "label": "错误模式", "layer": "auxiliary", "value": {"tags": ["概念混淆", "贝叶斯公式", "梯度计算"]}, "source": "inferred", "confidence": 0.78, "updated_at": "2026-05-10T09:15:00+08:00"}]', '{"goal": "期末85分", "style": ["代码实例", "可视化"], "weak_top": ["贝叶斯公式"], "minutes_per_day": 60}', '["chat-003"]', '2026-05-10 19:15:25');
INSERT INTO profile_versions VALUES ('pv-003', 'user-test-001', 'course-ai-001', 3, '[{"key": "major_context", "label": "专业上下文", "layer": "auxiliary", "value": {"major": "计算机", "course": "AI导论", "current_chapter": "第4章"}, "source": "explicit", "confidence": 0.95, "updated_at": "2026-05-10T09:30:00+08:00"}, {"key": "knowledge_basis", "label": "知识基础", "layer": "core", "value": {"weak": ["贝叶斯公式"], "strong": ["概率基础", "线性回归", "A*搜索"]}, "source": "explicit", "confidence": 0.9, "updated_at": "2026-05-10T09:30:00+08:00"}, {"key": "learning_goal", "label": "学习目标", "layer": "core", "value": {"target": "期末85分", "deadline": "2026-06-30", "sub_goals": ["掌握搜索算法", "理解贝叶斯分类器", "完成课程项目"]}, "source": "explicit", "confidence": 0.92, "updated_at": "2026-05-10T09:30:00+08:00"}, {"key": "cognitive_style", "label": "认知偏好", "layer": "style", "value": {"avoid": ["纯公式推导"], "style": ["代码实例", "可视化", "动画演示"]}, "source": "inferred", "confidence": 0.75, "updated_at": "2026-05-10T09:30:00+08:00"}, {"key": "learning_pace", "label": "学习节奏", "layer": "style", "value": {"urgency": "high", "days_per_week": 6, "minutes_per_day": 90}, "source": "inferred", "confidence": 0.82, "updated_at": "2026-05-10T09:30:00+08:00"}, {"key": "interest_direction", "label": "兴趣方向", "layer": "auxiliary", "value": {"topics": ["推荐系统", "计算机视觉", "强化学习"], "applications": ["医疗AI"]}, "source": "inferred", "confidence": 0.65, "updated_at": "2026-05-10T09:30:00+08:00"}, {"key": "error_pattern", "label": "错误模式", "layer": "auxiliary", "value": {"tags": ["概念混淆", "贝叶斯公式"]}, "source": "inferred", "confidence": 0.8, "updated_at": "2026-05-10T09:30:00+08:00"}]', '{"goal": "期末85分", "style": ["代码实例", "可视化", "动画演示"], "weak_top": ["贝叶斯公式"], "minutes_per_day": 90}', '["chat-004"]', '2026-05-10 19:15:25');

-- 11.6 画像对话记录（部分关键数据）
INSERT INTO profile_chats VALUES ('chat-001', 'user-test-001', 'course-ai-001', 'pv-001', '[{"at": "2026-05-10T08:55:00+08:00", "role": "system", "content": "你好，我来帮你了解你的学习情况。你是什么专业的？"}, {"at": "2026-05-10T08:56:00+08:00", "role": "user", "content": "我是计算机专业的，这学期在学人工智能导论"}, {"at": "2026-05-10T08:57:00+08:00", "role": "system", "content": "了解了。你觉得自己哪些知识点比较有把握，哪些比较薄弱？"}, {"at": "2026-05-10T08:58:00+08:00", "role": "user", "content": "概率基础还行，但贝叶斯公式和A*搜索不太懂"}]', '2026-05-10 19:15:25');
INSERT INTO profile_chats VALUES ('chat-004', 'user-test-001', 'course-ai-001', 'pv-003', '[{"at": "2026-05-10T09:25:00+08:00", "role": "system", "content": "最近学习进度怎么样？"}, {"at": "2026-05-10T09:27:00+08:00", "role": "user", "content": "A*搜索现在搞懂了，但贝叶斯还是有点模糊，另外我对强化学习很感兴趣"}]', '2026-05-10 19:15:25');
INSERT INTO profile_chats VALUES ('591bfca35c3d5f52cb42488e8dd633bd', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[{"at": "2026-05-11T12:21:32.7265588", "role": "user", "content": "你好"}, {"at": "2026-05-11T12:21:36.645519", "role": "assistant", "content": "你好呀！很高兴见到你！😊 我是你的学习画像分析助手，将和你一起探索《人工智能导论》这门课程的学习之旅。"}]', '2026-05-11 12:17:15');

-- 11.7 任务数据
INSERT INTO tasks VALUES ('task-001', 'user-test-001', 'course-ai-001', 'resource_generate', '贝叶斯分类器', '["doc", "quiz", "code"]', 'pv-003', 'SUCCEEDED', 'PUBLISHING', 100, NULL, NULL, '2026-05-10 19:15:25', NULL, NULL);
INSERT INTO tasks VALUES ('task-002', 'user-test-001', 'course-ai-001', 'resource_generate', 'A*搜索', '["doc", "mindmap", "quiz"]', 'pv-003', 'RUNNING', 'GENERATING', 55, NULL, NULL, '2026-05-10 19:15:25', NULL, NULL);
INSERT INTO tasks VALUES ('task-003', 'user-demo-001', 'course-ai-001', 'path_generate', 'AI导论-全局路径', '["doc", "mindmap", "quiz", "reading", "code"]', 'pv-003', 'PENDING', NULL, 0, NULL, NULL, '2026-05-10 19:15:25', NULL, NULL);

-- 11.8 任务事件
INSERT INTO task_events VALUES ('te-001', 'task-001', 'task.accepted', '{"topic": "贝叶斯分类器", "types": ["doc", "quiz", "code"], "task_id": "task-001"}', '2026-05-10 19:15:25');
INSERT INTO task_events VALUES ('te-002', 'task-001', 'task.stage', '{"stage": "RETRIEVING", "message": "检索证据中", "percent": 15}', '2026-05-10 19:15:25');
INSERT INTO task_events VALUES ('te-003', 'task-001', 'task.stage', '{"stage": "PLANNING", "message": "Planner 做出决策", "percent": 35}', '2026-05-10 19:15:25');
INSERT INTO task_events VALUES ('te-004', 'task-001', 'task.stage', '{"stage": "GENERATING", "message": "生成资源中", "percent": 50}', '2026-05-10 19:15:25');
INSERT INTO task_events VALUES ('te-005', 'task-001', 'resource.ready', '{"type": "doc", "title": "贝叶斯分类器精讲", "resource_id": "res-001"}', '2026-05-10 19:15:25');
INSERT INTO task_events VALUES ('te-006', 'task-001', 'resource.ready', '{"type": "quiz", "title": "贝叶斯分类器练习", "resource_id": "res-002"}', '2026-05-10 19:15:25');
INSERT INTO task_events VALUES ('te-007', 'task-001', 'resource.ready', '{"type": "code", "title": "贝叶斯分类器代码实现", "resource_id": "res-003"}', '2026-05-10 19:15:25');
INSERT INTO task_events VALUES ('te-008', 'task-001', 'task.done', '{"pack_id": "pack-001"}', '2026-05-10 19:15:25');

-- 11.9 Agent思考链
INSERT INTO agent_thinking_traces VALUES ('att-001', 'task-001', 'Retriever', 'RAG检索', 'RETRIEVING', '用户 query="贝叶斯分类器 概念定义 核心原理"', 'Milvus 检索返回 15 个候选 chunk，top-8 中 relevance>0.6 的有 6 个', '来源覆盖较好：3 条讲义 + 2 条术语 + 1 条题库。但缺少代码示例来源，需标记为 low confidence 提醒 Planner', '返回 8 条 sources，标记 code 类型证据不足', 'medium', 'system_prompt', NULL, '2026-05-10 19:15:25');
INSERT INTO agent_thinking_traces VALUES ('att-002', 'task-001', 'Planner', '规划编排', 'PLANNING', '画像: weak_top=["贝叶斯公式"], style=["代码实例","可视化"]; 证据: 8 条来源', '弱项聚焦+代码偏好+应试目标 → 优先 doc+quiz+code', '根据弱项匹配(0.30)和风格亲和(0.25)评分：doc=0.88, quiz=0.82, code=0.78, mindmap=0.72, reading=0.65。取 top-3', '选择 [doc, quiz, code]，难度 medium，预估 45min', 'high', 'system_prompt', NULL, '2026-05-10 19:15:25');

-- 11.10 资源包
INSERT INTO resource_packs VALUES ('pack-001', 'user-test-001', 'course-ai-001', '贝叶斯分类器', 'pv-003', 'task-001', '["针对薄弱点: 贝叶斯公式", "偏好: 代码实例", "节奏: 15分钟/天"]', NULL, '2026-05-10 19:15:25');


-- ============================================================
-- 最终完成提示
-- ============================================================
SELECT '✅ 数据库重建完成！所有表结构和测试数据已按照 v2.0 schema 导入。' AS message;
SELECT COUNT(*) AS total_tables FROM information_schema.tables WHERE table_schema = 'learnthink';
SELECT COUNT(*) AS total_users FROM users;
SELECT COUNT(*) AS total_courses FROM courses;
SELECT COUNT(*) AS total_tasks FROM tasks;

-- ============================================================
-- 学思伴行（LearnThink Companion）MySQL 8.0 数据库初始化脚本
-- 版本：v2.0
-- 日期：2026-05-10
-- 用法：mysql -u root -p < sql/schema.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS learnthink DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE learnthink;


-- ============================================================
-- 1. 用户与鉴权
-- ============================================================
CREATE TABLE IF NOT EXISTS users (
    id CHAR(36) PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) DEFAULT 'student' COMMENT 'student / admin',
    display_name VARCHAR(50) COMMENT '显示昵称',
    avatar_url VARCHAR(500) COMMENT '头像 OSS URL',
    bio VARCHAR(200) COMMENT '个人简介',
    major VARCHAR(100) COMMENT '专业方向',
    grade VARCHAR(20) COMMENT '年级',
    phone VARCHAR(20) COMMENT '手机号（预留）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ============================================================
-- 1.5 课程
-- ============================================================
CREATE TABLE IF NOT EXISTS courses (
    id CHAR(36) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    emoji VARCHAR(10) DEFAULT '📚' COMMENT '课程图标',
    deleted_at DATETIME DEFAULT NULL COMMENT '逻辑删除时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- 用户-课程选课关系（M:N）
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


-- ============================================================
-- 2. 画像（版本化）
-- ============================================================
-- 画像主表：每个用户在每个课程下有一份画像，仅存最新指针
CREATE TABLE IF NOT EXISTS profiles (
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
CREATE TABLE IF NOT EXISTS profile_versions (
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
CREATE TABLE IF NOT EXISTS profile_chats (
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
-- 3. 任务编排与事件（多智能体流水线）
-- ============================================================
-- 任务主表
CREATE TABLE IF NOT EXISTS tasks (
    id CHAR(36) PRIMARY KEY COMMENT 'task_id',
    user_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    task_type VARCHAR(50) COMMENT 'resource_generate / path_generate',
    topic VARCHAR(200),
    requested_resource_types JSON,
    profile_version_id CHAR(36) COMMENT '生成时所依据的画像版本',
    chat_id CHAR(36) COMMENT '关联的对话会话ID',
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
    INDEX idx_task_chat (chat_id),
    CONSTRAINT fk_tasks_profiles FOREIGN KEY (user_id, course_id)
        REFERENCES profiles(user_id, course_id) ON DELETE RESTRICT,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT,
    FOREIGN KEY (profile_version_id) REFERENCES profile_versions(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- 任务事件日志（可回放流水线）
CREATE TABLE IF NOT EXISTS task_events (
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
    trigger VARCHAR(30) COMMENT 'autonomous / response_to_agent / system_prompt',
    in_response_to CHAR(36) COMMENT '回复目标 trace ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_att_task (task_id),
    INDEX idx_att_task_agent (task_id, agent_name),
    INDEX idx_att_task_created (task_id, created_at),
    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- Agent 间协作消息
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
-- 4. 资源包与资源项
-- ============================================================
-- 资源包主表
CREATE TABLE IF NOT EXISTS resource_packs (
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
CREATE TABLE IF NOT EXISTS resource_items (
    id CHAR(36) PRIMARY KEY,
    pack_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    type VARCHAR(30) COMMENT 'doc / quiz / mindmap / reading / code / video',
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
CREATE TABLE IF NOT EXISTS review_records (
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
-- 5. 学习路径（版本化）
-- ============================================================
-- 路径主表（当前指针）
CREATE TABLE IF NOT EXISTS learning_paths (
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
CREATE TABLE IF NOT EXISTS learning_path_versions (
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
-- 6. 做题与学习行为
-- ============================================================
-- 做题记录
CREATE TABLE IF NOT EXISTS quiz_attempts (
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
CREATE TABLE IF NOT EXISTS learning_events (
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
-- 7. 知识库元数据
-- ============================================================
CREATE TABLE IF NOT EXISTS knowledge_documents (
    id CHAR(36) PRIMARY KEY,
    course_id CHAR(36) NOT NULL,
    title VARCHAR(500),
    source_type VARCHAR(50) COMMENT 'lecture / glossary / exercise_bank / reading',
    file_path VARCHAR(500),
    parent_id CHAR(36) DEFAULT NULL COMMENT '父文档ID，关联教材PDF/MD → 讲义章节',
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
-- 8. 课程知识图谱与画像锚定（v3.0）
-- ============================================================
CREATE TABLE IF NOT EXISTS course_knowledge_points (
    id CHAR(36) PRIMARY KEY,
    course_id CHAR(36) NOT NULL,
    parent_id CHAR(36),
    name VARCHAR(200) NOT NULL,
    kp_type ENUM('course','chapter','section','concept','skill') NOT NULL,
    scope ENUM('core_curriculum','prerequisite','supplementary') NOT NULL DEFAULT 'core_curriculum',
    depth INT DEFAULT 0,
    sort_order INT DEFAULT 0,
    description TEXT,
    learning_objectives JSON,
    difficulty INT DEFAULT 3 COMMENT '1-5',
    estimated_minutes INT,
    prerequisite_kps JSON COMMENT 'pre-KP IDs',
    related_kps JSON COMMENT 'related KP IDs',
    keywords JSON COMMENT 'fuzzy match keywords',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_ckp_course (course_id),
    INDEX idx_ckp_parent (parent_id),
    INDEX idx_ckp_scope (course_id, scope),
    CONSTRAINT chk_kp_type_scope CHECK (
        (kp_type IN ('chapter', 'section') AND scope IN ('core_curriculum', 'prerequisite'))
        OR (kp_type = 'concept' AND scope IN ('core_curriculum', 'prerequisite', 'supplementary'))
        OR (kp_type IN ('course', 'skill'))
    ),
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE,
    FOREIGN KEY (parent_id) REFERENCES course_knowledge_points(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS profile_kp_anchors (
    id CHAR(36) PRIMARY KEY,
    profile_version_id CHAR(36) NOT NULL,
    kp_id CHAR(36) COMMENT 'NULL = extracurricular',
    dimension_key VARCHAR(50) NOT NULL COMMENT 'knowledge_basis / interest_direction / error_pattern',
    relation_type ENUM('strong','weak','interest','error_prone') NOT NULL,
    scope_at_anchor ENUM('core_curriculum','prerequisite','supplementary','extracurricular') NOT NULL,
    confidence DECIMAL(3,2) NOT NULL DEFAULT 0.50,
    source VARCHAR(20) NOT NULL DEFAULT 'inferred' COMMENT 'explicit / inferred / quiz_result',
    match_method VARCHAR(20) NOT NULL DEFAULT 'fuzzy' COMMENT 'fuzzy / embedding / llm_direct / keyword',
    label_text VARCHAR(300) COMMENT 'original label from profile',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_pka_pv (profile_version_id),
    INDEX idx_pka_kp (kp_id),
    INDEX idx_pka_pv_dim (profile_version_id, dimension_key),
    CONSTRAINT chk_pka_confidence CHECK (confidence BETWEEN 0.50 AND 1.0),
    FOREIGN KEY (profile_version_id) REFERENCES profile_versions(id) ON DELETE CASCADE,
    FOREIGN KEY (kp_id) REFERENCES course_knowledge_points(id) ON DELETE SET NULL,
    UNIQUE KEY uk_pka (profile_version_id, kp_id, dimension_key, relation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS document_kp_links (
    id CHAR(36) PRIMARY KEY,
    doc_id CHAR(36) NOT NULL,
    kp_id CHAR(36) NOT NULL,
    relevance DECIMAL(3,2) DEFAULT 1.0 COMMENT 'how central this doc is to the KP',
    UNIQUE KEY uk_dkl (doc_id, kp_id),
    FOREIGN KEY (doc_id) REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    FOREIGN KEY (kp_id) REFERENCES course_knowledge_points(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ============================================================
-- 9. 用户通知与学习统计（v4.0）
-- ============================================================

-- 消息通知（含 SSE 推送可靠性标记）
CREATE TABLE IF NOT EXISTS notifications (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    type VARCHAR(30) COMMENT 'resource_ready / review_flag / profile_updated / task_done',
    title VARCHAR(200),
    message TEXT,
    is_read TINYINT(1) DEFAULT 0,
    is_pushed TINYINT(1) DEFAULT 0 COMMENT '是否已通过 SSE 推送成功（0=未推送或推送失败）',
    pushed_at DATETIME COMMENT 'SSE 推送成功时间',
    ref_id CHAR(36) COMMENT '关联资源包/任务/画像版本 ID',
    ref_type VARCHAR(30) COMMENT 'pack / task / profile_version',
    payload_json JSON,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_notif_user_read (user_id, is_read),
    INDEX idx_notif_user_pushed (user_id, is_pushed),
    INDEX idx_notif_user_created (user_id, created_at),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- 学习统计汇总（预聚合，API 直接查汇总表，避免实时跨表计算）
CREATE TABLE IF NOT EXISTS user_stats (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    total_learning_minutes INT DEFAULT 0,
    total_resource_packs INT DEFAULT 0,
    total_quiz_attempts INT DEFAULT 0,
    total_quiz_score_avg DECIMAL(5,2),
    path_mastered_nodes INT DEFAULT 0,
    path_total_nodes INT DEFAULT 0,
    current_weak_count INT DEFAULT 0,
    prev_weak_count INT DEFAULT 0,
    profile_version INT DEFAULT 0,
    week_learning_minutes INT DEFAULT 0,
    week_resource_packs INT DEFAULT 0,
    week_quiz_attempts INT DEFAULT 0,
    weekly_activity_json JSON,
    calculated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_stats_user_course (user_id, course_id),
    INDEX idx_stats_user (user_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

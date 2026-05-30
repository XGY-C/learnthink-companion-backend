/*
 Navicat Premium Dump SQL

 Source Server         : xgysql
 Source Server Type    : MySQL
 Source Server Version : 80042 (8.0.42)
 Source Host           : localhost:3306
 Source Schema         : learnthink

 Target Server Type    : MySQL
 Target Server Version : 80042 (8.0.42)
 File Encoding         : 65001

 Date: 24/05/2026 11:45:03
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for agent_messages
-- ----------------------------
DROP TABLE IF EXISTS `agent_messages`;
CREATE TABLE `agent_messages`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `task_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `from_agent` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `to_agent` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'NULL = 广播',
  `agent_role` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `action` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'coverage_report / plan_adjusted / revision_request / revision_applied / revision_approved / parallel_dispatch / fallback_decision',
  `message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `detail_json` json NULL COMMENT '结构化详情',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_am_task`(`task_id` ASC) USING BTREE,
  INDEX `idx_am_task_from`(`task_id` ASC, `from_agent` ASC) USING BTREE,
  CONSTRAINT `agent_messages_ibfk_1` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for agent_thinking_traces
-- ----------------------------
DROP TABLE IF EXISTS `agent_thinking_traces`;
CREATE TABLE `agent_thinking_traces`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `task_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '任务ID（任务流使用，对话流为 NULL）',
  `agent_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'Agent 标识：Retriever / Planner / Generator / Reviewer',
  `agent_role` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'Agent 角色描述',
  `phase` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '流水线阶段',
  `context` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '输入上下文摘要',
  `observation` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '观察',
  `thought` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '思考过程',
  `decision` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '决策结论',
  `confidence_level` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'high / medium / low',
  `round_num` int NULL DEFAULT NULL COMMENT '对应对话轮次（从1开始），用于历史消息重建思考链',
  `trigger` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'autonomous / response_to_agent / system_prompt',
  `in_response_to` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '回复目标 trace ID',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `chat_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '会话ID（对话流使用，任务流为 NULL）',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_att_task`(`task_id` ASC) USING BTREE,
  INDEX `idx_att_task_agent`(`task_id` ASC, `agent_name` ASC) USING BTREE,
  INDEX `idx_att_task_created`(`task_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_att_chat`(`chat_id` ASC) USING BTREE,
  INDEX `idx_att_chat_round`(`chat_id` ASC, `round_num` ASC) USING BTREE,
  CONSTRAINT `agent_thinking_traces_ibfk_1` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_att_chat` FOREIGN KEY (`chat_id`) REFERENCES `profile_chats` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for book_info
-- ----------------------------
DROP TABLE IF EXISTS `book_info`;
CREATE TABLE `book_info`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `document_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '关联父文档ID（PDF/MD教材）',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '书名',
  `author` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '作者',
  `introduction` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '内容简介（内容提要/内容简介/前言）',
  `toc` json NULL COMMENT '目录结构 [{title, chapterIndex}]',
  `extracted_at` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提取时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_document_id`(`document_id` ASC) USING BTREE,
  CONSTRAINT `book_info_ibfk_1` FOREIGN KEY (`document_id`) REFERENCES `knowledge_documents` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '书籍基本信息' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for course_knowledge_points
-- ----------------------------
DROP TABLE IF EXISTS `course_knowledge_points`;
CREATE TABLE `course_knowledge_points`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `parent_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `kp_type` enum('course','chapter','section','concept','skill') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `scope` enum('core_curriculum','prerequisite','supplementary') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'core_curriculum',
  `depth` int NULL DEFAULT 0,
  `sort_order` int NULL DEFAULT 0,
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `learning_objectives` json NULL,
  `difficulty` int NULL DEFAULT 3 COMMENT '1-5',
  `estimated_minutes` int NULL DEFAULT NULL,
  `prerequisite_kps` json NULL COMMENT '前置KP ID列表',
  `related_kps` json NULL COMMENT '弱关联KP ID列表',
  `keywords` json NULL COMMENT '模糊匹配用关键词',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_ckp_course`(`course_id` ASC) USING BTREE,
  INDEX `idx_ckp_parent`(`parent_id` ASC) USING BTREE,
  INDEX `idx_ckp_scope`(`course_id` ASC, `scope` ASC) USING BTREE,
  CONSTRAINT `course_knowledge_points_ibfk_1` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `course_knowledge_points_ibfk_2` FOREIGN KEY (`parent_id`) REFERENCES `course_knowledge_points` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `chk_kp_type_scope` CHECK (((`kp_type` in (_utf8mb4'chapter',_utf8mb4'section')) and (`scope` in (_utf8mb4'core_curriculum',_utf8mb4'prerequisite'))) or ((`kp_type` = _utf8mb4'concept') and (`scope` in (_utf8mb4'core_curriculum',_utf8mb4'prerequisite',_utf8mb4'supplementary'))) or (`kp_type` in (_utf8mb4'course',_utf8mb4'skill')))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for courses
-- ----------------------------
DROP TABLE IF EXISTS `courses`;
CREATE TABLE `courses`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `emoji` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '?' COMMENT '课程图标',
  `grade` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '适用年级',
  `subject` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '学科分类',
  `enabled` tinyint(1) NULL DEFAULT 1 COMMENT '启用/停用',
  `deleted_at` datetime NULL DEFAULT NULL COMMENT '逻辑删除时间',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for document_kp_links
-- ----------------------------
DROP TABLE IF EXISTS `document_kp_links`;
CREATE TABLE `document_kp_links`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `doc_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `kp_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `relevance` decimal(3, 2) NULL DEFAULT 1.00 COMMENT '该文档对此KP的核心程度',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_dkl`(`doc_id` ASC, `kp_id` ASC) USING BTREE,
  INDEX `kp_id`(`kp_id` ASC) USING BTREE,
  CONSTRAINT `document_kp_links_ibfk_1` FOREIGN KEY (`doc_id`) REFERENCES `knowledge_documents` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `document_kp_links_ibfk_2` FOREIGN KEY (`kp_id`) REFERENCES `course_knowledge_points` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for knowledge_documents
-- ----------------------------
DROP TABLE IF EXISTS `knowledge_documents`;
CREATE TABLE `knowledge_documents`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `title` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `source_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'lecture / glossary / exercise_bank / reading',
  `file_path` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `parent_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `doc_hash` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'SHA-256 文件哈希，用于增量索引去重',
  `chunk_count` int NULL DEFAULT 0 COMMENT '切分后的 chunk 数量',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_kd_course_hash`(`course_id` ASC, `doc_hash` ASC) USING BTREE,
  INDEX `idx_kd_course`(`course_id` ASC) USING BTREE,
  INDEX `idx_kd_course_type`(`course_id` ASC, `source_type` ASC) USING BTREE,
  INDEX `idx_kd_parent`(`parent_id` ASC) USING BTREE,
  CONSTRAINT `knowledge_documents_ibfk_1` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for learning_events
-- ----------------------------
DROP TABLE IF EXISTS `learning_events`;
CREATE TABLE `learning_events`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '可为空：非课程特定事件（如登录）不关联课程',
  `event_type` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'resource_opened / quiz_submitted / node_completed / resource_shared',
  `payload_json` json NULL,
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_le_user_event`(`user_id` ASC, `event_type` ASC) USING BTREE,
  INDEX `idx_le_user_created`(`user_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_le_user_course_created`(`user_id` ASC, `course_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `course_id`(`course_id` ASC) USING BTREE,
  CONSTRAINT `learning_events_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `learning_events_ibfk_2` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for learning_path_versions
-- ----------------------------
DROP TABLE IF EXISTS `learning_path_versions`;
CREATE TABLE `learning_path_versions`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `version` int NOT NULL,
  `generated_from_profile_version_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `path_json` json NULL COMMENT '{nodes: [], edges: [], adjustments: []}',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_lpv_user_course_version`(`user_id` ASC, `course_id` ASC, `version` ASC) USING BTREE,
  INDEX `idx_lpv_user`(`user_id` ASC) USING BTREE,
  INDEX `idx_lpv_user_course`(`user_id` ASC, `course_id` ASC) USING BTREE,
  INDEX `course_id`(`course_id` ASC) USING BTREE,
  INDEX `generated_from_profile_version_id`(`generated_from_profile_version_id` ASC) USING BTREE,
  CONSTRAINT `fk_lpv_paths` FOREIGN KEY (`user_id`, `course_id`) REFERENCES `learning_paths` (`user_id`, `course_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `learning_path_versions_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `learning_path_versions_ibfk_2` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `learning_path_versions_ibfk_3` FOREIGN KEY (`generated_from_profile_version_id`) REFERENCES `profile_versions` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for learning_paths
-- ----------------------------
DROP TABLE IF EXISTS `learning_paths`;
CREATE TABLE `learning_paths`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `current_version` int NULL DEFAULT 0,
  `deleted_at` datetime NULL DEFAULT NULL COMMENT '软删除时间（NULL=未删除）',
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_lp_user_course`(`user_id` ASC, `course_id` ASC) USING BTREE,
  INDEX `idx_lp_user`(`user_id` ASC) USING BTREE,
  INDEX `course_id`(`course_id` ASC) USING BTREE,
  CONSTRAINT `fk_lp_profiles` FOREIGN KEY (`user_id`, `course_id`) REFERENCES `profiles` (`user_id`, `course_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `learning_paths_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `learning_paths_ibfk_2` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for learning_plan_versions
-- ----------------------------
DROP TABLE IF EXISTS `learning_plan_versions`;
CREATE TABLE `learning_plan_versions`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `plan_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `version` int NOT NULL,
  `generated_from_profile_version_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '关联画像版本',
  `plan_json` json NULL COMMENT '{modules: [], edges: [], summary: {}}',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_lpv2_user_course_version`(`user_id` ASC, `course_id` ASC, `version` ASC) USING BTREE,
  INDEX `idx_lpv2_plan`(`plan_id` ASC) USING BTREE,
  INDEX `idx_lpv2_user`(`user_id` ASC) USING BTREE,
  INDEX `course_id`(`course_id` ASC) USING BTREE,
  CONSTRAINT `learning_plan_versions_ibfk_1` FOREIGN KEY (`plan_id`) REFERENCES `learning_plans` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `learning_plan_versions_ibfk_2` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `learning_plan_versions_ibfk_3` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for learning_plans
-- ----------------------------
DROP TABLE IF EXISTS `learning_plans`;
CREATE TABLE `learning_plans`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `profile_version` int NOT NULL COMMENT '生成时锁定的画像版本',
  `current_version` int NULL DEFAULT 1,
  `plan_json` json NULL COMMENT '{modules: [], edges: [], summary: {}}',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'generating' COMMENT 'generating / ready / archived',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_lplan_user_course`(`user_id` ASC, `course_id` ASC) USING BTREE,
  INDEX `idx_lplan_user`(`user_id` ASC) USING BTREE,
  INDEX `idx_lplan_status`(`status` ASC) USING BTREE,
  INDEX `course_id`(`course_id` ASC) USING BTREE,
  CONSTRAINT `learning_plans_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `learning_plans_ibfk_2` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for notifications
-- ----------------------------
DROP TABLE IF EXISTS `notifications`;
CREATE TABLE `notifications`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `type` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'resource_ready / review_flag / profile_updated / task_done',
  `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `is_read` tinyint(1) NULL DEFAULT 0,
  `is_pushed` tinyint(1) NULL DEFAULT 0 COMMENT '是否已通过 SSE 推送成功（0=未推送或推送失败）',
  `pushed_at` datetime NULL DEFAULT NULL COMMENT 'SSE 推送成功时间',
  `ref_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '关联资源包/任务/画像版本 ID',
  `ref_type` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'pack / task / profile_version',
  `payload_json` json NULL,
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_notif_user_read`(`user_id` ASC, `is_read` ASC) USING BTREE,
  INDEX `idx_notif_user_pushed`(`user_id` ASC, `is_pushed` ASC) USING BTREE,
  INDEX `idx_notif_user_created`(`user_id` ASC, `created_at` ASC) USING BTREE,
  CONSTRAINT `notifications_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for profile_chats
-- ----------------------------
DROP TABLE IF EXISTS `profile_chats`;
CREATE TABLE `profile_chats`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `profile_version_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '处理完成后关联的画像版本（NULL=未处理）',
  `messages_json` json NULL COMMENT '[{role, content, at}]',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_pc_user`(`user_id` ASC) USING BTREE,
  INDEX `idx_pc_user_course`(`user_id` ASC, `course_id` ASC) USING BTREE,
  INDEX `idx_pc_profile_version`(`profile_version_id` ASC) USING BTREE,
  INDEX `course_id`(`course_id` ASC) USING BTREE,
  CONSTRAINT `profile_chats_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `profile_chats_ibfk_2` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `profile_chats_ibfk_3` FOREIGN KEY (`profile_version_id`) REFERENCES `profile_versions` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for profile_kp_anchors
-- ----------------------------
DROP TABLE IF EXISTS `profile_kp_anchors`;
CREATE TABLE `profile_kp_anchors`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `profile_version_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `kp_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'NULL = extracurricular (不在课程KP树中)',
  `dimension_key` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'knowledge_basis / interest_direction / error_pattern',
  `relation_type` enum('strong','weak','interest','error_prone') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `scope_at_anchor` enum('core_curriculum','prerequisite','supplementary','extracurricular') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `confidence` decimal(3, 2) NOT NULL DEFAULT 0.50,
  `source` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'inferred' COMMENT 'explicit / inferred / quiz_result',
  `match_method` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'fuzzy' COMMENT 'fuzzy / embedding / llm_direct / keyword',
  `label_text` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '画像原文中的标签文本',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_pka`(`profile_version_id` ASC, `kp_id` ASC, `dimension_key` ASC, `relation_type` ASC) USING BTREE,
  INDEX `idx_pka_pv`(`profile_version_id` ASC) USING BTREE,
  INDEX `idx_pka_kp`(`kp_id` ASC) USING BTREE,
  INDEX `idx_pka_pv_dim`(`profile_version_id` ASC, `dimension_key` ASC) USING BTREE,
  CONSTRAINT `profile_kp_anchors_ibfk_1` FOREIGN KEY (`profile_version_id`) REFERENCES `profile_versions` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `profile_kp_anchors_ibfk_2` FOREIGN KEY (`kp_id`) REFERENCES `course_knowledge_points` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `chk_pka_confidence` CHECK (`confidence` between 0.50 and 1.0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for profile_versions
-- ----------------------------
DROP TABLE IF EXISTS `profile_versions`;
CREATE TABLE `profile_versions`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `version` int NOT NULL,
  `dimensions_json` json NULL COMMENT '7维画像数据（v2：不含 assessment_summary）',
  `summary_json` json NULL COMMENT '供 Planner 使用的压缩摘要',
  `source_chat_ids` json NULL COMMENT '关联对话记录 ID 列表',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_pv_user_course_version`(`user_id` ASC, `course_id` ASC, `version` ASC) USING BTREE,
  INDEX `idx_pv_user`(`user_id` ASC) USING BTREE,
  INDEX `idx_pv_user_course`(`user_id` ASC, `course_id` ASC) USING BTREE,
  INDEX `course_id`(`course_id` ASC) USING BTREE,
  CONSTRAINT `fk_pv_profiles` FOREIGN KEY (`user_id`, `course_id`) REFERENCES `profiles` (`user_id`, `course_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `profile_versions_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `profile_versions_ibfk_2` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for profiles
-- ----------------------------
DROP TABLE IF EXISTS `profiles`;
CREATE TABLE `profiles`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `current_version` int NULL DEFAULT 0,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_profiles_user_course`(`user_id` ASC, `course_id` ASC) USING BTREE,
  INDEX `idx_profiles_user`(`user_id` ASC) USING BTREE,
  INDEX `idx_profiles_course`(`course_id` ASC) USING BTREE,
  CONSTRAINT `profiles_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `profiles_ibfk_2` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for quiz_attempts
-- ----------------------------
DROP TABLE IF EXISTS `quiz_attempts`;
CREATE TABLE `quiz_attempts`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `topic` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `node_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '关联路径节点',
  `activity_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '关联 sub_plans activities',
  `pack_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `answers_json` json NULL COMMENT '用户作答',
  `score` decimal(5, 2) NULL DEFAULT NULL,
  `weak_tags` json NULL COMMENT '错误标签列表',
  `duration_seconds` int NULL DEFAULT NULL,
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_qa_user_topic`(`user_id` ASC, `topic` ASC) USING BTREE,
  INDEX `idx_qa_user_created`(`user_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_qa_user_course_created`(`user_id` ASC, `course_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `course_id`(`course_id` ASC) USING BTREE,
  INDEX `pack_id`(`pack_id` ASC) USING BTREE,
  INDEX `idx_qa_activity`(`activity_id` ASC) USING BTREE,
  CONSTRAINT `quiz_attempts_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `quiz_attempts_ibfk_2` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `quiz_attempts_ibfk_3` FOREIGN KEY (`pack_id`) REFERENCES `resource_packs` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for resource_items
-- ----------------------------
DROP TABLE IF EXISTS `resource_items`;
CREATE TABLE `resource_items`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `pack_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `task_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `type` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'doc / quiz / mindmap / reading / code / video',
  `title` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'pending' COMMENT 'pending / ready / failed / rejected',
  `content_ref` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '对象存储 key',
  `content_mime` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'text/markdown / application/json',
  `confidence` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `quality_score` decimal(3, 2) NULL DEFAULT NULL COMMENT '质量评分 0.00~100.00',
  `metadata_json` json NULL COMMENT '难度、标签、估时等扩展字段',
  `sources_json` json NULL COMMENT '证据列表 [{doc_id, title, chunk_id, quote, locator, relevance}]',
  `review_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'pending' COMMENT 'pending / approved / rejected',
  `review_summary` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `deleted_at` datetime NULL DEFAULT NULL COMMENT '软删除时间（NULL=未删除）',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `subtopic_index` int NOT NULL DEFAULT 0 COMMENT 'Sub-topic index (0 = topic-level,\r\n   1..N = sub-topic)',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_ri_pack`(`pack_id` ASC) USING BTREE,
  INDEX `idx_ri_task`(`task_id` ASC) USING BTREE,
  INDEX `idx_ri_pack_type_status`(`pack_id` ASC, `type` ASC, `status` ASC) USING BTREE,
  INDEX `idx_ri_status`(`status` ASC) USING BTREE,
  INDEX `idx_ri_type_status`(`type` ASC, `status` ASC) USING BTREE,
  INDEX `idx_resource_items_subtopic`(`pack_id` ASC, `subtopic_index` ASC) USING BTREE,
  CONSTRAINT `resource_items_ibfk_1` FOREIGN KEY (`pack_id`) REFERENCES `resource_packs` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `resource_items_ibfk_2` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for resource_packs
-- ----------------------------
DROP TABLE IF EXISTS `resource_packs`;
CREATE TABLE `resource_packs`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `topic` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `generated_from_profile_version_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `task_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `push_reason_json` json NULL COMMENT '个性化推送原因标签',
  `deleted_at` datetime NULL DEFAULT NULL COMMENT '软删除时间（NULL=未删除）',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_rp_user`(`user_id` ASC) USING BTREE,
  INDEX `idx_rp_user_created`(`user_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_rp_task`(`task_id` ASC) USING BTREE,
  INDEX `fk_rp_profiles`(`user_id` ASC, `course_id` ASC) USING BTREE,
  INDEX `course_id`(`course_id` ASC) USING BTREE,
  INDEX `generated_from_profile_version_id`(`generated_from_profile_version_id` ASC) USING BTREE,
  CONSTRAINT `fk_rp_profiles` FOREIGN KEY (`user_id`, `course_id`) REFERENCES `profiles` (`user_id`, `course_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `resource_packs_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `resource_packs_ibfk_2` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `resource_packs_ibfk_3` FOREIGN KEY (`generated_from_profile_version_id`) REFERENCES `profile_versions` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `resource_packs_ibfk_4` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for review_records
-- ----------------------------
DROP TABLE IF EXISTS `review_records`;
CREATE TABLE `review_records`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `resource_item_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `resource_pack_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `task_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `result` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'approved / rejected',
  `reasons_json` json NULL COMMENT '结构化驳回原因 [{type, detail}]',
  `citation_coverage` decimal(5, 2) NULL DEFAULT NULL COMMENT '引用覆盖率 0.00~100.00',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_rr_resource`(`resource_item_id` ASC) USING BTREE,
  INDEX `idx_rr_pack`(`resource_pack_id` ASC) USING BTREE,
  INDEX `idx_rr_task`(`task_id` ASC) USING BTREE,
  CONSTRAINT `review_records_item_fk` FOREIGN KEY (`resource_item_id`) REFERENCES `resource_items` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `review_records_pack_fk` FOREIGN KEY (`resource_pack_id`) REFERENCES `resource_packs` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `review_records_task_fk` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sub_plans
-- ----------------------------
DROP TABLE IF EXISTS `sub_plans`;
CREATE TABLE `sub_plans`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `plan_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `module_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '对应 plan_json.modules[].module_id',
  `version` int NULL DEFAULT 1,
  `sub_plan_json` json NULL COMMENT '{activities: [], adjustments: [], stats: {}, match_summary: {}}',
  `generation_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'pending' COMMENT 'pending / matching / generating / ready',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_sp_plan_module`(`plan_id` ASC, `module_id` ASC) USING BTREE,
  INDEX `idx_sp_plan`(`plan_id` ASC) USING BTREE,
  INDEX `idx_sp_generation`(`generation_status` ASC) USING BTREE,
  CONSTRAINT `sub_plans_ibfk_1` FOREIGN KEY (`plan_id`) REFERENCES `learning_plans` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for task_events
-- ----------------------------
DROP TABLE IF EXISTS `task_events`;
CREATE TABLE `task_events`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `task_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `event_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'task.accepted / task.stage / resource.ready / review.flag / agent.thought / agent.message / task.done / task.failed',
  `payload_json` json NULL,
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_te_task`(`task_id` ASC) USING BTREE,
  INDEX `idx_te_task_event`(`task_id` ASC, `event_type` ASC) USING BTREE,
  INDEX `idx_te_task_created`(`task_id` ASC, `created_at` ASC) USING BTREE,
  CONSTRAINT `task_events_ibfk_1` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for tasks
-- ----------------------------
DROP TABLE IF EXISTS `tasks`;
CREATE TABLE `tasks`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'task_id',
  `user_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `task_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'resource_generate / path_generate',
  `topic` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `requested_resource_types` json NULL,
  `profile_version_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '生成时所依据的画像版本',
  `chat_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '关联的对话会话ID',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED',
  `stage` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'PROFILING / RETRIEVING / PLANNING / GENERATING / REVIEWING / PUBLISHING',
  `percent` int NULL DEFAULT 0,
  `error_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `started_at` datetime NULL DEFAULT NULL,
  `finished_at` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_task_user_status`(`user_id` ASC, `status` ASC) USING BTREE,
  INDEX `idx_task_user_created`(`user_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_task_type`(`task_type` ASC) USING BTREE,
  INDEX `idx_task_stage`(`stage` ASC) USING BTREE,
  INDEX `idx_task_status`(`status` ASC) USING BTREE,
  INDEX `fk_tasks_profiles`(`user_id` ASC, `course_id` ASC) USING BTREE,
  INDEX `course_id`(`course_id` ASC) USING BTREE,
  INDEX `profile_version_id`(`profile_version_id` ASC) USING BTREE,
  INDEX `idx_task_chat`(`chat_id` ASC) USING BTREE,
  CONSTRAINT `fk_tasks_profiles` FOREIGN KEY (`user_id`, `course_id`) REFERENCES `profiles` (`user_id`, `course_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `tasks_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `tasks_ibfk_2` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `tasks_ibfk_3` FOREIGN KEY (`profile_version_id`) REFERENCES `profile_versions` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for user_course_enrollments
-- ----------------------------
DROP TABLE IF EXISTS `user_course_enrollments`;
CREATE TABLE `user_course_enrollments`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `enrolled_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_uce_user_course`(`user_id` ASC, `course_id` ASC) USING BTREE,
  INDEX `idx_uce_course`(`course_id` ASC) USING BTREE,
  CONSTRAINT `user_course_enrollments_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `user_course_enrollments_ibfk_2` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for user_stats
-- ----------------------------
DROP TABLE IF EXISTS `user_stats`;
CREATE TABLE `user_stats`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `total_learning_minutes` int NULL DEFAULT 0,
  `total_resource_packs` int NULL DEFAULT 0,
  `total_quiz_attempts` int NULL DEFAULT 0,
  `total_quiz_score_avg` decimal(5, 2) NULL DEFAULT NULL,
  `path_mastered_nodes` int NULL DEFAULT 0,
  `path_total_nodes` int NULL DEFAULT 0,
  `current_weak_count` int NULL DEFAULT 0,
  `prev_weak_count` int NULL DEFAULT 0,
  `profile_version` int NULL DEFAULT 0,
  `week_learning_minutes` int NULL DEFAULT 0,
  `week_resource_packs` int NULL DEFAULT 0,
  `week_quiz_attempts` int NULL DEFAULT 0,
  `weekly_activity_json` json NULL,
  `calculated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_stats_user_course`(`user_id` ASC, `course_id` ASC) USING BTREE,
  INDEX `idx_stats_user`(`user_id` ASC) USING BTREE,
  INDEX `course_id`(`course_id` ASC) USING BTREE,
  CONSTRAINT `user_stats_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `user_stats_ibfk_2` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for users
-- ----------------------------
DROP TABLE IF EXISTS `users`;
CREATE TABLE `users`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `email` varchar(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `password_hash` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `role` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'student' COMMENT 'student / admin',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `display_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '显示昵称',
  `avatar_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '头像 OSS URL',
  `bio` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '个人简介',
  `major` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '专业方向',
  `grade` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '年级',
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '手机号（预留）',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'enabled' COMMENT 'enabled / disabled',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `username`(`username` ASC) USING BTREE,
  UNIQUE INDEX `email`(`email` ASC) USING BTREE,
  INDEX `idx_users_email`(`email` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;

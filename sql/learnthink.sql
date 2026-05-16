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

 Date: 11/05/2026 12:58:11
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
  `to_agent` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'NULL = 骞挎挱',
  `agent_role` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `action` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'coverage_report / plan_adjusted / revision_request / revision_applied / revision_approved / parallel_dispatch / fallback_decision',
  `message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `detail_json` json NULL COMMENT '缁撴瀯鍖栬?鎯',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_am_task`(`task_id` ASC) USING BTREE,
  INDEX `idx_am_task_from`(`task_id` ASC, `from_agent` ASC) USING BTREE,
  CONSTRAINT `agent_messages_ibfk_1` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `agent_messages_ibfk_2` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of agent_messages
-- ----------------------------

-- ----------------------------
-- Table structure for agent_thinking_traces
-- ----------------------------
DROP TABLE IF EXISTS `agent_thinking_traces`;
CREATE TABLE `agent_thinking_traces`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `task_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `agent_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'Agent 鏍囪瘑锛歊etriever / Planner / Generator / Reviewer',
  `agent_role` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'Agent 瑙掕壊鎻忚堪',
  `phase` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '娴佹按绾块樁娈',
  `context` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '杈撳叆涓婁笅鏂囨憳瑕',
  `observation` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '瑙傚療',
  `thought` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '鎬濊?杩囩▼',
  `decision` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '鍐崇瓥缁撹?',
  `confidence_level` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'high / medium / low',
  `trigger` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'autonomous / response_to_agent / system_prompt',
  `in_response_to` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '鍥炲?鐩?爣 trace ID',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_att_task`(`task_id` ASC) USING BTREE,
  INDEX `idx_att_task_agent`(`task_id` ASC, `agent_name` ASC) USING BTREE,
  INDEX `idx_att_task_created`(`task_id` ASC, `created_at` ASC) USING BTREE,
  CONSTRAINT `agent_thinking_traces_ibfk_1` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `agent_thinking_traces_ibfk_2` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of agent_thinking_traces
-- ----------------------------
INSERT INTO `agent_thinking_traces` VALUES ('att-001', 'task-001', 'Retriever', 'RAG检索', 'RETRIEVING', '用户 query=\"贝叶斯分类器 概念定义 核心原理\"', 'Milvus 检索返回 15 个候选 chunk，top-8 中 relevance>0.6 的有 6 个', '来源覆盖较好：3 条讲义 + 2 条术语 + 1 条题库。但缺少代码示例来源，需标记为 low confidence 提醒 Planner', '返回 8 条 sources，标记 code 类型证据不足', 'medium', 'system_prompt', NULL, '2026-05-10 19:15:25');
INSERT INTO `agent_thinking_traces` VALUES ('att-002', 'task-001', 'Planner', '规划编排', 'PLANNING', '画像: weak_top=[\"贝叶斯公式\"], style=[\"代码实例\",\"可视化\"]; 证据: 8 条来源', '弱项聚焦+代码偏好+应试目标 → 优先 doc+quiz+code', '根据弱项匹配(0.30)和风格亲和(0.25)评分：doc=0.88, quiz=0.82, code=0.78, mindmap=0.72, reading=0.65。取 top-3', '选择 [doc, quiz, code]，难度 medium，预估 45min', 'high', 'system_prompt', NULL, '2026-05-10 19:15:25');

-- ----------------------------
-- Table structure for courses
-- ----------------------------
DROP TABLE IF EXISTS `courses`;
CREATE TABLE `courses`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of courses
-- ----------------------------
INSERT INTO `courses` VALUES ('course-ai-001', '人工智能导论', '大学本科 AI 入门课程，涵盖搜索、机器学习、深度学习基础', '2026-05-10 19:15:25');
INSERT INTO `courses` VALUES ('course-db-001', '数据库原理', '关系型数据库与 SQL 基础课程', '2026-05-10 19:15:25');

-- ----------------------------
-- Table structure for knowledge_documents
-- ----------------------------
DROP TABLE IF EXISTS `knowledge_documents`;
CREATE TABLE `knowledge_documents`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `title` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `source_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '讲义/术语/题库/阅读',
  `file_path` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `doc_hash` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'SHA-256 鏂囦欢鍝堝笇锛岀敤浜庡?閲忕储寮曞幓閲',
  `chunk_count` int NULL DEFAULT 0 COMMENT '鍒囧垎鍚庣殑 chunk 鏁伴噺',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_kd_course_hash`(`course_id` ASC, `doc_hash` ASC) USING BTREE,
  INDEX `idx_kd_course`(`course_id` ASC) USING BTREE,
  INDEX `idx_kd_course_type`(`course_id` ASC, `source_type` ASC) USING BTREE,
  CONSTRAINT `knowledge_documents_ibfk_1` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of knowledge_documents
-- ----------------------------

-- ----------------------------
-- Table structure for learning_events
-- ----------------------------
DROP TABLE IF EXISTS `learning_events`;
CREATE TABLE `learning_events`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '鍙?负绌猴細闈炶?绋嬬壒瀹氫簨浠讹紙濡傜櫥褰曪級涓嶅叧鑱旇?绋',
  `event_type` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'resource_opened/quiz_submitted/node_completed',
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
-- Records of learning_events
-- ----------------------------

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
  `path_json` json NULL COMMENT 'nodes/edges/adjustments',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_lpv_user_course`(`user_id` ASC, `course_id` ASC) USING BTREE,
  INDEX `idx_lpv_user_version`(`user_id` ASC, `version` ASC) USING BTREE,
  INDEX `course_id`(`course_id` ASC) USING BTREE,
  INDEX `generated_from_profile_version_id`(`generated_from_profile_version_id` ASC) USING BTREE,
  CONSTRAINT `fk_lpv_paths` FOREIGN KEY (`user_id`, `course_id`) REFERENCES `learning_paths` (`user_id`, `course_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `learning_path_versions_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `learning_path_versions_ibfk_2` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `learning_path_versions_ibfk_3` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `learning_path_versions_ibfk_4` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `learning_path_versions_ibfk_5` FOREIGN KEY (`generated_from_profile_version_id`) REFERENCES `profile_versions` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of learning_path_versions
-- ----------------------------

-- ----------------------------
-- Table structure for learning_paths
-- ----------------------------
DROP TABLE IF EXISTS `learning_paths`;
CREATE TABLE `learning_paths`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `current_version` int NULL DEFAULT 0,
  `deleted_at` datetime NULL DEFAULT NULL COMMENT '杞?垹闄ゆ椂闂达紙NULL=鏈?垹闄わ級',
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
-- Records of learning_paths
-- ----------------------------

-- ----------------------------
-- Table structure for profile_chats
-- ----------------------------
DROP TABLE IF EXISTS `profile_chats`;
CREATE TABLE `profile_chats`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `profile_version_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '澶勭悊瀹屾垚鍚庡叧鑱旂殑鐢诲儚鐗堟湰锛圢ULL=鏈??鐞嗭級',
  `messages_json` json NULL COMMENT '[{role, content, at}]',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_pc_user`(`user_id` ASC) USING BTREE,
  INDEX `idx_pc_user_course`(`user_id` ASC, `course_id` ASC) USING BTREE,
  INDEX `course_id`(`course_id` ASC) USING BTREE,
  INDEX `profile_version_id`(`profile_version_id` ASC) USING BTREE,
  CONSTRAINT `profile_chats_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `profile_chats_ibfk_2` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `profile_chats_ibfk_3` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `profile_chats_ibfk_4` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `profile_chats_ibfk_5` FOREIGN KEY (`profile_version_id`) REFERENCES `profile_versions` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of profile_chats
-- ----------------------------
INSERT INTO `profile_chats` VALUES ('06247296a2e204fc9699592999b4c925', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[]', '2026-05-10 21:14:29');
INSERT INTO `profile_chats` VALUES ('0b1c8c029f03ef16a391f74244699c71', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[{\"at\": \"2026-05-10T21:06:38.4857377\", \"role\": \"assistant\", \"content\": \"嗨！今天学习状态怎么样，有没有遇到什么有趣的知识点？\"}, {\"at\": \"2026-05-10T21:06:41.6807499\", \"role\": \"user\", \"content\": \"你好\"}, {\"at\": \"2026-05-10T21:06:44.7529049\", \"role\": \"assistant\", \"content\": \"你好！很高兴见到你！😊 先聊聊你的基本情况吧——你目前是什么专业的？最近在学什么课程呀？\"}]', '2026-05-10 21:06:38');
INSERT INTO `profile_chats` VALUES ('206c5729a85d5f3f7ee29866cc449ac3', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[{\"at\": \"2026-05-10T20:49:53.7717737\", \"role\": \"assistant\", \"content\": \"嗨！今天学习中有没有遇到什么有趣的问题？一起聊聊吧😊\"}, {\"at\": \"2026-05-10T20:49:56.6798534\", \"role\": \"user\", \"content\": \"你好\"}, {\"at\": \"2026-05-10T20:50:01.2122529\", \"role\": \"assistant\", \"content\": \"你好呀！很高兴见到你！🌟 我是你的学习画像分析助手，希望能帮你更好地了解自己的学习情况，找到最适合的学习方式。\\n\\n先聊聊看吧——你现在在学什么专业或者课程呢？最近有在忙哪些学习任务吗？😊\"}]', '2026-05-10 20:49:53');
INSERT INTO `profile_chats` VALUES ('2769744234c7d82685e4f48088e2bc49', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[{\"at\": \"2026-05-10T20:35:16.5081525\", \"role\": \"system\", \"content\": \"你好！我来帮你了解你的学习情况，以便为你定制个性化的学习资源。你是什么专业的？目前在学什么课程呢？\"}]', '2026-05-10 20:35:16');
INSERT INTO `profile_chats` VALUES ('321f3239d2fec31dd5f6f7770b7d9fc4', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[{\"at\": \"2026-05-10T20:36:18.6870701\", \"role\": \"system\", \"content\": \"你好！我来帮你了解你的学习情况，以便为你定制个性化的学习资源。你是什么专业的？目前在学什么课程呢？\"}]', '2026-05-10 20:36:18');
INSERT INTO `profile_chats` VALUES ('49e4d6ad516e924f2938a44c1a46426f', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[{\"at\": \"2026-05-10T20:49:33.1671422\", \"role\": \"assistant\", \"content\": \"嘿，今天学得怎么样？有什么新发现想跟我聊聊吗？\"}]', '2026-05-10 20:49:33');
INSERT INTO `profile_chats` VALUES ('501b31330531858f9fbd9933a4ba7624', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[{\"at\": \"2026-05-10T21:02:52.5900763\", \"role\": \"assistant\", \"content\": \"嗨，今天在学习上有什么新发现吗？\"}, {\"at\": \"2026-05-10T21:02:54.4881614\", \"role\": \"user\", \"content\": \"你好\"}, {\"at\": \"2026-05-10T21:02:58.1348075\", \"role\": \"assistant\", \"content\": \"嗨，很高兴见到你！想先聊聊，你现在在学什么专业或者课程呀？\"}]', '2026-05-10 21:02:52');
INSERT INTO `profile_chats` VALUES ('591bfca35c3d5f52cb42488e8dd633bd', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[{\"at\": \"2026-05-11T12:21:32.7265588\", \"role\": \"user\", \"content\": \"你好\"}, {\"at\": \"2026-05-11T12:21:36.645519\", \"role\": \"assistant\", \"content\": \"你好呀！很高兴见到你！😊 我是你的学习画像分析助手，将和你一起探索《人工智能导论》这门课程的学习之旅。\\n\\n作为开始，我想先简单了解一下你的背景——请问你目前是什么专业的学生？或者是已经在工作了？你是在学校修这门课，还是因为对AI感兴趣自己在学习呢？\"}, {\"at\": \"2026-05-11T12:22:22.0563071\", \"role\": \"user\", \"content\": \"请你教我一些这门课程的知识\"}, {\"at\": \"2026-05-11T12:22:28.1853389\", \"role\": \"assistant\", \"content\": \"好呀！《人工智能导论》是一门很有趣的课程，涵盖了很多基础而重要的内容，比如：\\n\\n- **什么是人工智能**（图灵测试、智能体的概念）\\n- **经典搜索算法**（DFS、BFS、A*算法等）\\n- **机器学习基础**（监督学习、无监督学习、强化学习）\\n- **神经网络与深度学习**（感知机、多层网络、CNN、RNN）\\n- **自然语言处理与计算机视觉**的简单应用\\n\\n不知道你对哪个部分比较感兴趣？或者，聊点具体的——你之前有接触过编程或者数学（如线性代数、概率统计）吗？这样我可以针对你的背景，挑一个容易理解又有意思的知识点讲给你听。😊\"}, {\"at\": \"2026-05-11T12:23:28.6078824\", \"role\": \"user\", \"content\": \"你能从知识库查找相关知识吗？\"}, {\"at\": \"2026-05-11T12:23:36.1967025\", \"role\": \"assistant\", \"content\": \"当然可以！我虽然无法直接连接外部数据库，但可以根据课程知识库的一般内容给你讲解核心知识点。比如《人工智能导论》中，一个经典的开篇概念是：\\n\\n> **智能体 (Agent)**：任何能够通过传感器感知环境，并利用执行器对环境产生影响的实体。  \\n> 比如一个扫地机器人——它通过传感器检测障碍物（感知），然后决定绕行或清扫（决策），最后移动轮子（执行）。\\n\\n围绕智能体，课程还会介绍不同的“理性智能体”类型（简单反射、基于模型、基于目标、基于效用的），以及搜索算法如何帮助智能体找到解决问题的路径。\\n\\n**接下来，我想先了解一下你的背景**，这样我就能挑选更适合你当前水平的知识来讲解，也能帮你更好地规划学习路径。请问你是学什么专业的？在学《人工智能导论》这门课吗？目前进度怎么样？😊\"}]', '2026-05-11 12:17:15');
INSERT INTO `profile_chats` VALUES ('5e8052400f51152d177f5873d55a784e', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[{\"at\": \"2026-05-10T20:55:04.4135096\", \"role\": \"assistant\", \"content\": \"嗨！最近有没有什么学习上的小发现，想和我聊聊？\"}, {\"at\": \"2026-05-10T20:55:28.0402193\", \"role\": \"user\", \"content\": \"你好\"}, {\"at\": \"2026-05-10T20:55:32.1288546\", \"role\": \"assistant\", \"content\": \"很高兴见到你！😊 先简单聊聊吧——你最近在学什么课程？是学校里的专业课，还是自己感兴趣的知识？\"}]', '2026-05-10 20:55:04');
INSERT INTO `profile_chats` VALUES ('6427615eba6bb52dd2ef0c49af1dde64', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[{\"at\": \"2026-05-10T20:38:59.3298759\", \"role\": \"assistant\", \"content\": \"嗨！今天学习状态怎么样？有没有什么有趣的新发现？\"}, {\"at\": \"2026-05-10T20:39:10.3438929\", \"role\": \"user\", \"content\": \"你好\"}, {\"at\": \"2026-05-10T20:39:13.2634679\", \"role\": \"assistant\", \"content\": \"你好呀！很高兴见到你～😊  \\n先说说你的专业背景吧？你目前学的是什么专业，最近在学哪些课程呢？\"}]', '2026-05-10 20:38:59');
INSERT INTO `profile_chats` VALUES ('6dac75d1648c9a7419ceedae09594380', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[{\"at\": \"2026-05-10T21:07:13.8986973\", \"role\": \"assistant\", \"content\": \"嘿！今天学习上有什么小进步想分享吗？😊\"}, {\"at\": \"2026-05-10T21:07:20.2266497\", \"role\": \"user\", \"content\": \"你好\"}, {\"at\": \"2026-05-10T21:07:23.179285\", \"role\": \"assistant\", \"content\": \"你好呀！很高兴见到你～😊\\n\\n可以先简单聊聊吗？比如，你目前是在读什么专业，或者最近在学什么课程呀？\"}]', '2026-05-10 21:07:13');
INSERT INTO `profile_chats` VALUES ('729eb1c9309c2a42cf6e7925ddf9f0a5', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[{\"at\": \"2026-05-11T12:28:50.2435861\", \"role\": \"user\", \"content\": \"你好\"}, {\"at\": \"2026-05-11T12:28:55.6298509\", \"role\": \"assistant\", \"content\": \"你好！很高兴认识你！😊 我是你的学习画像分析助手，可以帮助你更好地了解自己的学习情况，从而让学思伴行平台为你提供更合适的学习资源。\\n\\n让我们先聊聊你的学习背景吧——你正在学**人工智能导论**这门课吗？如果是的话，可以告诉我你是哪个专业的，以及目前课程大概学到哪里了吗？比如是刚刚开始，还是已经学到某个具体的章节了？\"}]', '2026-05-11 12:28:40');
INSERT INTO `profile_chats` VALUES ('822c2d9a3422bac9f25c0c1a88e3fdff', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[{\"at\": \"2026-05-10T19:21:06.9177986\", \"role\": \"system\", \"content\": \"你好！我来帮你了解你的学习情况，以便为你定制个性化的学习资源。你是什么专业的？目前在学什么课程呢？\"}, {\"at\": \"2026-05-10T19:25:08.5289088\", \"role\": \"user\", \"content\": \"你好\"}, {\"at\": \"2026-05-10T19:25:11.9516322\", \"role\": \"assistant\", \"content\": \"你好呀！很高兴能和你一起聊聊学习。那你现在是在哪个专业领域学习，或者最近在学什么课程呢？😊\"}, {\"at\": \"2026-05-10T19:25:31.2617454\", \"role\": \"user\", \"content\": \"我在上什么课？\"}, {\"at\": \"2026-05-10T19:25:36.4192586\", \"role\": \"assistant\", \"content\": \"哈哈，这个问题问得好！其实我刚才就是在问你呢——你正在学什么课程呀？比如是编程、数学、物理，还是其他专业方向的课？可以告诉我一两门你最近在学的课程名字吗？😊\"}, {\"at\": \"2026-05-10T19:50:17.4110816\", \"role\": \"user\", \"content\": \"你能生成资料吗？\"}, {\"at\": \"2026-05-10T19:50:23.2087105\", \"role\": \"assistant\", \"content\": \"谢谢你的提问！目前我的主要任务是通过对话了解你的学习背景和需求，然后为你推荐合适的学习资源（比如文章、视频、练习题等）。我**不能直接生成**全新的资料，但可以根据你的情况整理出最适合你的学习路径和材料链接。\\n\\n不过，要想推荐得精准，需要先了解你的情况。不如我们继续聊聊：你最近在学什么课程？比如数学、编程、物理，还是其他方向？这样我才能更好地帮你规划哦！😊\"}]', '2026-05-10 19:21:06');
INSERT INTO `profile_chats` VALUES ('91ae4804ee6763d4d4f7ab837deb233b', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[{\"at\": \"2026-05-10T20:50:36.9675391\", \"role\": \"assistant\", \"content\": \"当然！这里有几个不同的开场白，你可以随机选用：\\n\\n1. 嗨，今天有什么新知识想和我聊聊吗？  \\n2. 学习顺利吗？有没有遇到什么有趣的题目？  \\n3. 嘿，最近在读什么书？分享一下呀！  \\n4. 今天心情怎么样？想先聊学习还是聊点别的？  \\n5. 刚做完作业吗？有没有需要帮忙的地方？\"}, {\"at\": \"2026-05-10T20:50:52.0066889\", \"role\": \"user\", \"content\": \"你好\"}, {\"at\": \"2026-05-10T20:50:56.7546804\", \"role\": \"assistant\", \"content\": \"你好呀！很高兴见到你！😊\\n\\n我是你的学习画像分析助手，希望能帮你更好地了解自己的学习情况。先问个轻松的问题吧——你最近在学什么专业或者课程呀？有没有什么特别感兴趣的科目？\"}]', '2026-05-10 20:50:36');
INSERT INTO `profile_chats` VALUES ('a495206a7899b7f4e66b821f31a7e03e', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[]', '2026-05-11 12:46:24');
INSERT INTO `profile_chats` VALUES ('b54d92fbfd84424bb39280a384008f0c', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[]', '2026-05-10 21:14:29');
INSERT INTO `profile_chats` VALUES ('b94c5df654f8ef19ffc9d9680fe3e92d', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[]', '2026-05-10 21:13:35');
INSERT INTO `profile_chats` VALUES ('c95b2c3ce510480e135eb779e7335573', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[{\"at\": \"2026-05-11T12:14:31.5503786\", \"role\": \"user\", \"content\": \"你好呀\"}, {\"at\": \"2026-05-11T12:14:35.8793081\", \"role\": \"assistant\", \"content\": \"你好呀！很高兴认识你~ 😊 我是你的学习画像分析助手，可以叫我“小伴”。为了更好地帮你推荐合适的学习内容和方式，我先从最基础的信息开始了解吧！\\n\\n请问你是什么专业的呢？目前正在学习哪些课程呀？\"}, {\"at\": \"2026-05-11T12:15:14.4221495\", \"role\": \"user\", \"content\": \"你知道我在学什么课程吗？\"}, {\"at\": \"2026-05-11T12:15:20.0914889\", \"role\": \"assistant\", \"content\": \"哈哈，目前你只告诉我“课程ID: course-ai-001”，但没有具体课程名称和你的学习情况哦~ 不过我能猜到这很可能和人工智能相关！🤖\\n\\n所以，你是正在学人工智能相关的课程吗？比如机器学习、深度学习、AI基础之类的？可以告诉我具体的课程名称和目前学到哪里了吗？这样我就能更好地帮你啦~😊\"}]', '2026-05-11 12:14:19');
INSERT INTO `profile_chats` VALUES ('chat-001', 'user-test-001', 'course-ai-001', 'pv-001', '[{\"at\": \"2026-05-10T08:55:00+08:00\", \"role\": \"system\", \"content\": \"你好，我来帮你了解你的学习情况。你是什么专业的？\"}, {\"at\": \"2026-05-10T08:56:00+08:00\", \"role\": \"user\", \"content\": \"我是计算机专业的，这学期在学人工智能导论\"}, {\"at\": \"2026-05-10T08:57:00+08:00\", \"role\": \"system\", \"content\": \"了解了。你觉得自己哪些知识点比较有把握，哪些比较薄弱？\"}, {\"at\": \"2026-05-10T08:58:00+08:00\", \"role\": \"user\", \"content\": \"概率基础还行，但贝叶斯公式和A*搜索不太懂\"}]', '2026-05-10 19:15:25');
INSERT INTO `profile_chats` VALUES ('chat-004', 'user-test-001', 'course-ai-001', 'pv-003', '[{\"at\": \"2026-05-10T09:25:00+08:00\", \"role\": \"system\", \"content\": \"最近学习进度怎么样？\"}, {\"at\": \"2026-05-10T09:27:00+08:00\", \"role\": \"user\", \"content\": \"A*搜索现在搞懂了，但贝叶斯还是有点模糊，另外我对强化学习很感兴趣\"}]', '2026-05-10 19:15:25');
INSERT INTO `profile_chats` VALUES ('d2f7fcdf3af78de2370a48a813e60bf4', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[{\"at\": \"2026-05-10T20:41:55.3845032\", \"role\": \"assistant\", \"content\": \"嗨，今天有什么新知识让你觉得特别有趣吗？\"}, {\"at\": \"2026-05-10T20:48:36.351807\", \"role\": \"user\", \"content\": \"你好\"}, {\"at\": \"2026-05-10T20:48:40.104794\", \"role\": \"assistant\", \"content\": \"你好呀！很高兴见到你～😊 你最近在学什么专业或者课程呢？有没有什么特别感兴趣的内容想聊聊？\"}]', '2026-05-10 20:41:55');
INSERT INTO `profile_chats` VALUES ('d53d2ad0ed255194f59007e9ae791dbe', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[{\"at\": \"2026-05-11T12:10:04.4816821\", \"role\": \"user\", \"content\": \"你好呀\"}, {\"at\": \"2026-05-11T12:10:08.0575348\", \"role\": \"assistant\", \"content\": \"你好呀！很高兴见到你~ 😊 我是你的学习画像分析助手，希望能帮你梳理学习需求，找到最适合你的学习方法。  \\n\\n我们先从最基础的问题开始吧：**你目前学的是什么专业？最近有没有在学什么让你印象深刻的课程？**\"}]', '2026-05-11 12:09:58');
INSERT INTO `profile_chats` VALUES ('e2e1a46743fb3a508ff99d2b41de4d78', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[]', '2026-05-10 21:13:34');
INSERT INTO `profile_chats` VALUES ('e9181f912247534407dcf9853aea3621', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[{\"at\": \"2026-05-10T20:56:08.9271026\", \"role\": \"assistant\", \"content\": \"今天学了什么有趣的知识吗？来聊聊吧！\"}, {\"at\": \"2026-05-10T20:56:19.126669\", \"role\": \"user\", \"content\": \"你好\"}, {\"at\": \"2026-05-10T20:56:23.2621052\", \"role\": \"assistant\", \"content\": \"你好呀！很高兴见到你～😊 我是你的学习伙伴，想先了解一下你的情况，这样才能更好地帮你分析学习方向。 \\n\\n先简单聊聊吧：**你目前学的是什么专业？最近在上哪些课程呢？**\"}]', '2026-05-10 20:56:08');
INSERT INTO `profile_chats` VALUES ('f4644c4aabab9be9d27a7276bb7a9f8f', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[{\"at\": \"2026-05-11T12:37:05.7744334\", \"role\": \"user\", \"content\": \"你好\"}, {\"at\": \"2026-05-11T12:37:11.3553756\", \"role\": \"assistant\", \"content\": \"你好！很高兴认识你！我是学思伴行的学习画像分析助手，正在陪你一起学习《人工智能导论》这门课程。\\n\\n为了给你提供更贴心的学习建议和资源，我想先简单了解一下你的情况。可以先告诉我你目前是什么专业的吗？以及你在学习《人工智能导论》的过程中，现在大概学到哪里啦？😊\"}]', '2026-05-11 12:36:59');
INSERT INTO `profile_chats` VALUES ('fc46ff5080929d3378b404ec4fead82a', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[]', '2026-05-10 21:13:30');
INSERT INTO `profile_chats` VALUES ('fe6a78774396c06afb17604c47cee08c', '5465d59c825f1c7eb811d6665872611d', 'course-ai-001', NULL, '[{\"at\": \"2026-05-11T12:03:32.2164334\", \"role\": \"user\", \"content\": \"你好\"}, {\"at\": \"2026-05-11T12:03:35.3532459\", \"role\": \"assistant\", \"content\": \"你好呀！很高兴见到你，我是你的学习画像分析助手！ 先和你打个招呼，你今天感觉怎么样？  \\n 我们慢慢聊聊，先从最基础的开始吧——你是学什么专业的？目前在上哪些课呢？\"}, {\"at\": \"2026-05-11T12:04:22.7913079\", \"role\": \"user\", \"content\": \"你了解这个专业吗？\"}, {\"at\": \"2026-05-11T12:04:28.6488774\", \"role\": \"assistant\", \"content\": \"当然了解啦！我接触过很多不同专业的同学，比如计算机、数学、物理、金融、医学、工程等等。每个专业的学习重点和难点都不太一样，所以我想先听听你的情况，才能更好地帮你分析学习路径。  \\n*不过就算碰到我不熟悉的领域，只要你告诉我课程名称和内容，我也能帮你梳理学习思路哦！*  \\n所以，你学的专业具体是哪个方向？比如计算机科学、机械工程、金融学……目前在上什么课程呢？\"}, {\"at\": \"2026-05-11T12:04:57.9941171\", \"role\": \"user\", \"content\": \"你知道这是什么课程吗？\"}, {\"at\": \"2026-05-11T12:05:02.2513077\", \"role\": \"assistant\", \"content\": \"哈哈，看来你在考我呢～ 你不妨直接告诉我课程的具体名称或者内容，比如“离散数学”、“操作系统”、“量子力学”之类的，这样我就能更好地帮你分析啦！  \\n或者，你可以描述一下这门课在讲什么，比如“研究数据结构”、“分析力学问题”…… 我来判断自己是否熟悉，如果不熟悉，我也会坦诚告诉你，然后一起探索学习思路哦！  \\n\\n对了，你目前这门课的学习进度怎么样？比如已经上到第几周了？有没有哪些部分觉得比较有挑战？\"}, {\"at\": \"2026-05-11T12:06:29.7154554\", \"role\": \"user\", \"content\": \"你知道哪些知识？\"}, {\"at\": \"2026-05-11T12:06:34.5223973\", \"role\": \"assistant\", \"content\": \"看来你对我还挺好奇的！😄  \\n我的知识储备覆盖了大学常见的理工科、部分文科和商科课程，比如高等数学、线性代数、编程（Python/C++/Java等）、数据结构、机器学习、经济学原理、会计学等等。不过，**最重要的是：你现在在学什么，我就重点关注什么！**  \\n\\n既然你暂时没透露具体课程，那我换个方式问吧～  \\n**你目前最头疼、最想突破的一门课是什么？** 哪怕只说一个关键词（比如“微积分”、“Python”、“有机化学”），我也能顺着往下聊！\"}]', '2026-05-10 21:14:30');

-- ----------------------------
-- Table structure for profile_versions
-- ----------------------------
DROP TABLE IF EXISTS `profile_versions`;
CREATE TABLE `profile_versions`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `version` int NOT NULL,
  `dimensions_json` json NULL COMMENT '8维画像数据',
  `summary_json` json NULL COMMENT '供Planner使用的压缩摘要',
  `source_chat_ids` json NULL COMMENT '关联对话记录ID列表',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_pv_user_course`(`user_id` ASC, `course_id` ASC) USING BTREE,
  INDEX `idx_pv_user_version`(`user_id` ASC, `version` ASC) USING BTREE,
  INDEX `idx_pv_user`(`user_id` ASC) USING BTREE,
  INDEX `course_id`(`course_id` ASC) USING BTREE,
  CONSTRAINT `fk_pv_profiles` FOREIGN KEY (`user_id`, `course_id`) REFERENCES `profiles` (`user_id`, `course_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `profile_versions_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `profile_versions_ibfk_2` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of profile_versions
-- ----------------------------
INSERT INTO `profile_versions` VALUES ('pv-001', 'user-test-001', 'course-ai-001', 1, '[{\"key\": \"major_context\", \"label\": \"专业上下文\", \"layer\": \"auxiliary\", \"value\": {\"major\": \"计算机\", \"course\": \"AI导论\", \"current_chapter\": \"第1章\"}, \"source\": \"explicit\", \"confidence\": 0.95, \"updated_at\": \"2026-05-10T09:00:00+08:00\"}, {\"key\": \"knowledge_basis\", \"label\": \"知识基础\", \"layer\": \"core\", \"value\": {\"weak\": [\"贝叶斯公式\", \"A*搜索\"], \"strong\": [\"概率基础\"]}, \"source\": \"explicit\", \"confidence\": 0.85, \"updated_at\": \"2026-05-10T09:00:00+08:00\"}, {\"key\": \"learning_goal\", \"label\": \"学习目标\", \"layer\": \"core\", \"value\": {\"target\": \"期末85分\", \"deadline\": \"2026-06-30\", \"sub_goals\": [\"掌握搜索算法\", \"理解贝叶斯分类器\"]}, \"source\": \"explicit\", \"confidence\": 0.9, \"updated_at\": \"2026-05-10T09:00:00+08:00\"}, {\"key\": \"cognitive_style\", \"label\": \"认知偏好\", \"layer\": \"style\", \"value\": {\"avoid\": [\"纯公式推导\"], \"style\": [\"代码实例\", \"可视化\"]}, \"source\": \"inferred\", \"confidence\": 0.7, \"updated_at\": \"2026-05-10T09:00:00+08:00\"}, {\"key\": \"learning_pace\", \"label\": \"学习节奏\", \"layer\": \"style\", \"value\": {\"urgency\": \"high\", \"days_per_week\": 5, \"minutes_per_day\": 60}, \"source\": \"explicit\", \"confidence\": 0.8, \"updated_at\": \"2026-05-10T09:00:00+08:00\"}, {\"key\": \"interest_direction\", \"label\": \"兴趣方向\", \"layer\": \"auxiliary\", \"value\": {\"topics\": [\"推荐系统\", \"计算机视觉\"], \"applications\": [\"医疗AI\"]}, \"source\": \"inferred\", \"confidence\": 0.6, \"updated_at\": \"2026-05-10T09:00:00+08:00\"}, {\"key\": \"error_pattern\", \"label\": \"错误模式\", \"layer\": \"auxiliary\", \"value\": {\"tags\": [\"概念混淆\", \"贝叶斯公式\"]}, \"source\": \"inferred\", \"confidence\": 0.75, \"updated_at\": \"2026-05-10T09:00:00+08:00\"}]', '{\"goal\": \"期末85分\", \"style\": [\"代码实例\", \"可视化\"], \"weak_top\": [\"贝叶斯公式\", \"A*搜索\"], \"minutes_per_day\": 60}', '[\"chat-001\", \"chat-002\"]', '2026-05-10 19:15:25');
INSERT INTO `profile_versions` VALUES ('pv-002', 'user-test-001', 'course-ai-001', 2, '[{\"key\": \"major_context\", \"label\": \"专业上下文\", \"layer\": \"auxiliary\", \"value\": {\"major\": \"计算机\", \"course\": \"AI导论\", \"current_chapter\": \"第3章\"}, \"source\": \"explicit\", \"confidence\": 0.95, \"updated_at\": \"2026-05-10T09:15:00+08:00\"}, {\"key\": \"knowledge_basis\", \"label\": \"知识基础\", \"layer\": \"core\", \"value\": {\"weak\": [\"贝叶斯公式\"], \"strong\": [\"概率基础\", \"线性回归\"]}, \"source\": \"explicit\", \"confidence\": 0.88, \"updated_at\": \"2026-05-10T09:15:00+08:00\"}, {\"key\": \"learning_goal\", \"label\": \"学习目标\", \"layer\": \"core\", \"value\": {\"target\": \"期末85分\", \"deadline\": \"2026-06-30\", \"sub_goals\": [\"掌握搜索算法\", \"理解贝叶斯分类器\"]}, \"source\": \"explicit\", \"confidence\": 0.9, \"updated_at\": \"2026-05-10T09:15:00+08:00\"}, {\"key\": \"cognitive_style\", \"label\": \"认知偏好\", \"layer\": \"style\", \"value\": {\"avoid\": [\"纯公式推导\"], \"style\": [\"代码实例\", \"可视化\", \"动画演示\"]}, \"source\": \"inferred\", \"confidence\": 0.72, \"updated_at\": \"2026-05-10T09:15:00+08:00\"}, {\"key\": \"learning_pace\", \"label\": \"学习节奏\", \"layer\": \"style\", \"value\": {\"urgency\": \"high\", \"days_per_week\": 5, \"minutes_per_day\": 60}, \"source\": \"explicit\", \"confidence\": 0.8, \"updated_at\": \"2026-05-10T09:15:00+08:00\"}, {\"key\": \"interest_direction\", \"label\": \"兴趣方向\", \"layer\": \"auxiliary\", \"value\": {\"topics\": [\"推荐系统\", \"计算机视觉\"], \"applications\": [\"医疗AI\"]}, \"source\": \"inferred\", \"confidence\": 0.6, \"updated_at\": \"2026-05-10T09:15:00+08:00\"}, {\"key\": \"error_pattern\", \"label\": \"错误模式\", \"layer\": \"auxiliary\", \"value\": {\"tags\": [\"概念混淆\", \"贝叶斯公式\", \"梯度计算\"]}, \"source\": \"inferred\", \"confidence\": 0.78, \"updated_at\": \"2026-05-10T09:15:00+08:00\"}]', '{\"goal\": \"期末85分\", \"style\": [\"代码实例\", \"可视化\"], \"weak_top\": [\"贝叶斯公式\"], \"minutes_per_day\": 60}', '[\"chat-003\"]', '2026-05-10 19:15:25');
INSERT INTO `profile_versions` VALUES ('pv-003', 'user-test-001', 'course-ai-001', 3, '[{\"key\": \"major_context\", \"label\": \"专业上下文\", \"layer\": \"auxiliary\", \"value\": {\"major\": \"计算机\", \"course\": \"AI导论\", \"current_chapter\": \"第4章\"}, \"source\": \"explicit\", \"confidence\": 0.95, \"updated_at\": \"2026-05-10T09:30:00+08:00\"}, {\"key\": \"knowledge_basis\", \"label\": \"知识基础\", \"layer\": \"core\", \"value\": {\"weak\": [\"贝叶斯公式\"], \"strong\": [\"概率基础\", \"线性回归\", \"A*搜索\"]}, \"source\": \"explicit\", \"confidence\": 0.9, \"updated_at\": \"2026-05-10T09:30:00+08:00\"}, {\"key\": \"learning_goal\", \"label\": \"学习目标\", \"layer\": \"core\", \"value\": {\"target\": \"期末85分\", \"deadline\": \"2026-06-30\", \"sub_goals\": [\"掌握搜索算法\", \"理解贝叶斯分类器\", \"完成课程项目\"]}, \"source\": \"explicit\", \"confidence\": 0.92, \"updated_at\": \"2026-05-10T09:30:00+08:00\"}, {\"key\": \"cognitive_style\", \"label\": \"认知偏好\", \"layer\": \"style\", \"value\": {\"avoid\": [\"纯公式推导\"], \"style\": [\"代码实例\", \"可视化\", \"动画演示\"]}, \"source\": \"inferred\", \"confidence\": 0.75, \"updated_at\": \"2026-05-10T09:30:00+08:00\"}, {\"key\": \"learning_pace\", \"label\": \"学习节奏\", \"layer\": \"style\", \"value\": {\"urgency\": \"high\", \"days_per_week\": 6, \"minutes_per_day\": 90}, \"source\": \"inferred\", \"confidence\": 0.82, \"updated_at\": \"2026-05-10T09:30:00+08:00\"}, {\"key\": \"interest_direction\", \"label\": \"兴趣方向\", \"layer\": \"auxiliary\", \"value\": {\"topics\": [\"推荐系统\", \"计算机视觉\", \"强化学习\"], \"applications\": [\"医疗AI\"]}, \"source\": \"inferred\", \"confidence\": 0.65, \"updated_at\": \"2026-05-10T09:30:00+08:00\"}, {\"key\": \"error_pattern\", \"label\": \"错误模式\", \"layer\": \"auxiliary\", \"value\": {\"tags\": [\"概念混淆\", \"贝叶斯公式\"]}, \"source\": \"inferred\", \"confidence\": 0.8, \"updated_at\": \"2026-05-10T09:30:00+08:00\"}]', '{\"goal\": \"期末85分\", \"style\": [\"代码实例\", \"可视化\", \"动画演示\"], \"weak_top\": [\"贝叶斯公式\"], \"minutes_per_day\": 90}', '[\"chat-004\"]', '2026-05-10 19:15:25');

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
-- Records of profiles
-- ----------------------------
INSERT INTO `profiles` VALUES ('prof-001', 'user-test-001', 'course-ai-001', 3, '2026-05-10 19:15:25');
INSERT INTO `profiles` VALUES ('prof-002', 'user-demo-001', 'course-ai-001', 1, '2026-05-10 19:15:25');

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
  CONSTRAINT `quiz_attempts_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `quiz_attempts_ibfk_2` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `quiz_attempts_ibfk_3` FOREIGN KEY (`pack_id`) REFERENCES `resource_packs` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of quiz_attempts
-- ----------------------------

-- ----------------------------
-- Table structure for resource_items
-- ----------------------------
DROP TABLE IF EXISTS `resource_items`;
CREATE TABLE `resource_items`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `pack_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `task_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `type` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'document/mindmap/exercise/reading/code/video_script',
  `title` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'pending' COMMENT 'pending/ready/failed',
  `content_ref` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '对象存储key',
  `content_mime` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'text/markdown, application/json',
  `confidence` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'high / medium / low',
  `quality_score` decimal(3, 2) NULL DEFAULT NULL COMMENT 'quality score 0.00~100.00',
  `metadata_json` json NULL COMMENT '难度、标签、估时、quality_score',
  `sources_json` json NULL COMMENT '证据列表（doc_id,title,chunk_id,quote,locator,relevance）',
  `review_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'pending' COMMENT 'approved/rejected/pending',
  `review_summary` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `deleted_at` datetime NULL DEFAULT NULL COMMENT '杞?垹闄ゆ椂闂达紙NULL=鏈?垹闄わ級',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_ri_pack`(`pack_id` ASC) USING BTREE,
  INDEX `idx_ri_task`(`task_id` ASC) USING BTREE,
  INDEX `idx_ri_pack_type_status`(`pack_id` ASC, `type` ASC, `status` ASC) USING BTREE,
  CONSTRAINT `resource_items_ibfk_1` FOREIGN KEY (`pack_id`) REFERENCES `resource_packs` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `resource_items_ibfk_2` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of resource_items
-- ----------------------------

-- ----------------------------
-- Table structure for resource_packs
-- ----------------------------
DROP TABLE IF EXISTS `resource_packs`;
CREATE TABLE `resource_packs`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `topic` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `generated_from_profile_version_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '生成时所依据的画像版本ID',
  `task_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `push_reason_json` json NULL COMMENT '个性化推送原因标签',
  `deleted_at` datetime NULL DEFAULT NULL COMMENT '杞?垹闄ゆ椂闂达紙NULL=鏈?垹闄わ級',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_rp_user`(`user_id` ASC) USING BTREE,
  INDEX `idx_rp_task`(`task_id` ASC) USING BTREE,
  INDEX `course_id`(`course_id` ASC) USING BTREE,
  INDEX `idx_rp_user_created`(`user_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `generated_from_profile_version_id`(`generated_from_profile_version_id` ASC) USING BTREE,
  CONSTRAINT `resource_packs_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `resource_packs_ibfk_2` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `resource_packs_ibfk_3` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `resource_packs_ibfk_4` FOREIGN KEY (`generated_from_profile_version_id`) REFERENCES `profile_versions` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of resource_packs
-- ----------------------------
INSERT INTO `resource_packs` VALUES ('pack-001', 'user-test-001', 'course-ai-001', '贝叶斯分类器', 'pv-003', 'task-001', '[\"针对薄弱点: 贝叶斯公式\", \"偏好: 代码实例\", \"节奏: 15分钟/天\"]', NULL, '2026-05-10 19:15:25');

-- ----------------------------
-- Table structure for review_records
-- ----------------------------
DROP TABLE IF EXISTS `review_records`;
CREATE TABLE `review_records`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `resource_item_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'nullable ? review before publish, backfilled by doPublishing',
  `resource_pack_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'nullable ? review before publish, backfilled by doPublishing',
  `task_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `result` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'approved / rejected',
  `reasons_json` json NULL COMMENT '结构化驳回原因（missing_sources, 不一致, 敏感内容）',
  `citation_coverage` decimal(5, 2) NULL DEFAULT NULL COMMENT '引用覆盖率 0.00~100.00',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_rr_resource`(`resource_item_id` ASC) USING BTREE,
  INDEX `idx_rr_task`(`task_id` ASC) USING BTREE,
  INDEX `idx_rr_pack`(`resource_pack_id` ASC) USING BTREE,
  CONSTRAINT `review_records_item_fk` FOREIGN KEY (`resource_item_id`) REFERENCES `resource_items` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `review_records_pack_fk` FOREIGN KEY (`resource_pack_id`) REFERENCES `resource_packs` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `review_records_task_fk` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of review_records
-- ----------------------------

-- ----------------------------
-- Table structure for task_events
-- ----------------------------
DROP TABLE IF EXISTS `task_events`;
CREATE TABLE `task_events`  (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `task_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `event_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'task.accepted/task.stage/resource.ready/review.flag/task.done',
  `payload_json` json NULL,
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_te_task`(`task_id` ASC) USING BTREE,
  INDEX `idx_te_task_event`(`task_id` ASC, `event_type` ASC) USING BTREE,
  INDEX `idx_te_task_created`(`task_id` ASC, `created_at` ASC) USING BTREE,
  CONSTRAINT `task_events_ibfk_1` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of task_events
-- ----------------------------
INSERT INTO `task_events` VALUES ('te-001', 'task-001', 'task.accepted', '{\"topic\": \"贝叶斯分类器\", \"types\": [\"doc\", \"quiz\", \"code\"], \"task_id\": \"task-001\"}', '2026-05-10 19:15:25');
INSERT INTO `task_events` VALUES ('te-002', 'task-001', 'task.stage', '{\"stage\": \"RETRIEVING\", \"message\": \"检索证据中\", \"percent\": 15}', '2026-05-10 19:15:25');
INSERT INTO `task_events` VALUES ('te-003', 'task-001', 'task.stage', '{\"stage\": \"PLANNING\", \"message\": \"Planner 做出决策\", \"percent\": 35}', '2026-05-10 19:15:25');
INSERT INTO `task_events` VALUES ('te-004', 'task-001', 'task.stage', '{\"stage\": \"GENERATING\", \"message\": \"生成资源中\", \"percent\": 50}', '2026-05-10 19:15:25');
INSERT INTO `task_events` VALUES ('te-005', 'task-001', 'resource.ready', '{\"type\": \"doc\", \"title\": \"贝叶斯分类器精讲\", \"resource_id\": \"res-001\"}', '2026-05-10 19:15:25');
INSERT INTO `task_events` VALUES ('te-006', 'task-001', 'resource.ready', '{\"type\": \"quiz\", \"title\": \"贝叶斯分类器练习\", \"resource_id\": \"res-002\"}', '2026-05-10 19:15:25');
INSERT INTO `task_events` VALUES ('te-007', 'task-001', 'resource.ready', '{\"type\": \"code\", \"title\": \"贝叶斯分类器代码实现\", \"resource_id\": \"res-003\"}', '2026-05-10 19:15:25');
INSERT INTO `task_events` VALUES ('te-008', 'task-001', 'task.done', '{\"pack_id\": \"pack-001\"}', '2026-05-10 19:15:25');

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
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED',
  `stage` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `percent` int NULL DEFAULT 0,
  `error_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `started_at` datetime NULL DEFAULT NULL,
  `finished_at` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_task_user_status`(`user_id` ASC, `status` ASC) USING BTREE,
  INDEX `idx_task_type`(`task_type` ASC) USING BTREE,
  INDEX `idx_task_user_created`(`user_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_task_stage`(`stage` ASC) USING BTREE,
  INDEX `idx_task_status`(`status` ASC) USING BTREE,
  INDEX `idx_task_chat`(`chat_id` ASC) USING BTREE,
  INDEX `fk_tasks_profiles`(`user_id` ASC, `course_id` ASC) USING BTREE,
  INDEX `course_id`(`course_id` ASC) USING BTREE,
  INDEX `profile_version_id`(`profile_version_id` ASC) USING BTREE,
  CONSTRAINT `fk_tasks_profiles` FOREIGN KEY (`user_id`, `course_id`) REFERENCES `profiles` (`user_id`, `course_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `tasks_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `tasks_ibfk_2` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `tasks_ibfk_3` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `tasks_ibfk_4` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `tasks_ibfk_5` FOREIGN KEY (`profile_version_id`) REFERENCES `profile_versions` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of tasks
-- ----------------------------
INSERT INTO `tasks` VALUES ('task-001', 'user-test-001', 'course-ai-001', 'resource_generate', '贝叶斯分类器', '[\"doc\", \"quiz\", \"code\"]', 'pv-003', 'SUCCEEDED', 'PUBLISHING', 100, NULL, NULL, '2026-05-10 19:15:25', NULL, NULL);
INSERT INTO `tasks` VALUES ('task-002', 'user-test-001', 'course-ai-001', 'resource_generate', 'A*搜索', '[\"doc\", \"mindmap\", \"quiz\"]', 'pv-003', 'RUNNING', 'GENERATING', 55, NULL, NULL, '2026-05-10 19:15:25', NULL, NULL);
INSERT INTO `tasks` VALUES ('task-003', 'user-demo-001', 'course-ai-001', 'path_generate', 'AI导论-全局路径', '[\"doc\", \"mindmap\", \"quiz\", \"reading\", \"code\"]', 'pv-003', 'PENDING', NULL, 0, NULL, NULL, '2026-05-10 19:15:25', NULL, NULL);

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
-- Records of user_course_enrollments
-- ----------------------------
INSERT INTO `user_course_enrollments` VALUES ('uce-001', 'user-test-001', 'course-ai-001', '2026-05-10 19:15:25');
INSERT INTO `user_course_enrollments` VALUES ('uce-002', 'user-demo-001', 'course-ai-001', '2026-05-10 19:15:25');
INSERT INTO `user_course_enrollments` VALUES ('uce-003', 'user-demo-001', 'course-db-001', '2026-05-10 19:15:25');

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
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `username`(`username` ASC) USING BTREE,
  UNIQUE INDEX `email`(`email` ASC) USING BTREE,
  INDEX `idx_users_email`(`email` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of users
-- ----------------------------
INSERT INTO `users` VALUES ('5465d59c825f1c7eb811d6665872611d', 'xgy', '3791158284@qq.com', '$2a$10$RXVCiXd8lHMo0LCOnzSJF.CPhRcOlaV8YUs1pPvn3IlyfbynA6zey', 'student', '2026-05-07 22:05:13', '2026-05-07 22:05:13');
INSERT INTO `users` VALUES ('user-admin-001', 'admin', 'admin@learnthink.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'admin', '2026-05-10 19:15:25', '2026-05-10 19:15:25');
INSERT INTO `users` VALUES ('user-demo-001', 'zhangsan', 'zhangsan@example.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'student', '2026-05-10 19:15:25', '2026-05-10 19:15:25');
INSERT INTO `users` VALUES ('user-test-001', 'testuser', 'test@learnthink.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'student', '2026-05-10 19:15:25', '2026-05-10 19:15:25');

SET FOREIGN_KEY_CHECKS = 1;

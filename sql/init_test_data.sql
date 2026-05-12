-- ============================================================
-- 学思伴行（LearnThink Companion）测试数据脚本
-- 版本：v2.0
-- 日期：2026-05-10
-- 用法：mysql -u root -p learnthink < sql/init_test_data.sql
-- 前置：先执行 sql/schema.sql 建表
-- 密码：所有测试用户密码均为 123456
-- ============================================================

USE learnthink;


-- ============================================================
-- 1. 用户
-- ============================================================
-- BCrypt hash of "123456": $2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
INSERT INTO users (id, username, email, password_hash, role) VALUES
    ('user-test-001', 'testuser',  'test@learnthink.com',  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',  'student'),
    ('user-admin-001', 'admin',     'admin@learnthink.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',   'admin'),
    ('user-demo-001',  'zhangsan',  'zhangsan@example.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',  'student')
ON DUPLICATE KEY UPDATE username=username;


-- ============================================================
-- 2. 课程
-- ============================================================
INSERT INTO courses (id, name, description) VALUES
    ('course-ai-001', '人工智能导论', '大学本科 AI 入门课程，涵盖搜索、机器学习、深度学习基础'),
    ('course-db-001', '数据库原理',    '关系型数据库与 SQL 基础课程')
ON DUPLICATE KEY UPDATE name=name;


-- ============================================================
-- 3. 用户选课
-- ============================================================
INSERT INTO user_course_enrollments (id, user_id, course_id) VALUES
    ('uce-001', 'user-test-001', 'course-ai-001'),
    ('uce-002', 'user-demo-001',  'course-ai-001'),
    ('uce-003', 'user-demo-001',  'course-db-001')
ON DUPLICATE KEY UPDATE enrolled_at=enrolled_at;


-- ============================================================
-- 4. 画像
-- ============================================================
INSERT INTO profiles (id, user_id, course_id, current_version) VALUES
    ('prof-001', 'user-test-001', 'course-ai-001', 3),
    ('prof-002', 'user-demo-001',  'course-ai-001', 1)
ON DUPLICATE KEY UPDATE current_version=current_version;


-- profile_versions — 测试学生的 3 个版本
INSERT INTO profile_versions (id, user_id, course_id, version, dimensions_json, summary_json, source_chat_ids) VALUES
    ('pv-001', 'user-test-001', 'course-ai-001', 1,
     '[{"key":"major_context","label":"专业上下文","layer":"auxiliary","value":{"major":"计算机","course":"AI导论","current_chapter":"第1章"},"confidence":0.95,"source":"explicit","updated_at":"2026-05-10T09:00:00+08:00"},{"key":"knowledge_basis","label":"知识基础","layer":"core","value":{"strong":["概率基础"],"weak":["贝叶斯公式","A*搜索"]},"confidence":0.85,"source":"explicit","updated_at":"2026-05-10T09:00:00+08:00"},{"key":"learning_goal","label":"学习目标","layer":"core","value":{"target":"期末85分","deadline":"2026-06-30","sub_goals":["掌握搜索算法","理解贝叶斯分类器"]},"confidence":0.90,"source":"explicit","updated_at":"2026-05-10T09:00:00+08:00"},{"key":"cognitive_style","label":"认知偏好","layer":"style","value":{"style":["代码实例","可视化"],"avoid":["纯公式推导"]},"confidence":0.70,"source":"inferred","updated_at":"2026-05-10T09:00:00+08:00"},{"key":"learning_pace","label":"学习节奏","layer":"style","value":{"minutes_per_day":60,"days_per_week":5,"urgency":"high"},"confidence":0.80,"source":"explicit","updated_at":"2026-05-10T09:00:00+08:00"},{"key":"interest_direction","label":"兴趣方向","layer":"auxiliary","value":{"topics":["推荐系统","计算机视觉"],"applications":["医疗AI"]},"confidence":0.60,"source":"inferred","updated_at":"2026-05-10T09:00:00+08:00"},{"key":"error_pattern","label":"错误模式","layer":"auxiliary","value":{"tags":["概念混淆","贝叶斯公式"]},"confidence":0.75,"source":"inferred","updated_at":"2026-05-10T09:00:00+08:00"}]',
     '{"weak_top":["贝叶斯公式","A*搜索"],"style":["代码实例","可视化"],"minutes_per_day":60,"goal":"期末85分"}',
     '["chat-001","chat-002"]'),

    ('pv-002', 'user-test-001', 'course-ai-001', 2,
     '[{"key":"major_context","label":"专业上下文","layer":"auxiliary","value":{"major":"计算机","course":"AI导论","current_chapter":"第3章"},"confidence":0.95,"source":"explicit","updated_at":"2026-05-10T09:15:00+08:00"},{"key":"knowledge_basis","label":"知识基础","layer":"core","value":{"strong":["概率基础","线性回归"],"weak":["贝叶斯公式"]},"confidence":0.88,"source":"explicit","updated_at":"2026-05-10T09:15:00+08:00"},{"key":"learning_goal","label":"学习目标","layer":"core","value":{"target":"期末85分","deadline":"2026-06-30","sub_goals":["掌握搜索算法","理解贝叶斯分类器"]},"confidence":0.90,"source":"explicit","updated_at":"2026-05-10T09:15:00+08:00"},{"key":"cognitive_style","label":"认知偏好","layer":"style","value":{"style":["代码实例","可视化","动画演示"],"avoid":["纯公式推导"]},"confidence":0.72,"source":"inferred","updated_at":"2026-05-10T09:15:00+08:00"},{"key":"learning_pace","label":"学习节奏","layer":"style","value":{"minutes_per_day":60,"days_per_week":5,"urgency":"high"},"confidence":0.80,"source":"explicit","updated_at":"2026-05-10T09:15:00+08:00"},{"key":"interest_direction","label":"兴趣方向","layer":"auxiliary","value":{"topics":["推荐系统","计算机视觉"],"applications":["医疗AI"]},"confidence":0.60,"source":"inferred","updated_at":"2026-05-10T09:15:00+08:00"},{"key":"error_pattern","label":"错误模式","layer":"auxiliary","value":{"tags":["概念混淆","贝叶斯公式","梯度计算"]},"confidence":0.78,"source":"inferred","updated_at":"2026-05-10T09:15:00+08:00"}]',
     '{"weak_top":["贝叶斯公式"],"style":["代码实例","可视化"],"minutes_per_day":60,"goal":"期末85分"}',
     '["chat-003"]'),

    ('pv-003', 'user-test-001', 'course-ai-001', 3,
     '[{"key":"major_context","label":"专业上下文","layer":"auxiliary","value":{"major":"计算机","course":"AI导论","current_chapter":"第4章"},"confidence":0.95,"source":"explicit","updated_at":"2026-05-10T09:30:00+08:00"},{"key":"knowledge_basis","label":"知识基础","layer":"core","value":{"strong":["概率基础","线性回归","A*搜索"],"weak":["贝叶斯公式"]},"confidence":0.90,"source":"explicit","updated_at":"2026-05-10T09:30:00+08:00"},{"key":"learning_goal","label":"学习目标","layer":"core","value":{"target":"期末85分","deadline":"2026-06-30","sub_goals":["掌握搜索算法","理解贝叶斯分类器","完成课程项目"]},"confidence":0.92,"source":"explicit","updated_at":"2026-05-10T09:30:00+08:00"},{"key":"cognitive_style","label":"认知偏好","layer":"style","value":{"style":["代码实例","可视化","动画演示"],"avoid":["纯公式推导"]},"confidence":0.75,"source":"inferred","updated_at":"2026-05-10T09:30:00+08:00"},{"key":"learning_pace","label":"学习节奏","layer":"style","value":{"minutes_per_day":90,"days_per_week":6,"urgency":"high"},"confidence":0.82,"source":"inferred","updated_at":"2026-05-10T09:30:00+08:00"},{"key":"interest_direction","label":"兴趣方向","layer":"auxiliary","value":{"topics":["推荐系统","计算机视觉","强化学习"],"applications":["医疗AI"]},"confidence":0.65,"source":"inferred","updated_at":"2026-05-10T09:30:00+08:00"},{"key":"error_pattern","label":"错误模式","layer":"auxiliary","value":{"tags":["概念混淆","贝叶斯公式"]},"confidence":0.80,"source":"inferred","updated_at":"2026-05-10T09:30:00+08:00"}]',
     '{"weak_top":["贝叶斯公式"],"style":["代码实例","可视化","动画演示"],"minutes_per_day":90,"goal":"期末85分"}',
     '["chat-004"]')
ON DUPLICATE KEY UPDATE version=version;


-- 画像对话记录
INSERT INTO profile_chats (id, user_id, course_id, profile_version_id, messages_json) VALUES
    ('chat-001', 'user-test-001', 'course-ai-001', 'pv-001',
     '[{"role":"system","content":"你好，我来帮你了解你的学习情况。你是什么专业的？","at":"2026-05-10T08:55:00+08:00"},{"role":"user","content":"我是计算机专业的，这学期在学人工智能导论","at":"2026-05-10T08:56:00+08:00"},{"role":"system","content":"了解了。你觉得自己哪些知识点比较有把握，哪些比较薄弱？","at":"2026-05-10T08:57:00+08:00"},{"role":"user","content":"概率基础还行，但贝叶斯公式和A*搜索不太懂","at":"2026-05-10T08:58:00+08:00"}]'),
    ('chat-004', 'user-test-001', 'course-ai-001', 'pv-003',
     '[{"role":"system","content":"最近学习进度怎么样？","at":"2026-05-10T09:25:00+08:00"},{"role":"user","content":"A*搜索现在搞懂了，但贝叶斯还是有点模糊，另外我对强化学习很感兴趣","at":"2026-05-10T09:27:00+08:00"}]')
ON DUPLICATE KEY UPDATE messages_json=messages_json;


-- ============================================================
-- 5. 任务
-- ============================================================
INSERT INTO tasks (id, user_id, course_id, task_type, topic, requested_resource_types, profile_version_id, status, stage, percent) VALUES
    ('task-001', 'user-test-001', 'course-ai-001', 'resource_generate', '贝叶斯分类器',
     '["doc","quiz","code"]', 'pv-003', 'SUCCEEDED', 'PUBLISHING', 100),
    ('task-002', 'user-test-001', 'course-ai-001', 'resource_generate', 'A*搜索',
     '["doc","mindmap","quiz"]', 'pv-003', 'RUNNING', 'GENERATING', 55),
    ('task-003', 'user-demo-001', 'course-ai-001', 'path_generate', 'AI导论-全局路径',
     '["doc","mindmap","quiz","reading","code"]', 'pv-003', 'PENDING', NULL, 0)
ON DUPLICATE KEY UPDATE status=status;


-- 任务事件
INSERT INTO task_events (id, task_id, event_type, payload_json) VALUES
    ('te-001', 'task-001', 'task.accepted',  '{"task_id":"task-001","topic":"贝叶斯分类器","types":["doc","quiz","code"]}'),
    ('te-002', 'task-001', 'task.stage',     '{"stage":"RETRIEVING","percent":15,"message":"检索证据中"}'),
    ('te-003', 'task-001', 'task.stage',     '{"stage":"PLANNING","percent":35,"message":"Planner 做出决策"}'),
    ('te-004', 'task-001', 'task.stage',     '{"stage":"GENERATING","percent":50,"message":"生成资源中"}'),
    ('te-005', 'task-001', 'resource.ready', '{"resource_id":"res-001","type":"doc","title":"贝叶斯分类器精讲"}'),
    ('te-006', 'task-001', 'resource.ready', '{"resource_id":"res-002","type":"quiz","title":"贝叶斯分类器练习"}'),
    ('te-007', 'task-001', 'resource.ready', '{"resource_id":"res-003","type":"code","title":"贝叶斯分类器代码实现"}'),
    ('te-008', 'task-001', 'task.done',      '{"pack_id":"pack-001"}')
ON DUPLICATE KEY UPDATE payload_json=payload_json;


-- Agent 思考链
INSERT INTO agent_thinking_traces (id, task_id, agent_name, agent_role, phase, context, observation, thought, decision, confidence_level, `trigger`) VALUES
    ('att-001', 'task-001', 'Retriever', 'RAG检索', 'RETRIEVING',
     '用户 query="贝叶斯分类器 概念定义 核心原理"',
     'Milvus 检索返回 15 个候选 chunk，top-8 中 relevance>0.6 的有 6 个',
     '来源覆盖较好：3 条讲义 + 2 条术语 + 1 条题库。但缺少代码示例来源，需标记为 low confidence 提醒 Planner',
     '返回 8 条 sources，标记 code 类型证据不足',
     'medium', 'system_prompt'),

    ('att-002', 'task-001', 'Planner', '规划编排', 'PLANNING',
     '画像: weak_top=["贝叶斯公式"], style=["代码实例","可视化"]; 证据: 8 条来源',
     '弱项聚焦+代码偏好+应试目标 → 优先 doc+quiz+code',
     '根据弱项匹配(0.30)和风格亲和(0.25)评分：doc=0.88, quiz=0.82, code=0.78, mindmap=0.72, reading=0.65。取 top-3',
     '选择 [doc, quiz, code]，难度 medium，预估 45min',
     'high', 'system_prompt')
ON DUPLICATE KEY UPDATE observation=observation;


-- ============================================================
-- 6. 资源包与资源项
-- ============================================================
INSERT INTO resource_packs (id, user_id, course_id, topic, generated_from_profile_version_id, task_id, push_reason_json) VALUES
    ('pack-001', 'user-test-001', 'course-ai-001', '贝叶斯分类器', 'pv-003', 'task-001',
     '["针对薄弱点: 贝叶斯公式","偏好: 代码实例","节奏: 15分钟/天"]')
ON DUPLICATE KEY UPDATE topic=topic;


INSERT INTO resource_items (id, pack_id, task_id, type, title, status, confidence_score, quality_score, sources_json, review_status) VALUES
    ('res-001', 'pack-001', 'task-001', 'doc',   '贝叶斯分类器精讲',          'ready',    0.92, 88.0,
     '[{"doc_id":"ai-ch3","doc_title":"AI导论-讲义第3章","chunk_id":"ai-ch3#2","excerpt":"贝叶斯分类器基于贝叶斯定理...","locator":"p3#L20-L35","relevance":0.87}]',
     'approved'),
    ('res-002', 'pack-001', 'task-001', 'quiz',  '贝叶斯分类器分层练习',       'ready',    0.78, 82.0,
     '[{"doc_id":"ai-ch3","doc_title":"AI导论-讲义第3章","chunk_id":"ai-ch3#5","excerpt":"给定训练集 D...","locator":"p5#L10-L25","relevance":0.82}]',
     'approved'),
    ('res-003', 'pack-001', 'task-001', 'code',  '朴素贝叶斯代码实现',         'pending',  NULL, NULL,  NULL,                                   'pending'),
    ('res-004', 'pack-001', 'task-001', 'mindmap', '贝叶斯知识图谱',            'rejected', 0.45, 30.0,
     '[]',
     'rejected')
ON DUPLICATE KEY UPDATE title=title;


-- 审校记录
INSERT INTO review_records (id, resource_item_id, resource_pack_id, task_id, result, reasons_json, citation_coverage) VALUES
    ('rr-001', 'res-001', 'pack-001', 'task-001', 'approved', '[]', 92.50),
    ('rr-002', 'res-002', 'pack-001', 'task-001', 'approved', '[]', 78.00),
    ('rr-003', 'res-004', 'pack-001', 'task-001', 'rejected',
     '[{"type":"missing_sources","detail":"无引用来源"},{"type":"low_quality","detail":"内容为空或质量过低"}]', 0.00)
ON DUPLICATE KEY UPDATE result=result;


-- ============================================================
-- 7. 学习路径
-- ============================================================
INSERT INTO learning_paths (id, user_id, course_id, current_version) VALUES
    ('lp-001', 'user-test-001', 'course-ai-001', 1)
ON DUPLICATE KEY UPDATE current_version=current_version;


INSERT INTO learning_path_versions (id, user_id, course_id, version, generated_from_profile_version_id, path_json) VALUES
    ('lpv-001', 'user-test-001', 'course-ai-001', 1, 'pv-003',
     '{
       "nodes": [
         {"node_id":"n1","title":"搜索算法基础","knowledge_point":"盲目搜索","status":"done","resource_pack_id":null,"estimated_minutes":30},
         {"node_id":"n2","title":"A*搜索算法","knowledge_point":"启发式搜索","status":"done","resource_pack_id":null,"estimated_minutes":45},
         {"node_id":"n3","title":"贝叶斯分类器","knowledge_point":"贝叶斯分类","status":"doing","resource_pack_id":"pack-001","estimated_minutes":60},
         {"node_id":"n4","title":"决策树与随机森林","knowledge_point":"决策树","status":"todo","resource_pack_id":null,"estimated_minutes":45},
         {"node_id":"n5","title":"神经网络基础","knowledge_point":"神经网络","status":"todo","resource_pack_id":null,"estimated_minutes":60}
       ],
       "edges": [{"from":"n1","to":"n2"},{"from":"n2","to":"n3"},{"from":"n3","to":"n4"},{"from":"n4","to":"n5"}],
       "adjustments": [
         {"at":"2026-05-10T09:00:00+08:00","reason":"A*搜索测验正确率仅55%，插入复习节点","diff":{"inserted_node_id":"n2b"}}
       ]
     }')
ON DUPLICATE KEY UPDATE path_json=path_json;


-- ============================================================
-- 8. 做题与学习行为
-- ============================================================
INSERT INTO quiz_attempts (id, user_id, course_id, topic, node_id, pack_id, answers_json, score, weak_tags, duration_seconds) VALUES
    ('qa-001', 'user-test-001', 'course-ai-001', '贝叶斯分类器', 'n3', 'pack-001',
     '[{"question_id":"q1","answer":"B"},{"question_id":"q2","answer":"C"},{"question_id":"q3","answer":"A"}]',
     0.67, '["贝叶斯公式","先验/后验混淆"]', 180),
    ('qa-002', 'user-test-001', 'course-ai-001', 'A*搜索', 'n2', NULL,
     '[{"question_id":"q1","answer":"A"},{"question_id":"q2","answer":"B"}]',
     0.55, '["启发函数设计","A*最优性条件"]', 240)
ON DUPLICATE KEY UPDATE answers_json=answers_json;


INSERT INTO learning_events (id, user_id, course_id, event_type, payload_json) VALUES
    ('le-001', 'user-test-001', 'course-ai-001', 'resource_opened',
     '{"resource_id":"res-001","type":"doc","title":"贝叶斯分类器精讲"}'),
    ('le-002', 'user-test-001', 'course-ai-001', 'quiz_submitted',
     '{"attempt_id":"qa-001","score":0.67}'),
    ('le-003', 'user-test-001', 'course-ai-001', 'node_completed',
     '{"node_id":"n2","title":"A*搜索算法"}')
ON DUPLICATE KEY UPDATE payload_json=payload_json;


-- ============================================================
-- 9. 知识库元数据
-- ============================================================
INSERT INTO knowledge_documents (id, course_id, title, source_type, file_path, doc_hash, chunk_count) VALUES
    ('kd-001', 'course-ai-001', 'AI导论-讲义-第1章 绪论',              'lecture',      'kb/course-ai-001/processed/ch01_绪论.md',              'a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6a7b8c9d0', 12),
    ('kd-002', 'course-ai-001', 'AI导论-讲义-第3章 贝叶斯分类器',        'lecture',      'kb/course-ai-001/processed/ch03_贝叶斯分类器.md',       'b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6a7b8c9d0e1', 18),
    ('kd-003', 'course-ai-001', 'AI导论-习题集-贝叶斯与决策树',          'exercise_bank','kb/course-ai-001/processed/exercises_贝叶斯练习.md',    'c3d4e5f6a7b8c9d0e1f2a3b4c5d6a7b8c9d0e1f2', 10),
    ('kd-004', 'course-ai-001', 'AI术语表',                             'glossary',     'kb/course-ai-001/processed/glossary_术语表.md',         'd4e5f6a7b8c9d0e1f2a3b4c5d6a7b8c9d0e1f2a3', 8)
ON DUPLICATE KEY UPDATE title=title;

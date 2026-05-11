-- ============================================================
-- [已废弃] 此迁移已在 sql/schema.sql v2.0 中内建
-- schema.sql 的 users 表已包含 email 字段，无需单独迁移
-- ============================================================

USE learnthink;

-- 检查并添加 email 字段
ALTER TABLE users 
ADD COLUMN IF NOT EXISTS email VARCHAR(254) COMMENT '邮箱地址';

-- 为 email 字段创建唯一索引（如果不存在）
-- 注意：如果表中已有数据，需要先为现有记录设置默认邮箱值
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email ON users(email);

-- 如果需要将 email 设置为 NOT NULL，需要先确保所有记录都有邮箱值
-- UPDATE users SET email = CONCAT(username, '@learnthink.com') WHERE email IS NULL;
-- ALTER TABLE users MODIFY COLUMN email VARCHAR(254) NOT NULL;

-- 查看表结构确认
DESCRIBE users;

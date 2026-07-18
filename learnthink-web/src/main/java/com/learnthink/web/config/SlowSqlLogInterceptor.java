package com.learnthink.web.config;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.Properties;

/**
 * Phase 5 H3: MyBatis 慢查询日志拦截器。
 * 记录执行时间 > 500ms 的 SQL 语句，便于性能分析。
 *
 * 通过 @Component 自动注册到 MyBatis SqlSessionFactory。
 */
@Component
@Intercepts({
    @Signature(
        type = StatementHandler.class,
        method = "query",
        args = {Statement.class, ResultHandler.class}
    ),
    @Signature(
        type = StatementHandler.class,
        method = "update",
        args = {Statement.class}
    ),
    @Signature(
        type = StatementHandler.class,
        method = "batch",
        args = {Statement.class}
    )
})
public class SlowSqlLogInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(SlowSqlLogInterceptor.class);

    /** 慢查询阈值（毫秒），默认 500ms */
    private long thresholdMs = 500;

    public void setThresholdMs(long thresholdMs) {
        this.thresholdMs = thresholdMs;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long startTime = System.currentTimeMillis();
        Object result = invocation.proceed();
        long elapsed = System.currentTimeMillis() - startTime;

        if (elapsed >= thresholdMs) {
            try {
                StatementHandler statementHandler = (StatementHandler) invocation.getTarget();
                BoundSql boundSql = statementHandler.getBoundSql();
                String sql = boundSql.getSql();
                // 截断过长 SQL 避免日志膨胀
                if (sql.length() > 500) {
                    sql = sql.substring(0, 500) + "...";
                }
                // 去除换行和多余空格
                sql = sql.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
                log.warn("Slow SQL [{}ms]: {}", elapsed, sql);
            } catch (Exception e) {
                log.debug("Failed to extract slow SQL log: {}", e.getMessage());
            }
        }

        return result;
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        String threshold = properties.getProperty("thresholdMs");
        if (threshold != null) {
            try {
                this.thresholdMs = Long.parseLong(threshold);
            } catch (NumberFormatException e) {
                log.warn("Invalid thresholdMs value: {}", threshold);
            }
        }
    }
}

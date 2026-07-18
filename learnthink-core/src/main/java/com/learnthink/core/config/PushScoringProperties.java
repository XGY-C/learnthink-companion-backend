package com.learnthink.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 推送评分配置 - 可通过 application.yml 动态调参
 *
 * <p>配置前缀：learnthink.push.scoring</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "learnthink.push.scoring")
public class PushScoringProperties {

    /** 路径匹配 - 下一模块知识点匹配的额外加分 */
    private double pathMatchBonusNext = 0.3;

    /** 路径匹配 - 当前模块未学知识点匹配的额外加分 */
    private double pathMatchBonusCurrent = 0.2;

    /** 薄弱知识点匹配的额外加分 */
    private double weaknessMatchBonus = 0.3;

    /** 兴趣匹配的额外加分 */
    private double interestMatchBonus = 0.2;

    /** 推荐理由 - path_match 高阈值（触发"即将进入的模块"理由） */
    private double reasonThresholdPathHigh = 0.5;

    /** 推荐理由 - path_match 低阈值（触发"当前学习模块"理由） */
    private double reasonThresholdPathLow = 0.3;

    /** 推荐理由 - weakness_match 阈值 */
    private double reasonThresholdWeakness = 0.3;

    /** 推荐理由 - interest_match 阈值 */
    private double reasonThresholdInterest = 0.3;

    /** 候选池最大数量 */
    private int candidatePoolLimit = 50;

    /** 最近薄弱标签查询天数 */
    private int weakTagsRecentDays = 7;

    /** bigram 匹配置信度阈值（交集比例超过此值判定匹配） */
    private double bigramMatchThreshold = 0.5;

    /** 缓存 TTL（分钟） */
    private int cacheTtlMinutes = 5;

    /** 缓存最大条目数 */
    private int cacheMaxSize = 500;
}

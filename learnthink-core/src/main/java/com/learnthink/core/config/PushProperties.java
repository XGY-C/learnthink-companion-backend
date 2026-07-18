package com.learnthink.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 推送顶层配置 - 绑定 learnthink.push 前缀下的非 scoring 字段
 *
 * <p>配置前缀：learnthink.push</p>
 * <p>scoring 子配置由 {@link PushScoringProperties} 单独绑定</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "learnthink.push")
public class PushProperties {

    /** 推送总开关 */
    private boolean enabled = true;

    /** 两次事件驱动推送的最小间隔（分钟） */
    private int cooldownMinutes = 120;

    /** Dashboard 推荐默认返回数量 */
    private int recommendationLimit = 5;
}

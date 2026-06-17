package com.learnthink.core.service;

import com.learnthink.common.dto.push.PushReason;
import com.learnthink.common.dto.push.ScoredPack;

import java.util.List;

/**
 * 精准资源推送服务接口
 */
public interface PushService {

    /**
     * 获取给用户的 Top-N 推荐资源包（Dashboard 主动查看场景）
     */
    List<ScoredPack> getRecommendations(String userId, String courseId, int limit);

    /**
     * 通知资源就绪（事件驱动，在资源生成完成时调用）
     *
     * @param pushType push_resource_ready / push_path_next
     */
    void notifyResourceReady(String userId, String courseId, String packId,
                             String pushType, List<PushReason> reasons);

    /**
     * 通知薄弱点发现（事件驱动，在 quiz 提交后发现新的 weak tag 时调用）
     */
    void notifyWeaknessFound(String userId, String courseId, String weakTag, String packId);

    /**
     * 获取未读推送通知数量
     */
    int getUnreadPushCount(String userId);
}

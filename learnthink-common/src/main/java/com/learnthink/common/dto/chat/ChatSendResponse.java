package com.learnthink.common.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSendResponse {
    /** 会话ID */
    private String chatId;
    /** 本轮新增的消息列表 */
    private List<ChatMessageDto> newMessages;
    /** 画像是否已就绪（覆盖度 >= 4/7） */
    private boolean profileReady;
    /** 当前画像版本ID */
    private String profileVersionId;

    /** 系统是否应向用户展示资源生成确认 */
    private boolean generationReady;
    /** 生成相关元数据（已覆盖维度、建议资源类型等） */
    private Map<String, Object> generationMeta;
}

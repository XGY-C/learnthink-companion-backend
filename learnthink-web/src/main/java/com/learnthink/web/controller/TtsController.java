package com.learnthink.web.controller;

import com.learnthink.common.result.Result;
import com.learnthink.common.util.TtsUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class TtsController {

    private final TtsUtil ttsUtil;

    /**
     * 将文本合成为语音并上传到 OSS，返回音频 URL
     */
    @PostMapping("/tts")
    public ResponseEntity<Result<Map<String, String>>> synthesize(@RequestBody Map<String, String> request) {
        String text = request.get("text");
        if (text == null || text.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Result.error("文本不能为空"));
        }
        if (text.length() > 10000) {
            text = text.substring(0, 10000);
        }
        try {
            TtsUtil.TaskStatus status = ttsUtil.synthesizeLongTextAndUpload(text);
            String audioUrl = status.getAudioAddress();
            log.info("TTS synthesis completed, audioUrl: {}", audioUrl);
            return ResponseEntity.ok(Result.success(Map.of("audioUrl", audioUrl)));
        } catch (Exception e) {
            log.error("TTS synthesis failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Result.error("语音合成失败: " + e.getMessage()));
        }
    }
}

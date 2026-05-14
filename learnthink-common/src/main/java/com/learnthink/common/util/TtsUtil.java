package com.learnthink.common.util;

import com.alibaba.nls.client.AccessToken;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.exception.BusinessException;
import com.learnthink.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;


/**
 * 阿里云语音合成（TTS）工具类
 * 注意：传入文本必须采用 UTF-8 编码。
 * 参考文档：<a href="https://help.aliyun.com/zh/isi/developer-reference/sdk-reference-1?spm=0.0.0.0">...</a>
 * @author 谢光益
 * @since 2026/2/28
 */
@Component
public class TtsUtil implements InitializingBean {

    @Value("${aliyun.oss.accessKeyId}")
    private String accessKeyId;
    @Value("${aliyun.oss.accessKeySecret}")
    private String accessKeySecret;
    @Value("${aliyun.tts.appKey}")
    private String appKey;
    @Value("${aliyun.tts.url}")
    private String asyncUrl;

    private static final String DEFAULT_ASYNC_URL = "https://nls-gateway-cn-shanghai.aliyuncs.com/rest/v1/tts/async";
    private static final Logger logger = LoggerFactory.getLogger(TtsUtil.class);

    private final RestTemplate restTemplate;
    private final AliOSSUtil aliOSSUtils;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TtsUtil(RestTemplate restTemplate, AliOSSUtil aliOSSUtils) {
        this.restTemplate = restTemplate;
        this.aliOSSUtils = aliOSSUtils;
    }

    private final AtomicReference<TokenHolder> tokenHolder = new AtomicReference<>();

    private static class TokenHolder {
        final String token;
        final long expireTime;

        TokenHolder(String token, long expireTime) {
            this.token = token;
            this.expireTime = expireTime;
        }

        boolean isValid() {
            return System.currentTimeMillis() / 1000 < expireTime - 300;
        }
    }

    @Override
    public void afterPropertiesSet() {
        if (accessKeyId == null || accessKeyId.isEmpty()) {
            throw new IllegalArgumentException("配置项 aliyun.oss.accessKeyId 不能为空");
        }
        if (accessKeySecret == null || accessKeySecret.isEmpty()) {
            throw new IllegalArgumentException("配置项 aliyun.oss.accessKeySecret 不能为空");
        }
        if (appKey == null || appKey.isEmpty()) {
            throw new IllegalArgumentException("配置项 aliyun.tts.appKey 不能为空");
        }
        if (asyncUrl == null || asyncUrl.isEmpty()) {
            asyncUrl = DEFAULT_ASYNC_URL;
            logger.info("使用默认异步 TTS 服务地址: {}", asyncUrl);
        }
    }

    private String getValidToken() throws IOException {
        TokenHolder holder = tokenHolder.get();
        if (holder != null && holder.isValid()) {
            return holder.token;
        }
        synchronized (this) {
            holder = tokenHolder.get();
            if (holder != null && holder.isValid()) {
                return holder.token;
            }
            AccessToken accessToken = new AccessToken(accessKeyId, accessKeySecret);
            accessToken.apply();
            String newToken = accessToken.getToken();
            long expireTime = accessToken.getExpireTime();
            tokenHolder.set(new TokenHolder(newToken, expireTime));
            logger.info("TTS Token 刷新成功，有效期至: {}", expireTime);
            return newToken;
        }
    }

    /**
     * 提交长文本语音合成任务
     *
     * @param request 合成请求参数封装对象
     * @return 任务ID (taskId)
     * @throws BusinessException 提交失败时抛出异常
     */
    public String submitTask(TtsRequest request) throws Exception {
        // 构建请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 构建完整的请求体（自动包含配置中的 appKey 和 token）
        TtsRequestWrapper wrapper = new TtsRequestWrapper();
        wrapper.setHeader(new TtsRequestWrapper.Header(appKey, getValidToken()));
        wrapper.setContext(new TtsRequestWrapper.Context("springboot-tts-client")); // device_id 可自定义
        wrapper.setPayload(new TtsRequestWrapper.Payload(request, false, null)); // 默认关闭回调
        logger.info("TTS任务提交成功，请求体：{}", objectMapper.writeValueAsString(wrapper));

        // 发送 POST 请求
        HttpEntity<TtsRequestWrapper> entity = new HttpEntity<>(wrapper, headers);
        ResponseEntity<TtsSubmitResponse> responseEntity;
        try {
            responseEntity = restTemplate.exchange(
                    asyncUrl,
                    HttpMethod.POST,
                    entity,
                    TtsSubmitResponse.class
            );
        } catch (RestClientException e) {
            logger.error("提交TTS任务失败，请求URL: {}", asyncUrl, e);
            throw new BusinessException(ErrorCode.TTS_SUBMIT_FAILED, "提交语音合成任务失败: " + e.getMessage());
        }

        // 解析响应
        TtsSubmitResponse response = responseEntity.getBody();
        if (response == null || response.getErrorCode() != 20000000) {
            String errorMsg = response == null ? "响应为空" : response.getErrorMessage();
            logger.error("提交TTS任务失败，错误码: {}, 错误信息: {}",
                    response == null ? "null" : response.getErrorCode(), errorMsg);
            throw new BusinessException(ErrorCode.TTS_SUBMIT_FAILED, "语音合成任务提交失败: " + errorMsg);
        }

        String taskId = response.getData().getTaskId();
        logger.info("TTS任务提交成功，taskId: {}", taskId);
        return taskId;
    }

    /**
     * 查询任务状态
     *
     * @param taskId 任务ID
     * @return 任务状态对象，包含当前状态和音频地址（若已完成）
     * @throws BusinessException 查询失败时抛出异常
     */
    public TaskStatus queryTaskStatus(String taskId) throws Exception {
        String url = UriComponentsBuilder.fromHttpUrl(asyncUrl)
                .queryParam("appkey", appKey)
                .queryParam("task_id", taskId)
                .queryParam("token", getValidToken())
                .build()
                .toUriString();

        logger.info("========== TTS查询请求开始 ==========");
        logger.info("taskId: {}", taskId);
        logger.info("请求URL: {}", url);

        ResponseEntity<String> rawResponseEntity;
        try {
            rawResponseEntity = restTemplate.getForEntity(url, String.class);
            logger.info("HTTP状态码: {}", rawResponseEntity.getStatusCode());
            logger.info("响应头: {}", rawResponseEntity.getHeaders());
        } catch (RestClientException e) {
            logger.error("❌ HTTP请求异常，taskId: {}", taskId, e);
            throw new BusinessException(ErrorCode.TTS_QUERY_FAILED, "查询任务状态失败: " + e.getMessage());
        }

        String responseBody = rawResponseEntity.getBody();
        logger.info("原始响应体长度: {}", responseBody == null ? 0 : responseBody.length());

        if (responseBody == null || responseBody.isEmpty()) {
            logger.error("❌ 响应体为空，taskId: {}", taskId);
            throw new BusinessException(ErrorCode.TTS_QUERY_FAILED, "查询任务状态失败: 响应为空");
        }

        String trimmedBody = responseBody.trim();
        logger.info("响应体前500字符: \n{}", trimmedBody.length() > 500 ? trimmedBody.substring(0, 500) : trimmedBody);

        if (!trimmedBody.startsWith("{")) {
            logger.error("========== ❌ 响应格式错误 ==========");
            logger.error("期望: JSON对象（以 { 开头）");
            logger.error("实际: 第1个字符是 '{}' (ASCII码: {})",
                    trimmedBody.charAt(0), (int) trimmedBody.charAt(0));
            logger.error("完整响应内容:\n{}", trimmedBody);
            logger.error("======================================");

            String preview = trimmedBody.length() > 200 ? trimmedBody.substring(0, 200) : trimmedBody;
            throw new BusinessException(ErrorCode.TTS_QUERY_FAILED, String.format(
                    "查询任务状态失败: 响应格式错误，非JSON格式。第1个字符='%c'(ASCII=%d)，前200字符: %s",
                    trimmedBody.charAt(0), (int) trimmedBody.charAt(0), preview));
        }

        TtsQueryResponse response;
        try {
            response = objectMapper.readValue(trimmedBody, TtsQueryResponse.class);
            logger.info("✅ JSON解析成功");
        } catch (Exception e) {
            logger.error("========== ❌ JSON解析失败 ==========");
            logger.error("异常类型: {}", e.getClass().getName());
            logger.error("异常消息: {}", e.getMessage());
            logger.error("响应内容:\n{}", trimmedBody);
            logger.error("======================================");
            throw new BusinessException(ErrorCode.TTS_QUERY_FAILED, "查询任务状态失败: JSON解析异常 - " + e.getMessage());
        }

        if (response == null) {
            logger.error("❌ 解析后响应对象为null，taskId: {}", taskId);
            throw new BusinessException(ErrorCode.TTS_QUERY_FAILED, "查询任务状态失败: 解析后响应为空");
        }

        logger.info("error_code: {}", response.getErrorCode());
        logger.info("error_message: {}", response.getErrorMessage());
        logger.info("request_id: {}", response.getRequestId());

        if (response.getErrorCode() != 20000000) {
            logger.error("========== ❌ 业务错误 ==========");
            logger.error("错误码: {}", response.getErrorCode());
            logger.error("错误信息: {}", response.getErrorMessage());
            logger.error("======================================");
            throw new BusinessException(ErrorCode.TTS_QUERY_FAILED, "查询任务状态失败: " + response.getErrorMessage());
        }

        TaskStatus status = new TaskStatus();
        status.setTaskId(taskId);

        TtsQueryResponse.Data data = response.getData();
        if (data != null) {
            status.setAudioAddress(data.getAudioAddress());
            status.setSentences(data.getSentences());

            logger.info("audio_address: {}", data.getAudioAddress());
            logger.info("sentences数量: {}", data.getSentences() == null ? 0 : data.getSentences().size());

            if (data.getAudioAddress() != null && !data.getAudioAddress().isEmpty()) {
                status.setStatus("SUCCESS");
                logger.info("========== ✅ TTS任务完成 ==========");
                logger.info("taskId: {}", taskId);
                logger.info("audioAddress: {}", data.getAudioAddress());
                logger.info("======================================");
            } else {
                status.setStatus("RUNNING");
                logger.debug("⏳ TTS任务处理中，taskId: {}", taskId);
            }
        } else {
            status.setStatus("RUNNING");
            logger.warn("⚠️ data字段为null，taskId: {}", taskId);
        }

        return status;
    }

    /**
     * 轮询等待任务完成
     *
     * @param taskId         任务ID
     * @param pollIntervalMs 轮询间隔（毫秒）
     * @param timeoutMs      超时时间（毫秒）
     * @return 音频下载地址
     * @throws BusinessException 超时或失败时抛出异常
     */
    public TaskStatus waitForCompletion(String taskId, long pollIntervalMs, long timeoutMs) throws Exception {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            TaskStatus status = queryTaskStatus(taskId);
            if (status.getAudioAddress() != null) {
                logger.info("TTS任务完成，status: {}", status);
                return status;
            }
            logger.debug("TTS任务处理中，taskId: {}, 当前状态: {}", taskId, status.getStatus());
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.TTS_TIMEOUT, "轮询被中断: " + e.getMessage());
            }
        }
        throw new BusinessException(ErrorCode.TTS_TIMEOUT, "轮询超时，taskId: " + taskId);
    }

    /**
     * 下载音频文件并上传到OSS
     *
     * @param audioUrl 音频下载地址
     * @param audioName 音频文件名（包含扩展名）
     * @param savePath 保存路径(xxx/xxx/xxx)
     * @throws BusinessException 下载失败时抛出异常
     */
    public String uploadAudio(String audioUrl, String audioName,String savePath) throws Exception {
        try {
            byte[] audioBytes = downloadFileAsBytes(audioUrl);
            if (audioBytes != null) {
                String ossUrl = aliOSSUtils.uploadAudio(audioBytes, audioName, savePath);
                logger.info("音频下载成功，上传至OSS: {}", ossUrl);
                return ossUrl;
            } else {
                throw new BusinessException(ErrorCode.TTS_DOWNLOAD_FAILED, "下载音频失败，HTTP状态码: " + 404);
            }
        } catch (IOException | RestClientException e) {
            logger.error("下载音频失败，audioUrl: {}", audioUrl, e);
            throw new BusinessException(ErrorCode.TTS_DOWNLOAD_FAILED, "上传音频失败: " + e.getMessage());
        }
    }

    /**
     * 从指定URL下载文件，返回文件的字节数组
     *
     * @param fileUrl 文件的HTTP/HTTPS地址
     * @return 文件内容的字节数组
     */
    public byte[] downloadFileAsBytes(String fileUrl) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient(); // 创建HttpClient实例
        java.net.http.HttpRequest request = HttpRequest.newBuilder() // 创建HttpRequest实例
                .uri(URI.create(fileUrl))  // URI.create 不会重新编码
                .GET() // 设置请求方法为GET
                .build();
        // 发送请求并获取响应
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return response.body();
    }

    /**
     * 长文本文本合成音频
     * @param text 待合成文本
     * @return 任务状态对象，包含任务ID、状态和音频地址（若已完成）
     * @throws BusinessException 处理过程中任何错误抛出此异常
     */
    public TaskStatus synthesizeLongText(String text) throws Exception {
        TtsRequest request = new TtsRequest();
        request.setText(text);
        request.setVoice("zhiqian");
        request.setFormat("wav");
        request.setSampleRate(24000);
        request.setEnableSubtitle(true);
        String taskId = submitTask(request);
        return waitForCompletion(taskId, 1000, 600000);
    }

    /**
     * 长文本文本合成音频并上传到OSS audio目录下
     * @param text 待合成文本
     */
    public TaskStatus synthesizeLongTextAndUpload(String text) throws Exception {
        TaskStatus status = synthesizeLongText(text);
        if (status.getAudioAddress() != null) {
            // 上传音频到OSS，音频名为sentence第一个句子的前三个字符
            String ossUrl =uploadAudio(status.getAudioAddress(), status.getSentences().getFirst().getText().substring(0, 3) + ".wav", "audio/");
            status.setAudioAddress(ossUrl);
        }
        return status;
    }

    /**
     * 一体化方法：提交任务 -> 等待完成 -> 下载音频 -> 上传到OSS
     *
     * @param text          待合成文本
     * @param voice         发音人
     * @param format        音频格式 (pcm/wav/mp3)
     * @param sampleRate    采样率 (8000/16000),实际参数请参考阿里云文档
     * @param audioName     音频文件名（包含扩展名）
     * @param savePath      音频保存路径（包含文件名）
     * @throws BusinessException 处理过程中任何错误抛出此异常
     */
    public void synthesizeToFile(String text, String voice, String format, int sampleRate, String audioName, String savePath) throws Exception {
        TtsRequest request = new TtsRequest();
        request.setText(text);
        request.setVoice(voice);
        request.setFormat(format);
        request.setSampleRate(sampleRate);
        request.setEnableSubtitle(true);

        String taskId = submitTask(request);
        String audioUrl = waitForCompletion(taskId, 5000, 3 * 60 * 60 * 1000).getAudioAddress(); // 默认超时3小时
        uploadAudio(audioUrl, audioName, savePath);
    }


    // ==================== 内部数据类 ====================

    /**
     * 用户提交任务的请求参数（对应 tts_request）
     */
    public static class TtsRequest {
        @JsonProperty("voice")
        private String voice = "zhiqian";
        @JsonProperty("sample_rate")
        private int sampleRate = 24000;
        @JsonProperty("format")
        private String format = "wav";
        @JsonProperty("text")
        private String text;
        @JsonProperty("enable_subtitle")
        private boolean enableSubtitle = true;
        @JsonProperty("volume")
        private Integer volume;
        @JsonProperty("speech_rate")
        private Integer speechRate;
        @JsonProperty("pitch_rate")
        private Integer pitchRate;

        // getters and setters
        public String getVoice() { return voice; }
        public void setVoice(String voice) { this.voice = voice; }
        public int getSampleRate() { return sampleRate; }
        public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public boolean isEnableSubtitle() { return enableSubtitle; }
        public void setEnableSubtitle(boolean enableSubtitle) { this.enableSubtitle = enableSubtitle; }
        public Integer getVolume() { return volume; }
        public void setVolume(Integer volume) { this.volume = volume; }
        public Integer getSpeechRate() { return speechRate; }
        public void setSpeechRate(Integer speechRate) { this.speechRate = speechRate; }
        public Integer getPitchRate() { return pitchRate; }
        public void setPitchRate(Integer pitchRate) { this.pitchRate = pitchRate; }
    }

    /**
     * 完整请求体包装类
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class TtsRequestWrapper {
        private Header header;
        private Context context;
        private Payload payload;

        public Header getHeader() { return header; }
        public void setHeader(Header header) { this.header = header; }
        public Context getContext() { return context; }
        public void setContext(Context context) { this.context = context; }
        public Payload getPayload() { return payload; }
        public void setPayload(Payload payload) { this.payload = payload; }

        public TtsRequestWrapper() {}

        public TtsRequestWrapper(Header header, Context context, Payload payload) {
            this.header = header;
            this.context = context;
            this.payload = payload;
        }

        static class Header {
            private String appkey;
            private String token;
            public Header(String appkey, String token) { this.appkey = appkey; this.token = token; }
            public Header() {}
            public String getAppkey() { return appkey; }
            public void setAppkey(String appkey) { this.appkey = appkey; }
            public String getToken() { return token; }
            public void setToken(String token) { this.token = token; }
        }

        static class Context {
            private String deviceId;
            public Context(String deviceId) { this.deviceId = deviceId; }
            public Context() {}
            public String getDeviceId() { return deviceId; }
            public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
        }

        static class Payload {
            @JsonProperty("tts_request")
            private TtsRequest ttsRequest;
            @JsonProperty("enable_notify")
            private boolean enableNotify;
            @JsonProperty("notify_url")
            private String notifyUrl;
            public Payload(TtsRequest ttsRequest, boolean enableNotify, String notifyUrl) {
                this.ttsRequest = ttsRequest;
                this.enableNotify = enableNotify;
                this.notifyUrl = notifyUrl;
            }
            public Payload() {}
            public TtsRequest getTtsRequest() { return ttsRequest; }
            public void setTtsRequest(TtsRequest ttsRequest) { this.ttsRequest = ttsRequest; }
            public boolean isEnableNotify() { return enableNotify; }
            public void setEnableNotify(boolean enableNotify) { this.enableNotify = enableNotify; }
            public String getNotifyUrl() { return notifyUrl; }
            public void setNotifyUrl(String notifyUrl) { this.notifyUrl = notifyUrl; }
        }
    }

    /**
     * 提交任务响应
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class TtsSubmitResponse {
        private int status;
        @JsonProperty("error_code")
        private int errorCode;
        @JsonProperty("error_message")
        private String errorMessage;
        @JsonProperty("request_id")
        private String requestId;
        private Data data;

        public int getStatus() { return status; }
        public void setStatus(int status) { this.status = status; }
        public int getErrorCode() { return errorCode; }
        public void setErrorCode(int errorCode) { this.errorCode = errorCode; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
        public Data getData() { return data; }
        public void setData(Data data) { this.data = data; }

        static class Data {
            @JsonProperty("task_id")
            private String taskId;
            public String getTaskId() { return taskId; }
            public void setTaskId(String taskId) { this.taskId = taskId; }
        }
    }

    /**
     * 查询任务响应
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TtsQueryResponse {
        private int status;
        @JsonProperty("error_code")
        private int errorCode;
        @JsonProperty("error_message")
        private String errorMessage;
        @JsonProperty("request_id")
        private String requestId;
        private Data data;

        public int getStatus() { return status; }
        public void setStatus(int status) { this.status = status; }
        public int getErrorCode() { return errorCode; }
        public void setErrorCode(int errorCode) { this.errorCode = errorCode; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
        public Data getData() { return data; }
        public void setData(Data data) { this.data = data; }

        @JsonIgnoreProperties(ignoreUnknown = true)
        static class Data {
            @JsonProperty("task_id")
            private String taskId;
            @JsonProperty("audio_address")
            private String audioAddress;
            private List<Sentence> sentences;
            public String getTaskId() { return taskId; }
            public void setTaskId(String taskId) { this.taskId = taskId; }
            public String getAudioAddress() { return audioAddress; }
            public void setAudioAddress(String audioAddress) { this.audioAddress = audioAddress; }
            public List<Sentence> getSentences() { return sentences; }
            public void setSentences(List<Sentence> sentences) { this.sentences = sentences; }
        }

        public static class Sentence {
            private String text;
            @JsonProperty("begin_time")
            private String beginTime;
            @JsonProperty("end_time")
            private String endTime;
            public String getText() { return text; }
            public void setText(String text) { this.text = text; }
            public String getBeginTime() { return beginTime; }
            public void setBeginTime(String beginTime) { this.beginTime = beginTime; }
            public String getEndTime() { return endTime; }
            public void setEndTime(String endTime) { this.endTime = endTime; }
        }
    }

    /**
     * 对外暴露的任务状态封装
     */
    public static class TaskStatus {
        private String taskId;
        private String status; // RUNNING, SUCCESS, etc.
        private String audioAddress;
        private List<TtsQueryResponse.Sentence> sentences;

        public String getTaskId() { return taskId; }
        public void setTaskId(String taskId) { this.taskId = taskId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getAudioAddress() { return audioAddress; }
        public void setAudioAddress(String audioAddress) { this.audioAddress = audioAddress; }
        public List<TtsQueryResponse.Sentence> getSentences() { return sentences; }
        public void setSentences(List<TtsQueryResponse.Sentence> sentences) { this.sentences = sentences; }
    }
}
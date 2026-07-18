package com.learnthink.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tutoring")
public class TutoringConfig {
    private ReactConfig react = new ReactConfig();
    private ClarificationConfig clarification = new ClarificationConfig();
    private RagConfig rag = new RagConfig();
    private GeneratorConfig generator = new GeneratorConfig();
    private DiagramConfig diagram = new DiagramConfig();
    private Phase2Config phase2 = new Phase2Config();
    private SmartConfig smart = new SmartConfig();

    public ReactConfig getReact() { return react; }
    public void setReact(ReactConfig react) { this.react = react; }
    public ClarificationConfig getClarification() { return clarification; }
    public void setClarification(ClarificationConfig clarification) { this.clarification = clarification; }
    public RagConfig getRag() { return rag; }
    public void setRag(RagConfig rag) { this.rag = rag; }
    public GeneratorConfig getGenerator() { return generator; }
    public void setGenerator(GeneratorConfig generator) { this.generator = generator; }
    public DiagramConfig getDiagram() { return diagram; }
    public void setDiagram(DiagramConfig diagram) { this.diagram = diagram; }
    public Phase2Config getPhase2() { return phase2; }
    public void setPhase2(Phase2Config phase2) { this.phase2 = phase2; }
    public SmartConfig getSmart() { return smart; }
    public void setSmart(SmartConfig smart) { this.smart = smart; }

    public static class ReactConfig {
        private int maxIterations = 10;
        private int perIterationTimeoutMs = 120000;
        public int getMaxIterations() { return maxIterations; }
        public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
        public int getPerIterationTimeoutMs() { return perIterationTimeoutMs; }
        public void setPerIterationTimeoutMs(int perIterationTimeoutMs) { this.perIterationTimeoutMs = perIterationTimeoutMs; }
    }

    public static class ClarificationConfig {
        private int timeoutSeconds = 30;
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public static class RagConfig {
        private int denseTopK = 20;
        private int sparseTopK = 20;
        private int rrfK = 60;
        private int rrfTopN = 15;
        private int crossEncoderTopNCore = 5;
        private int crossEncoderTopNSupplement = 3;
        private int perQueryTimeoutMs = 500;

        public int getDenseTopK() { return denseTopK; }
        public void setDenseTopK(int denseTopK) { this.denseTopK = denseTopK; }
        public int getSparseTopK() { return sparseTopK; }
        public void setSparseTopK(int sparseTopK) { this.sparseTopK = sparseTopK; }
        public int getRrfK() { return rrfK; }
        public void setRrfK(int rrfK) { this.rrfK = rrfK; }
        public int getRrfTopN() { return rrfTopN; }
        public void setRrfTopN(int rrfTopN) { this.rrfTopN = rrfTopN; }
        public int getCrossEncoderTopNCore() { return crossEncoderTopNCore; }
        public void setCrossEncoderTopNCore(int crossEncoderTopNCore) { this.crossEncoderTopNCore = crossEncoderTopNCore; }
        public int getCrossEncoderTopNSupplement() { return crossEncoderTopNSupplement; }
        public void setCrossEncoderTopNSupplement(int crossEncoderTopNSupplement) { this.crossEncoderTopNSupplement = crossEncoderTopNSupplement; }
        public int getPerQueryTimeoutMs() { return perQueryTimeoutMs; }
        public void setPerQueryTimeoutMs(int perQueryTimeoutMs) { this.perQueryTimeoutMs = perQueryTimeoutMs; }
    }

    public static class GeneratorConfig {
        private int streamTimeoutSeconds = 30;
        private String model = "deepseek-v4-flash";
        private double temperature = 0.7;
        private int maxTokens = 8192;
        public int getStreamTimeoutSeconds() { return streamTimeoutSeconds; }
        public void setStreamTimeoutSeconds(int streamTimeoutSeconds) { this.streamTimeoutSeconds = streamTimeoutSeconds; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }

    public static class DiagramConfig {
        private int maxConcurrency = 3;
        private int generateTimeoutSeconds = 15;
        private boolean degradationEnabled = true;
        public int getMaxConcurrency() { return maxConcurrency; }
        public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
        public int getGenerateTimeoutSeconds() { return generateTimeoutSeconds; }
        public void setGenerateTimeoutSeconds(int generateTimeoutSeconds) { this.generateTimeoutSeconds = generateTimeoutSeconds; }
        public boolean isDegradationEnabled() { return degradationEnabled; }
        public void setDegradationEnabled(boolean degradationEnabled) { this.degradationEnabled = degradationEnabled; }
    }

    public static class Phase2Config {
        private int threadPoolSize = 8;
        public int getThreadPoolSize() { return threadPoolSize; }
        public void setThreadPoolSize(int threadPoolSize) { this.threadPoolSize = threadPoolSize; }
    }

    /**
     * Smart v2 模式配置。
     */
    public static class SmartConfig {
        /** 最大交互轮数（兜底） */
        private int maxInteractions = 20;
        /** 最少交互轮数 */
        private int minInteractions = 3;
        /** 单工具超时（毫秒） */
        private int toolTimeoutMs = 30000;
        /** LLM 流式超时（毫秒） */
        private int llmTimeoutMs = 120000;
        /** 状态 TTL（秒，2小时） */
        private int stateTtlSeconds = 7200;
        /** 每轮最大工具调用次数 */
        private int maxToolCallsPerTurn = 50;
        /** 是否启用可视化工具 */
        private boolean enableVisualTools = true;
        /** 是否启用代码执行 */
        private boolean enableCodeExecution = true;
        /** 送入 LLM 的最大消息条数（约 6 轮） */
        private int contextWindowMessages = 24;
        /** 完整保留的轮数，更早的用摘要替代 */
        private int contextWindowRounds = 6;
        /** 工具结果最大字符数，超则截断 */
        private int maxToolResultChars = 2000;

        public int getMaxInteractions() { return maxInteractions; }
        public void setMaxInteractions(int maxInteractions) { this.maxInteractions = maxInteractions; }
        public int getMinInteractions() { return minInteractions; }
        public void setMinInteractions(int minInteractions) { this.minInteractions = minInteractions; }
        public int getToolTimeoutMs() { return toolTimeoutMs; }
        public void setToolTimeoutMs(int toolTimeoutMs) { this.toolTimeoutMs = toolTimeoutMs; }
        public int getLlmTimeoutMs() { return llmTimeoutMs; }
        public void setLlmTimeoutMs(int llmTimeoutMs) { this.llmTimeoutMs = llmTimeoutMs; }
        public int getStateTtlSeconds() { return stateTtlSeconds; }
        public void setStateTtlSeconds(int stateTtlSeconds) { this.stateTtlSeconds = stateTtlSeconds; }
        public int getMaxToolCallsPerTurn() { return maxToolCallsPerTurn; }
        public void setMaxToolCallsPerTurn(int maxToolCallsPerTurn) { this.maxToolCallsPerTurn = maxToolCallsPerTurn; }
        public boolean isEnableVisualTools() { return enableVisualTools; }
        public void setEnableVisualTools(boolean enableVisualTools) { this.enableVisualTools = enableVisualTools; }
        public boolean isEnableCodeExecution() { return enableCodeExecution; }
        public void setEnableCodeExecution(boolean enableCodeExecution) { this.enableCodeExecution = enableCodeExecution; }
        public int getContextWindowMessages() { return contextWindowMessages; }
        public void setContextWindowMessages(int contextWindowMessages) { this.contextWindowMessages = contextWindowMessages; }
        public int getContextWindowRounds() { return contextWindowRounds; }
        public void setContextWindowRounds(int contextWindowRounds) { this.contextWindowRounds = contextWindowRounds; }
        public int getMaxToolResultChars() { return maxToolResultChars; }
        public void setMaxToolResultChars(int maxToolResultChars) { this.maxToolResultChars = maxToolResultChars; }
    }
}

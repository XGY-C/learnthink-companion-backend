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
}

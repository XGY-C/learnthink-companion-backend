package com.learnthink.core.agent.manager;

import com.learnthink.core.agent.runtime.AgentContext;
import com.learnthink.core.agent.impl.generators.TypeGenerator;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 管理自主智能体实例，用于并行资源生成。
 *
 * <p>职责：
 * <ul>
 *   <li>按类型注册生成器，支持可配置并行度</li>
 *   <li>将清单项分发到智能体池进行并行执行</li>
 *   <li>汇总所有智能体的进度</li>
 *   <li>触发生命周期事件（started、resourceReady、failed、allDone）</li>
 *   <li>支持任务取消</li>
 * </ul>
 */
public class AgentManager {

    private static final Logger log = LoggerFactory.getLogger(AgentManager.class);

    private static final int MAX_GLOBAL_CONCURRENCY = 8;
    private static final int DEFAULT_PARALLELISM = 3;

    private final Map<String, List<TypeGenerator>> registry = new ConcurrentHashMap<>();
    private final Map<String, Integer> parallelismConfig = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final Map<String, List<Future<?>>> activeJobs = new ConcurrentHashMap<>();
    private Consumer<AgentEvent> eventListener;

    public AgentManager() {
        this.executor = new ThreadPoolExecutor(
            2, MAX_GLOBAL_CONCURRENCY, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100), new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public void register(String resourceType, TypeGenerator generator, int parallelism) {
        registry.computeIfAbsent(resourceType, k -> new CopyOnWriteArrayList<>()).add(generator);
        parallelismConfig.put(resourceType, parallelism);
        log.info("AgentManager registered: type={}, generator={}, parallelism={}",
            resourceType, generator.getClass().getSimpleName(), parallelism);
    }

    public void register(String resourceType, TypeGenerator generator) {
        register(resourceType, generator, DEFAULT_PARALLELISM);
    }

    /**
     * 将生成清单分发到智能体池。
     * 项目按类型分组并行处理。
     */
    public CompletableFuture<DispatchResult> dispatch(
            GenerationChecklist checklist,
            ResourceGenerationState state,
            java.util.function.Function<String, List<ResourceGenerationState.SourceItem>> sourceProvider) {

        String taskId = state.taskId;
        List<GenerationJob> allJobs = new ArrayList<>();

        // 按类型分组
        Map<String, List<GenerationChecklist.ChecklistItem>> grouped = new LinkedHashMap<>();
        for (var item : checklist.items()) {
            grouped.computeIfAbsent(item.type(), k -> new ArrayList<>()).add(item);
        }

        log.info("AgentManager: dispatching {} items across {} types for task {}",
            checklist.items().size(), grouped.size(), taskId);

        for (var entry : grouped.entrySet()) {
            String type = entry.getKey();
            List<GenerationChecklist.ChecklistItem> items = entry.getValue();
            List<TypeGenerator> generators = registry.getOrDefault(type, List.of());

            if (generators.isEmpty()) {
                log.warn("No generator for type: {} — skipping {} items", type, items.size());
                for (var item : items) checklist.markFailed(item.title());
                continue;
            }

            int parallelism = Math.min(parallelismConfig.getOrDefault(type, DEFAULT_PARALLELISM), items.size());
            Semaphore semaphore = new Semaphore(parallelism);

            for (int i = 0; i < items.size(); i++) {
                var item = items.get(i);
                TypeGenerator gen = generators.get(i % generators.size());
                allJobs.add(new GenerationJob(
                    taskId + "-" + type + "-" + i, item, type, gen, semaphore, state, sourceProvider));
            }
        }

        List<Future<?>> futures = new ArrayList<>();
        activeJobs.put(taskId, futures);

        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        Map<String, List<ResourceGenerationState.GeneratedContent>> resultsByType = new ConcurrentHashMap<>();
        Map<String, String> failedItems = new ConcurrentHashMap<>();

        for (GenerationJob job : allJobs) {
            Future<?> future = executor.submit(() -> {
                try {
                    job.semaphore().acquire();
                    checklist.markGenerating(job.item().title());
                    emit(AgentEvent.started(taskId, job.id(), job.type(), job.item().title()));

                    AgentContext ctx = new AgentContext.Builder(
                        taskId, state.userId).courseId(state.courseId).build();

                    List<ResourceGenerationState.SourceItem> typeSources = job.sourceProvider().apply(job.type());
                    ResourceGenerationState.ResourcePlanItem planItem = toPlanItem(job.item());
                    var content = job.generator().generate(planItem, typeSources, state.profileSummary, false, null, ctx);

                    resultsByType.computeIfAbsent(job.type(), k -> new CopyOnWriteArrayList<>()).add(content);
                    checklist.markDone(job.item().title());
                    completed.incrementAndGet();
                    emit(AgentEvent.resourceReady(taskId, job.id(), job.type(),
                        job.item().title(), content.confidence()));

                } catch (Exception e) {
                    log.error("Generation failed: {} ({}): {}", job.id(), job.item().title(), e.getMessage());
                    checklist.markFailed(job.item().title());
                    failed.incrementAndGet();
                    failedItems.put(job.item().title(), e.getMessage());
                    emit(AgentEvent.failed(taskId, job.id(), job.type(),
                        job.item().title(), e.getMessage()));
                } finally {
                    job.semaphore().release();
                }
            });
            futures.add(future);
        }

        return CompletableFuture.supplyAsync(() -> {
            for (Future<?> f : futures) {
                try {
                    f.get(10, TimeUnit.MINUTES);
                } catch (TimeoutException e) {
                    log.error("Job timed out");
                    f.cancel(true);
                    failed.incrementAndGet();
                } catch (Exception e) {
                    log.error("Job error: {}", e.getMessage());
                }
            }
            activeJobs.remove(taskId);
            emit(AgentEvent.allDone(taskId, completed.get(), failed.get(), checklist.totalCount()));
            return new DispatchResult(resultsByType, failedItems, completed.get(), failed.get(), checklist.totalCount());
        }, executor);
    }

    public void cancelTask(String taskId) {
        List<Future<?>> futures = activeJobs.remove(taskId);
        if (futures != null) {
            log.info("AgentManager: cancelling {} jobs for task {}", futures.size(), taskId);
            futures.forEach(f -> f.cancel(true));
        }
    }

    public boolean isTaskActive(String taskId) {
        return activeJobs.containsKey(taskId) &&
            activeJobs.get(taskId).stream().anyMatch(f -> !f.isDone());
    }

    public void setEventListener(Consumer<AgentEvent> listener) { this.eventListener = listener; }

    private void emit(AgentEvent e) {
        if (eventListener != null) eventListener.accept(e);
    }

    private ResourceGenerationState.ResourcePlanItem toPlanItem(GenerationChecklist.ChecklistItem item) {
        return new ResourceGenerationState.ResourcePlanItem(
            item.type(), item.title(), item.difficulty(), item.estimatedMinutes(),
            item.format(), item.keyPoints(), item.personalizationNote(), 0);
    }

    // ---- 内部类型 ----

    private record GenerationJob(
        String id, GenerationChecklist.ChecklistItem item, String type,
        TypeGenerator generator, Semaphore semaphore,
        ResourceGenerationState state,
        java.util.function.Function<String, List<ResourceGenerationState.SourceItem>> sourceProvider
    ) {}

    public record DispatchResult(
        Map<String, List<ResourceGenerationState.GeneratedContent>> results,
        Map<String, String> failedItems,
        int completedCount, int failedCount, int totalCount
    ) {
        public boolean allSucceeded() { return failedCount == 0; }
    }

    public record AgentEvent(
        String eventType, String taskId, String jobId, String resourceType,
        String title, String confidence, String errorMessage,
        int completedCount, int failedCount, int totalCount
    ) {
        public static AgentEvent started(String tid, String jid, String type, String title) {
            return new AgentEvent("started", tid, jid, type, title, null, null, 0, 0, 0);
        }
        public static AgentEvent resourceReady(String tid, String jid, String type, String title, String conf) {
            return new AgentEvent("resourceReady", tid, jid, type, title, conf, null, 0, 0, 0);
        }
        public static AgentEvent failed(String tid, String jid, String type, String title, String err) {
            return new AgentEvent("failed", tid, jid, type, title, null, err, 0, 0, 0);
        }
        public static AgentEvent allDone(String tid, int comp, int fail, int total) {
            return new AgentEvent("allDone", tid, null, null, null, null, null, comp, fail, total);
        }
    }
}

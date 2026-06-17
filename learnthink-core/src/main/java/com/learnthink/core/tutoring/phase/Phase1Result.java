package com.learnthink.core.tutoring.phase;

import com.learnthink.core.tutoring.domain.ExecutionPlan;
import com.learnthink.core.tutoring.domain.ResolvedResources;
import java.util.concurrent.CompletableFuture;

public record Phase1Result(ExecutionPlan plan, CompletableFuture<ResolvedResources> resourcesFuture) {}

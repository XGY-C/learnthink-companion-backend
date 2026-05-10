package com.learnthink.core.agent.base;

/**
 * Legacy agent interface. Kept for backward compatibility.
 *
 * @deprecated Use {@link com.learnthink.core.agent.framework.Agent} instead,
 *             which provides structured I/O, tracing, memory, and budget control.
 */
@Deprecated
public interface Agent {
    String execute(String input);
    String getName();
}

package com.learnthink.core.service;

/**
 * 通用文本评价服务。
 * <p>输入一段文本，输出符合统一标准与规范的结构化评价文本。
 * 供其它评价场景（作答评价、学习行为评价、资源内容评价等）直接复用。
 *
 * <h3>评价标准</h3>
 * <ul>
 *   <li>准确性：内容是否正确、有无事实或概念错误</li>
 *   <li>完整性：是否覆盖核心要点、有无遗漏</li>
 *   <li>条理性：逻辑与结构是否清晰、层次是否分明</li>
 *   <li>深度：理解与思考是否到位、有无见解</li>
 * </ul>
 *
 * <h3>输出规范</h3>
 * 固定 Markdown 结构：总体评价（含等级）-> 维度评价 -> 亮点 -> 不足 -> 改进建议。
 */
public interface EvaluationService {

    /**
     * 对给定文本进行通用评价。
     *
     * @param text 待评价文本
     * @return 结构化评价文本（Markdown）
     */
    String evaluate(String text);

    /**
     * 对给定文本进行评价，可传入场景提示以微调评价侧重点。
     *
     * @param text 待评价文本
     * @param hint 评价场景提示（如"学生作答评价"、"学习行为评价"），可为 null
     * @return 结构化评价文本（Markdown）
     */
    String evaluate(String text, String hint);
}

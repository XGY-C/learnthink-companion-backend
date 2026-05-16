package com.learnthink.core.domain.dto;

import lombok.Data;
import java.util.List;

/**
 * 代码样式指南
 */
@Data
public class CodeStyleGuide {
    private boolean singleFile = true;
    private String mainSceneClass;
    private boolean useHelpers = true;
    private boolean preferBasicShapes = true;
    private boolean preferSafeAnimations = true;
    private String subtitleMethod;
    private List<String> fontFallback;
}

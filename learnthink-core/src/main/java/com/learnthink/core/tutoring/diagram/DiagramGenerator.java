package com.learnthink.core.tutoring.diagram;

import com.learnthink.core.tutoring.domain.DiagramResult;
import com.learnthink.core.tutoring.domain.DiagramSpec;

public interface DiagramGenerator {
    String toolName();
    DiagramResult generate(DiagramSpec spec);
}

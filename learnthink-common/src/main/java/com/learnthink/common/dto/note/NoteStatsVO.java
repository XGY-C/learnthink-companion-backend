package com.learnthink.common.dto.note;

import lombok.Data;
import java.util.List;

@Data
public class NoteStatsVO {
    private int totalCount;
    private List<ResourceStats> byResource;

    @Data
    public static class ResourceStats {
        private String packId;
        private String resourceTitle;
        private int count;
    }
}

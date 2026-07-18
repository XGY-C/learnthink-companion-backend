package com.learnthink.common.dto.resource;

import lombok.Data;
import java.util.List;

@Data
public class ResourceFilePageDTO {
    private List<ResourceFileDTO> items;
    private long total;
    private int page;
    private int size;
}

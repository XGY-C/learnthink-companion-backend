package com.learnthink.common.dto.profile;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProfileVersionItemDto {
    private Integer version;
    private String updatedAt;
    private String source;
}

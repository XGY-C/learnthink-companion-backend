package com.learnthink.common.dto.profile;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProfileVersionsResponse {
    private List<ProfileVersionItemDto> versions;
}

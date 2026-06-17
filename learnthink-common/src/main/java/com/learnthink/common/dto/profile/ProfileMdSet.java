package com.learnthink.common.dto.profile;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProfileMdSet {

    private String coreProfileMd;

    private String learningProfileMd;

    private String knowledgeProfileMd;

    private String displayJson;
}

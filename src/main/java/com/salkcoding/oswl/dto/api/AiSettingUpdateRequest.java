package com.salkcoding.oswl.dto.api;

import com.salkcoding.oswl.domain.enums.AiProvider;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** PUT /api/settings/ai 요청 바디 */
@Getter
@Setter
public class AiSettingUpdateRequest {
    @NotNull
    private AiProvider provider;
    private String     apiKey;
    private String     modelName;
    private String     baseUrl;
    /** true면 저장과 동시에 이 제공자를 활성화 */
    private Boolean    activate;
}

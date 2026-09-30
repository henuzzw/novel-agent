package com.novelagent.canon.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateEntityAliasRequest(
        @NotBlank @Size(max = 200) String alias,
        @NotBlank @Pattern(regexp = "NICKNAME|TITLE|FORMER_NAME|OTHER") String aliasType) {
}

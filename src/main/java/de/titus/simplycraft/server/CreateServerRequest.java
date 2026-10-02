package de.titus.simplycraft.server;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateServerRequest(
        @NotBlank @Size(max = 48) String name,
        @Min(1) @Max(65535) Integer port,
        String xms,
        String xmx
) {
}

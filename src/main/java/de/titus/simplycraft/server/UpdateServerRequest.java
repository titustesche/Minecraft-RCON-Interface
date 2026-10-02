package de.titus.simplycraft.server;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Every field is optional, null keeps the current value. An empty customCommand removes it. */
public record UpdateServerRequest(
        @Size(min = 1, max = 48) String name,
        @Min(1) @Max(65535) Integer port,
        String xms,
        String xmx,
        String javaPath,
        @Size(max = 2000) String customCommand,
        Boolean eulaAccepted,
        String loader,
        String gameVersion
) {
}

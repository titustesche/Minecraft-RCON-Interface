package de.titus.simplycraft.process;

import java.time.Instant;
import java.util.List;

public record ServerStatus(
        String id,
        ServerState state,
        Long pid,
        Instant startedAt,
        Long uptimeSeconds,
        Long memoryBytes,
        List<Player> players,
        Integer maxPlayers,
        Integer exitCode
) {
    public record Player(String name, Instant joinedAt) {
    }
}

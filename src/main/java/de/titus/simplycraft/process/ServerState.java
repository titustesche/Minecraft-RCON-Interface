package de.titus.simplycraft.process;

public enum ServerState {
    OFFLINE,
    STARTING,
    ONLINE,
    STOPPING,
    /** Process ended on its own with an exit code other than 0 */
    CRASHED;

    public boolean running() {
        return this == STARTING || this == ONLINE || this == STOPPING;
    }
}

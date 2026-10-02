package de.titus.simplycraft.server;

import java.time.Instant;

/**
 * Per-server settings, stored as servers/&lt;id&gt;/simplycraft.json next to the server files.
 * There is no database: the server directory is the single source of truth and can be
 * copied, backed up or restored as a whole.
 */
public class ServerConfig {
    private String id;
    private String name;
    private Integer port;
    private String xms;
    private String xmx;
    /** Overrides simplycraft.java-path, e.g. for older Minecraft versions that need another Java */
    private String javaPath;
    /** Optional own start command, supports {java} {xms} {xmx} {jar} */
    private String customCommand;
    private boolean eulaAccepted;
    /** vanilla, fabric, forge, neoforge, quilt, ... – used to filter Modrinth */
    private String loader;
    private String gameVersion;
    private JarInfo jar;
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getXms() { return xms; }
    public void setXms(String xms) { this.xms = xms; }
    public String getXmx() { return xmx; }
    public void setXmx(String xmx) { this.xmx = xmx; }
    public String getJavaPath() { return javaPath; }
    public void setJavaPath(String javaPath) { this.javaPath = javaPath; }
    public String getCustomCommand() { return customCommand; }
    public void setCustomCommand(String customCommand) { this.customCommand = customCommand; }
    public boolean isEulaAccepted() { return eulaAccepted; }
    public void setEulaAccepted(boolean eulaAccepted) { this.eulaAccepted = eulaAccepted; }
    public String getLoader() { return loader; }
    public void setLoader(String loader) { this.loader = loader; }
    public String getGameVersion() { return gameVersion; }
    public void setGameVersion(String gameVersion) { this.gameVersion = gameVersion; }
    public JarInfo getJar() { return jar; }
    public void setJar(JarInfo jar) { this.jar = jar; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

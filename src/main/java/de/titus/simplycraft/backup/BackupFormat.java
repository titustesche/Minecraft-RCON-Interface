package de.titus.simplycraft.backup;

public enum BackupFormat {
    ZIP(".zip"),
    TAR_GZ(".tar.gz");

    private final String extension;

    BackupFormat(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    public static BackupFormat of(String fileName) {
        return fileName.endsWith(TAR_GZ.extension) ? TAR_GZ : ZIP;
    }
}

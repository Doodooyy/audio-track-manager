package com.player;

import java.io.File;

/** Simple track — just a file reference with a display name. */
public class Track {
    private final File   file;
    private final String name;

    public Track(File file) {
        this.file = file;
        String n  = file.getName();
        int dot   = n.lastIndexOf('.');
        this.name = dot > 0 ? n.substring(0, dot) : n;
    }

    public File   getFile() { return file; }
    public String getName() { return name; }

    @Override public String toString() { return name; }
}

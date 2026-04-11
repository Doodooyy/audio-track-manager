package com.player;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class Playlist {
    private String name;
    private final ObservableList<Track> tracks = FXCollections.observableArrayList();

    public Playlist(String name) { this.name = name; }

    public String               getName()          { return name; }
    public void                 setName(String n)  { this.name = n; }
    public ObservableList<Track>getTracks()        { return tracks; }
    public void                 add(Track t)       { if (!tracks.contains(t)) tracks.add(t); }
    public void                 remove(Track t)    { tracks.remove(t); }

    @Override public String toString() { return name; }
}

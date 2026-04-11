package com.player;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Simple Audio Player — bare-bones JavaFX desktop app.
 *
 * Features:
 *  - Add / remove audio files
 *  - Play, Pause, Stop, Next, Previous
 *  - Seek slider + timestamps
 *  - Volume control
 *  - Last-used folder remembered via Preferences
 */
public class App extends Application {

    // ── State ──────────────────────────────────────────────────────────────────
    private final ObservableList<Track> tracks = FXCollections.observableArrayList();
    private MediaPlayer   player;
    private int           currentIndex = -1;
    private boolean       seeking      = false;

    // ── UI refs ────────────────────────────────────────────────────────────────
    private ListView<Track> trackList;
    private Label           nowPlayingLabel;
    private Button          playBtn;
    private Slider          seekSlider;
    private Label           timeLabel;
    private Slider          volSlider;
    private Label           statusLabel;

    // ── Preferences ───────────────────────────────────────────────────────────
    private final Preferences prefs = Preferences.userNodeForPackage(App.class);

    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void start(Stage stage) {
        stage.setTitle("Simple Audio Player");
        stage.setScene(buildScene(stage));
        stage.setMinWidth(480);
        stage.setMinHeight(400);
        stage.show();
    }

    @Override
    public void stop() {
        if (player != null) { player.stop(); player.dispose(); }
    }

    // ── Scene ──────────────────────────────────────────────────────────────────

    private Scene buildScene(Stage stage) {

        // ── Track list ────────────────────────────────────────────────────────
        trackList = new ListView<>(tracks);
        trackList.setPlaceholder(new Label("No tracks — click Add Files"));
        VBox.setVgrow(trackList, Priority.ALWAYS);

        // Double-click to play
        trackList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) playIndex(trackList.getSelectionModel().getSelectedIndex());
        });

        // ── Toolbar ───────────────────────────────────────────────────────────
        Button addBtn = btn("Add Files");
        Button remBtn = btn("Remove");
        Button clrBtn = btn("Clear All");

        addBtn.setOnAction(e -> addFiles(stage));
        remBtn.setOnAction(e -> removeSelected());
        clrBtn.setOnAction(e -> { stopPlayback(); tracks.clear(); currentIndex = -1; });

        HBox toolbar = row(4, addBtn, remBtn, clrBtn);
        toolbar.setPadding(new Insets(8));
        toolbar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #ccc; -fx-border-width: 0 0 1 0;");

        // ── Now playing ───────────────────────────────────────────────────────
        nowPlayingLabel = new Label("Nothing playing");
        nowPlayingLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        nowPlayingLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(nowPlayingLabel, Priority.ALWAYS);

        // ── Seek ──────────────────────────────────────────────────────────────
        seekSlider = new Slider(0, 1, 0);
        seekSlider.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(seekSlider, Priority.ALWAYS);
        seekSlider.setOnMousePressed(e  -> seeking = true);
        seekSlider.setOnMouseReleased(e -> {
            if (player != null) {
                Duration total = player.getTotalDuration();
                if (total != null && !total.isUnknown())
                    player.seek(Duration.seconds(seekSlider.getValue() * total.toSeconds()));
            }
            seeking = false;
        });

        timeLabel = new Label("0:00 / 0:00");
        timeLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        timeLabel.setMinWidth(90);
        timeLabel.setAlignment(Pos.CENTER_RIGHT);

        HBox seekRow = row(6, seekSlider, timeLabel);
        seekRow.setPadding(new Insets(0, 8, 0, 8));

        // ── Transport ─────────────────────────────────────────────────────────
        Button prevBtn = btn("⏮");
        playBtn        = btn("▶");
        Button stopBtn = btn("⏹");
        Button nextBtn = btn("⏭");

        prevBtn.setOnAction(e -> playIndex(currentIndex - 1));
        playBtn.setOnAction(e -> togglePlayPause());
        stopBtn.setOnAction(e -> stopPlayback());
        nextBtn.setOnAction(e -> playIndex(currentIndex + 1));

        // ── Volume ────────────────────────────────────────────────────────────
        Label volLbl = new Label("🔊");
        volSlider = new Slider(0, 1, 0.8);
        volSlider.setMaxWidth(100);
        volSlider.valueProperty().addListener((obs, o, n) -> {
            if (player != null) player.setVolume(n.doubleValue());
        });

        HBox controls = row(8, prevBtn, stopBtn, playBtn, nextBtn,
                new Separator(javafx.geometry.Orientation.VERTICAL), volLbl, volSlider);
        controls.setPadding(new Insets(8));
        controls.setAlignment(Pos.CENTER);

        // ── Status ────────────────────────────────────────────────────────────
        statusLabel = new Label("Ready");
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        HBox statusBar = row(0, statusLabel);
        statusBar.setPadding(new Insets(2, 8, 2, 8));
        statusBar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #ccc; -fx-border-width: 1 0 0 0;");

        // ── Now playing row ───────────────────────────────────────────────────
        HBox npRow = row(0, nowPlayingLabel);
        npRow.setPadding(new Insets(6, 8, 2, 8));

        // ── Root ──────────────────────────────────────────────────────────────
        VBox root = new VBox(toolbar, trackList, npRow, seekRow, controls, statusBar);
        root.setStyle("-fx-background-color: white;");

        return new Scene(root, 520, 480);
    }

    // ── File management ────────────────────────────────────────────────────────

    private void addFiles(Stage stage) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Add Audio Files");
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Audio", "*.mp3","*.wav","*.aac","*.m4a","*.aiff","*.aif"));

        // Restore last folder
        String last = prefs.get("lastDir", System.getProperty("user.home"));
        File dir = new File(last);
        if (dir.exists()) fc.setInitialDirectory(dir);

        List<File> files = fc.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) return;

        // Save last folder
        prefs.put("lastDir", files.get(0).getParent());

        for (File f : files) {
            boolean dup = tracks.stream().anyMatch(t -> t.getFile().equals(f));
            if (!dup) tracks.add(new Track(f));
        }
        setStatus("Added " + files.size() + " file(s). Total: " + tracks.size());
    }

    private void removeSelected() {
        int idx = trackList.getSelectionModel().getSelectedIndex();
        if (idx < 0) return;
        if (idx == currentIndex) stopPlayback();
        tracks.remove(idx);
        // Fix currentIndex after removal
        if (idx < currentIndex) currentIndex--;
        else if (idx == currentIndex) currentIndex = -1;
    }

    // ── Playback ───────────────────────────────────────────────────────────────

    private void playIndex(int idx) {
        if (tracks.isEmpty()) return;
        // Clamp
        if (idx < 0) idx = tracks.size() - 1;
        if (idx >= tracks.size()) idx = 0;

        currentIndex = idx;
        trackList.getSelectionModel().select(idx);
        trackList.scrollTo(idx);

        Track track = tracks.get(idx);

        // Dispose old player
        if (player != null) { player.stop(); player.dispose(); player = null; }

        try {
            Media media = new Media(track.getFile().toURI().toString());
            player = new MediaPlayer(media);
            player.setVolume(volSlider.getValue());

            // Ready
            player.setOnReady(() -> {
                Duration total = player.getTotalDuration();
                Platform.runLater(() -> updateTimeLabel(Duration.ZERO, total));
            });

            // Time updates
            player.currentTimeProperty().addListener((ChangeListener<Duration>) (obs, o, n) -> {
                if (!seeking) Platform.runLater(() -> {
                    Duration total = player.getTotalDuration();
                    if (total != null && !total.isUnknown() && total.toSeconds() > 0)
                        seekSlider.setValue(n.toSeconds() / total.toSeconds());
                    updateTimeLabel(n, total);
                });
            });

            // End → next
            player.setOnEndOfMedia(() -> Platform.runLater(() -> playIndex(currentIndex + 1)));

            // Error
            player.setOnError(() ->
                    Platform.runLater(() -> setStatus("Error: " + player.getError().getMessage())));

            player.play();
            nowPlayingLabel.setText("▶  " + track.getName());
            playBtn.setText("⏸");
            setStatus("Playing: " + track.getName());

        } catch (Exception ex) {
            setStatus("Cannot play: " + ex.getMessage());
        }
    }

    private void togglePlayPause() {
        if (player == null) {
            // Nothing loaded — play first or selected track
            int sel = trackList.getSelectionModel().getSelectedIndex();
            playIndex(sel >= 0 ? sel : 0);
            return;
        }
        MediaPlayer.Status status = player.getStatus();
        if (status == MediaPlayer.Status.PLAYING) {
            player.pause();
            playBtn.setText("▶");
            nowPlayingLabel.setText("⏸  " + tracks.get(currentIndex).getName());
            setStatus("Paused");
        } else {
            player.play();
            playBtn.setText("⏸");
            nowPlayingLabel.setText("▶  " + tracks.get(currentIndex).getName());
            setStatus("Playing: " + tracks.get(currentIndex).getName());
        }
    }

    private void stopPlayback() {
        if (player != null) { player.stop(); }
        seekSlider.setValue(0);
        timeLabel.setText("0:00 / 0:00");
        playBtn.setText("▶");
        nowPlayingLabel.setText("Nothing playing");
        setStatus("Stopped");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void updateTimeLabel(Duration current, Duration total) {
        timeLabel.setText(fmt(current) + " / " + fmt(total));
    }

    private static String fmt(Duration d) {
        if (d == null || d.isUnknown() || d.isIndefinite()) return "0:00";
        long s = (long) d.toSeconds();
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    private void setStatus(String msg) { statusLabel.setText(msg); }

    private static Button btn(String text) {
        Button b = new Button(text);
        b.setFocusTraversable(false);
        return b;
    }

    private static HBox row(double spacing, javafx.scene.Node... nodes) {
        HBox box = new HBox(spacing, nodes);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    public static void main(String[] args) { launch(args); }
}

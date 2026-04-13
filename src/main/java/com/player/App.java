package com.player;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.*;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.canvas.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.media.*;
import javafx.scene.paint.Color;
import javafx.stage.*;
import javafx.util.Duration;

import java.io.File;
import java.util.*;
import java.util.prefs.Preferences;

public class App extends Application {

    // ── Repeat modes ──────────────────────────────────────────────────────────
    enum Repeat { NONE, ONE, ALL }

    // ── Data ──────────────────────────────────────────────────────────────────
    private final ObservableList<Track>    library   = FXCollections.observableArrayList();
    private final ObservableList<Playlist> playlists = FXCollections.observableArrayList();
    private FilteredList<Track>            filtered;

    // ── Player state ──────────────────────────────────────────────────────────
    private MediaPlayer player;
    private int         currentIndex = -1;
    private List<Track> queue        = new ArrayList<>();
    private boolean     seeking      = false;
    private boolean     shuffle      = false;
    private Repeat      repeat       = Repeat.NONE;

    // ── Visualizer ────────────────────────────────────────────────────────────
    private final float[] specMags = new float[32];
    private final float[] smoothed = new float[32];
    private Canvas        vizCanvas;

    // ── UI refs ───────────────────────────────────────────────────────────────
    private ListView<Track>    trackListView;
    private ListView<Playlist> playlistView;
    private Label              nowPlaying;
    private Button             playBtn;
    private Slider             seekSlider;
    private Label              timeLabel;
    private Slider             volSlider;
    private Label              statusLabel;
    private Button             shuffleBtn;
    private Button             repeatBtn;
    private TextField          searchField;

    private final Preferences prefs = Preferences.userNodeForPackage(App.class);

    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void start(Stage stage) {
        Playlist allTracks = new Playlist("All Tracks");
        playlists.add(allTracks);
        filtered = new FilteredList<>(library, t -> true);

        stage.setTitle("Audio Player");
        stage.setScene(buildScene(stage));
        stage.setMinWidth(640);
        stage.setMinHeight(500);
        stage.show();

        // startVisualizer();
    }

    @Override public void stop() {
        if (player != null) { player.stop(); player.dispose(); }
    }

    // ── Scene ─────────────────────────────────────────────────────────────────

    private Scene buildScene(Stage stage) {

        // ── Sidebar: playlists ────────────────────────────────────────────────
        playlistView = new ListView<>(playlists);
        playlistView.getSelectionModel().select(0);
        playlistView.setPrefWidth(150);
        VBox.setVgrow(playlistView, Priority.ALWAYS);
        playlistView.getSelectionModel().selectedItemProperty()
                .addListener((obs, o, n) -> { if (n != null) refreshTrackList(n); });

        Button newPlBtn  = new Button("＋ New Playlist");
        Button delPlBtn  = new Button("🗑 Delete");
        newPlBtn.setMaxWidth(Double.MAX_VALUE);
        delPlBtn.setMaxWidth(Double.MAX_VALUE);
        newPlBtn.setOnAction(e -> createPlaylist());
        delPlBtn.setOnAction(e -> deletePlaylist());

        Label plLabel = new Label("PLAYLISTS");
        plLabel.setStyle("-fx-font-size:10px;-fx-text-fill:#888;-fx-font-weight:bold;");

        VBox sidebar = new VBox(6, plLabel, playlistView, newPlBtn, delPlBtn);
        sidebar.setPadding(new Insets(8));
        sidebar.setStyle("-fx-background-color:#f7f7f7;-fx-border-color:#ddd;-fx-border-width:0 1 0 0;");

        // ── Toolbar ───────────────────────────────────────────────────────────
        Button addBtn = new Button("➕ Add Files");
        Button remBtn = new Button("🗑 Remove");
        addBtn.setOnAction(e -> addFiles(stage));
        remBtn.setOnAction(e -> removeSelected());

        searchField = new TextField();
        searchField.setPromptText("🔍 Search...");
        searchField.setPrefWidth(180);
        searchField.textProperty().addListener((obs, o, n) -> applySearch(n));

        shuffleBtn = new Button("⇄ Shuffle");
        repeatBtn  = new Button("↻ Repeat: Off");
        shuffleBtn.setOnAction(e -> toggleShuffle());
        repeatBtn.setOnAction(e  -> cycleRepeat());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(8, addBtn, remBtn, spacer, searchField, shuffleBtn, repeatBtn);
        toolbar.setPadding(new Insets(8));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setStyle("-fx-background-color:#f4f4f4;-fx-border-color:#ddd;-fx-border-width:0 0 1 0;");

        // ── Track list ────────────────────────────────────────────────────────
        trackListView = new ListView<>();
        trackListView.setPlaceholder(new Label("No tracks — click Add Files"));
        VBox.setVgrow(trackListView, Priority.ALWAYS);
        trackListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) playSelected();
        });
        trackListView.setContextMenu(buildContextMenu());
        refreshTrackList(playlists.get(0));

        // ── Visualizer ────────────────────────────────────────────────────────
        vizCanvas = new Canvas(600, 60);
        vizCanvas.widthProperty().bind(stage.widthProperty().subtract(166));
        StackPane vizBox = new StackPane(vizCanvas);
        // vizBox.setStyle("-fx-background-color:#111;");

        // ── Now playing ───────────────────────────────────────────────────────
        nowPlaying = new Label("Nothing playing");
        nowPlaying.setStyle("-fx-font-weight:bold;-fx-font-size:13px;");
        nowPlaying.setMaxWidth(Double.MAX_VALUE);
        HBox npRow = new HBox(nowPlaying);
        npRow.setPadding(new Insets(4, 10, 2, 10));

        // ── Seek ──────────────────────────────────────────────────────────────
        seekSlider = new Slider(0, 1, 0);
        HBox.setHgrow(seekSlider, Priority.ALWAYS);
        seekSlider.setOnMousePressed(e  -> seeking = true);
        seekSlider.setOnMouseReleased(e -> {
            if (player != null) {
                Duration tot = player.getTotalDuration();
                if (tot != null && !tot.isUnknown())
                    player.seek(Duration.seconds(seekSlider.getValue() * tot.toSeconds()));
            }
            seeking = false;
        });
        timeLabel = new Label("0:00 / 0:00");
        timeLabel.setStyle("-fx-font-family:monospace;-fx-font-size:11px;");
        timeLabel.setMinWidth(90);
        HBox seekRow = new HBox(6, seekSlider, timeLabel);
        seekRow.setPadding(new Insets(0, 10, 0, 10));
        seekRow.setAlignment(Pos.CENTER);

        // ── Transport ─────────────────────────────────────────────────────────
        Button prevBtn = new Button("⏮");
        playBtn        = new Button("▶");
        Button stopBtn = new Button("⏹");
        Button nextBtn = new Button("⏭");
        prevBtn.setOnAction(e -> playIndex(currentIndex - 1));
        playBtn.setOnAction(e -> togglePlayPause());
        stopBtn.setOnAction(e -> stopPlayback());
        nextBtn.setOnAction(e -> playIndex(currentIndex + 1));

        Label volIcon = new Label("🔊");
        volSlider = new Slider(0, 1, 0.8);
        volSlider.setMaxWidth(90);
        volSlider.valueProperty().addListener((o, ov, nv) -> {
            if (player != null) player.setVolume(nv.doubleValue());
        });

        HBox controls = new HBox(8, prevBtn, stopBtn, playBtn, nextBtn,
                new Separator(Orientation.VERTICAL), volIcon, volSlider);
        controls.setPadding(new Insets(6, 10, 6, 10));
        controls.setAlignment(Pos.CENTER);

        // ── Status bar ────────────────────────────────────────────────────────
        statusLabel = new Label("Ready");
        statusLabel.setStyle("-fx-font-size:11px;-fx-text-fill:#888;");
        HBox statusBar = new HBox(statusLabel);
        statusBar.setPadding(new Insets(3, 10, 3, 10));
        statusBar.setStyle("-fx-background-color:#f4f4f4;-fx-border-color:#ddd;-fx-border-width:1 0 0 0;");

        // ── Right panel ───────────────────────────────────────────────────────
        VBox rightPanel = new VBox(trackListView, vizBox, npRow, seekRow, controls, statusBar);
        VBox.setVgrow(trackListView, Priority.ALWAYS);

        HBox body = new HBox(sidebar, rightPanel);
        HBox.setHgrow(rightPanel, Priority.ALWAYS);
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox root = new VBox(toolbar, body);
        root.setStyle("-fx-background-color:white;");
        return new Scene(root, 760, 540);
    }

    // ── Context menu ──────────────────────────────────────────────────────────

    private ContextMenu buildContextMenu() {
        ContextMenu cm = new ContextMenu();
        Menu addToMenu = new Menu("Add to Playlist ▶");
        cm.getItems().add(addToMenu);
        cm.setOnShowing(e -> {
            addToMenu.getItems().clear();
            for (Playlist pl : playlists) {
                if (pl.getName().equals("All Tracks")) continue;
                MenuItem mi = new MenuItem(pl.getName());
                mi.setOnAction(ae -> {
                    Track t = trackListView.getSelectionModel().getSelectedItem();
                    if (t != null) { pl.add(t); setStatus("Added to \"" + pl.getName() + "\""); }
                });
                addToMenu.getItems().add(mi);
            }
            if (addToMenu.getItems().isEmpty())
                addToMenu.getItems().add(new MenuItem("(create a playlist first)"));
        });
        return cm;
    }

    // ── Playlist management ───────────────────────────────────────────────────

    private void createPlaylist() {
        TextInputDialog dlg = new TextInputDialog("New Playlist");
        dlg.setTitle("New Playlist");
        dlg.setHeaderText(null);
        dlg.setContentText("Name:");
        dlg.showAndWait().map(String::trim).filter(s -> !s.isEmpty()).ifPresent(name -> {
            Playlist pl = new Playlist(name);
            playlists.add(pl);
            playlistView.getSelectionModel().select(pl);
        });
    }

    private void deletePlaylist() {
        Playlist sel = playlistView.getSelectionModel().getSelectedItem();
        if (sel == null || sel.getName().equals("All Tracks")) return;
        playlists.remove(sel);
        playlistView.getSelectionModel().select(0);
    }

    private void refreshTrackList(Playlist pl) {
        if (pl.getName().equals("All Tracks")) {
            trackListView.setItems(filtered);
        } else {
            FilteredList<Track> pf = new FilteredList<>(pl.getTracks(), t -> true);
            applySearchTo(pf, searchField != null ? searchField.getText() : "");
            trackListView.setItems(pf);
        }
    }

    // ── File management ───────────────────────────────────────────────────────

    private void addFiles(Stage stage) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Add Audio Files");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Audio Files", "*.mp3","*.wav","*.aac","*.m4a","*.aiff","*.aif"));
        String lastDir = prefs.get("lastDir", System.getProperty("user.home"));
        File dir = new File(lastDir);
        if (dir.exists()) fc.setInitialDirectory(dir);

        List<File> files = fc.showOpenMultipleDialog(stage);
        if (files == null) return;
        prefs.put("lastDir", files.get(0).getParent());

        int added = 0;
        for (File f : files) {
            if (library.stream().noneMatch(t -> t.getFile().equals(f))) {
                Track t = new Track(f);
                library.add(t);
                playlists.get(0).add(t);
                added++;
            }
        }
        setStatus(added + " file(s) added. Total: " + library.size());
    }

    private void removeSelected() {
        Playlist pl  = playlistView.getSelectionModel().getSelectedItem();
        Track    sel = trackListView.getSelectionModel().getSelectedItem();
        if (sel == null || pl == null) return;
        if (pl.getName().equals("All Tracks")) {
            library.remove(sel);
            playlists.forEach(p -> p.remove(sel));
        } else {
            pl.remove(sel);
        }
        if (sel.equals(currentTrack())) stopPlayback();
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private void applySearch(String query) {
        Playlist pl = playlistView.getSelectionModel().getSelectedItem();
        if (pl == null) return;
        if (trackListView.getItems() instanceof FilteredList<?> fl) {
            applySearchTo((FilteredList<Track>) fl, query);
        }
    }

    private void applySearchTo(FilteredList<Track> list, String query) {
        if (query == null || query.isBlank()) {
            list.setPredicate(t -> true);
        } else {
            String q = query.toLowerCase();
            list.setPredicate(t -> t.getName().toLowerCase().contains(q));
        }
    }

    // ── Shuffle / Repeat ──────────────────────────────────────────────────────

    private void toggleShuffle() {
        shuffle = !shuffle;
        shuffleBtn.setStyle(shuffle ? "-fx-background-color:#d0eaff;" : "");
        shuffleBtn.setText(shuffle ? "⇄ Shuffle: On" : "⇄ Shuffle");
        rebuildQueue();
        setStatus(shuffle ? "Shuffle on" : "Shuffle off");
    }

    private void cycleRepeat() {
        repeat = switch (repeat) {
            case NONE -> Repeat.ALL;
            case ALL  -> Repeat.ONE;
            case ONE  -> Repeat.NONE;
        };
        String label = switch (repeat) {
            case NONE -> "↻ Repeat: Off";
            case ALL  -> "↻ Repeat: All";
            case ONE  -> "↺ Repeat: One";
        };
        repeatBtn.setText(label);
        repeatBtn.setStyle(repeat != Repeat.NONE ? "-fx-background-color:#d0eaff;" : "");
    }

    private void rebuildQueue() {
        queue = new ArrayList<>(trackListView.getItems());
        if (shuffle) Collections.shuffle(queue);
        Track cur = currentTrack();
        currentIndex = cur != null ? queue.indexOf(cur) : -1;
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    private void playSelected() {
        rebuildQueue();
        Track sel = trackListView.getSelectionModel().getSelectedItem();
        int idx   = sel != null ? queue.indexOf(sel) : 0;
        playIndex(idx >= 0 ? idx : 0);
    }

    private void playIndex(int idx) {
        if (queue.isEmpty()) rebuildQueue();
        if (queue.isEmpty()) return;

        if (idx < 0 || idx >= queue.size()) {
            if (repeat == Repeat.ALL) idx = idx < 0 ? queue.size() - 1 : 0;
            else { stopPlayback(); return; }
        }

        currentIndex = idx;
        Track track  = queue.get(idx);
        trackListView.getSelectionModel().select(track);
        trackListView.scrollTo(track);

        if (player != null) { player.stop(); player.dispose(); }

        try {
            Media media = new Media(track.getFile().toURI().toString());
            player = new MediaPlayer(media);
            player.setVolume(volSlider.getValue());

            player.setOnReady(() -> Platform.runLater(() ->
                    updateTime(Duration.ZERO, player.getTotalDuration())));

            player.currentTimeProperty().addListener((obs, o, n) -> {
                if (!seeking) Platform.runLater(() -> {
                    Duration tot = player.getTotalDuration();
                    if (tot != null && !tot.isUnknown() && tot.toSeconds() > 0)
                        seekSlider.setValue(n.toSeconds() / tot.toSeconds());
                    updateTime(n, tot);
                });
            });

            player.setAudioSpectrumNumBands(32);
            player.setAudioSpectrumInterval(0.05);
            player.setAudioSpectrumListener((ts, dur, mags, phases) ->
                    System.arraycopy(mags, 0, specMags, 0, 32));

            player.setOnEndOfMedia(() -> Platform.runLater(() -> {
                if (repeat == Repeat.ONE) { player.seek(Duration.ZERO); player.play(); }
                else playIndex(currentIndex + 1);
            }));

            player.setOnError(() -> Platform.runLater(() ->
                    setStatus("Error: " + player.getError().getMessage())));

            player.play();
            nowPlaying.setText("▶  " + track.getName());
            playBtn.setText("⏸");
            setStatus("Playing: " + track.getName());

        } catch (Exception ex) {
            setStatus("Cannot play: " + ex.getMessage());
        }
    }

    private void togglePlayPause() {
        if (player == null) { playSelected(); return; }
        if (player.getStatus() == MediaPlayer.Status.PLAYING) {
            player.pause();
            playBtn.setText("▶");
            setStatus("Paused");
        } else {
            player.play();
            playBtn.setText("⏸");
            if (currentTrack() != null) setStatus("Playing: " + currentTrack().getName());
        }
    }

    private void stopPlayback() {
        if (player != null) player.stop();
        seekSlider.setValue(0);
        timeLabel.setText("0:00 / 0:00");
        playBtn.setText("▶");
        nowPlaying.setText("Nothing playing");
        setStatus("Stopped");
    }

    private Track currentTrack() {
        if (currentIndex < 0 || currentIndex >= queue.size()) return null;
        return queue.get(currentIndex);
    }

    // // ── Visualizer ────────────────────────────────────────────────────────────

    // private void startVisualizer() {
    //     new AnimationTimer() {
    //         @Override public void handle(long now) { drawVisualizer(); }
    //     }.start();
    // }

    // private void drawVisualizer() {
    //     GraphicsContext gc = vizCanvas.getGraphicsContext2D();
    //     double w = vizCanvas.getWidth();
    //     double h = vizCanvas.getHeight();

    //     gc.setFill(Color.web("#111"));
    //     gc.fillRect(0, 0, w, h);

    //     boolean playing = player != null &&
    //             player.getStatus() == MediaPlayer.Status.PLAYING;

    //     if (!playing) {
    //         // Flat line when idle
    //         gc.setStroke(Color.web("#333"));
    //         gc.setLineWidth(1.5);
    //         gc.strokeLine(0, h / 2, w, h / 2);
    //         return;
    //     }

    //     int    bands = 32;
    //     double gap   = 3;
    //     double barW  = (w - gap * (bands + 1)) / bands;

    //     for (int i = 0; i < bands; i++) {
    //         float raw = Math.max(0f, (specMags[i] + 60f) / 60f);
    //         smoothed[i] = smoothed[i] * 0.7f + raw * 0.3f;

    //         double barH = Math.max(2, smoothed[i] * (h - 4));
    //         double x    = gap + i * (barW + gap);
    //         double y    = h - barH;

    //         // Green → yellow → red based on height
    //         Color c = Color.hsb((1.0 - smoothed[i]) * 120, 0.9, 0.85);
    //         gc.setFill(c);
    //         gc.fillRoundRect(x, y, barW, barH, 3, 3);
    //     }
    // }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void updateTime(Duration cur, Duration tot) {
        timeLabel.setText(fmt(cur) + " / " + fmt(tot));
    }

    private static String fmt(Duration d) {
        if (d == null || d.isUnknown() || d.isIndefinite()) return "0:00";
        long s = (long) d.toSeconds();
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    private void setStatus(String msg) { statusLabel.setText(msg); }

    public static void main(String[] args) { launch(args); }
}

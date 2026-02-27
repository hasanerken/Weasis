/*
 * Copyright (c) 2026 ZenPACS and other contributors.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.dicom.explorer.wado.DownloadManager;

public class AudioRecorderPanel extends JPanel {

  private static final Logger LOGGER = LoggerFactory.getLogger(AudioRecorderPanel.class);

  private static final Color DARK_BG = new Color(40, 42, 46);
  private static final Color CARD_BG = new Color(53, 55, 64);
  private static final Color BORDER_COLOR = new Color(60, 63, 68);
  private static final Color TEXT_COLOR = new Color(211, 211, 211);
  private static final Color ACCENT_BLUE = new Color(100, 181, 246);
  private static final Color RECORD_RED = new Color(239, 83, 80);
  private static final Color SUCCESS_GREEN = new Color(102, 187, 106);
  private static final Color WARN_AMBER = new Color(255, 183, 77);

  private static final int COLLAPSED_W = 80;
  private static final int COLLAPSED_H = 28;
  private static final int EXPANDED_WIDTH = 280;
  private static final int EXPANDED_HEIGHT = 400;
  private static final int MARGIN = 8;

  private final DicomExplorer explorer;
  private final AudioRecorderService recorderService;
  private boolean expanded = false;
  private boolean micAvailable = false;

  // Collapsed state
  private JButton micButton;

  // Expanded state
  private JPanel expandedPanel;
  private JLabel timerLabel;
  private JLabel statusLabel;
  private JButton btnRecord;
  private JButton btnPause;
  private JButton btnStop;
  private JButton btnUpload;
  private JPanel recordingsListPanel;
  private JScrollPane recordingsScroll;
  private Timer uiTimer;

  // Track recordings
  private final List<RecordingEntry> localRecordings = new ArrayList<>();
  private volatile boolean playing = false;
  private Thread playbackThread;

  public AudioRecorderPanel(DicomExplorer explorer) {
    this.explorer = explorer;
    this.recorderService = new AudioRecorderService();
    setOpaque(false);
    setLayout(null); // Absolute positioning within layered pane

    checkMicAndBuild();
  }

  private void checkMicAndBuild() {
    new Thread(() -> {
      try {
        micAvailable = AudioRecorderService.isMicrophoneAvailable();
        System.out.println("[ZenPACS] Microphone available: " + micAvailable);
        SwingUtilities.invokeLater(() -> {
          if (micAvailable) {
            buildUI();
          } else {
            setVisible(false);
          }
        });
      } catch (Exception e) {
        System.err.println("[ZenPACS] Mic check error: " + e.getMessage());
        e.printStackTrace();
      }
    }, "ZenPACS-MicCheck").start();
  }

  private void buildUI() {
    // Collapsed mic button
    micButton = new JButton("Ses Kayd\u0131");
    micButton.setFont(new Font("Dialog", Font.PLAIN, 11));
    micButton.setForeground(RECORD_RED);
    micButton.setPreferredSize(new Dimension(COLLAPSED_W, COLLAPSED_H));
    micButton.setToolTipText("Ses Kay\u0131t");
    micButton.setFocusPainted(false);
    micButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    micButton.addActionListener(e -> toggleExpanded());
    micButton.setBounds(0, 0, COLLAPSED_W, COLLAPSED_H);
    add(micButton);

    System.out.println("[ZenPACS] Mic button built, panel size: " + getWidth() + "x" + getHeight());

    // Expanded panel
    expandedPanel = new JPanel(new BorderLayout(0, 4));
    expandedPanel.setBackground(DARK_BG);
    expandedPanel.setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(BORDER_COLOR, 1),
        BorderFactory.createEmptyBorder(8, 8, 8, 8)));
    expandedPanel.setVisible(false);

    // Header
    JPanel header = new JPanel(new BorderLayout());
    header.setOpaque(false);
    JLabel titleLabel = new JLabel("Ses Kay\u0131t");
    titleLabel.setForeground(ACCENT_BLUE);
    titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 12f));
    header.add(titleLabel, BorderLayout.WEST);

    JButton closeBtn = new JButton("\u2715");
    closeBtn.setForeground(TEXT_COLOR);
    closeBtn.setContentAreaFilled(false);
    closeBtn.setBorderPainted(false);
    closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    closeBtn.addActionListener(e -> toggleExpanded());
    header.add(closeBtn, BorderLayout.EAST);
    expandedPanel.add(header, BorderLayout.NORTH);

    // Center: split into top (timer/controls) and bottom (recordings)
    JPanel center = new JPanel(new BorderLayout(0, 0));
    center.setOpaque(false);

    // Top section: timer + controls + status (centered)
    JPanel topSection = new JPanel();
    topSection.setLayout(new BoxLayout(topSection, BoxLayout.Y_AXIS));
    topSection.setOpaque(false);

    timerLabel = new JLabel("00:00");
    timerLabel.setForeground(TEXT_COLOR);
    timerLabel.setFont(new Font("Monospaced", Font.BOLD, 24));
    timerLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
    topSection.add(timerLabel);
    topSection.add(Box.createVerticalStrut(8));

    JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
    controls.setOpaque(false);
    btnRecord = createControlButton("\u25CF", "Kay\u0131t Ba\u015flat ("
        + ZenShortcuts.getInstance().getDisplayText(ZenShortcuts.Action.RECORD) + ")", RECORD_RED);
    btnRecord.addActionListener(e -> onRecord());
    controls.add(btnRecord);
    btnPause = createControlButton("\u2759\u2759", "Duraklat/Devam ("
        + ZenShortcuts.getInstance().getDisplayText(ZenShortcuts.Action.PAUSE_RESUME) + ")", WARN_AMBER);
    btnPause.setEnabled(false);
    btnPause.addActionListener(e -> onPause());
    controls.add(btnPause);
    btnStop = createControlButton("\u25A0", "Durdur ("
        + ZenShortcuts.getInstance().getDisplayText(ZenShortcuts.Action.RECORD) + ")", TEXT_COLOR);
    btnStop.setEnabled(false);
    btnStop.addActionListener(e -> onStop());
    controls.add(btnStop);
    controls.setAlignmentX(Component.CENTER_ALIGNMENT);
    topSection.add(controls);
    topSection.add(Box.createVerticalStrut(6));

    statusLabel = new JLabel("Haz\u0131r");
    statusLabel.setForeground(new Color(150, 150, 150));
    statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
    statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
    topSection.add(statusLabel);
    topSection.add(Box.createVerticalStrut(8));

    JPanel sep = new JPanel();
    sep.setBackground(BORDER_COLOR);
    sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
    sep.setPreferredSize(new Dimension(0, 1));
    sep.setAlignmentX(Component.CENTER_ALIGNMENT);
    topSection.add(sep);

    center.add(topSection, BorderLayout.NORTH);

    // Bottom section: recordings label + list (full width, left-aligned)
    JPanel bottomSection = new JPanel(new BorderLayout(0, 4));
    bottomSection.setOpaque(false);

    JLabel recLabel = new JLabel("Kay\u0131tlar");
    recLabel.setForeground(ACCENT_BLUE);
    recLabel.setFont(recLabel.getFont().deriveFont(Font.BOLD, 11f));
    recLabel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
    bottomSection.add(recLabel, BorderLayout.NORTH);

    recordingsListPanel = new JPanel();
    recordingsListPanel.setLayout(new BoxLayout(recordingsListPanel, BoxLayout.Y_AXIS));
    recordingsListPanel.setBackground(DARK_BG);

    recordingsScroll = new JScrollPane(recordingsListPanel);
    recordingsScroll.getViewport().setBackground(DARK_BG);
    recordingsScroll.setBorder(BorderFactory.createEmptyBorder());
    recordingsScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    bottomSection.add(recordingsScroll, BorderLayout.CENTER);

    center.add(bottomSection, BorderLayout.CENTER);

    expandedPanel.add(center, BorderLayout.CENTER);
    expandedPanel.setBounds(0, 0, EXPANDED_WIDTH, EXPANDED_HEIGHT);
    add(expandedPanel);

    // Set up recording listener
    recorderService.setListener(new AudioRecorderService.RecordingListener() {
      @Override
      public void onStateChanged(AudioRecorderService.State state) {
        SwingUtilities.invokeLater(() -> updateControlStates(state));
      }

      @Override
      public void onDurationUpdate(long elapsedSeconds) {
        SwingUtilities.invokeLater(() -> {
          long mins = elapsedSeconds / 60;
          long secs = elapsedSeconds % 60;
          timerLabel.setText(String.format("%02d:%02d", mins, secs));
        });
      }

      @Override
      public void onError(String message) {
        SwingUtilities.invokeLater(() -> {
          statusLabel.setText(message);
          statusLabel.setForeground(RECORD_RED);
        });
      }

      @Override
      public void onSaved(File file) {
        SwingUtilities.invokeLater(() -> {
          RecordingEntry entry = new RecordingEntry(file, false, null);
          localRecordings.add(entry);
          refreshRecordingsList();
          statusLabel.setText("Kaydedildi: " + file.getName());
          statusLabel.setForeground(SUCCESS_GREEN);
        });
      }
    });

    // Position and show
    setSize(COLLAPSED_W, COLLAPSED_H);
    setBounds(0, 0, COLLAPSED_W, COLLAPSED_H);
    setVisible(true);
    repositionInParent();
    System.out.println("[ZenPACS] Mic button final bounds: " + getBounds() + ", parent=" + (getParent() != null ? getParent().getSize() : "null"));
  }

  private JButton createControlButton(String text, String tooltip, Color fg) {
    JButton btn = new JButton(text);
    btn.setForeground(fg);
    btn.setFont(new Font("Dialog", Font.BOLD, 16));
    btn.setPreferredSize(new Dimension(44, 36));
    btn.setToolTipText(tooltip);
    btn.setFocusPainted(false);
    btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    return btn;
  }

  private void toggleExpanded() {
    expanded = !expanded;
    if (expanded) {
      micButton.setVisible(false);
      expandedPanel.setVisible(true);
      setSize(EXPANDED_WIDTH, EXPANDED_HEIGHT);
      loadRecordings();
    } else {
      expandedPanel.setVisible(false);
      micButton.setVisible(true);
      setSize(COLLAPSED_W, COLLAPSED_H);
    }
    repositionInParent();
    revalidate();
    repaint();
  }

  private void onRecord() {
    String caseId = getSelectedCaseId();
    recorderService.setPatientCaseId(caseId);
    recorderService.startRecording();
  }

  private void onPause() {
    if (recorderService.getState() == AudioRecorderService.State.PAUSED) {
      recorderService.resumeRecording();
    } else {
      recorderService.pauseRecording();
    }
  }

  private void onStop() {
    recorderService.stopRecording();
  }

  private void updateControlStates(AudioRecorderService.State state) {
    switch (state) {
      case IDLE -> {
        btnRecord.setEnabled(true);
        btnPause.setEnabled(false);
        btnStop.setEnabled(false);
        btnRecord.setForeground(RECORD_RED);
        btnPause.setText("\u2759\u2759");
        timerLabel.setText("00:00");
        statusLabel.setText("Haz\u0131r");
        statusLabel.setForeground(new Color(150, 150, 150));
      }
      case RECORDING -> {
        btnRecord.setEnabled(false);
        btnPause.setEnabled(true);
        btnStop.setEnabled(true);
        btnPause.setText("\u2759\u2759");
        btnPause.setForeground(WARN_AMBER);
        statusLabel.setText("Kaydediliyor...");
        statusLabel.setForeground(RECORD_RED);
      }
      case PAUSED -> {
        btnRecord.setEnabled(false);
        btnPause.setEnabled(true);
        btnStop.setEnabled(true);
        btnPause.setText("\u25B6");
        btnPause.setForeground(SUCCESS_GREEN);
        statusLabel.setText("Duraklatld\u0131");
        statusLabel.setForeground(WARN_AMBER);
      }
      case STOPPED -> {
        statusLabel.setText("Kaydediliyor...");
        statusLabel.setForeground(WARN_AMBER);
      }
    }
  }

  private void loadRecordings() {
    String caseId = getSelectedCaseId();
    localRecordings.clear();

    // Load local unsent files
    File dir = AudioRecorderService.getRecordingsDir(caseId);
    if (dir.exists()) {
      File[] wavFiles = dir.listFiles((d, name) -> name.endsWith(".wav"));
      if (wavFiles != null) {
        for (File f : wavFiles) {
          localRecordings.add(new RecordingEntry(f, false, null));
        }
      }
    }

    refreshRecordingsList();

    // Also fetch server recordings
    if (caseId != null) {
      VoiceUploadWorker.fetchVoices(caseId, serverVoices -> {
        for (VoiceUploadWorker.VoiceInfo vi : serverVoices) {
          // Check if already in local list (uploaded)
          boolean found = localRecordings.stream()
              .anyMatch(e -> e.serverKey != null && e.serverKey.equals(vi.key));
          if (!found) {
            RecordingEntry entry = new RecordingEntry(null, true, vi.key);
            entry.serverFilename = vi.filename;
            entry.serverSize = vi.size;
            entry.uploadedBy = vi.uploadedBy;
            entry.serverUrl = vi.url;
            localRecordings.add(entry);
          }
        }
        refreshRecordingsList();
      });
    }
  }

  private void refreshRecordingsList() {
    recordingsListPanel.removeAll();

    if (localRecordings.isEmpty()) {
      JLabel empty = new JLabel("Kay\u0131t yok");
      empty.setForeground(new Color(100, 100, 100));
      empty.setFont(empty.getFont().deriveFont(10f));
      empty.setAlignmentX(Component.LEFT_ALIGNMENT);
      recordingsListPanel.add(empty);
    } else {
      for (int i = 0; i < localRecordings.size(); i++) {
        RecordingEntry entry = localRecordings.get(i);
        recordingsListPanel.add(createRecordingRow(entry, i));
        recordingsListPanel.add(Box.createVerticalStrut(3));
      }
    }

    recordingsListPanel.revalidate();
    recordingsListPanel.repaint();
  }

  private JButton createSmallIconButton(String text, String tooltip, Color fg) {
    JButton btn = new JButton(text);
    btn.setForeground(fg);
    btn.setFont(new Font("Dialog", Font.PLAIN, 12));
    btn.setMargin(new Insets(0, 0, 0, 0));
    btn.setPreferredSize(new Dimension(24, 22));
    btn.setMinimumSize(new Dimension(24, 22));
    btn.setMaximumSize(new Dimension(24, 22));
    btn.setToolTipText(tooltip);
    btn.setContentAreaFilled(false);
    btn.setBorderPainted(false);
    btn.setFocusPainted(false);
    btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    return btn;
  }

  private JPanel createRecordingRow(RecordingEntry entry, int index) {
    JPanel row = new JPanel(new BorderLayout(2, 0));
    row.setBackground(CARD_BG);
    row.setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(BORDER_COLOR, 1),
        BorderFactory.createEmptyBorder(3, 6, 3, 4)));
    row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
    row.setAlignmentX(Component.LEFT_ALIGNMENT);

    // Left: filename + size
    String name = entry.localFile != null ? entry.localFile.getName() :
        (entry.serverFilename != null ? entry.serverFilename : "voice");
    // Truncate long names
    if (name.length() > 18) {
      name = name.substring(0, 15) + "...";
    }
    String sizeStr = "";
    if (entry.localFile != null) {
      sizeStr = " " + formatSize(entry.localFile.length());
    } else if (entry.serverSize > 0) {
      sizeStr = " " + formatSize(entry.serverSize);
    }

    JLabel nameLabel = new JLabel(name + sizeStr);
    nameLabel.setForeground(TEXT_COLOR);
    nameLabel.setFont(nameLabel.getFont().deriveFont(10f));
    row.add(nameLabel, BorderLayout.CENTER);

    // Right: compact action buttons
    JPanel actions = new JPanel();
    actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
    actions.setOpaque(false);

    // Play button (local files or server recordings with URL)
    boolean canPlay = (entry.localFile != null && entry.localFile.exists())
        || (entry.serverUrl != null && !entry.serverUrl.isEmpty());
    if (canPlay) {
      JButton playBtn = createSmallIconButton("\u25B6", "Dinle", ACCENT_BLUE);
      playBtn.addActionListener(e -> playRecording(entry, playBtn));
      actions.add(playBtn);
    }

    // Upload button (only for local unsent files)
    if (entry.localFile != null && !entry.uploaded) {
      JButton uploadBtn = createSmallIconButton("\u2191", "Y\u00fckle", SUCCESS_GREEN);
      uploadBtn.addActionListener(e -> uploadRecording(entry, uploadBtn));
      actions.add(uploadBtn);
    }

    // Uploaded indicator
    if (entry.uploaded || entry.localFile == null) {
      JLabel uploaded = new JLabel("\u2713");
      uploaded.setForeground(SUCCESS_GREEN);
      uploaded.setFont(uploaded.getFont().deriveFont(11f));
      uploaded.setToolTipText("Y\u00fcklenmi\u015f");
      uploaded.setPreferredSize(new Dimension(18, 22));
      actions.add(uploaded);
    }

    // Delete button (only for local files that haven't been uploaded)
    if (entry.localFile != null && !entry.uploaded) {
      JButton deleteBtn = createSmallIconButton("\u2715", "Sil", new Color(190, 130, 130));
      deleteBtn.addActionListener(e -> deleteRecording(entry, index));
      actions.add(deleteBtn);
    }

    row.add(actions, BorderLayout.EAST);
    return row;
  }

  private void playRecording(RecordingEntry entry, JButton playBtn) {
    // If already playing, stop
    if (playing) {
      stopPlayback();
      refreshRecordingsList();
      return;
    }
    stopPlayback();

    playBtn.setText("\u25A0");
    playBtn.setForeground(WARN_AMBER);
    statusLabel.setText("Dinleniyor...");
    statusLabel.setForeground(ACCENT_BLUE);
    playing = true;

    playbackThread = new Thread(() -> {
      File fileToPlay = entry.localFile;
      File tempFile = null;
      try {
        // If no local file, download from server
        if (fileToPlay == null || !fileToPlay.exists()) {
          if (entry.serverUrl != null && !entry.serverUrl.isEmpty()) {
            SwingUtilities.invokeLater(() -> {
              statusLabel.setText("\u0130ndiriliyor...");
              statusLabel.setForeground(WARN_AMBER);
            });
            tempFile = File.createTempFile("zenvoice_", ".wav");
            tempFile.deleteOnExit();
            String token = DownloadManager.getAuthToken();
            HttpURLConnection conn = (HttpURLConnection) new URI(entry.serverUrl).toURL().openConnection();
            if (token != null) {
              conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            try (InputStream is = conn.getInputStream()) {
              Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
              conn.disconnect();
            }
            fileToPlay = tempFile;
            SwingUtilities.invokeLater(() -> {
              statusLabel.setText("Dinleniyor...");
              statusLabel.setForeground(ACCENT_BLUE);
            });
          } else {
            return;
          }
        }

        try (AudioInputStream ais = AudioSystem.getAudioInputStream(fileToPlay)) {
          AudioFormat fmt = ais.getFormat();
          DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
          try (SourceDataLine sdl = (SourceDataLine) AudioSystem.getLine(info)) {
            sdl.open(fmt);
            sdl.start();

            byte[] buf = new byte[4096];
            int bytesRead;
            while (playing && (bytesRead = ais.read(buf, 0, buf.length)) != -1) {
              sdl.write(buf, 0, bytesRead);
            }

            if (playing) {
              sdl.drain();
            }
            sdl.stop();
          }
        }
      } catch (Exception ex) {
        LOGGER.error("Playback error: {}", ex.getMessage());
        SwingUtilities.invokeLater(() -> {
          statusLabel.setText("Oynatma hatas\u0131");
          statusLabel.setForeground(RECORD_RED);
        });
      } finally {
        playing = false;
        if (tempFile != null) {
          tempFile.delete();
        }
        SwingUtilities.invokeLater(() -> {
          statusLabel.setText("Haz\u0131r");
          statusLabel.setForeground(new Color(150, 150, 150));
          playBtn.setText("\u25B6");
          playBtn.setForeground(ACCENT_BLUE);
        });
      }
    }, "ZenPACS-AudioPlayback");
    playbackThread.setDaemon(true);
    playbackThread.start();
  }

  private void stopPlayback() {
    playing = false;
    if (playbackThread != null) {
      playbackThread.interrupt();
      try {
        playbackThread.join(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      playbackThread = null;
    }
  }

  /** Ctrl+R: toggle recording — if idle, start; if recording/paused, stop */
  public void triggerRecord() {
    if (!micAvailable) return;
    AudioRecorderService.State state = recorderService.getState();
    if (state == AudioRecorderService.State.IDLE) {
      if (!expanded) toggleExpanded();
      onRecord();
    } else {
      onStop();
    }
  }

  /** Ctrl+T: toggle pause/resume while recording */
  public void triggerPauseResume() {
    if (!micAvailable) return;
    AudioRecorderService.State state = recorderService.getState();
    if (state == AudioRecorderService.State.RECORDING || state == AudioRecorderService.State.PAUSED) {
      onPause();
    }
  }

  /** Ctrl+Y: upload the most recent local unuploaded recording */
  public void triggerUpload() {
    if (!micAvailable) return;
    // Find last unuploaded local recording
    for (int i = localRecordings.size() - 1; i >= 0; i--) {
      RecordingEntry entry = localRecordings.get(i);
      if (entry.localFile != null && !entry.uploaded) {
        // Create a temporary button reference for the upload callback
        uploadRecording(entry, null);
        return;
      }
    }
  }

  private void uploadRecording(RecordingEntry entry, JButton uploadBtn) {
    String caseId = getSelectedCaseId();
    if (caseId == null || entry.localFile == null) return;

    if (uploadBtn != null) {
      uploadBtn.setEnabled(false);
      uploadBtn.setText("...");
    }
    statusLabel.setText("Y\u00fckleniyor...");
    statusLabel.setForeground(WARN_AMBER);

    new VoiceUploadWorker(entry.localFile, caseId, success -> {
      if (success) {
        entry.uploaded = true;
        // Delete local file after successful upload
        if (entry.localFile.delete()) {
          LOGGER.info("Deleted local file after upload: {}", entry.localFile.getName());
        }
        statusLabel.setText("Y\u00fcklendi");
        statusLabel.setForeground(SUCCESS_GREEN);
      } else {
        statusLabel.setText("Y\u00fckleme hatas\u0131");
        statusLabel.setForeground(RECORD_RED);
      }
      refreshRecordingsList();
    }).execute();
  }

  private void deleteRecording(RecordingEntry entry, int index) {
    // Delete local file
    if (entry.localFile != null && entry.localFile.exists()) {
      if (entry.localFile.delete()) {
        LOGGER.info("Deleted local recording: {}", entry.localFile.getName());
      }
    }

    // Delete from server
    if (entry.serverKey != null && !entry.serverKey.isEmpty()) {
      String caseId = getSelectedCaseId();
      if (caseId != null) {
        VoiceUploadWorker.deleteVoice(caseId, entry.serverKey, success -> {
          if (success) {
            LOGGER.info("Deleted server voice: {}", entry.serverKey);
          }
        });
      }
    }

    if (index >= 0 && index < localRecordings.size()) {
      localRecordings.remove(index);
    }
    refreshRecordingsList();
  }

  private String getSelectedCaseId() {
    MediaSeriesGroup patient = explorer.getSelectedPatient();
    if (patient == null) return null;
    return (String) patient.getTagValue(DownloadManager.PATIENT_CASE_ID);
  }

  /** Called when the selected patient changes in DicomExplorer. Reloads recordings if expanded. */
  public void onPatientChanged() {
    if (expanded) {
      loadRecordings();
    }
  }

  public void installInLayeredPane(JLayeredPane layeredPane) {
    layeredPane.add(this, JLayeredPane.PALETTE_LAYER);
    repositionInParent();

    layeredPane.addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent e) {
        repositionInParent();
      }
    });
  }

  private void repositionInParent() {
    if (getParent() == null) return;
    int parentW = getParent().getWidth();
    int myW = getWidth();
    int x = parentW - myW - MARGIN - 80;
    int y = MARGIN;
    setLocation(x, y);
  }

  private static String formatSize(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
    return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
  }

  private static class RecordingEntry {
    File localFile;
    boolean uploaded;
    String serverKey;
    String serverFilename;
    long serverSize;
    String uploadedBy;
    String serverUrl;

    RecordingEntry(File localFile, boolean uploaded, String serverKey) {
      this.localFile = localFile;
      this.uploaded = uploaded;
      this.serverKey = serverKey;
    }
  }
}

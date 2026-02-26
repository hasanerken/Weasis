/*
 * Copyright (c) 2026 ZenPACS and other contributors.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AudioRecorderService {

  private static final Logger LOGGER = LoggerFactory.getLogger(AudioRecorderService.class);

  public enum State {
    IDLE, RECORDING, PAUSED, STOPPED
  }

  public interface RecordingListener {
    void onStateChanged(State state);
    void onDurationUpdate(long elapsedSeconds);
    void onError(String message);
    void onSaved(File file);
  }

  private static final AudioFormat FORMAT =
      new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100, 16, 1, 2, 44100, false);

  private static final File RECORDINGS_DIR;

  static {
    String home = System.getProperty("user.home", ".");
    RECORDINGS_DIR = new File(home, ".weasis/recordings");
  }

  private volatile State state = State.IDLE;
  private TargetDataLine line;
  private Thread recordingThread;
  private ByteArrayOutputStream audioBuffer;
  private RecordingListener listener;
  private long recordingStartTime;
  private long totalPausedDuration;
  private long pauseStartTime;
  private String currentPatientCaseId;

  public static boolean isMicrophoneAvailable() {
    DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
    if (!AudioSystem.isLineSupported(info)) {
      LOGGER.debug("No supported audio line for recording");
      return false;
    }
    try {
      TargetDataLine testLine = (TargetDataLine) AudioSystem.getLine(info);
      testLine.open(FORMAT);
      testLine.close();
      return true;
    } catch (LineUnavailableException | SecurityException e) {
      LOGGER.debug("Microphone not available: {}", e.getMessage());
      return false;
    }
  }

  public void setListener(RecordingListener listener) {
    this.listener = listener;
  }

  public State getState() {
    return state;
  }

  public void setPatientCaseId(String patientCaseId) {
    this.currentPatientCaseId = patientCaseId;
  }

  public void startRecording() {
    if (state == State.RECORDING) return;

    DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
    try {
      line = (TargetDataLine) AudioSystem.getLine(info);
      line.open(FORMAT);
    } catch (LineUnavailableException e) {
      LOGGER.error("Cannot open microphone", e);
      notifyError("Mikrofon a\u00e7\u0131lam\u0131yor");
      return;
    }

    audioBuffer = new ByteArrayOutputStream();
    recordingStartTime = System.currentTimeMillis();
    totalPausedDuration = 0;
    state = State.RECORDING;
    notifyState(state);

    line.start();

    recordingThread = new Thread(this::captureAudio, "ZenPACS-AudioCapture");
    recordingThread.setDaemon(true);
    recordingThread.start();

    startDurationTimer();
  }

  public void pauseRecording() {
    if (state != State.RECORDING) return;
    pauseStartTime = System.currentTimeMillis();
    state = State.PAUSED;
    notifyState(state);
  }

  public void resumeRecording() {
    if (state != State.PAUSED) return;
    totalPausedDuration += System.currentTimeMillis() - pauseStartTime;
    state = State.RECORDING;
    notifyState(state);
  }

  public void stopRecording() {
    if (state == State.IDLE || state == State.STOPPED) return;

    if (state == State.PAUSED) {
      totalPausedDuration += System.currentTimeMillis() - pauseStartTime;
    }

    state = State.STOPPED;
    notifyState(state);

    if (line != null) {
      line.stop();
      line.close();
    }

    if (recordingThread != null) {
      recordingThread.interrupt();
      try {
        recordingThread.join(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    File saved = saveToWav();
    if (saved != null) {
      notifySaved(saved);
    }

    state = State.IDLE;
    notifyState(state);
  }

  public long getElapsedSeconds() {
    if (state == State.IDLE || recordingStartTime == 0) return 0;
    long now = System.currentTimeMillis();
    long paused = totalPausedDuration;
    if (state == State.PAUSED) {
      paused += now - pauseStartTime;
    }
    return (now - recordingStartTime - paused) / 1000;
  }

  public static File getRecordingsDir(String patientCaseId) {
    if (patientCaseId != null && !patientCaseId.isEmpty()) {
      return new File(RECORDINGS_DIR, patientCaseId);
    }
    return RECORDINGS_DIR;
  }

  private void captureAudio() {
    byte[] buffer = new byte[4096];
    while (state == State.RECORDING || state == State.PAUSED) {
      if (state == State.PAUSED) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        // Drain the line buffer during pause to avoid stale data
        if (line.available() > 0) {
          line.read(buffer, 0, Math.min(buffer.length, line.available()));
        }
        continue;
      }

      int bytesRead = line.read(buffer, 0, buffer.length);
      if (bytesRead > 0) {
        audioBuffer.write(buffer, 0, bytesRead);
      }
    }
  }

  private File saveToWav() {
    if (audioBuffer == null || audioBuffer.size() == 0) {
      LOGGER.warn("No audio data to save");
      return null;
    }

    File dir = getRecordingsDir(currentPatientCaseId);
    if (!dir.exists() && !dir.mkdirs()) {
      LOGGER.error("Cannot create recordings directory: {}", dir);
      notifyError("Kay\u0131t klas\u00f6r\u00fc olu\u015fturulamad\u0131");
      return null;
    }

    String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
    File wavFile = new File(dir, "voice_" + timestamp + ".wav");

    byte[] audioData = audioBuffer.toByteArray();
    long frameCount = audioData.length / FORMAT.getFrameSize();

    try (AudioInputStream ais = new AudioInputStream(
        new ByteArrayInputStream(audioData), FORMAT, frameCount)) {
      AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavFile);
      LOGGER.info("Saved recording: {} ({} bytes)", wavFile.getAbsolutePath(), wavFile.length());
      return wavFile;
    } catch (IOException e) {
      LOGGER.error("Failed to save WAV file", e);
      notifyError("Ses kaydedilemedi");
      return null;
    }
  }

  private void startDurationTimer() {
    Thread timerThread = new Thread(() -> {
      while (state == State.RECORDING || state == State.PAUSED) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        if (listener != null) {
          listener.onDurationUpdate(getElapsedSeconds());
        }
      }
    }, "ZenPACS-DurationTimer");
    timerThread.setDaemon(true);
    timerThread.start();
  }

  private void notifyState(State s) {
    if (listener != null) listener.onStateChanged(s);
  }

  private void notifyError(String msg) {
    if (listener != null) listener.onError(msg);
  }

  private void notifySaved(File file) {
    if (listener != null) listener.onSaved(file);
  }
}

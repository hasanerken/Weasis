/*
 * Copyright (c) 2026 ZenPACS and other contributors.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.SwingWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.dicom.explorer.wado.DownloadManager;

public class VoiceUploadWorker extends SwingWorker<Boolean, Void> {

  private static final Logger LOGGER = LoggerFactory.getLogger(VoiceUploadWorker.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String BOUNDARY = "----ZenPACSVoiceBoundary" + System.currentTimeMillis();

  private final File wavFile;
  private final String patientCaseId;
  private final Consumer<Boolean> callback;

  public VoiceUploadWorker(File wavFile, String patientCaseId, Consumer<Boolean> callback) {
    this.wavFile = wavFile;
    this.patientCaseId = patientCaseId;
    this.callback = callback;
  }

  @Override
  protected Boolean doInBackground() throws Exception {
    String token = DownloadManager.getAuthToken();
    String baseUrl = DownloadManager.getApiBaseUrl();
    if (token == null || baseUrl == null) {
      LOGGER.warn("Cannot upload: missing token or baseUrl");
      return false;
    }

    String url = baseUrl + "/v2/patient-cases/upload-voice";
    LOGGER.info("Uploading voice: {} to {}", wavFile.getName(), url);

    HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
    conn.setRequestMethod("PUT");
    conn.setRequestProperty("Authorization", "Bearer " + token);
    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
    conn.setRequestProperty("Accept", "application/json");
    conn.setDoOutput(true);
    conn.setConnectTimeout(30000);
    conn.setReadTimeout(60000);

    try (OutputStream os = conn.getOutputStream()) {
      // Write patient_case_id field
      writeFormField(os, "patient_case_id", patientCaseId);

      // Write voice file
      writeFilePart(os, "voices", wavFile);

      // End boundary
      os.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
      os.flush();
    }

    int code = conn.getResponseCode();
    LOGGER.info("Upload response: {}", code);
    conn.disconnect();

    return code >= 200 && code < 300;
  }

  @Override
  protected void done() {
    try {
      boolean success = get();
      if (success) {
        LOGGER.info("Voice uploaded successfully: {}", wavFile.getName());
      }
      if (callback != null) callback.accept(success);
    } catch (Exception e) {
      LOGGER.error("Voice upload failed", e);
      if (callback != null) callback.accept(false);
    }
  }

  private void writeFormField(OutputStream os, String name, String value) throws Exception {
    os.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
    os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    os.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
  }

  private void writeFilePart(OutputStream os, String fieldName, File file) throws Exception {
    os.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
    os.write(("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + file.getName() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
    os.write(("Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    os.write(Files.readAllBytes(file.toPath()));
    os.write(("\r\n").getBytes(StandardCharsets.UTF_8));
  }

  /** Fetch existing voice recordings from server */
  public static void fetchVoices(String patientCaseId, Consumer<List<VoiceInfo>> callback) {
    String token = DownloadManager.getAuthToken();
    String baseUrl = DownloadManager.getApiBaseUrl();
    if (token == null || baseUrl == null || patientCaseId == null) {
      callback.accept(new ArrayList<>());
      return;
    }

    new SwingWorker<List<VoiceInfo>, Void>() {
      @Override
      protected List<VoiceInfo> doInBackground() throws Exception {
        String url = baseUrl + "/v2/patient-cases/" + patientCaseId + "/voices";
        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        List<VoiceInfo> voices = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
          StringBuilder sb = new StringBuilder();
          String line;
          while ((line = reader.readLine()) != null) sb.append(line);

          JsonNode root = MAPPER.readTree(sb.toString());
          JsonNode voicesNode = root.get("voices");
          if (voicesNode != null && voicesNode.isArray()) {
            for (JsonNode v : voicesNode) {
              VoiceInfo info = new VoiceInfo();
              info.key = textVal(v, "key");
              info.filename = textVal(v, "filename");
              info.size = v.has("size") ? v.get("size").asLong() : 0;
              info.uploadedBy = textVal(v, "uploaded_by");
              info.uploadedAt = textVal(v, "uploaded_at");
              info.url = textVal(v, "url");
              voices.add(info);
            }
          }
        } finally {
          conn.disconnect();
        }
        return voices;
      }

      @Override
      protected void done() {
        try {
          callback.accept(get());
        } catch (Exception e) {
          LOGGER.error("Failed to fetch voices", e);
          callback.accept(new ArrayList<>());
        }
      }
    }.execute();
  }

  /** Delete a voice recording from server */
  public static void deleteVoice(String patientCaseId, String voiceKey, Consumer<Boolean> callback) {
    String token = DownloadManager.getAuthToken();
    String baseUrl = DownloadManager.getApiBaseUrl();
    if (token == null || baseUrl == null) {
      callback.accept(false);
      return;
    }

    new SwingWorker<Boolean, Void>() {
      @Override
      protected Boolean doInBackground() throws Exception {
        String url = baseUrl + "/v2/patient-cases/delete-voice";
        String body = "{\"patient_case_id\":" + patientCaseId + ",\"voice_key\":\"" + voiceKey + "\"}";

        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        try (OutputStream os = conn.getOutputStream()) {
          os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        conn.disconnect();
        return code >= 200 && code < 300;
      }

      @Override
      protected void done() {
        try {
          callback.accept(get());
        } catch (Exception e) {
          LOGGER.error("Failed to delete voice", e);
          callback.accept(false);
        }
      }
    }.execute();
  }

  private static String textVal(JsonNode node, String field) {
    if (node == null) return "";
    JsonNode val = node.get(field);
    if (val == null || val.isNull()) return "";
    return val.asText("");
  }

  public static class VoiceInfo {
    public String key;
    public String filename;
    public long size;
    public String uploadedBy;
    public String uploadedAt;
    public String url;
  }
}

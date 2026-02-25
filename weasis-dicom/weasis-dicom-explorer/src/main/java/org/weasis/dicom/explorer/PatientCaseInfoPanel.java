/*
 * Copyright (c) 2026 ZenPACS and other contributors.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer;

import bibliothek.gui.dock.common.CLocation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.BorderLayout;
import java.awt.Color;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JEditorPane;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.Insertable;
import org.weasis.dicom.explorer.wado.DownloadManager;
import org.weasis.core.api.util.ResourceUtil;
import org.weasis.core.api.util.ResourceUtil.OtherIcon;
import org.weasis.core.ui.docking.PluginTool;

/**
 * Right-side dockable panel that displays patient case information
 * (orders, notes, anamnesis). Registered as a PluginTool so it appears
 * in the right sidebar alongside Display, Measure, etc.
 */
public class PatientCaseInfoPanel extends PluginTool {

  public static final String BUTTON_NAME = "Anamnez";

  private static final Logger LOGGER = LoggerFactory.getLogger(PatientCaseInfoPanel.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Color DARK_BG = new Color(40, 42, 46);

  /** Singleton instance — there is at most one right-side tool panel active at a time. */
  private static volatile PatientCaseInfoPanel instance;

  private final JEditorPane editorPane;
  private final JScrollPane rootPane;
  private String currentCaseId;
  private SwingWorker<JsonNode, Void> currentWorker;

  public PatientCaseInfoPanel() {
    super(BUTTON_NAME, Insertable.Type.TOOL, 35);
    dockable.setTitleIcon(ResourceUtil.getIcon(OtherIcon.PATIENT));
    setDockableWidth(260);

    editorPane = new JEditorPane();
    editorPane.setContentType("text/html");
    editorPane.setEditable(false);
    editorPane.setBackground(DARK_BG);

    rootPane = new JScrollPane(editorPane);
    rootPane.getViewport().setBackground(DARK_BG);
    rootPane.setBorder(BorderFactory.createEmptyBorder());
    rootPane.getVerticalScrollBar().setUnitIncrement(16);

    setLayout(new BorderLayout());
    setBackground(DARK_BG);
    add(rootPane, BorderLayout.CENTER);

    showPlaceholder();
    instance = this;
  }

  // ─── Static entry point called by DicomExplorer on patient selection ───

  /**
   * Called by DicomExplorer when the selected patient changes.
   * Updates the panel if it is currently visible/instantiated.
   */
  public static void updateGlobal(String caseId) {
    PatientCaseInfoPanel panel = instance;
    if (panel != null) {
      panel.setPatientCase(caseId);
    }
  }

  /**
   * Force-refresh the current case (e.g., when the Anamnez toolbar button is clicked).
   */
  public static void refreshGlobal() {
    PatientCaseInfoPanel panel = instance;
    if (panel != null && panel.currentCaseId != null) {
      String id = panel.currentCaseId;
      panel.currentCaseId = null;
      panel.setPatientCase(id);
    }
  }

  // ─── PluginTool contract ───

  @Override
  protected void changeToolWindowAnchor(CLocation clocation) {
    // No layout change needed when docked position changes
  }

  // ─── Internal logic ───

  private void showPlaceholder() {
    editorPane.setText(
        "<html><body style='font-family:sans-serif;font-size:11px;padding:12px;"
            + "background:#282A2E;color:#777;text-align:center;'>"
            + "<br/><br/>Hasta se\u00e7iniz..."
            + "</body></html>");
  }

  private void setPatientCase(String caseId) {
    if (caseId == null) {
      currentCaseId = null;
      showPlaceholder();
      return;
    }

    if (caseId.equals(currentCaseId)) {
      return;
    }
    currentCaseId = caseId;

    String token = DownloadManager.getAuthToken();
    String baseUrl = DownloadManager.getApiBaseUrl();

    if (token == null || baseUrl == null) {
      LOGGER.debug("Cannot fetch case info: token={}, baseUrl={}", token != null, baseUrl != null);
      showPlaceholder();
      return;
    }

    editorPane.setText(
        "<html><body style='font-family:sans-serif;font-size:11px;padding:12px;"
            + "background:#282A2E;color:#999;text-align:center;'>"
            + "<br/>Y\u00fckleniyor..."
            + "</body></html>");

    if (currentWorker != null && !currentWorker.isDone()) {
      currentWorker.cancel(true);
    }

    currentWorker =
        new SwingWorker<>() {
          @Override
          protected JsonNode doInBackground() throws Exception {
            String url = baseUrl + "/v2/patient-cases/" + caseId;
            LOGGER.info("Fetching patient case info: {}", url);
            HttpURLConnection conn =
                (HttpURLConnection) new URI(url).toURL().openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            try (BufferedReader reader =
                new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
              StringBuilder sb = new StringBuilder();
              String line;
              while ((line = reader.readLine()) != null) sb.append(line);
              return MAPPER.readTree(sb.toString());
            } finally {
              conn.disconnect();
            }
          }

          @Override
          protected void done() {
            try {
              if (isCancelled()) return;
              JsonNode data = get();
              if (caseId.equals(currentCaseId)) {
                renderData(data);
              }
            } catch (Exception e) {
              LOGGER.error("Failed to fetch patient case info", e);
              if (caseId.equals(currentCaseId)) {
                editorPane.setText(
                    "<html><body style='font-family:sans-serif;font-size:11px;padding:12px;"
                        + "background:#282A2E;color:#CC6666;'>"
                        + "Bilgiler y\u00fcklenemedi."
                        + "</body></html>");
              }
            }
          }
        };
    currentWorker.execute();
  }

  private void renderData(JsonNode data) {
    editorPane.setText(buildFullHtml(data));
    editorPane.setCaretPosition(0);
  }

  // ─── HTML Generation ───

  private String buildFullHtml(JsonNode data) {
    StringBuilder sb = new StringBuilder();
    sb.append("<html><body style='font-family:sans-serif;font-size:11px;padding:6px;")
        .append("background:#282A2E;color:#D3D3D3;'>");

    String patientName = textVal(data, "patient_name").replace("^", " ");
    String patientId = textVal(data, "patient_id");
    String modality = textVal(data, "modality");
    String studyDesc = textVal(data, "study_description");

    sb.append("<div style='background:#1565C0;color:white;padding:6px;border-radius:4px;")
        .append("margin-bottom:8px;'>");
    sb.append("<b style='font-size:12px;'>").append(esc(patientName)).append("</b><br/>");
    sb.append("<span style='font-size:10px;'>TC: ").append(esc(patientId));
    if (!modality.isEmpty()) sb.append(" | ").append(esc(modality));
    sb.append("</span></div>");

    if (!studyDesc.isEmpty()) {
      sb.append("<div style='color:#AAAAAA;margin-bottom:8px;font-size:10px;'>")
          .append(esc(studyDesc)).append("</div>");
    }

    List<JsonNode> orders = toList(data.get("orders"));
    sb.append(buildOrdersSection(orders));
    sb.append(buildNotesSection(data, orders));
    sb.append(buildAnamnesisSection(orders));
    sb.append("</body></html>");
    return sb.toString();
  }

  private String buildOrdersSection(List<JsonNode> orders) {
    StringBuilder sb = new StringBuilder();
    sb.append("<h3 style='color:#64B5F6;border-bottom:1px solid #64B5F6;padding-bottom:3px;")
        .append("margin-top:4px;font-size:12px;'>\u0130stemler</h3>");
    if (orders.isEmpty()) {
      sb.append("<p style='color:#777;font-size:10px;'>Sipari\u015f bulunamad\u0131.</p>");
    } else {
      for (int i = 0; i < orders.size(); i++) {
        JsonNode order = orders.get(i);
        String serviceName = textVal(order, "service_name");
        String serviceCode = textVal(order, "service_code");
        String status = textVal(order, "status");
        String orderModality = textVal(order, "modality");
        sb.append("<div style='background:#353740;padding:5px;border-radius:3px;")
            .append("margin-bottom:4px;border-left:3px solid #64B5F6;font-size:10px;'>");
        sb.append("<b style='color:#E0E0E0;'>")
            .append(i + 1).append(". ").append(esc(serviceName)).append("</b>");
        if (!serviceCode.isEmpty())
          sb.append("<br/><span style='color:#AAAAAA;'>Kod: ").append(esc(serviceCode)).append("</span>");
        if (!orderModality.isEmpty())
          sb.append(" <span style='background:#1565C0;color:white;padding:0 4px;")
              .append("border-radius:2px;'>").append(esc(orderModality)).append("</span>");
        if (!status.isEmpty()) {
          String statusColor = "completed".equals(status) ? "#66BB6A" : "#FFB74D";
          sb.append(" <span style='color:").append(statusColor).append(";'>")
              .append(esc(translateStatus(status))).append("</span>");
        }
        sb.append("</div>");
      }
    }
    return sb.toString();
  }

  private String buildNotesSection(JsonNode data, List<JsonNode> orders) {
    StringBuilder sb = new StringBuilder();

    String notes = textVal(data, "notes");
    if (!notes.isEmpty()) {
      sb.append("<h3 style='color:#64B5F6;border-bottom:1px solid #64B5F6;padding-bottom:3px;")
          .append("margin-top:8px;font-size:12px;'>Notlar</h3>");
      sb.append("<div style='background:#3A3520;padding:5px;border-radius:3px;")
          .append("border-left:3px solid #FFB74D;margin-bottom:4px;color:#E0E0E0;font-size:10px;'>")
          .append(esc(notes)).append("</div>");
    }

    JsonNode userNotes = data.get("user_notes");
    if (userNotes != null && userNotes.isArray() && !userNotes.isEmpty()) {
      sb.append("<h3 style='color:#64B5F6;border-bottom:1px solid #64B5F6;padding-bottom:3px;")
          .append("margin-top:8px;font-size:12px;'>Kullan\u0131c\u0131 Notlar\u0131</h3>");
      for (JsonNode note : userNotes) {
        sb.append("<div style='background:#2A3A2A;padding:4px;border-radius:3px;")
            .append("margin-bottom:3px;border-left:3px solid #66BB6A;color:#E0E0E0;font-size:10px;'>");
        if (note.isTextual()) {
          sb.append(esc(note.asText()));
        } else if (note.isObject()) {
          String noteText = textVal(note, "text");
          String author = textVal(note, "author");
          String createdAt = textVal(note, "created_at");
          if (!noteText.isEmpty()) sb.append(esc(noteText).replace("\n", "<br/>"));
          if (!author.isEmpty() || !createdAt.isEmpty()) {
            sb.append("<div style='margin-top:2px;font-size:9px;color:#999;'>");
            if (!author.isEmpty()) sb.append(esc(author));
            if (!createdAt.isEmpty()) {
              String dateOnly = createdAt.length() >= 10 ? createdAt.substring(0, 10) : createdAt;
              sb.append(author.isEmpty() ? "" : " \u2014 ").append(esc(dateOnly));
            }
            sb.append("</div>");
          }
        } else {
          sb.append(esc(note.toString()));
        }
        sb.append("</div>");
      }
    }

    Map<String, String> diagMap = new LinkedHashMap<>();
    for (JsonNode order : orders) {
      for (JsonNode diag : toList(order.get("prediagnosis"))) {
        String code = textVal(diag, "code");
        String desc = textVal(diag, "description");
        String key = code.isEmpty() ? desc : code;
        if (!key.isEmpty()) diagMap.putIfAbsent(key, desc);
      }
    }
    if (!diagMap.isEmpty()) {
      sb.append("<h3 style='color:#64B5F6;border-bottom:1px solid #64B5F6;padding-bottom:3px;")
          .append("margin-top:8px;font-size:12px;'>Tan\u0131lar</h3>");
      for (Map.Entry<String, String> entry : diagMap.entrySet()) {
        String code = entry.getKey();
        String desc = entry.getValue();
        sb.append("<div style='background:#352A3A;padding:4px;border-radius:3px;")
            .append("margin-bottom:3px;border-left:3px solid #BA68C8;color:#E0E0E0;font-size:10px;'>");
        if (!code.equals(desc) && !code.isEmpty())
          sb.append("<b style='color:#CE93D8;'>").append(esc(code)).append("</b> \u2014 ");
        sb.append(esc(desc)).append("</div>");
      }
    }
    return sb.toString();
  }

  private String buildAnamnesisSection(List<JsonNode> orders) {
    StringBuilder sb = new StringBuilder();
    sb.append("<h3 style='color:#64B5F6;border-bottom:1px solid #64B5F6;padding-bottom:3px;")
        .append("margin-top:8px;font-size:12px;'>Anamnez</h3>");

    Set<String> seenKeys = new LinkedHashSet<>();
    List<JsonNode> uniqueAnamnesis = new ArrayList<>();
    for (JsonNode order : orders) {
      for (JsonNode anam : toList(order.get("anamnesis"))) {
        String contentKey =
            textVal(anam, "complaints")
                + "|" + textVal(anam, "history")
                + "|" + textVal(anam, "symptoms")
                + "|" + textVal(anam, "pre_diagnosis")
                + "|" + textVal(anam, "cure");
        if (!contentKey.trim().isEmpty() && seenKeys.add(contentKey.trim())) {
          uniqueAnamnesis.add(anam);
        }
      }
    }

    if (uniqueAnamnesis.isEmpty()) {
      sb.append("<p style='color:#777;font-size:10px;'>Anamnez bulunamad\u0131.</p>");
    } else {
      for (JsonNode anam : uniqueAnamnesis) {
        String source = textVal(anam, "source");
        sb.append("<div style='background:#353740;padding:5px;border-radius:3px;")
            .append("margin-bottom:6px;border-left:3px solid #64B5F6;color:#E0E0E0;font-size:10px;'>");
        if (!source.isEmpty()) {
          String sourceColor = "manual".equals(source) ? "#FFB74D" : "#66BB6A";
          sb.append("<span style='background:").append(sourceColor)
              .append(";color:#1A1A1A;padding:0 4px;border-radius:2px;font-size:9px;'>")
              .append(esc(source)).append("</span><br/>");
        }
        appendField(sb, "\u015Eikayet", textVal(anam, "complaints"));
        appendField(sb, "\u00D6yk\u00FC", textVal(anam, "history"));
        appendField(sb, "Semptom", textVal(anam, "symptoms"));
        appendField(sb, "\u00D6n Tan\u0131", textVal(anam, "pre_diagnosis"));
        appendField(sb, "Tedavi", textVal(anam, "cure"));
        sb.append("</div>");
      }
    }
    return sb.toString();
  }

  // ─── Utilities ───

  private static String translateStatus(String status) {
    if (status == null) return "";
    return switch (status.toLowerCase()) {
      case "open" -> "a\u00e7\u0131k";
      case "completed" -> "tamamland\u0131";
      case "saved" -> "kaydedildi";
      case "reported" -> "raporland\u0131";
      case "approved" -> "onayland\u0131";
      case "cancelled", "canceled" -> "iptal edildi";
      case "pending" -> "beklemede";
      case "in_progress", "in progress" -> "devam ediyor";
      case "draft" -> "taslak";
      case "sent" -> "g\u00f6nderildi";
      default -> status;
    };
  }

  private static void appendField(StringBuilder sb, String label, String value) {
    if (value != null && !value.isEmpty()) {
      sb.append("<div style='margin-top:2px;'>");
      sb.append("<b style='color:#64B5F6;'>").append(esc(label)).append(":</b> ");
      sb.append(esc(value));
      sb.append("</div>");
    }
  }

  private static List<JsonNode> toList(JsonNode node) {
    List<JsonNode> list = new ArrayList<>();
    if (node == null || node.isNull()) return list;
    if (node.isArray()) {
      for (JsonNode item : node) list.add(item);
    } else if (node.isObject()) {
      list.add(node);
    }
    return list;
  }

  private static String textVal(JsonNode node, String field) {
    if (node == null) return "";
    JsonNode val = node.get(field);
    if (val == null || val.isNull()) return "";
    return val.asText("");
  }

  private static String esc(String text) {
    if (text == null) return "";
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}

/*
 * Copyright (c) 2026 ZenPACS and other contributors.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
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
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.dicom.explorer.wado.DownloadManager;

public class PatientCaseInfoDialog {

  private static final Logger LOGGER = LoggerFactory.getLogger(PatientCaseInfoDialog.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static JDialog currentDialog;
  private static AWTEventListener dismissListener;

  public static void show(Component parent, String patientCaseId) {
    String token = DownloadManager.getAuthToken();
    String baseUrl = DownloadManager.getApiBaseUrl();

    if (token == null || baseUrl == null || patientCaseId == null) {
      LOGGER.debug(
          "Cannot show case info: token={}, baseUrl={}, caseId={}",
          token != null,
          baseUrl != null,
          patientCaseId);
      return;
    }

    new SwingWorker<JsonNode, Void>() {
      @Override
      protected JsonNode doInBackground() throws Exception {
        String url = baseUrl + "/v2/patient-cases/" + patientCaseId;
        LOGGER.info("Fetching patient case info: {}", url);
        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        try (BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
          StringBuilder sb = new StringBuilder();
          String line;
          while ((line = reader.readLine()) != null) {
            sb.append(line);
          }
          return MAPPER.readTree(sb.toString());
        } finally {
          conn.disconnect();
        }
      }

      @Override
      protected void done() {
        try {
          JsonNode data = get();
          showDialog(parent, data, patientCaseId);
        } catch (Exception e) {
          LOGGER.error("Failed to fetch patient case info", e);
        }
      }
    }.execute();
  }

  private static void showDialog(Component parent, JsonNode data, String patientCaseId) {
    // Auto-close previous popup and clean up listener
    removeDismissListener();
    if (currentDialog != null) {
      currentDialog.dispose();
      currentDialog = null;
    }

    Window owner =
        parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);

    JDialog dialog = new JDialog(owner, "Hasta Bilgileri", JDialog.ModalityType.MODELESS);
    dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
    currentDialog = dialog;

    // Clean up AWTEventListener when dialog is closed via X button
    dialog.addWindowListener(new java.awt.event.WindowAdapter() {
      @Override
      public void windowClosed(java.awt.event.WindowEvent e) {
        removeDismissListener();
        if (currentDialog == dialog) {
          currentDialog = null;
        }
      }
    });

    // 70% width, 50% height
    Rectangle screenBounds =
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getDefaultScreenDevice()
            .getDefaultConfiguration()
            .getBounds();
    int w = (int) (screenBounds.width * 0.70);
    int h = (int) (screenBounds.height * 0.50);
    dialog.setSize(w, h);
    dialog.setLocationRelativeTo(owner);

    // Dark theme background
    Color darkBg = new Color(40, 42, 46);
    dialog.getContentPane().setBackground(darkBg);

    // Patient header
    String patientName = textVal(data, "patient_name").replace("^", " ");
    String patientId = textVal(data, "patient_id");
    String modality = textVal(data, "modality");
    String studyDesc = textVal(data, "study_description");

    // Three column layout
    JPanel mainPanel = new JPanel(new MigLayout("fill, insets 8", "[33%,fill][34%,fill][33%,fill]", "[fill]"));
    mainPanel.setBackground(darkBg);

    // Parse orders (handle both array and single object)
    List<JsonNode> orders = toList(data.get("orders"));

    // Column 1: İstemler (Orders)
    mainPanel.add(createScrollColumn(buildOrdersHtml(orders, patientName, patientId, modality, studyDesc)), "cell 0 0");

    // Column 2: Notes & Diagnosis
    mainPanel.add(createScrollColumn(buildNotesHtml(data, orders)), "cell 1 0");

    // Column 3: Anamnesis (deduplicated)
    mainPanel.add(createScrollColumn(buildAnamnesisHtml(orders)), "cell 2 0");

    dialog.getContentPane().add(mainPanel, BorderLayout.CENTER);
    dialog.setVisible(true);

    // Dismiss when clicking outside — install listener after 500ms so initial focus settles
    javax.swing.Timer installTimer = new javax.swing.Timer(500, evt -> {
      // Don't install if dialog was already closed
      if (!dialog.isShowing()) return;
      removeDismissListener();
      dismissListener = event -> {
        if (event.getID() == MouseEvent.MOUSE_PRESSED) {
          try {
            if (!dialog.isShowing()) {
              SwingUtilities.invokeLater(PatientCaseInfoDialog::removeDismissListener);
              return;
            }
            MouseEvent me = (MouseEvent) event;
            java.awt.Point screenPoint = me.getLocationOnScreen();
            java.awt.Point dialogLoc = dialog.getLocationOnScreen();
            java.awt.Rectangle dialogBounds = new java.awt.Rectangle(
                dialogLoc.x, dialogLoc.y, dialog.getWidth(), dialog.getHeight());
            if (!dialogBounds.contains(screenPoint)) {
              SwingUtilities.invokeLater(() -> {
                removeDismissListener();
                dialog.dispose();
                if (currentDialog == dialog) {
                  currentDialog = null;
                }
              });
            }
          } catch (Exception ignored) {
            // Dialog disposed — clean up listener to prevent blocking events
            SwingUtilities.invokeLater(PatientCaseInfoDialog::removeDismissListener);
          }
        }
      };
      Toolkit.getDefaultToolkit().addAWTEventListener(
          dismissListener, java.awt.AWTEvent.MOUSE_EVENT_MASK);
    });
    installTimer.setRepeats(false);
    installTimer.start();
  }

  private static void removeDismissListener() {
    if (dismissListener != null) {
      Toolkit.getDefaultToolkit().removeAWTEventListener(dismissListener);
      dismissListener = null;
    }
  }

  private static JScrollPane createScrollColumn(String html) {
    JEditorPane pane = new JEditorPane();
    pane.setContentType("text/html");
    pane.setEditable(false);
    pane.setText(html);
    pane.setCaretPosition(0);
    Color darkBg = new Color(40, 42, 46);
    pane.setBackground(darkBg);
    JScrollPane scrollPane = new JScrollPane(pane);
    scrollPane.setMinimumSize(new Dimension(100, 200));
    scrollPane.getViewport().setBackground(darkBg);
    scrollPane.setBorder(BorderFactory.createLineBorder(new Color(60, 63, 68)));
    return scrollPane;
  }

  // Column 1: Orders with service names
  private static String buildOrdersHtml(
      List<JsonNode> orders, String patientName, String patientId,
      String modality, String studyDesc) {
    StringBuilder sb = new StringBuilder();
    sb.append("<html><body style='font-family:sans-serif;font-size:11px;padding:8px;background:#282A2E;color:#D3D3D3;'>");

    // Patient info header
    sb.append("<div style='background:#1565C0;color:white;padding:8px;border-radius:4px;margin-bottom:10px;'>");
    sb.append("<b style='font-size:13px;'>").append(esc(patientName)).append("</b><br/>");
    sb.append("TC: ").append(esc(patientId));
    sb.append(" &nbsp;|&nbsp; ").append(esc(modality));
    sb.append("</div>");

    if (studyDesc != null && !studyDesc.isEmpty()) {
      sb.append("<div style='color:#AAAAAA;margin-bottom:10px;'>")
          .append(esc(studyDesc))
          .append("</div>");
    }

    sb.append("<h3 style='color:#64B5F6;border-bottom:2px solid #64B5F6;padding-bottom:4px;'>\u0130stemler</h3>");

    if (orders.isEmpty()) {
      sb.append("<p style='color:#777;'>Sipari\u015f bulunamad\u0131.</p>");
    } else {
      for (int i = 0; i < orders.size(); i++) {
        JsonNode order = orders.get(i);
        String serviceName = textVal(order, "service_name");
        String serviceCode = textVal(order, "service_code");
        String status = textVal(order, "status");
        String orderModality = textVal(order, "modality");

        sb.append("<div style='background:#353740;padding:8px;border-radius:4px;margin-bottom:6px;border-left:3px solid #64B5F6;'>");
        sb.append("<b style='color:#E0E0E0;'>").append(i + 1).append(". ").append(esc(serviceName)).append("</b><br/>");
        if (!serviceCode.isEmpty()) {
          sb.append("<span style='color:#AAAAAA;font-size:10px;'>Kod: ")
              .append(esc(serviceCode)).append("</span>");
        }
        if (!orderModality.isEmpty()) {
          sb.append(" &nbsp;<span style='background:#1565C0;color:white;padding:1px 6px;border-radius:3px;font-size:10px;'>")
              .append(esc(orderModality)).append("</span>");
        }
        if (!status.isEmpty()) {
          String statusColor = "completed".equals(status) ? "#66BB6A" : "#FFB74D";
          sb.append(" &nbsp;<span style='color:").append(statusColor).append(";font-size:10px;'>")
              .append(esc(translateStatus(status))).append("</span>");
        }
        sb.append("</div>");
      }
    }

    sb.append("</body></html>");
    return sb.toString();
  }

  // Column 2: Notes + User Notes + Diagnosis
  private static String buildNotesHtml(JsonNode data, List<JsonNode> orders) {
    StringBuilder sb = new StringBuilder();
    sb.append("<html><body style='font-family:sans-serif;font-size:11px;padding:8px;background:#282A2E;color:#D3D3D3;'>");

    // Notes
    sb.append("<h3 style='color:#64B5F6;border-bottom:2px solid #64B5F6;padding-bottom:4px;'>Notlar</h3>");
    String notes = textVal(data, "notes");
    if (!notes.isEmpty()) {
      sb.append("<div style='background:#3A3520;padding:8px;border-radius:4px;border-left:3px solid #FFB74D;margin-bottom:8px;color:#E0E0E0;'>")
          .append(esc(notes)).append("</div>");
    } else {
      sb.append("<p style='color:#777;'>Not yok.</p>");
    }

    // User Notes
    JsonNode userNotes = data.get("user_notes");
    if (userNotes != null && userNotes.isArray() && !userNotes.isEmpty()) {
      sb.append("<h3 style='color:#64B5F6;border-bottom:2px solid #64B5F6;padding-bottom:4px;'>Kullan\u0131c\u0131 Notlar\u0131</h3>");
      for (JsonNode note : userNotes) {
        sb.append("<div style='background:#2A3A2A;padding:6px;border-radius:4px;margin-bottom:4px;border-left:3px solid #66BB6A;color:#E0E0E0;'>");
        if (note.isTextual()) {
          sb.append(esc(note.asText()));
        } else if (note.isObject()) {
          String noteText = textVal(note, "text");
          String author = textVal(note, "author");
          String createdAt = textVal(note, "created_at");
          if (!noteText.isEmpty()) {
            sb.append(esc(noteText).replace("\n", "<br/>"));
          }
          if (!author.isEmpty() || !createdAt.isEmpty()) {
            sb.append("<div style='margin-top:4px;font-size:9px;color:#999;'>");
            if (!author.isEmpty()) {
              sb.append(esc(author));
            }
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

    // Diagnosis from all orders (deduplicated)
    sb.append("<h3 style='color:#64B5F6;border-bottom:2px solid #64B5F6;padding-bottom:4px;margin-top:12px;'>Tan\u0131lar</h3>");
    Map<String, String> diagMap = new LinkedHashMap<>(); // code -> description, dedup
    for (JsonNode order : orders) {
      List<JsonNode> prediagList = toList(order.get("prediagnosis"));
      for (JsonNode diag : prediagList) {
        String code = textVal(diag, "code");
        String desc = textVal(diag, "description");
        String key = code.isEmpty() ? desc : code;
        if (!key.isEmpty()) {
          diagMap.putIfAbsent(key, desc);
        }
      }
    }

    if (diagMap.isEmpty()) {
      sb.append("<p style='color:#777;'>Tan\u0131 bulunamad\u0131.</p>");
    } else {
      for (Map.Entry<String, String> entry : diagMap.entrySet()) {
        String code = entry.getKey();
        String desc = entry.getValue();
        sb.append("<div style='background:#352A3A;padding:6px;border-radius:4px;margin-bottom:4px;border-left:3px solid #BA68C8;color:#E0E0E0;'>");
        if (!code.equals(desc) && !code.isEmpty()) {
          sb.append("<b style='color:#CE93D8;'>").append(esc(code)).append("</b> &mdash; ");
        }
        sb.append(esc(desc));
        sb.append("</div>");
      }
    }

    sb.append("</body></html>");
    return sb.toString();
  }

  // Column 3: Anamnesis (deduplicated across orders)
  private static String buildAnamnesisHtml(List<JsonNode> orders) {
    StringBuilder sb = new StringBuilder();
    sb.append("<html><body style='font-family:sans-serif;font-size:11px;padding:8px;background:#282A2E;color:#D3D3D3;'>");
    sb.append("<h3 style='color:#64B5F6;border-bottom:2px solid #64B5F6;padding-bottom:4px;'>Anamnez</h3>");

    // Collect all anamnesis, deduplicate by ID
    Set<String> seenIds = new LinkedHashSet<>();
    List<JsonNode> uniqueAnamnesis = new ArrayList<>();
    for (JsonNode order : orders) {
      List<JsonNode> anamnesisList = toList(order.get("anamnesis"));
      for (JsonNode anam : anamnesisList) {
        String id = textVal(anam, "id");
        // Deduplicate by ID, or by complaints content if no ID
        String dedupeKey = id.isEmpty() ? textVal(anam, "complaints") : id;
        if (!dedupeKey.isEmpty() && seenIds.add(dedupeKey)) {
          uniqueAnamnesis.add(anam);
        }
      }
    }

    if (uniqueAnamnesis.isEmpty()) {
      sb.append("<p style='color:#777;'>Anamnez bulunamad\u0131.</p>");
    } else {
      for (int i = 0; i < uniqueAnamnesis.size(); i++) {
        JsonNode anam = uniqueAnamnesis.get(i);
        String complaints = textVal(anam, "complaints");
        String history = textVal(anam, "history");
        String symptoms = textVal(anam, "symptoms");
        String preDiagnosis = textVal(anam, "pre_diagnosis");
        String cure = textVal(anam, "cure");
        String source = textVal(anam, "source");

        sb.append("<div style='background:#353740;padding:8px;border-radius:4px;margin-bottom:8px;border-left:3px solid #64B5F6;color:#E0E0E0;'>");

        if (!source.isEmpty()) {
          String sourceColor = "manual".equals(source) ? "#FFB74D" : "#66BB6A";
          sb.append("<span style='background:").append(sourceColor)
              .append(";color:#1A1A1A;padding:1px 6px;border-radius:3px;font-size:10px;'>")
              .append(esc(source)).append("</span><br/>");
        }

        appendField(sb, "\u015Eikayet", complaints);
        appendField(sb, "\u00D6yk\u00FC", history);
        appendField(sb, "Semptom", symptoms);
        appendField(sb, "\u00D6n Tan\u0131", preDiagnosis);
        appendField(sb, "Tedavi", cure);

        sb.append("</div>");
      }
    }

    sb.append("</body></html>");
    return sb.toString();
  }

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
      sb.append("<div style='margin-top:4px;'>");
      sb.append("<b style='color:#64B5F6;'>").append(esc(label)).append(":</b> ");
      sb.append(esc(value));
      sb.append("</div>");
    }
  }

  /** Convert a JsonNode to a list — handles null, array, and single object. */
  private static List<JsonNode> toList(JsonNode node) {
    List<JsonNode> list = new ArrayList<>();
    if (node == null || node.isNull()) {
      return list;
    }
    if (node.isArray()) {
      for (JsonNode item : node) {
        list.add(item);
      }
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

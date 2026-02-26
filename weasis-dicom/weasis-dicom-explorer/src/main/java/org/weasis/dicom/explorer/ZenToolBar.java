/*
 * Copyright (c) 2026 ZenPACS and other contributors.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.core.ui.editor.image.ViewerPlugin;
import org.weasis.core.ui.util.WtoolBar;
import org.weasis.dicom.explorer.wado.DownloadManager;

public class ZenToolBar extends WtoolBar {

  private static final Logger LOGGER = LoggerFactory.getLogger(ZenToolBar.class);

  private final DicomExplorer explorer;
  private final JButton btnOkundu;
  private final JButton btnOkunmadi;
  private final JButton btnImajEksik;
  private final JButton btnOkKapat;
  private final JButton[] allButtons;

  public ZenToolBar(int index, DicomExplorer explorer) {
    super("ZenPACS", index);
    setAttachedInsertable(explorer);
    this.explorer = explorer;

    Insets btnMargin = new Insets(6, 14, 6, 14);
    Dimension btnMinSize = new Dimension(90, 36);

    // Okundu button (soft muted green)
    btnOkundu = new JButton("Okundu");
    btnOkundu.setForeground(new Color(140, 180, 140));
    btnOkundu.setMargin(btnMargin);
    btnOkundu.setMinimumSize(btnMinSize);
    btnOkundu.setToolTipText("Hasta durumunu okundu olarak i\u015faretle ("
        + ZenShortcuts.getInstance().getDisplayText(ZenShortcuts.Action.OKUNDU) + ")");
    btnOkundu.addActionListener(e -> updateStatus("dictated", btnOkundu, "Okundu"));
    add(btnOkundu);

    // Okunmadı button (soft muted amber)
    btnOkunmadi = new JButton("Okunmad\u0131");
    btnOkunmadi.setForeground(new Color(190, 170, 120));
    btnOkunmadi.setMargin(btnMargin);
    btnOkunmadi.setMinimumSize(btnMinSize);
    btnOkunmadi.setToolTipText("Hasta durumunu okunmad\u0131 olarak i\u015faretle");
    btnOkunmadi.addActionListener(e -> updateStatus("pending", btnOkunmadi, "Okunmad\u0131"));
    add(btnOkunmadi);

    // İmaj Eksik button (soft muted rose)
    btnImajEksik = new JButton("\u0130maj Eksik");
    btnImajEksik.setForeground(new Color(190, 130, 130));
    btnImajEksik.setMargin(btnMargin);
    btnImajEksik.setMinimumSize(btnMinSize);
    btnImajEksik.setToolTipText("Eksik g\u00f6r\u00fcnt\u00fc bildir");
    btnImajEksik.addActionListener(e -> updateScope("missing_image", btnImajEksik, "\u0130maj Eksik"));
    add(btnImajEksik);

    // OK + Kapat button (green background, white text) - marks okundu and closes the active tab
    btnOkKapat = new JButton("OK + Kapat");
    btnOkKapat.putClientProperty("JButton.buttonType", "none");
    btnOkKapat.setBackground(new Color(76, 175, 80));
    btnOkKapat.setForeground(Color.WHITE);
    btnOkKapat.setOpaque(true);
    btnOkKapat.setFont(btnOkKapat.getFont().deriveFont(Font.BOLD));
    btnOkKapat.setMargin(btnMargin);
    btnOkKapat.setMinimumSize(btnMinSize);
    btnOkKapat.setToolTipText("Okundu olarak i\u015faretle ve aktif sekmeyi kapat ("
        + ZenShortcuts.getInstance().getDisplayText(ZenShortcuts.Action.OK_KAPAT) + ")");
    btnOkKapat.addActionListener(e -> updateStatusAndClose(btnOkKapat));
    add(btnOkKapat);

    allButtons = new JButton[] {btnOkundu, btnOkunmadi, btnImajEksik, btnOkKapat};
  }

  /** Trigger Okundu action via keyboard shortcut */
  public void triggerOkundu() {
    updateStatus("dictated", btnOkundu, "Okundu");
  }

  /** Trigger OK + Kapat action via keyboard shortcut */
  public void triggerOkKapat() {
    updateStatusAndClose(btnOkKapat);
  }

  private String getSelectedCaseId() {
    MediaSeriesGroup patient = explorer.getSelectedPatient();
    if (patient == null) return null;
    return (String) patient.getTagValue(DownloadManager.PATIENT_CASE_ID);
  }

  private void updateStatus(String status, JButton button, String originalText) {
    String caseId = getSelectedCaseId();
    String token = DownloadManager.getAuthToken();
    String baseUrl = DownloadManager.getApiBaseUrl();

    if (caseId == null || token == null || baseUrl == null) {
      LOGGER.warn("Cannot update status: caseId={}, token={}, baseUrl={}", caseId != null, token != null, baseUrl != null);
      flashButton(button, originalText, "Hasta se\u00e7in", new Color(190, 130, 130));
      return;
    }

    String url = baseUrl + "/v2/patient-cases/update-status";
    String body = "{\"patient_case_ids\":[" + caseId + "],\"status\":\"" + status + "\"}";
    sendPutRequest(url, body, token, button, originalText, "Kaydedildi");
  }

  private void updateScope(String scope, JButton button, String originalText) {
    String caseId = getSelectedCaseId();
    String token = DownloadManager.getAuthToken();
    String baseUrl = DownloadManager.getApiBaseUrl();

    if (caseId == null || token == null || baseUrl == null) {
      LOGGER.warn("Cannot update scope: caseId={}, token={}, baseUrl={}", caseId != null, token != null, baseUrl != null);
      flashButton(button, originalText, "Hasta se\u00e7in", new Color(190, 130, 130));
      return;
    }

    String url = baseUrl + "/v2/patient-cases/update-scope";
    String body = "{\"patient_case_ids\":[" + caseId + "],\"patient_scope\":\"" + scope + "\"}";
    sendPutRequest(url, body, token, button, originalText, "G\u00f6nderildi");
  }

  private void updateStatusAndClose(JButton button) {
    String caseId = getSelectedCaseId();
    String token = DownloadManager.getAuthToken();
    String baseUrl = DownloadManager.getApiBaseUrl();

    if (caseId == null || token == null || baseUrl == null) {
      LOGGER.warn("Cannot update status: caseId={}, token={}, baseUrl={}", caseId != null, token != null, baseUrl != null);
      flashButton(button, "OK + Kapat", "Hasta se\u00e7in", new Color(190, 130, 130));
      return;
    }

    String url = baseUrl + "/v2/patient-cases/update-status";
    String body = "{\"patient_case_ids\":[" + caseId + "],\"status\":\"dictated\"}";
    // Capture the patient before the async request so we close the correct tab
    MediaSeriesGroup patient = explorer.getSelectedPatient();
    sendPutRequestWithCallback(url, body, token, button, "OK + Kapat", () -> {
      // On success, close only the viewer tab for this patient
      if (patient != null) {
        List<ViewerPlugin<?>> plugins = GuiUtils.getUICore().getViewerPlugins();
        List<ViewerPlugin<?>> toClose = new ArrayList<>();
        synchronized (plugins) {
          for (ViewerPlugin<?> p : plugins) {
            if (patient.equals(p.getGroupID())) {
              toClose.add(p);
            }
          }
        }
        if (!toClose.isEmpty()) {
          GuiUtils.getUICore().closeSeriesViewer(toClose);
        }
      }
    });
  }

  private void sendPutRequestWithCallback(
      String url, String jsonBody, String token,
      JButton button, String originalText, Runnable onSuccess) {

    for (JButton b : allButtons) b.setEnabled(false);

    new SwingWorker<Integer, Void>() {
      @Override
      protected Integer doInBackground() throws Exception {
        LOGGER.info("PUT {} body={}", url, jsonBody);
        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        try (OutputStream os = conn.getOutputStream()) {
          os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        LOGGER.info("PUT response: {}", code);
        conn.disconnect();
        return code;
      }

      @Override
      protected void done() {
        try {
          int code = get();
          if (code >= 200 && code < 300) {
            flashButton(button, originalText, "Kaydedildi", new Color(140, 180, 140));
            if (onSuccess != null) {
              SwingUtilities.invokeLater(onSuccess);
            }
          } else {
            flashButton(button, originalText, "Hata: " + code, new Color(190, 130, 130));
          }
        } catch (Exception e) {
          LOGGER.error("PUT request failed: {}", url, e);
          flashButton(button, originalText, "Ba\u011flant\u0131 Hatas\u0131", new Color(190, 130, 130));
        }
      }
    }.execute();
  }

  private void sendPutRequest(
      String url, String jsonBody, String token,
      JButton button, String originalText, String successText) {

    for (JButton b : allButtons) b.setEnabled(false);

    new SwingWorker<Integer, Void>() {
      @Override
      protected Integer doInBackground() throws Exception {
        LOGGER.info("PUT {} body={}", url, jsonBody);
        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        try (OutputStream os = conn.getOutputStream()) {
          os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        LOGGER.info("PUT response: {}", code);
        conn.disconnect();
        return code;
      }

      @Override
      protected void done() {
        try {
          int code = get();
          if (code >= 200 && code < 300) {
            flashButton(button, originalText, successText, new Color(140, 180, 140));
          } else {
            flashButton(button, originalText, "Hata: " + code, new Color(190, 130, 130));
          }
        } catch (Exception e) {
          LOGGER.error("PUT request failed: {}", url, e);
          flashButton(button, originalText, "Ba\u011flant\u0131 Hatas\u0131", new Color(190, 130, 130));
        }
      }
    }.execute();
  }

  private void flashButton(JButton button, String originalText, String flashText, Color flashColor) {
    Color originalFg = button.getForeground();
    Color originalBg = button.getBackground();
    boolean wasOpaque = button.isOpaque();
    button.setText(flashText);
    button.setForeground(flashColor);
    // Remove background during flash so colored text is visible
    if (wasOpaque) {
      button.setOpaque(false);
      button.setBackground(null);
    }

    new Timer().schedule(new TimerTask() {
      @Override
      public void run() {
        SwingUtilities.invokeLater(() -> {
          button.setText(originalText);
          button.setForeground(originalFg);
          if (wasOpaque) {
            button.setOpaque(true);
            button.setBackground(originalBg);
          }
          for (JButton b : allButtons) b.setEnabled(true);
        });
      }
    }, 800);
  }
}

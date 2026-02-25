/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer;

import java.awt.Window;
import java.util.Hashtable;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Deactivate;
import org.weasis.core.api.explorer.DataExplorerView;
import org.weasis.core.api.explorer.DataExplorerViewFactory;
import org.weasis.core.api.explorer.ObservableEvent;
import org.weasis.core.api.explorer.model.DataExplorerModel;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.ui.editor.ViewerPluginBuilder;
import org.weasis.core.ui.util.Toolbar;

@org.osgi.service.component.annotations.Component(service = DataExplorerViewFactory.class)
public class DicomExplorerFactory implements DataExplorerViewFactory {

  private DicomExplorer explorer = null;

  @org.osgi.service.component.annotations.Reference private DicomModel model;

  @Override
  public DataExplorerView createDataExplorerView(Hashtable<String, Object> properties) {
    if (explorer == null) {
      explorer = new DicomExplorer(model);
      model.addPropertyChangeListener(explorer);
      List<Toolbar> toolbar = GuiUtils.getUICore().getExplorerPluginToolbars();
      toolbar.add(new ImportToolBar(5, explorer));
      toolbar.add(new ExportToolBar(7, explorer));
      toolbar.add(new ZenToolBar(200, explorer));
      ViewerPluginBuilder.DefaultDataModel.firePropertyChange(
          new ObservableEvent(ObservableEvent.BasicAction.NULL_SELECTION, explorer, null, null));

      // Install floating audio recorder panel after frame is ready
      DicomExplorer ref = explorer;
      Timer installTimer = new Timer(2000, e -> {
        SwingUtilities.invokeLater(() -> installAudioRecorder(ref));
      });
      installTimer.setRepeats(false);
      installTimer.start();
    }
    return explorer;
  }

  private void installAudioRecorder(DicomExplorer explorerRef) {
    try {
      Window window = GuiUtils.getUICore().getApplicationWindow();
      System.out.println("[ZenPACS] installAudioRecorder: window=" + (window != null ? window.getClass().getName() : "null"));
      if (window instanceof JFrame frame) {
        JLayeredPane layeredPane = frame.getLayeredPane();
        System.out.println("[ZenPACS] layeredPane size: " + layeredPane.getWidth() + "x" + layeredPane.getHeight());
        AudioRecorderPanel recorderPanel = new AudioRecorderPanel(explorerRef);
        recorderPanel.installInLayeredPane(layeredPane);
        System.out.println("[ZenPACS] AudioRecorderPanel installed");
      } else {
        System.out.println("[ZenPACS] Window is not JFrame, skipping audio recorder");
      }
    } catch (Exception e) {
      System.err.println("[ZenPACS] installAudioRecorder error: " + e.getMessage());
      e.printStackTrace();
    }
  }

  // ================================================================================
  // OSGI service implementation
  // ================================================================================

  @Activate
  protected void activate(ComponentContext context) {
    // Do nothing
  }

  @Deactivate
  protected void deactivate(ComponentContext context) {
    if (explorer != null) {
      DataExplorerModel dataModel = explorer.getDataExplorerModel();
      dataModel.removePropertyChangeListener(explorer);
      GuiUtils.getUICore()
          .getExplorerPluginToolbars()
          .removeIf(b -> b.getComponent().getAttachedInsertable() == explorer);
    }
  }
}

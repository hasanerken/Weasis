/*
 * Copyright (c) 2022 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.media.data.MediaElement;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.core.api.media.data.Series;
import org.weasis.core.ui.editor.SeriesViewerFactory;
import org.weasis.core.ui.editor.ViewerPluginBuilder;
import org.weasis.dicom.explorer.HangingProtocols.OpeningViewer;

public class PluginOpeningStrategy {

  private static final Logger LOGGER = LoggerFactory.getLogger(PluginOpeningStrategy.class);

  private OpeningViewer openingMode;
  private boolean fullImportSession;

  private final Set<MediaSeriesGroup> openPatients = Collections.synchronizedSet(new HashSet<>());
  // Immutable flag: once a viewer has been opened, removePatient() cannot reset this.
  // Prevents the mergePatientUID race where removePatient temporarily empties openPatients.
  private final AtomicBoolean firstViewerOpened = new AtomicBoolean(false);

  public PluginOpeningStrategy(OpeningViewer openingMode) {
    setOpeningMode(openingMode);
    this.fullImportSession = true;
  }

  public OpeningViewer getOpeningMode() {
    return openingMode;
  }

  public void setOpeningMode(OpeningViewer openingMode) {
    this.openingMode = Objects.requireNonNullElse(openingMode, OpeningViewer.ALL_PATIENTS);
  }

  public boolean containsPatient(MediaSeriesGroup patient) {
    return openPatients.contains(Objects.requireNonNull(patient));
  }

  public boolean isFullImportSession() {
    return fullImportSession;
  }

  public void setFullImportSession(boolean fullImportSession) {
    this.fullImportSession = fullImportSession;
  }

  public void reset() {
    if (fullImportSession) {
      openPatients.clear();
      firstViewerOpened.set(false);
    }
  }

  public void addPatient(MediaSeriesGroup patient) {
    openPatients.add(Objects.requireNonNull(patient));
  }

  public void removePatient(MediaSeriesGroup patient) {
    openPatients.remove(patient);
  }

  public void prepareImport() {
    if (isRemovingPrevious() && (fullImportSession || openPatients.isEmpty())) {
      GuiUtils.getUICore()
          .closeSeriesViewer(new ArrayList<>(GuiUtils.getUICore().getViewerPlugins()));
    }
  }

  public boolean isRemovingPrevious() {
    return OpeningViewer.ONE_PATIENT_CLEAN.equals(openingMode)
        || OpeningViewer.ALL_PATIENTS_CLEAN.equals(openingMode);
  }

  public void openViewerPlugin(
      MediaSeriesGroup patient, DicomModel dicomModel, Series<?> dicomSeries) {
    Objects.requireNonNull(dicomModel);
    Objects.requireNonNull(dicomSeries);

    if (DicomModel.isHiddenModality(dicomSeries)) {
      return;
    }

    // Synchronized to prevent race condition: multiple download threads could pass
    // the canAddNewPatient() check simultaneously before any calls addPatient().
    synchronized (openPatients) {
      boolean isPatientOpen = containsPatient(patient);
      if (!isPatientOpen && canAddNewPatient()) {
        String mime = dicomSeries.getMimeType();
        SeriesViewerFactory plugin = GuiUtils.getUICore().getViewerFactory(mime);
        if (plugin != null
            && !("sr/dicom".equals(mime)) // NON-NLS
            && !(plugin instanceof MimeSystemAppFactory)) {
          LOGGER.info(
              "Opening viewer: mode={}, patient={}, openPatients={}",
              openingMode,
              patient,
              openPatients.size());
          addPatient(patient);
          firstViewerOpened.set(true);

          // Collect up to 2 non-hidden series of the same type for side-by-side display
          List<MediaSeries<? extends MediaElement>> seriesToOpen = new ArrayList<>();
          seriesToOpen.add(dicomSeries);
          for (MediaSeriesGroup study : dicomModel.getChildren(patient)) {
            for (MediaSeriesGroup seq : dicomModel.getChildren(study)) {
              if (seq instanceof Series<?> s
                  && s != dicomSeries
                  && !DicomModel.isHiddenModality(s)
                  && s.getMimeType() != null
                  && s.getMimeType().equals(mime)) {
                seriesToOpen.add((MediaSeries) s);
                if (seriesToOpen.size() >= 2) break;
              }
            }
            if (seriesToOpen.size() >= 2) break;
          }

          ViewerPluginBuilder.openSequenceInPlugin(
              plugin, seriesToOpen, dicomModel, true, true);
        }
      } else {
        LOGGER.debug(
            "Viewer skipped: mode={}, patient={}, isOpen={}, canAdd={}",
            openingMode,
            patient,
            isPatientOpen,
            canAddNewPatient());
      }
    }
  }

  private boolean canAddNewPatient() {
    if (OpeningViewer.NONE.equals(openingMode)) {
      return false;
    }
    if (OpeningViewer.ONE_PATIENT.equals(openingMode)
        || OpeningViewer.ONE_PATIENT_CLEAN.equals(openingMode)) {
      return !firstViewerOpened.get();
    }
    return true;
  }
}

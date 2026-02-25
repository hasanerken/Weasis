/*
 * Copyright (c) 2026 ZenPACS / Minasoft Technology.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.wado;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.media.data.MediaSeriesGroup;

/**
 * Tracks per-study download completion across multiple series. When all series in a study finish
 * downloading, fires the onStudyComplete callback. Thread-safe.
 *
 * <p>Auto-focus behavior: Only the FIRST completed study triggers tab focus. Subsequent completions
 * update the green checkmark but do NOT steal focus from the radiologist.
 */
public class StudyDownloadTracker {

  private static final Logger LOGGER = LoggerFactory.getLogger(StudyDownloadTracker.class);
  private static final StudyDownloadTracker INSTANCE = new StudyDownloadTracker();

  public static StudyDownloadTracker getInstance() {
    return INSTANCE;
  }

  // studyUID -> set of series UIDs still downloading
  private final Map<String, Set<String>> pending = new ConcurrentHashMap<>();
  // studyUID -> study group reference
  private final Map<String, MediaSeriesGroup> studyMap = new ConcurrentHashMap<>();
  private volatile Consumer<MediaSeriesGroup> onStudyComplete;

  // Only auto-focus the first completed study to avoid distracting the radiologist
  private final AtomicBoolean firstFocusDone = new AtomicBoolean(false);

  private StudyDownloadTracker() {}

  public void setOnStudyComplete(Consumer<MediaSeriesGroup> callback) {
    this.onStudyComplete = callback;
  }

  /**
   * Returns true only on the first call (for the first completed study). Subsequent calls return
   * false, preventing tab-switching that would distract the radiologist.
   */
  public boolean shouldAutoFocus() {
    return firstFocusDone.compareAndSet(false, true);
  }

  public void registerSeries(String studyUID, String seriesUID, MediaSeriesGroup study) {
    studyMap.putIfAbsent(studyUID, study);
    pending.computeIfAbsent(studyUID, k -> new CopyOnWriteArraySet<>()).add(seriesUID);
    LOGGER.info("Tracking: study={}, series={}", studyUID, seriesUID);
  }

  public void markSeriesComplete(String studyUID, String seriesUID) {
    Set<String> set = pending.get(studyUID);
    if (set != null) {
      set.remove(seriesUID);
      int remaining = set.size();
      LOGGER.info("Series done: study={}, series={}, remaining={}", studyUID, seriesUID, remaining);
      if (set.isEmpty()) {
        pending.remove(studyUID);
        LOGGER.info("Study fully loaded: study={}", studyUID);
        MediaSeriesGroup study = studyMap.get(studyUID);
        if (study != null && onStudyComplete != null) {
          onStudyComplete.accept(study);
        }
      }
    }
  }

  public boolean isStudyComplete(String studyUID) {
    return !pending.containsKey(studyUID) && studyMap.containsKey(studyUID);
  }

  /** Check if ALL tracked studies are complete (no pending series anywhere). */
  public boolean isAllComplete() {
    return pending.isEmpty() && !studyMap.isEmpty();
  }

  /** Get the set of all tracked study UIDs. */
  public Set<String> getTrackedStudyUIDs() {
    return studyMap.keySet();
  }

  public void clear() {
    pending.clear();
    studyMap.clear();
    firstFocusDone.set(false);
  }
}

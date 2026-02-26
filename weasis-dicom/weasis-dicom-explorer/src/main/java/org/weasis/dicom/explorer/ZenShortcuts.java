/*
 * Copyright (c) 2026 ZenPACS and other contributors.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.KeyStroke;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.service.WProperties;

public class ZenShortcuts {

  public enum Action {
    OKUNDU("zenpacs.shortcut.okundu", InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_O, "Okundu"),
    RECORD("zenpacs.shortcut.record", InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_R, "Kay\u0131t"),
    PAUSE_RESUME(
        "zenpacs.shortcut.pauseResume",
        InputEvent.CTRL_DOWN_MASK,
        KeyEvent.VK_T,
        "Duraklat/Devam"),
    UPLOAD("zenpacs.shortcut.upload", InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_Y, "Y\u00fckle"),
    OK_KAPAT(
        "zenpacs.shortcut.okKapat",
        InputEvent.CTRL_DOWN_MASK,
        KeyEvent.VK_K,
        "OK + Kapat");

    private final String propertyKey;
    private final int defaultModifiers;
    private final int defaultKeyCode;
    private final String label;

    Action(String propertyKey, int defaultModifiers, int defaultKeyCode, String label) {
      this.propertyKey = propertyKey;
      this.defaultModifiers = defaultModifiers;
      this.defaultKeyCode = defaultKeyCode;
      this.label = label;
    }

    public String getLabel() {
      return label;
    }

    public int getDefaultModifiers() {
      return defaultModifiers;
    }

    public int getDefaultKeyCode() {
      return defaultKeyCode;
    }
  }

  private static final class Binding {
    int modifiers;
    int keyCode;

    Binding(int modifiers, int keyCode) {
      this.modifiers = modifiers;
      this.keyCode = keyCode;
    }
  }

  private static ZenShortcuts instance;

  private final Map<Action, Binding> bindings = new EnumMap<>(Action.class);

  private ZenShortcuts() {
    load();
  }

  public static synchronized ZenShortcuts getInstance() {
    if (instance == null) {
      instance = new ZenShortcuts();
    }
    return instance;
  }

  public void load() {
    WProperties prefs = GuiUtils.getUICore().getSystemPreferences();
    for (Action action : Action.values()) {
      String val = prefs.getProperty(action.propertyKey);
      if (val != null && val.contains(",")) {
        try {
          String[] parts = val.split(",", 2);
          int mod = Integer.parseInt(parts[0].trim());
          int key = Integer.parseInt(parts[1].trim());
          bindings.put(action, new Binding(mod, key));
          continue;
        } catch (NumberFormatException ignored) {
          // fall through to default
        }
      }
      bindings.put(action, new Binding(action.defaultModifiers, action.defaultKeyCode));
    }
  }

  public void save() {
    WProperties prefs = GuiUtils.getUICore().getSystemPreferences();
    for (Action action : Action.values()) {
      Binding b = bindings.get(action);
      prefs.put(action.propertyKey, b.modifiers + "," + b.keyCode);
    }
    GuiUtils.getUICore().saveSystemPreferences();
  }

  public boolean matches(Action action, KeyEvent e) {
    Binding b = bindings.get(action);
    if (b == null) return false;
    int eventMod = e.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK
        | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK);
    return eventMod == b.modifiers && e.getKeyCode() == b.keyCode;
  }

  public int getModifiers(Action action) {
    Binding b = bindings.get(action);
    return b != null ? b.modifiers : action.defaultModifiers;
  }

  public int getKeyCode(Action action) {
    Binding b = bindings.get(action);
    return b != null ? b.keyCode : action.defaultKeyCode;
  }

  public void setBinding(Action action, int modifiers, int keyCode) {
    bindings.put(action, new Binding(modifiers, keyCode));
  }

  public void resetDefaults() {
    for (Action action : Action.values()) {
      bindings.put(action, new Binding(action.defaultModifiers, action.defaultKeyCode));
    }
  }

  public KeyStroke getKeyStroke(Action action) {
    Binding b = bindings.get(action);
    return b != null
        ? KeyStroke.getKeyStroke(b.keyCode, b.modifiers)
        : KeyStroke.getKeyStroke(action.defaultKeyCode, action.defaultModifiers);
  }

  public String getDisplayText(Action action) {
    KeyStroke ks = getKeyStroke(action);
    if (ks == null) return "";
    String mod = InputEvent.getModifiersExText(ks.getModifiers());
    String key = KeyEvent.getKeyText(ks.getKeyCode());
    return mod.isEmpty() ? key : mod + "+" + key;
  }

  public boolean hasConflict(Action target, int modifiers, int keyCode) {
    for (Action action : Action.values()) {
      if (action == target) continue;
      Binding b = bindings.get(action);
      if (b != null && b.modifiers == modifiers && b.keyCode == keyCode) {
        return true;
      }
    }
    return false;
  }

  public Action getConflictingAction(Action target, int modifiers, int keyCode) {
    for (Action action : Action.values()) {
      if (action == target) continue;
      Binding b = bindings.get(action);
      if (b != null && b.modifiers == modifiers && b.keyCode == keyCode) {
        return action;
      }
    }
    return null;
  }
}

/*
 * Copyright (c) 2026 ZenPACS and other contributors.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.pref;

import java.awt.Color;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.weasis.core.api.gui.util.AbstractItemDialogPage;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.ui.pref.PreferenceDialog;
import org.weasis.dicom.explorer.ZenShortcuts;
import org.weasis.dicom.explorer.ZenShortcuts.Action;

public class ZenShortcutsPrefView extends AbstractItemDialogPage {

  private static final Color CONFLICT_COLOR = new Color(190, 100, 100);

  private final Map<Action, JTextField> fields = new EnumMap<>(Action.class);
  private final Map<Action, int[]> pendingBindings = new EnumMap<>(Action.class);

  public ZenShortcutsPrefView() {
    super("ZenPACS K\u0131sayol Tu\u015flar\u0131", 800);

    ZenShortcuts shortcuts = ZenShortcuts.getInstance();

    JPanel panel = GuiUtils.getVerticalBoxLayoutPanel();
    panel.setBorder(
        GuiUtils.getTitledBorder("K\u0131sayol Tu\u015flar\u0131"));

    for (Action action : Action.values()) {
      int mod = shortcuts.getModifiers(action);
      int key = shortcuts.getKeyCode(action);
      pendingBindings.put(action, new int[] {mod, key});

      JLabel label = new JLabel(action.getLabel() + ":");
      GuiUtils.setPreferredWidth(label, 120, 80);

      JTextField field = new JTextField(shortcuts.getDisplayText(action), 14);
      field.setEditable(false);
      field.setFocusable(true);
      field.addKeyListener(new ShortcutKeyListener(action, field));
      fields.put(action, field);

      JButton resetBtn = new JButton("S\u0131f\u0131rla");
      resetBtn.addActionListener(e -> resetSingleAction(action));

      panel.add(GuiUtils.getFlowLayoutPanel(label, field, resetBtn));
    }

    add(panel);
    add(GuiUtils.boxYLastElement(LAST_FILLER_HEIGHT));

    getProperties().setProperty(PreferenceDialog.KEY_SHOW_APPLY, Boolean.TRUE.toString());
    getProperties().setProperty(PreferenceDialog.KEY_SHOW_RESTORE, Boolean.TRUE.toString());
  }

  private void resetSingleAction(Action action) {
    pendingBindings.put(action, new int[] {action.getDefaultModifiers(), action.getDefaultKeyCode()});
    JTextField field = fields.get(action);
    // Show the default text
    field.setText(InputEvent.getModifiersExText(action.getDefaultModifiers())
        + "+" + KeyEvent.getKeyText(action.getDefaultKeyCode()));
    field.setForeground(null);
    clearAllConflictHighlights();
  }

  @Override
  public void resetToDefaultValues() {
    for (Action action : Action.values()) {
      resetSingleAction(action);
    }
  }

  @Override
  public void closeAdditionalWindow() {
    ZenShortcuts shortcuts = ZenShortcuts.getInstance();
    for (Action action : Action.values()) {
      int[] binding = pendingBindings.get(action);
      shortcuts.setBinding(action, binding[0], binding[1]);
    }
    shortcuts.save();
  }

  private void clearAllConflictHighlights() {
    for (Map.Entry<Action, JTextField> entry : fields.entrySet()) {
      Action a = entry.getKey();
      int[] b = pendingBindings.get(a);
      boolean conflict = false;
      for (Action other : Action.values()) {
        if (other == a) continue;
        int[] ob = pendingBindings.get(other);
        if (ob[0] == b[0] && ob[1] == b[1]) {
          conflict = true;
          break;
        }
      }
      entry.getValue().setForeground(conflict ? CONFLICT_COLOR : null);
    }
  }

  private class ShortcutKeyListener extends KeyAdapter {
    private final Action action;
    private final JTextField field;

    ShortcutKeyListener(Action action, JTextField field) {
      this.action = action;
      this.field = field;
    }

    @Override
    public void keyPressed(KeyEvent e) {
      // Ignore lone modifier keys
      int code = e.getKeyCode();
      if (code == KeyEvent.VK_SHIFT
          || code == KeyEvent.VK_CONTROL
          || code == KeyEvent.VK_ALT
          || code == KeyEvent.VK_META) {
        return;
      }

      int mod =
          e.getModifiersEx()
              & (InputEvent.CTRL_DOWN_MASK
                  | InputEvent.SHIFT_DOWN_MASK
                  | InputEvent.ALT_DOWN_MASK
                  | InputEvent.META_DOWN_MASK);

      e.consume();

      // Check conflicts
      boolean conflict = false;
      for (Action other : Action.values()) {
        if (other == action) continue;
        int[] ob = pendingBindings.get(other);
        if (ob[0] == mod && ob[1] == code) {
          conflict = true;
          break;
        }
      }

      if (conflict) {
        field.setForeground(CONFLICT_COLOR);
        // Still show the attempted binding but don't save it
        String modText = InputEvent.getModifiersExText(mod);
        String keyText = KeyEvent.getKeyText(code);
        field.setText(modText.isEmpty() ? keyText : modText + "+" + keyText + " (\u00c7ak\u0131\u015fma!)");
        return;
      }

      pendingBindings.put(action, new int[] {mod, code});
      String modText = InputEvent.getModifiersExText(mod);
      String keyText = KeyEvent.getKeyText(code);
      field.setText(modText.isEmpty() ? keyText : modText + "+" + keyText);
      field.setForeground(null);
      clearAllConflictHighlights();
    }
  }
}

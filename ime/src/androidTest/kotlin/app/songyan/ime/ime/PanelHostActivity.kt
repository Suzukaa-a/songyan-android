package app.songyan.ime.ime

import android.app.Activity

/**
 * A bare window the keyboard view is shown in during tests.
 *
 * Accessibility clients only read view trees attached to a window, and a view only announces a
 * changed subtree from one; the input method service itself never runs here.
 */
class PanelHostActivity : Activity()

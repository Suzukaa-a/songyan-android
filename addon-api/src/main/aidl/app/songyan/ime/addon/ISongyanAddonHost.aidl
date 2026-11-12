// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon;

import android.os.Bundle;

/**
 * Addon to host callbacks. Every method is oneway: an addon must never block on a host that is
 * busy drawing the keyboard, and a dead host must not take the addon down.
 */
oneway interface ISongyanAddonHost {
    /** Result of one text tool run, as the host will show it in the preview. */
    void onToolResults(String requestId, in List<String> results);

    /** One run failed; the failure bundle carries an AddonFailure code and message. */
    void onToolFailed(String requestId, in Bundle failure);

    /** One voice transcription, partial (isFinal = false) or final. */
    void onVoiceText(String text, boolean isFinal);

    /** The addon's voice flow changed state; state is an AddonVoiceState wire name. */
    void onVoiceState(String state);

    /** Non-fatal problem the user may want to see (for example a tool is not on this device). */
    void onWarning(String message);
}

package com.chlorine;

/**
 * Tracks whether the server is inside its periodic autosave, and how many
 * chunks that autosave has already saved.
 *
 * Only MinecraftServer.autoSave() opens this window (see
 * AutosaveWindowMixin), so shutdown, /save-all and the pause-menu save
 * never see a limit. Everything runs on the server thread.
 */
public final class AutosaveSmoothing {
    private static boolean autosaving;
    private static int savedThisAutosave;
    private static int deferredThisAutosave;

    private AutosaveSmoothing() {
    }

    public static void begin() {
        autosaving = Chlorine.CONFIG.enableAutosaveSmoothing;
        savedThisAutosave = 0;
        deferredThisAutosave = 0;
    }

    public static void end() {
        if (autosaving && deferredThisAutosave > 0) {
            Chlorine.LOGGER.debug("Autosave saved {} chunks now, handed {} to the background saver",
                    savedThisAutosave, deferredThisAutosave);
        }
        autosaving = false;
    }

    public static boolean isLimiting() {
        return autosaving;
    }

    public static boolean hasBudget() {
        return savedThisAutosave < Math.max(1, Chlorine.CONFIG.autosaveImmediateChunkLimit);
    }

    public static void recordSaved() {
        savedThisAutosave++;
    }

    public static void recordDeferred() {
        deferredThisAutosave++;
    }
}

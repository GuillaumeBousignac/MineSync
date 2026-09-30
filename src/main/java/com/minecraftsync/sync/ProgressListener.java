package com.minecraftsync.sync;

/** Progression d'une synchronisation (fraction entre 0 et 1, ou -1 si indéterminée). */
@FunctionalInterface
public interface ProgressListener {

    ProgressListener NONE = (message, fraction) -> { };

    void progress(String message, double fraction);
}

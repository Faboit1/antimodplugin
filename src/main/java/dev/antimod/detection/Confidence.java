package dev.antimod.detection;

/**
 * Confidence level for a detection event.
 *
 * <p>CONFIRMED detections are based on data that is exclusive to a
 * specific mod (e.g. a translation key that only resolves inside
 * Meteor Client). These trigger full actions by default.
 *
 * <p>HEURISTIC detections are based on indicators that are associated
 * with a mod but not exclusive to it (e.g. a "fabric" client brand
 * could come from an innocent Fabric modpack). These should use
 * gentler actions (alert only, no kick/ban) to reduce false positives.
 */
public enum Confidence {
    CONFIRMED,
    HEURISTIC
}

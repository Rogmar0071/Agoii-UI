package com.agoii.mobile.governance

// ─── L3 — State Surface Mirror ───────────────────────────────────────────────

/**
 * In-memory mirror of all active governance surfaces.
 *
 * Constraints:
 *  - No external calls.
 *  - No persistence.
 *  - Read-only access to surface list via [getActiveSurfaces].
 */
class StateSurfaceMirror {

    private var initialized = false
    private val activeSurfaces = mutableListOf<SurfaceType>()

    /** Initializes the mirror and clears any previously tracked surfaces. */
    fun initialize() {
        initialized = true
        activeSurfaces.clear()
    }

    /** Returns `true` once [initialize] has been called at least once. */
    fun isInitialized(): Boolean = initialized

    /**
     * Registers [surface] as active.
     * Duplicate entries are ignored to keep the surface list canonical.
     */
    fun activateSurface(surface: SurfaceType) {
        if (!activeSurfaces.contains(surface)) {
            activeSurfaces.add(surface)
        }
    }

    /** Returns a snapshot of all currently active surfaces (read-only). */
    fun getActiveSurfaces(): List<SurfaceType> = activeSurfaces.toList()
}

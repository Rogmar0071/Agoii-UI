package com.agoii.mobile.governance

/**
 * SurfaceStateManager (SSM) — tracks surface activation state.
 *
 * Rules:
 *  - Must be explicitly initialized before surfaces can be activated.
 *  - Surfaces are registered via activateSurface(); absent surfaces are inactive.
 *  - Read access via isInitialized() and getActiveSurfaces() is always safe.
 */
class SurfaceStateManager {

    private var initialized = false
    private val activeSurfaces = mutableSetOf<SurfaceType>()

    fun initialize() {
        initialized = true
    }

    fun activateSurface(surfaceType: SurfaceType) {
        activeSurfaces.add(surfaceType)
    }

    fun isInitialized(): Boolean = initialized

    fun getActiveSurfaces(): Set<SurfaceType> = activeSurfaces.toSet()
}

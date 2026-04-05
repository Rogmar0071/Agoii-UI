package agoii.ui.bridge

/**
 * The ONLY bridge between the UI module and the Agoii core system.
 *
 * This class implements [BridgeContract] and is the sole entry point for
 * all UI → core communication. No other path to the core is permitted.
 *
 * Architecture enforcement:
 * - UI module files import ONLY from [agoii.ui.bridge].
 * - Core system files are NEVER imported directly by the UI module.
 * - CoreBridge translates core types into UI bridge types ([UIEvent],
 *   [UIReplayState], etc.) so the UI module has ZERO coupling to core internals.
 *
 * The concrete wiring to the core system (EventLedger, Governor, Replay,
 * ExecutionAuthority, etc.) is performed by the host application at
 * initialisation time — NOT by the UI module.
 */
class CoreBridge(
    private val delegate: BridgeContract
) : BridgeContract by delegate

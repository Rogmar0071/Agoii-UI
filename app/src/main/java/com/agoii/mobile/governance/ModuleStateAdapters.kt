package com.agoii.mobile.governance

import com.agoii.mobile.core.ReplayState

class IntentModuleState(private val state: ReplayState) : ModuleState {

    override fun isValid(): Boolean {
        return !state.objective.isNullOrBlank()
    }

    override fun isComplete(): Boolean {
        return isValid()
    }

    override fun getStateSignature(): String {
        return state.objective ?: "NONE"
    }
}

class ContractModuleState(private val state: ReplayState) : ModuleState {

    override fun isValid(): Boolean {
        return state.totalContracts > 0
    }

    override fun isComplete(): Boolean = isValid()

    override fun getStateSignature(): String {
        return "contracts:${state.totalContracts}"
    }
}

class ExecutionModuleState(private val state: ReplayState) : ModuleState {

    override fun isValid(): Boolean {
        return state.totalContracts > 0 &&
               state.contractsCompleted <= state.totalContracts
    }

    override fun isComplete(): Boolean {
        return state.contractsCompleted == state.totalContracts
    }

    override fun getStateSignature(): String {
        return "exec:${state.contractsCompleted}/${state.totalContracts}"
    }
}

class AssemblyModuleState(private val state: ReplayState) : ModuleState {

    override fun isValid(): Boolean {
        return state.totalContracts > 0 &&
               state.contractsCompleted == state.totalContracts
    }

    override fun isComplete(): Boolean = isValid()

    override fun getStateSignature(): String {
        return "assembly:${isValid()}"
    }
}

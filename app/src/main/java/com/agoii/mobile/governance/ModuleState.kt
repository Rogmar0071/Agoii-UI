package com.agoii.mobile.governance

interface ModuleState {

    fun isValid(): Boolean

    fun isComplete(): Boolean

    fun getStateSignature(): String
}

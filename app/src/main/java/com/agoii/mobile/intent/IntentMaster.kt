package com.agoii.mobile.intent

import com.agoii.mobile.irs.IntentData
import com.agoii.mobile.irs.IrsSession

data class IntentMaster(
    val sessionId:   String,
    val intentData:  IntentData,
    val irsSession:  IrsSession,
    val certifiedAt: Long = System.currentTimeMillis()
)

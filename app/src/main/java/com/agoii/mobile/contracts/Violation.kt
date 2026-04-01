package com.agoii.mobile.contracts

enum class FailureClass {
    STRUCTURAL,
    LOGICAL,
    CONSTRAINT,
    COMPLETENESS,
    DETERMINISM,
    TRUST
}

data class Violation(
    val reportReference: String,
    val contractId: String,
    val fieldPath: String,
    val failureClass: FailureClass,
    val expected: String,
    val actual: String,
    val message: String,
    val correctionDirective: String
)

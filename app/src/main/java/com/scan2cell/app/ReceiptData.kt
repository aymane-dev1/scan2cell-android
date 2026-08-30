package com.scan2cell.app

data class ReceiptData(
    val treasuryNumber: String = "",
    val clientName: String = "",
    val contractNumber: String = "",
    val tierReference: String = "",
    val amount: String = ""
) {
    val detectedCount: Int
        get() = listOf(treasuryNumber, clientName, contractNumber, tierReference, amount)
            .count { it.isNotBlank() }
}

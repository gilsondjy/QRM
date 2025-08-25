package com.tuduticket.qrm

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun ScanScreen(
    scanned: String?,
    canSave: Boolean,
    onScan: (ScanOptions) -> Unit,
    onSave: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Button(
            onClick = {
                onScan(
                    ScanOptions().apply {
                        setBeepEnabled(true)
                        setOrientationLocked(false)
                    }
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Scanner le QR Code")
        }

        Text(text = "Dernier scan : ${scanned ?: "aucun"}")

        if (canSave && scanned != null) {
            Button(
                onClick = { onSave(scanned) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enregistrer le scan")
            }
        }
    }
}

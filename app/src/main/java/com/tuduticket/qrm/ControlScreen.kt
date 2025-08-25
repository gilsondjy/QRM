package com.tuduticket.qrm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanOptions

/**
 * @param ref     Référence du ticket (String?) ou null
 * @param count   Nombre de fois déjà contrôlé (Int?) ou null
 * @param message Message de statut ("Validé", "Déjà contrôlé", "Non valide", etc.)
 * @param onScan  Callback pour relancer la capture QR
 */
@Composable
fun ControlScreen(
    ref: String?,
    count: Int?,
    message: String?,
    onScan: (ScanOptions) -> Unit
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
            Text("Scanner le ticket")
        }

        Text(
            text = "Réf scannée : ${ref ?: "Aucune"}",
            modifier = Modifier.fillMaxWidth()
        )

        if (count != null) {
            Text(
                text = "Nombre de contrôles : $count",
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text(
            text = message ?: "Statut inconnu",
            modifier = Modifier.fillMaxWidth()
        )
    }
}

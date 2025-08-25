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
import androidx.compose.ui.unit.sp

/**
 * Écran pour sélectionner et importer un CSV
 */
@Composable
fun ImportScreen(
    message: String?,
    onPickCsv: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Button(
            onClick = onPickCsv,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Choisir un fichier CSV", fontSize = 18.sp)
        }
        Text(
            text = message ?: "Aucun import effectué",
            fontSize = 18.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

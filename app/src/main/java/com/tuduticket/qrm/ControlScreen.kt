package com.tuduticket.qrm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun ControlScreen(
    eventName: String?,
    total: Int?,
    scanned: Int?,
    remaining: Int?,
    ref: String?,
    count: Int?,
    message: String?,
    firstValidatedAt: String?,
    onScan: (ScanOptions) -> Unit
) {
    val cs = MaterialTheme.colorScheme

    // Message normalizado
    val rawMsg = (message ?: "").trim()

    // Détection simple du statut
    val status = when {
        rawMsg.startsWith("Válido", true) -> Status.VALID
        rawMsg.startsWith("Já controlado", true) ||
                rawMsg.contains("Já", true)       -> Status.DUPLICATE
        rawMsg.contains("inválid", true) ||
                rawMsg.contains("desconhecido", true) ||
                rawMsg.contains("erro", true)     -> Status.ERROR
        else                              -> Status.NEUTRAL
    }

    // Texte & couleurs du bandeau
    val statusText =
        if (status == Status.VALID) "VÁLIDO" else rawMsg.uppercase()

    val statusFg = when (status) {
        Status.VALID     -> cs.onPrimary
        Status.DUPLICATE -> cs.onTertiary
        Status.ERROR     -> cs.onError
        Status.NEUTRAL   -> cs.onSurface
    }
    val statusBg = when (status) {
        Status.VALID     -> cs.primary
        Status.DUPLICATE -> cs.tertiary
        Status.ERROR     -> cs.error
        Status.NEUTRAL   -> cs.surfaceVariant
    }

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
                        setBeepEnabled(false)      // on gère le son côté Activity
                        setOrientationLocked(true) // évite la recréation d’Activity
                    }
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Vérificar ticket")
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = eventName?.let { "Evento: $it" } ?: "Evento : —",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatItem(label = "Total", value = total?.toString() ?: "—")
                    StatItem(label = "Validados", value = scanned?.toString() ?: "—")
                    StatItem(label = "Restantes", value = remaining?.toString() ?: "—")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Último Ticket Verificado", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Réf : ${ref ?: "—"}")
                if (count != null) Text("N.º de controlos : $count")

                // ======= BANDEAU DE STATUT (GRAND + COULEUR) =======
                // ======= BANDEAU DE STATUT (un peu plus petit) =======
                Text(
                    text = statusText,
                    color = statusFg,
                    // ↓ plus petit: passe de headlineSmall → titleMedium (tu peux essayer titleLarge si tu veux entre-deux)
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        // ↓ coins et marges réduits
                        .background(statusBg, shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                // ===================================================

                Text("Primeira validação com sucesso : ${firstValidatedAt ?: "—"}")
            }
        }
    }
}

private enum class Status { VALID, DUPLICATE, ERROR, NEUTRAL }

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

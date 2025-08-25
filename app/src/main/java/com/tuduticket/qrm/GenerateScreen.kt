package com.tuduticket.qrm

import android.app.DatePickerDialog
import android.widget.DatePicker
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Calendar

/**
 * Écran Compose pour saisir un événement + nombre de QR à générer.
 * Champ Date désormais non éditable, cliquable pour ouvrir le DatePicker.
 * Utilise un défilement vertical pour éviter les coupures sur petits écrans.
 * Ajoute une option pour choisir entre sauvegarde en galerie ou Cloud Storage.
 */
@Composable
fun GenerateScreen(onGenerate: (EventData, Boolean) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var quantityText by remember { mutableStateOf("") }
    var saveToCloud by remember { mutableStateOf(false) }

    fun pickDate() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            context,
            { _: DatePicker, year, month, day ->
                date = "%04d-%02d-%02d".format(year, month + 1, day)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth()
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { pickDate() }
            ) {
                OutlinedTextField(
                    value = date,
                    onValueChange = { /* no-op */ },
                    label = { Text("Date_event") },
                    readOnly = true,
                    enabled = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = start,
                onValueChange = { start = it },
                label = { Text("Time_start") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = end,
                onValueChange = { end = it },
                label = { Text("Time_ended") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = place,
                onValueChange = { place = it },
                label = { Text("Place") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = quantityText,
                onValueChange = { quantityText = it.filter(Char::isDigit) },
                label = { Text("Quantité") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Sauvegarder dans le cloud")
                Switch(
                    checked = saveToCloud,
                    onCheckedChange = { saveToCloud = it }
                )
            }
        }

        Button(
            onClick = {
                val qty = quantityText.toIntOrNull() ?: 1
                onGenerate(
                    EventData(
                        name = name,
                        date = date,
                        start = start,
                        end = end,
                        place = place,
                        quantity = qty
                    ),
                    saveToCloud
                )
            },
            enabled = listOf(name, date, start, end, place).all(String::isNotBlank)
                    && quantityText.toIntOrNull()?.let { it > 0 } == true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            Text("Générer CSV & QR")
        }
    }
}
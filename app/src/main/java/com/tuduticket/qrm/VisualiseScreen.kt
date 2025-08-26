package com.tuduticket.qrm

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

// Modèle pour l’affichage et l’export
data class QRItem(
    val name: String,
    val url: String,
    val number: Int,     // No: du ticket (1..N) ; Int.MAX_VALUE si inconnu
    val ref: String?     // Référence visuelle
)

@Composable
fun VisualiseScreen(storage: FirebaseStorage) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var folders by remember { mutableStateOf(listOf<String>()) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var qrItems by remember { mutableStateOf(listOf<QRItem>()) }

    var isLoading by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0f) } // 0..1

    // Charge les dossiers (dates)
    LaunchedEffect(Unit) {
        try {
            val list = storage.reference.child("qrcodes").listAll().await()
            folders = list.prefixes.map { it.name }.sortedDescending()
        } catch (e: Exception) {
            Toast.makeText(context, "Erreur dossiers: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            item { Text("Dossiers disponibles (dates) :") }

            // Liste des dossiers
            items(folders) { folder ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedFolder = folder
                            isLoading = true
                            scope.launch {
                                try {
                                    val refs = storage.reference
                                        .child("qrcodes/$folder")
                                        .listAll().await().items

                                    // Construit les items (lit métadonnées no/ref)
                                    val items = refs.mapNotNull { buildQRItem(it) }

                                    // TRI STRICT: No valides d’abord, puis No croissant, puis ref/nom
                                    qrItems = items.sortedWith(
                                        compareBy<QRItem>(
                                            { it.number == Int.MAX_VALUE },  // false (bons) avant true
                                            { it.number },
                                            { it.ref ?: "" },
                                            { it.name }
                                        )
                                    )
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        context,
                                        "Erreur images: ${e.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } finally {
                                    isLoading = false
                                }
                            }
                        }
                ) {
                    Text(folder, modifier = Modifier.padding(16.dp))
                }
            }

            if (selectedFolder != null) {
                item { Text("QR codes dans ${selectedFolder} :") }

                // Indicateur de chargement
                if (isLoading) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Chargement des QR…")
                        }
                    }
                }

                // Prévisualisation (No réel, pas l’index)
                items(qrItems) { item ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val noTxt = if (item.number != Int.MAX_VALUE) item.number.toString() else "?"
                            Text("No. $noTxt • ${item.name}")
                            Spacer(Modifier.height(6.dp))
                            Image(
                                painter = rememberAsyncImagePainter(item.url),
                                contentDescription = "QR Code",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .sizeIn(maxWidth = 320.dp, maxHeight = 320.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Ref.: ${item.ref ?: "-"}, No: $noTxt",
                                color = Color(0xFF2F80ED)
                            )
                        }
                    }
                }

                // Bouton d’export (même ordre)
                item {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = !isLoading && !isExporting && qrItems.isNotEmpty(),
                        onClick = {
                            scope.launch {
                                isExporting = true
                                exportProgress = 0f

                                val sorted = qrItems.sortedWith(
                                    compareBy<QRItem>(
                                        { it.number == Int.MAX_VALUE },
                                        { it.number },
                                        { it.ref ?: "" },
                                        { it.name }
                                    )
                                )

                                exportToPdf(
                                    context = context,
                                    folder = selectedFolder!!,
                                    imageUrls = sorted.map { it.url },
                                    onProgress = { done, total ->
                                        exportProgress =
                                            if (total > 0) done.toFloat() / total else 0f
                                    }
                                )

                                isExporting = false
                            }
                        }
                    ) {
                        Text("Exporter en PDF")
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // Overlay progression export
        if (isExporting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .background(Color.White, shape = RoundedCornerShape(12.dp))
                        .padding(20.dp)
                ) {
                    Text("Exportation du PDF…")
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = exportProgress, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${(exportProgress * 100).toInt()} %")
                }
            }
        }
    }
}

/** Construit un item en lisant d’abord les MÉTADONNÉES Storage (no/ref). */
private suspend fun buildQRItem(ref: StorageReference): QRItem? = try {
    val meta = runCatching { ref.metadata.await() }.getOrNull()

    // IMPORTANT: sur Android, on utilise getCustomMetadata(key)
    val number = listOf("no", "number", "order", "ticketNo", "ticket_no")
        .mapNotNull { key -> meta?.getCustomMetadata(key)?.toIntOrNull() }
        .firstOrNull() ?: Int.MAX_VALUE

    val ticketRef = listOf("ref", "reference", "ticketRef", "ticket_ref")
        .mapNotNull { key -> meta?.getCustomMetadata(key) }
        .firstOrNull() ?: extractRefFromName(ref.name)

    val url = ref.downloadUrl.await().toString()
    QRItem(name = ref.name, url = url, number = number, ref = ticketRef)
} catch (_: Exception) { null }

/** Fallback simple pour REF si absente des métadonnées. */
private fun extractRefFromName(name: String): String? {
    // ex: ticket_0f97c74e.png -> 0f97c74e
    Regex("([A-Fa-f0-9]{6,})").find(name)?.let { return it.value }
    return name.substringBeforeLast('.', missingDelimiterValue = name)
}

/** Wrapper : export 30 QR/page (5×6) en respectant l’ordre fourni. */
private suspend fun exportToPdf(
    context: Context,
    folder: String,
    imageUrls: List<String>,
    onProgress: (processed: Int, total: Int) -> Unit
) = exportToPdfGrid(
    context = context,
    folder = folder,
    imageUrls = imageUrls,
    columns = 5,  // 5 colonnes
    rows = 6,     // 6 lignes -> 30 QR/page
    onProgress = onProgress
)

/** Export PDF en grille (ordre conservé), compatible minSdk 23. */
private suspend fun exportToPdfGrid(
    context: Context,
    folder: String,
    imageUrls: List<String>,
    columns: Int,
    rows: Int,
    pageWidth: Int = 1240,   // A4 ~150 dpi
    pageHeight: Int = 1754,
    margin: Int = 60,
    gutter: Int = 16,
    onProgress: (processed: Int, total: Int) -> Unit
) {
    if (imageUrls.isEmpty()) {
        Toast.makeText(context, "Aucun QR à exporter.", Toast.LENGTH_LONG).show()
        return
    }

    val pdf = PdfDocument()

    // Zone utile
    val availW = pageWidth - margin * 2
    val availH = pageHeight - margin * 2

    // Cellule carrée
    val cellW = (availW - (columns - 1) * gutter) / columns
    val cellH = (availH - (rows - 1) * gutter) / rows
    val cellSide = minOf(cellW, cellH)

    val perPage = columns * rows
    val total = imageUrls.size
    var processed = 0

    fun startPage(n: Int) =
        pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, n).create())

    var pageNum = 1
    var page = startPage(pageNum)
    var indexOnPage = 0
    var anyDrawn = false

    try {
        for ((i, url) in imageUrls.withIndex()) {
            val bmp: Bitmap? = withContext(Dispatchers.IO) {
                try { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }
                catch (_: Exception) { null }
            }

            processed++
            onProgress(processed, total)

            if (bmp == null) continue
            anyDrawn = true

            val row = indexOnPage / columns
            val col = indexOnPage % columns

            val left = margin + col * (cellW + gutter) + (cellW - cellSide) / 2
            val top  = margin + row * (cellH + gutter) + (cellH - cellSide) / 2
            val dest = Rect(left, top, left + cellSide, top + cellSide)

            page.canvas.drawBitmap(bmp, null, dest, null)
            indexOnPage++

            if (indexOnPage == perPage && i != imageUrls.lastIndex) {
                pdf.finishPage(page)
                pageNum++
                page = startPage(pageNum)
                indexOnPage = 0
            }
        }

        if (!anyDrawn) {
            Toast.makeText(context, "Échec: aucune image valide.", Toast.LENGTH_LONG).show()
            pdf.close(); return
        }

        pdf.finishPage(page)

        val fileName = "qrcodes_${folder}_${System.currentTimeMillis()}.pdf"

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Android 10+ : Téléchargements via MediaStore
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { pdf.writeTo(it) }
                Toast.makeText(context, "PDF enregistré dans Téléchargements: $fileName", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Erreur: création du fichier PDF", Toast.LENGTH_LONG).show()
            }
        } else {
            // Android < 10 : /storage/emulated/0/Download/
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, fileName)
            FileOutputStream(outFile).use { pdf.writeTo(it) }
            Toast.makeText(context, "PDF enregistré: ${outFile.absolutePath}", Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Erreur export: ${e.message}", Toast.LENGTH_LONG).show()
    } finally {
        pdf.close()
    }
}

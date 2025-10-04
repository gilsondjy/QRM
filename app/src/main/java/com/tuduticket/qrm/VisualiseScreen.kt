package com.tuduticket.qrm

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
import androidx.compose.material3.*
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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

data class QRItem(val name: String, val url: String, val number: Int, val ref: String?)

@Composable
fun VisualiseScreen(storage: FirebaseStorage) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var folders by remember { mutableStateOf(listOf<String>()) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var qrItems by remember { mutableStateOf(listOf<QRItem>()) }

    var isLoading by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0f) }

    // ⚙️ Nouvelle option: 1 QR par page ?
    var singlePerPage by remember { mutableStateOf(false) }

    // Charger les dossiers (dates)
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
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            item { Text("Dossiês disponíveis (datas):") }

            items(folders) { folder ->
                Card(
                    Modifier.fillMaxWidth().clickable {
                        selectedFolder = folder
                        isLoading = true
                        scope.launch {
                            try {
                                val refs = storage.reference.child("qrcodes/$folder").listAll().await().items
                                val items = refs.mapNotNull { buildQRItem(it) }
                                qrItems = items.sortedWith(
                                    compareBy<QRItem>({ it.number == Int.MAX_VALUE }, { it.number }, { it.ref ?: "" }, { it.name })
                                )
                            } catch (e: Exception) {
                                Toast.makeText(context, "Erreur images: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally { isLoading = false }
                        }
                    }
                ) { Text(folder, Modifier.padding(16.dp)) }
            }

            if (selectedFolder != null) {
                item { Text("QR codes dans ${selectedFolder} :") }

                if (isLoading) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Chargement des QR…")
                        }
                    }
                }

                // Prévisualisation (indicative)
                items(qrItems) { item ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val noTxt = if (item.number != Int.MAX_VALUE) item.number.toString() else "?"
                            Text("No. $noTxt • ${item.name}", color = Color.Black)
                            Spacer(Modifier.height(2.dp))
                            Image(
                                painter = rememberAsyncImagePainter(item.url),
                                contentDescription = "QR Code",
                                modifier = Modifier.fillMaxWidth().sizeIn(maxWidth = 320.dp, maxHeight = 320.dp)
                            )
                        }
                    }
                }

                // 👉 Choix du mode d’export
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        RadioButton(
                            selected = !singlePerPage,
                            onClick = { singlePerPage = false }
                        )
                        Text("Plusieurs par page")
                        Spacer(Modifier.width(20.dp))
                        RadioButton(
                            selected = singlePerPage,
                            onClick = { singlePerPage = true }
                        )
                        Text("1 QR par page")
                    }
                }

                // Bouton d’export
                item {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                isExporting = true
                                exportProgress = 0f
                                val sorted = qrItems.sortedWith(
                                    compareBy<QRItem>({ it.number == Int.MAX_VALUE }, { it.number }, { it.ref ?: "" }, { it.name })
                                )
                                exportToPdf_RepaintFooterStrict(
                                    context = context,
                                    folder = selectedFolder!!,
                                    items = sorted,
                                    // ⚠️ On laisse VOS tailles actuelles (ne pas changer)
                                    qrSizeMm = 16f,
                                    marginMm = 5f,
                                    gutterMm = 2f,
                                    labelScale = 0.095f,
                                    labelTopPadMm = 0f,
                                    labelNudgeUpMm = 0.1f,
                                    singlePerPage = singlePerPage     // ← NOUVEAU
                                ) { done, total ->
                                    exportProgress = if (total > 0) done.toFloat() / total else 0f
                                }
                                isExporting = false
                            }
                        },
                        enabled = !isLoading && !isExporting && qrItems.isNotEmpty()
                    ) { Text("Exporter en PDF") }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        if (isExporting) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
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

/** Lit métadonnées no/ref pour trier et étiqueter. */
private suspend fun buildQRItem(ref: StorageReference): QRItem? = try {
    val meta = runCatching { ref.metadata.await() }.getOrNull()
    val number = listOf("no","number","order","ticketNo","ticket_no")
        .mapNotNull { k -> meta?.getCustomMetadata(k)?.toIntOrNull() }
        .firstOrNull() ?: Int.MAX_VALUE
    val ticketRef = listOf("ref","reference","ticketRef","ticket_ref")
        .mapNotNull { k -> meta?.getCustomMetadata(k) }
        .firstOrNull() ?: extractRefFromName(ref.name)
    val url = ref.downloadUrl.await().toString()
    QRItem(ref.name, url, number, ticketRef)
} catch (_: Exception) { null }

private fun extractRefFromName(name: String): String? {
    Regex("([A-Fa-f0-9]{6,})").find(name)?.let { return it.value }
    return name.substringBeforeLast('.', missingDelimiterValue = name)
}

/* ================================================================
   EXPORT PDF — Respecte l’aspect (pas d’étirement) et recolore
   SEULEMENT la bande texte (sous le QR). On peut remonter la ligne
   via labelNudgeUpMm. Supporte maintenant 1 QR par page OU grille.
   ================================================================ */
private suspend fun exportToPdf_RepaintFooterStrict(
    context: Context,
    folder: String,
    items: List<QRItem>,
    qrSizeMm: Float = 20f,   // largeur cible de l'image (mm)
    marginMm: Float = 5f,
    gutterMm: Float = 3f,
    labelScale: Float = 0.10f,   // proportion de la largeur pour la taille du texte
    labelTopPadMm: Float = 0.3f, // espace entre QR et début du texte (mm)
    labelNudgeUpMm: Float = 0f,  // remonte la ligne (mm) pour coller davantage
    singlePerPage: Boolean = false, // ← NOUVEAU : 1 QR par page
    onProgress: (processed: Int, total: Int) -> Unit
) {
    if (items.isEmpty()) {
        Toast.makeText(context, "Aucun QR à exporter.", Toast.LENGTH_LONG).show()
        return
    }

    // A4 ~150 dpi
    val pageWidth = 1240
    val pageHeight = 1754
    fun mmToPx(mm: Float): Int = ((mm / 210f) * pageWidth).toInt()

    val marginPx = mmToPx(marginMm).coerceAtLeast(0)
    val gutterPx = mmToPx(gutterMm).coerceAtLeast(0)
    val imgWidthPx = max(mmToPx(qrSizeMm), 48)

    // 1) Ratio H/L (évite la déformation)
    val sampleUrl = items.firstOrNull()?.url
    val sampleRatio: Float = withContext(Dispatchers.IO) {
        try {
            if (sampleUrl == null) 1.12f else URL(sampleUrl).openStream().use {
                val bmp = BitmapFactory.decodeStream(it)
                if (bmp != null && bmp.width > 0) bmp.height.toFloat() / bmp.width else 1.12f
            }
        } catch (_: Exception) { 1.12f }
    }.coerceAtLeast(1.0f)

    val imgHeightPx = ceil(imgWidthPx * sampleRatio).toInt()

    // Grille
    val cellW = imgWidthPx
    val cellH = imgHeightPx
    val availW = pageWidth - marginPx * 2
    val availH = pageHeight - marginPx * 2

    val columns = if (singlePerPage) 1
    else max(1, floor((availW + gutterPx).toFloat() / (cellW + gutterPx)).toInt())

    val rows = if (singlePerPage) 1
    else max(1, floor((availH + gutterPx).toFloat() / (cellH + gutterPx)).toInt())

    val perPage = columns * rows

    val pdf = PdfDocument()
    var pageNum = 1
    var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
    var indexOnPage = 0

    val total = items.size
    var processed = 0
    var anyDrawn = false

    val paintWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        textAlign = Paint.Align.CENTER
    }
    val fm = Paint.FontMetrics()
    val topPadPx = mmToPx(labelTopPadMm).coerceAtLeast(0)
    val nudgeUpPx = mmToPx(labelNudgeUpMm).coerceAtLeast(0)

    try {
        for ((i, item) in items.withIndex()) {
            val bmp: Bitmap? = withContext(Dispatchers.IO) {
                try { URL(item.url).openStream().use { BitmapFactory.decodeStream(it) } }
                catch (_: Exception) { null }
            }
            processed++; onProgress(processed, total)
            if (bmp == null) continue
            anyDrawn = true

            val row = indexOnPage / columns
            val col = indexOnPage % columns

            // Positionnement : centre si 1 par page
            val left = if (singlePerPage)
                ((pageWidth - cellW) / 2)
            else
                marginPx + col * (cellW + gutterPx)

            val top = if (singlePerPage)
                max(marginPx, (pageHeight - cellH) / 2)
            else
                marginPx + row * (cellH + gutterPx)

            val dest = Rect(left, top, left + cellW, top + cellH)
            page.canvas.drawBitmap(bmp, null, dest, null)

            // Bande texte = hauteur - largeur (QR carré en haut)
            val footerH = max(dest.height() - dest.width(), 0)
            if (footerH > 0) {
                val maskTop = (dest.bottom - footerH - topPadPx).coerceAtLeast(dest.top)
                page.canvas.drawRect(
                    RectF(dest.left.toFloat(), maskTop.toFloat(), dest.right.toFloat(), dest.bottom.toFloat()),
                    paintWhite
                )

                val label = "Ref.: ${item.ref ?: "-"}, No: ${if (item.number != Int.MAX_VALUE) item.number else "?"}"

                var textSize = dest.width() * labelScale
                val minSize = dest.width() * 0.07f
                paintText.textSize = textSize
                val maxWidth = dest.width() * 0.95f
                var w = paintText.measureText(label)
                while (w > maxWidth && textSize > minSize) {
                    textSize *= 0.95f
                    paintText.textSize = textSize
                    w = paintText.measureText(label)
                }
                paintText.getFontMetrics(fm)

                val baseline = maskTop - fm.ascent - nudgeUpPx
                page.canvas.drawText(label, dest.exactCenterX(), baseline, paintText)
            }

            indexOnPage++
            if (indexOnPage == perPage && i != items.lastIndex) {
                pdf.finishPage(page)
                pageNum++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
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
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { pdf.writeTo(it) }
                Toast.makeText(context, "PDF Téléchargements: $fileName", Toast.LENGTH_LONG).show()
            } else Toast.makeText(context, "Erreur: création PDF", Toast.LENGTH_LONG).show()
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, fileName)
            FileOutputStream(outFile).use { pdf.writeTo(it) }
            Toast.makeText(context, "PDF enregistré: ${outFile.absolutePath}", Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Erreur export: ${e.message}", Toast.LENGTH_LONG).show()
    } finally { pdf.close() }
}

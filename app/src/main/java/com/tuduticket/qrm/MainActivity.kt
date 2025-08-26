package com.tuduticket.qrm

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.tuduticket.qrm.ui.theme.QRMTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    // Firebase
    private lateinit var db: FirebaseFirestore
    private lateinit var storage: FirebaseStorage

    // Navigation
    private var currentPage by mutableStateOf(Page.Scan)

    // Scan/Contrôle/Import (inchangés)
    private var scannedCode by mutableStateOf<String?>(null)
    private var canSaveScan by mutableStateOf(false)
    private var controlRef by mutableStateOf<String?>(null)
    private var controlMessage by mutableStateOf<String?>(null)
    private var controlCount by mutableStateOf<Int?>(null)
    private var importMessage by mutableStateOf<String?>(null)

    // --- Nouvel état: progression de GÉNÉRATION ---
    private var isGenerating by mutableStateOf(false)
    private var genProcessed by mutableStateOf(0)
    private var genTotal by mutableStateOf(0)
    private var genProgress by mutableStateOf(0f) // 0..1

    private val scanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        try {
            result.contents?.let { scannedCode = it; canSaveScan = true }
                ?: Toast.makeText(this, "Scan annulé", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur lors du scan: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val controlLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        try { result.contents?.let { checkTicketByCode(it) } ?: run {
            controlMessage = "Scan annulé"; playTone(false)
        } } catch (e: Exception) {
            controlMessage = "Erreur: ${e.message}"; playTone(false)
        }
    }

    private val csvPicker = registerForActivityResult(GetContent()) { uri: Uri? ->
        try { uri?.let { importCsv(it) } } catch (e: Exception) {
            importMessage = "Erreur lors de l'import: ${e.message}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        db = FirebaseFirestore.getInstance()
        storage = FirebaseStorage.getInstance()

        setContent {
            QRMTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            navigationIcon = {
                                Image(
                                    painter = painterResource(R.drawable.tuduticket_logo),
                                    contentDescription = "Logo",
                                    modifier = Modifier.padding(start = 12.dp).size(70.dp)
                                )
                            },
                            title = { Text("") },
                            colors = TopAppBarDefaults.smallTopAppBarColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                titleContentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            actions = {
                                TextButton({ currentPage = Page.Scan }) { Text("Scanner") }
                                TextButton({ currentPage = Page.Generate }) { Text("Générer") }
                                TextButton({ currentPage = Page.Control }) { Text("Contrôler") }
                                TextButton({ currentPage = Page.Visualise }) { Text("Visualiser") }
                            }
                        )
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when (currentPage) {
                            Page.Scan -> ScanScreen(
                                scanned = scannedCode, canSave = canSaveScan,
                                onScan = { opts -> scanLauncher.launch(opts) },
                                onSave = { code -> saveScan(code) }
                            )
                            Page.Generate -> GenerateScreen { data, saveToCloud ->
                                // Lance la génération
                                generateTickets(data, saveToCloud)
                            }
                            Page.Control -> ControlScreen(
                                ref = controlRef, count = controlCount, message = controlMessage,
                                onScan = { opts -> controlLauncher.launch(opts) }
                            )
                            Page.Import -> ImportScreen(
                                message = importMessage,
                                onPickCsv = { csvPicker.launch("text/csv") }
                            )
                            Page.Visualise -> VisualiseScreen(storage)
                        }

                        // --- Overlay de progression pendant la GÉNÉRATION ---
                        if (isGenerating) {
                            Box(
                                Modifier.fillMaxSize().background(ComposeColor.Black.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .fillMaxWidth(0.85f)
                                        .background(ComposeColor.White, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                        .padding(20.dp)
                                ) {
                                    Text("Génération des QR…")
                                    Spacer(Modifier.height(12.dp))
                                    LinearProgressIndicator(progress = genProgress, modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(8.dp))
                                    Text("${genProcessed} / ${genTotal}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Fonctions existantes (scan/contrôle/import) inchangées ---

    private fun saveScan(code: String) {
        db.collection("scannedCodes")
            .add(mapOf("code" to code, "timestamp" to System.currentTimeMillis()))
            .addOnSuccessListener { Toast.makeText(this, "Scan enregistré", Toast.LENGTH_SHORT).show() }
            .addOnFailureListener { e -> Toast.makeText(this, "Erreur: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        scannedCode = null; canSaveScan = false
    }

    private fun playTone(success: Boolean) {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                .startTone(if (success) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_NACK, 200)
        } catch (_: Exception) {}
    }

    private fun checkTicketByCode(raw: String) {
        db.collection("generatedEvents").whereEqualTo("code", raw).get()
            .addOnSuccessListener { snap ->
                if (snap.isEmpty) {
                    controlRef = null; controlCount = null; controlMessage = "Non valide / Ticket inconnu"; playTone(false)
                } else {
                    val doc = snap.documents.first()
                    val ref = doc.getString("ref")
                    val existing = doc.getLong("nbControle")?.toInt() ?: 0
                    val newCount = existing + 1
                    doc.reference.set(mapOf("nbControle" to newCount, "status" to "OK"), SetOptions.merge())
                    controlRef = ref; controlCount = newCount
                    controlMessage = if (existing == 0) "Validé" else "Déjà contrôlé ($existing fois)"
                    playTone(true)
                }
            }.addOnFailureListener { e ->
                controlMessage = "Erreur: ${e.localizedMessage}"; playTone(false)
            }
    }

    private fun importCsv(uri: Uri) {
        // (inchangé – si tu veux aussi la progression ici, dis-moi)
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val reader = BufferedReader(InputStreamReader(stream))
                reader.readLine()
                var ticketNumber = 1
                var count = 0
                val qrSize = 300
                val textHeight = 50
                val totalHeight = qrSize + textHeight
                val writer = QRCodeWriter()
                val dateFormat = SimpleDateFormat("yyyy-MM-dd")
                val generationDate = dateFormat.format(Calendar.getInstance().time)

                reader.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    val c = line.split(';'); if (c.size < 6) return@forEach
                    val ref = c[0].trim(); val name = c[1].trim()
                    val date = c[2].trim(); val start = c[3].trim()
                    val end = c[4].trim(); val place = c[5].trim()

                    val payload = "Name: $name  Date: $date  Time: $start - $end  Place: $place  Ref.:$ref"
                    db.collection("generatedEvents").add(
                        mapOf("code" to payload, "ref" to ref, "name" to name, "date" to date,
                            "start" to start, "end" to end, "place" to place,
                            "timestamp" to System.currentTimeMillis(), "no" to ticketNumber)
                    )

                    val matrix = writer.encode(payload, BarcodeFormat.QR_CODE, qrSize, qrSize)
                    val bmp = Bitmap.createBitmap(qrSize, totalHeight, Bitmap.Config.RGB_565)
                    val canvas = Canvas(bmp)
                    val paint = Paint().apply { color = Color.parseColor("#1E90FF"); textSize = 20f; textAlign = Paint.Align.CENTER }
                    canvas.drawColor(Color.WHITE)
                    for (x in 0 until qrSize) for (y in 0 until qrSize)
                        bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                    canvas.drawText("Ref.:$ref, No: $ticketNumber", qrSize / 2f, (qrSize + 30f), paint)

                    val fn = "imported_${ref}.png"
                    val vals = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fn)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/QRM/$generationDate")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, vals)?.also { outUri ->
                        contentResolver.openOutputStream(outUri)?.use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                        vals.clear(); vals.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(outUri, vals, null, null)
                    }

                    try {
                        val storageRef = storage.reference.child("qrcodes/$generationDate/$fn")
                        val baos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                        val data = baos.toByteArray()
                        val metadata = StorageMetadata.Builder()
                            .setContentType("image/png")
                            .setCustomMetadata("no", ticketNumber.toString())
                            .setCustomMetadata("ref", ref).build()
                        storageRef.putBytes(data, metadata)
                            .addOnFailureListener { e -> if (e is StorageException) Toast.makeText(this, "Upload (CSV) échoué: ${e.message}", Toast.LENGTH_LONG).show() }
                    } catch (_: Exception) {}
                    count++; ticketNumber++
                }
                importMessage = "Import terminé : $count tickets ajoutés"
            } ?: run { importMessage = "Erreur lecture fichier" }
        } catch (e: Exception) { importMessage = "Erreur: ${e.message}" }
    }

    /** GÉNÉRATION AVEC PROGRESSION & THREAD IO */
    private fun generateTickets(form: EventData, saveToCloud: Boolean) {
        val writer = QRCodeWriter()
        val qrSize = 300
        val textHeight = 50
        val totalHeight = qrSize + textHeight
        val dateFormat = SimpleDateFormat("yyyy-MM-dd")
        val generationDate = dateFormat.format(Calendar.getInstance().time)

        // init état UI
        isGenerating = true; genProcessed = 0; genTotal = form.quantity; genProgress = 0f

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var ticketNumber = 1
                repeat(form.quantity) {
                    val ref = UUID.randomUUID().toString().takeLast(8)
                    val payload = "Name: ${form.name}  Date: ${form.date}  Time: ${form.start} - ${form.end}  Place: ${form.place}  Ref.:$ref"

                    // Firestore (info + n°)
                    db.collection("generatedEvents").add(
                        mapOf("code" to payload, "ref" to ref, "name" to form.name, "date" to form.date,
                            "start" to form.start, "end" to form.end, "place" to form.place,
                            "timestamp" to System.currentTimeMillis(), "no" to ticketNumber)
                    )

                    // Image QR + footer
                    val matrix = writer.encode(payload, BarcodeFormat.QR_CODE, qrSize, qrSize)
                    val bmp = Bitmap.createBitmap(qrSize, totalHeight, Bitmap.Config.RGB_565)
                    val canvas = Canvas(bmp)
                    val paint = Paint().apply { color = Color.parseColor("#1E90FF"); textSize = 20f; textAlign = Paint.Align.CENTER }
                    canvas.drawColor(Color.WHITE)
                    for (x in 0 until qrSize) for (y in 0 until qrSize)
                        bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                    canvas.drawText("Ref.:$ref, No: $ticketNumber", qrSize / 2f, (qrSize + 30f), paint)

                    val fn = "ticket_$ref.png"

                    if (saveToCloud) {
                        try {
                            val storageRef = storage.reference.child("qrcodes/$generationDate/$fn")
                            val baos = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                            val data = baos.toByteArray()
                            val metadata = StorageMetadata.Builder()
                                .setContentType("image/png")
                                .setCustomMetadata("no", ticketNumber.toString())
                                .setCustomMetadata("ref", ref).build()
                            storageRef.putBytes(data, metadata)
                        } catch (_: Exception) { /* on continue */ }
                    } else {
                        val vals = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, fn)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/QRM/$generationDate")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, vals)?.also { uriOut ->
                            contentResolver.openOutputStream(uriOut)?.use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                            vals.clear(); vals.put(MediaStore.Images.Media.IS_PENDING, 0)
                            contentResolver.update(uriOut, vals, null, null)
                        }
                    }

                    // mise à jour progression UI
                    withContext(Dispatchers.Main) {
                        genProcessed++
                        genProgress = if (genTotal > 0) genProcessed.toFloat() / genTotal else 0f
                    }

                    ticketNumber++
                }

                withContext(Dispatchers.Main) {
                    isGenerating = false
                    Toast.makeText(this@MainActivity, "Créé ${form.quantity} tickets", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isGenerating = false
                    Toast.makeText(this@MainActivity, "Erreur génération: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

enum class Page { Scan, Generate, Control, Import, Visualise }

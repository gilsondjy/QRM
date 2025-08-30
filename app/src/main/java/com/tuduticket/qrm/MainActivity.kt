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
import com.google.firebase.storage.StorageMetadata
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.tuduticket.qrm.ui.theme.QRMTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.tasks.await
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    // ====== Deep link custom (QR opaque) ======
    private companion object {
        private const val CUSTOM_SCHEME_PREFIX = "qrm://t/"
        private fun newToken(): String =
            UUID.randomUUID().toString().replace("-", "").take(24)
        private fun buildDeepLink(token: String) = "$CUSTOM_SCHEME_PREFIX$token"
    }

    // Firebase
    private lateinit var db: FirebaseFirestore
    private lateinit var storage: FirebaseStorage

    // Navigation
    private var currentPage by mutableStateOf(Page.Scan)

    // Scan / Contrôle / Import
    private var scannedCode by mutableStateOf<String?>(null)
    private var canSaveScan by mutableStateOf(false)
    private var controlRef by mutableStateOf<String?>(null)
    private var controlMessage by mutableStateOf<String?>(null)
    private var controlCount by mutableStateOf<Int?>(null)
    private var importMessage by mutableStateOf<String?>(null)

    // Progression Génération
    private var isGenerating by mutableStateOf(false)
    private var genProcessed by mutableStateOf(0)
    private var genTotal by mutableStateOf(0)
    private var genProgress by mutableStateOf(0f)

    // Launchers
    private val scanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        try {
            result.contents?.let { scannedCode = it; canSaveScan = true }
                ?: Toast.makeText(this, "Scan annulé", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur lors du scan: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val controlLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        try {
            val raw = result.contents
            if (raw.isNullOrBlank()) {
                controlMessage = "Scan annulé"; playTone(false)
            } else {
                checkTicketByCode(raw)
            }
        } catch (e: Exception) {
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
                               // TextButton({ currentPage = Page.Scan }) { Text("Scanner") }
                                TextButton({ currentPage = Page.Generate }) { Text("Générer") }
                                TextButton({ currentPage = Page.Control }) { Text("Contrôler") }
                               // TextButton({ currentPage = Page.Import }) { Text("Importer") }
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

    // ====== Utilitaires ======
    private fun playTone(success: Boolean) {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                .startTone(if (success) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_NACK, 200)
        } catch (_: Exception) {}
    }

    private fun saveScan(code: String) {
        db.collection("scannedCodes")
            .add(mapOf("code" to code, "timestamp" to System.currentTimeMillis()))
            .addOnSuccessListener { Toast.makeText(this, "Scan enregistré", Toast.LENGTH_SHORT).show() }
            .addOnFailureListener { e -> Toast.makeText(this, "Erreur: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        scannedCode = null; canSaveScan = false
    }

    // ====== Contrôle : n'accepte que nos QR qrm://t/<token> ======
    private fun checkTicketByCode(raw: String) {
        if (!raw.startsWith(CUSTOM_SCHEME_PREFIX)) {
            controlRef = null; controlCount = null
            controlMessage = "QR inconnu : ce ticket nécessite l'application officielle."
            playTone(false)
            return
        }
        db.collection("generatedEvents").whereEqualTo("code", raw).get()
            .addOnSuccessListener { snap ->
                if (snap.isEmpty) {
                    controlRef = null; controlCount = null
                    controlMessage = "Non valide / Ticket inconnu"
                    playTone(false)
                } else {
                    val doc = snap.documents.first()
                    val ref = doc.getString("ref")
                    val existing = doc.getLong("nbControle")?.toInt() ?: 0
                    val newCount = existing + 1
                    doc.reference.set(
                        mapOf("nbControle" to newCount, "status" to "OK"),
                        SetOptions.merge()
                    )
                    controlRef = ref; controlCount = newCount
                    controlMessage = if (existing == 0) "Validé" else "Déjà contrôlé ($existing fois)"
                    playTone(true)
                }
            }
            .addOnFailureListener { e ->
                controlMessage = "Erreur: ${e.localizedMessage}"
                playTone(false)
            }
    }

    // ====== Import CSV (séquentiel & mémoire-safe) ======
    private fun importCsv(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val reader = BufferedReader(InputStreamReader(stream))
                    reader.readLine() // header
                    var ticketNumber = 1
                    var count = 0

                    val qrSize = 300
                    val textHeight = 24
                    val totalHeight = qrSize + textHeight
                    val writer = QRCodeWriter()
                    val hints = mapOf(EncodeHintType.MARGIN to 1) // quiet-zone réduite
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd")
                    val generationDate = dateFormat.format(Calendar.getInstance().time)

                    reader.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                        val c = line.split(';')
                        if (c.size >= 6) {
                            val ref = c[0].trim()
                            val name = c[1].trim()
                            val date = c[2].trim()
                            val start = c[3].trim()
                            val end = c[4].trim()
                            val place = c[5].trim()

                            val token = newToken()
                            val payload = buildDeepLink(token)

                            // 1) Firestore
                            db.collection("generatedEvents").add(
                                mapOf(
                                    "code" to payload,
                                    "token" to token,
                                    "ref" to ref,
                                    "name" to name,
                                    "date" to date,
                                    "start" to start,
                                    "end" to end,
                                    "place" to place,
                                    "status" to "valid",
                                    "timestamp" to System.currentTimeMillis(),
                                    "no" to ticketNumber
                                )
                            ).await()

                            // 2) Générer le bitmap (quiet-zone fine)
                            val matrix = writer.encode(payload, BarcodeFormat.QR_CODE, qrSize, qrSize, hints)
                            var bmp: Bitmap? = Bitmap.createBitmap(qrSize, totalHeight, Bitmap.Config.RGB_565)
                            val canvas = Canvas(bmp!!)
                            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.BLACK
                                textSize = 18f
                                textAlign = Paint.Align.CENTER
                            }
                            canvas.drawColor(Color.WHITE)
                            for (x in 0 until qrSize) for (y in 0 until qrSize)
                                bmp!!.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                            canvas.drawText("Ref.:$ref, No: $ticketNumber", qrSize / 2f, (qrSize + 16f), paint)

                            val fn = "imported_${ref}.png"

                            // 3) Galerie
                            withContext(Dispatchers.Main) {
                                val vals = ContentValues().apply {
                                    put(MediaStore.Images.Media.DISPLAY_NAME, fn)
                                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/QRM/$generationDate")
                                    put(MediaStore.Images.Media.IS_PENDING, 1)
                                }
                                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, vals)?.also { outUri ->
                                    contentResolver.openOutputStream(outUri)?.use { out ->
                                        bmp!!.compress(Bitmap.CompressFormat.PNG, 100, out)
                                    }
                                    vals.clear(); vals.put(MediaStore.Images.Media.IS_PENDING, 0)
                                    contentResolver.update(outUri, vals, null, null)
                                }
                            }

                            // 4) Upload Storage via fichier temp
                            val temp = File.createTempFile("qr_", ".png", cacheDir)
                            FileOutputStream(temp).use { out ->
                                bmp!!.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            val storageRef = storage.reference.child("qrcodes/$generationDate/$fn")
                            val metadata = StorageMetadata.Builder()
                                .setContentType("image/png")
                                .setCustomMetadata("no", ticketNumber.toString())
                                .setCustomMetadata("ref", ref)
                                .build()
                            storageRef.putFile(Uri.fromFile(temp), metadata).await()
                            temp.delete()

                            // 5) Libère mémoire
                            bmp!!.recycle()
                            bmp = null

                            count++; ticketNumber++
                            withContext(Dispatchers.Main) { importMessage = "Import: $count" }
                            if (count % 50 == 0) { yield(); delay(5) }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        importMessage = "Import terminé : $count tickets ajoutés"
                    }
                } ?: run {
                    withContext(Dispatchers.Main) { importMessage = "Erreur lecture fichier" }
                }
            } catch (e: OutOfMemoryError) {
                withContext(Dispatchers.Main) { importMessage = "Mémoire insuffisante : ${e.message}" }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { importMessage = "Erreur: ${e.message}" }
            }
        }
    }

    // ====== Génération (2000+ robuste, quiet-zone fine) ======
    private fun generateTickets(form: EventData, saveToCloud: Boolean) {
        val writer = QRCodeWriter()
        val hints = mapOf(EncodeHintType.MARGIN to 1) // quiet-zone réduite
        val qrSize = 300
        val textHeight = 24
        val totalHeight = qrSize + textHeight
        val dateFormat = SimpleDateFormat("yyyy-MM-dd")
        val generationDate = dateFormat.format(Calendar.getInstance().time)

        isGenerating = true; genProcessed = 0; genTotal = form.quantity; genProgress = 0f

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var ticketNumber = 1

                repeat(form.quantity) {
                    val ref = UUID.randomUUID().toString().takeLast(8)
                    val token = newToken()
                    val payload = buildDeepLink(token)

                    // 1) Firestore
                    db.collection("generatedEvents").add(
                        mapOf(
                            "code" to payload,
                            "token" to token,
                            "ref" to ref,
                            "name" to form.name,
                            "date" to form.date,
                            "start" to form.start,
                            "end" to form.end,
                            "place" to form.place,
                            "status" to "valid",
                            "timestamp" to System.currentTimeMillis(),
                            "no" to ticketNumber
                        )
                    ).await()

                    // 2) Générer le bitmap (quiet-zone fine)
                    val matrix = writer.encode(payload, BarcodeFormat.QR_CODE, qrSize, qrSize, hints)
                    var bmp: Bitmap? = Bitmap.createBitmap(qrSize, totalHeight, Bitmap.Config.RGB_565)
                    val canvas = Canvas(bmp!!)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 18f
                        textAlign = Paint.Align.CENTER
                    }
                    canvas.drawColor(Color.WHITE)
                    for (x in 0 until qrSize) for (y in 0 until qrSize)
                        bmp!!.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                    canvas.drawText("Ref.:$ref, No: $ticketNumber", qrSize / 2f, (qrSize + 16f), paint)

                    val fn = "ticket_$ref.png"

                    if (saveToCloud) {
                        val temp = File.createTempFile("qr_", ".png", cacheDir)
                        FileOutputStream(temp).use { out ->
                            bmp!!.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        val storageRef = storage.reference.child("qrcodes/$generationDate/$fn")
                        val metadata = StorageMetadata.Builder()
                            .setContentType("image/png")
                            .setCustomMetadata("no", ticketNumber.toString())
                            .setCustomMetadata("ref", ref)
                            .build()
                        storageRef.putFile(Uri.fromFile(temp), metadata).await()
                        temp.delete()
                    } else {
                        withContext(Dispatchers.Main) {
                            val vals = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, fn)
                                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/QRM/$generationDate")
                                put(MediaStore.Images.Media.IS_PENDING, 1)
                            }
                            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, vals)?.also { uriOut ->
                                contentResolver.openOutputStream(uriOut)?.use { out ->
                                    bmp!!.compress(Bitmap.CompressFormat.PNG, 100, out)
                                }
                                vals.clear(); vals.put(MediaStore.Images.Media.IS_PENDING, 0)
                                contentResolver.update(uriOut, vals, null, null)
                            }
                        }
                    }

                    // 3) Libère mémoire + progress
                    bmp!!.recycle(); bmp = null
                    withContext(Dispatchers.Main) {
                        genProcessed++
                        genProgress = if (genTotal > 0) genProcessed.toFloat() / genTotal else 0f
                    }
                    if (ticketNumber % 50 == 0) { yield(); delay(5) }
                    ticketNumber++
                }

                withContext(Dispatchers.Main) {
                    isGenerating = false
                    Toast.makeText(this@MainActivity, "Créé ${form.quantity} tickets", Toast.LENGTH_LONG).show()
                }
            } catch (e: OutOfMemoryError) {
                withContext(Dispatchers.Main) {
                    isGenerating = false
                    Toast.makeText(this@MainActivity, "Mémoire insuffisante : ${e.message}", Toast.LENGTH_LONG).show()
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

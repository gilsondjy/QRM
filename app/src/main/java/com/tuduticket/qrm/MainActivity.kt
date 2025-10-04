package com.tuduticket.qrm

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.activity.viewModels
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
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
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/* ===========================
   ViewModel : états persistants
   =========================== */
class MainVM : ViewModel() {
    // Navigation
    var currentPage by mutableStateOf(Page.Control)

    // Firebase
    val db: FirebaseFirestore = FirebaseFirestore.getInstance()
    val storage: FirebaseStorage = FirebaseStorage.getInstance()

    // Génération
    var isGenerating by mutableStateOf(false)
    var genProcessed by mutableStateOf(0)
    var genTotal by mutableStateOf(0)
    var genProgress by mutableStateOf(0f)

    // Import
    var importMessage by mutableStateOf<String?>(null)

    // Contrôle (états affichés)
    var controlRef by mutableStateOf<String?>(null)
    var controlMessage by mutableStateOf<String?>(null)
    var controlCount by mutableStateOf<Int?>(null)
    var controlFirstValidatedAt by mutableStateOf<String?>(null)

    var controlEventName by mutableStateOf<String?>(null)
    var controlEventDate by mutableStateOf<String?>(null)
    var controlTotal by mutableStateOf<Int?>(null)
    var controlScanned by mutableStateOf<Int?>(null)
    val controlRemaining: Int?
        get() = if (controlTotal != null && controlScanned != null)
            (controlTotal!! - controlScanned!!).coerceAtLeast(0) else null

    // Utilitaires
    fun formatTs(ts: Long?): String? {
        if (ts == null || ts <= 0L) return null
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(java.util.Date(ts))
    }
}

/* ===========================
   Activity
   =========================== */
@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private val vm: MainVM by viewModels()

    // ====== Deep link custom (QR opaque) ======
    private companion object {
        private const val CUSTOM_SCHEME_PREFIX = "qrm://t/"
        private fun newToken(): String =
            UUID.randomUUID().toString().replace("-", "").take(24)
        private fun buildDeepLink(token: String) = "$CUSTOM_SCHEME_PREFIX$token"
    }

    // Scan basique (non utilisé pour le contrôle)
    private var scannedCode by mutableStateOf<String?>(null)
    private var canSaveScan by mutableStateOf(false)

    // === SONS ===
    private enum class ScanSound { SUCCESS, DUPLICATE, INVALID }
    private lateinit var toneGen: ToneGenerator
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

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
                vm.controlMessage = "Scan annulé"
                playPattern(ScanSound.INVALID) // petit rappel sonore
            } else {
                checkTicketByCode(raw)
            }
        } catch (e: Exception) {
            vm.controlMessage = "Erreur: ${e.message}"
            playPattern(ScanSound.INVALID)
        }
    }

    private val csvPicker = registerForActivityResult(GetContent()) { uri: Uri? ->
        try { uri?.let { importCsv(it) } } catch (e: Exception) {
            vm.importMessage = "Erreur lors de l'import: ${e.message}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)

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
                                TextButton({ vm.currentPage = Page.Control }) { Text("Validar") }
                                TextButton({ vm.currentPage = Page.Generate }) { Text("Gerar") }
                                TextButton({ vm.currentPage = Page.Visualise }) { Text("Visualizar") }
                            }
                        )
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when (vm.currentPage) {
                            Page.Scan -> ScanScreen(
                                scanned = scannedCode, canSave = canSaveScan,
                                onScan = { opts -> scanLauncher.launch(opts) },
                                onSave = { code -> saveScan(code) }
                            )
                            Page.Generate -> GenerateScreen { data, saveToCloud ->
                                generateTickets(data, saveToCloud)
                            }
                            Page.Control -> ControlScreen(
                                eventName = vm.controlEventName,
                                total = vm.controlTotal,
                                scanned = vm.controlScanned,
                                remaining = vm.controlRemaining,
                                ref = vm.controlRef,
                                count = vm.controlCount,
                                message = vm.controlMessage,
                                firstValidatedAt = vm.controlFirstValidatedAt,
                                onScan = { opts -> controlLauncher.launch(opts) }
                            )
                            Page.Import -> ImportScreen(
                                message = vm.importMessage,
                                onPickCsv = { csvPicker.launch("text/csv") }
                            )
                            Page.Visualise -> VisualiseScreen(vm.storage)
                        }

                        if (vm.isGenerating) {
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
                                    LinearProgressIndicator(progress = vm.genProgress, modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(8.dp))
                                    Text("${vm.genProcessed} / ${vm.genTotal}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        try { toneGen.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    /* ===========================
       SONS : patterns agressifs
       =========================== */
    /* ===========================
    SONS : patterns personnalisés
    =========================== */
    private fun playPattern(kind: ScanSound, repeats: Int = 1) {
        fun beep(tone: Int, dur: Int) {
            try { toneGen.startTone(tone, dur) } catch (_: Exception) {}
        }

        when (kind) {
            ScanSound.SUCCESS -> {
                // Bip standard de lecteur QR (proche du bip par défaut)
                beep(ToneGenerator.TONE_PROP_BEEP, 180)
            }

            ScanSound.DUPLICATE -> {
                // N bips courts et très rapprochés (agressifs)
                // repeats = 2, 3 ou 4 (voir appel plus bas)
                val count = repeats.coerceIn(2, 4)
                val tone = ToneGenerator.TONE_PROP_BEEP2
                val dur  = 120   // durée de chaque bip
                val gap  = 120   // intervalle entre bips

                // premier bip immédiat
                beep(tone, dur)
                // les suivants avec un petit décalage
                for (i in 1 until count) {
                    mainHandler.postDelayed({ beep(tone, dur) }, (i * (dur + gap)).toLong())
                }
            }

            ScanSound.INVALID -> {
                // Triple bip d'erreur (bien audible)
                beep(ToneGenerator.TONE_PROP_NACK, 220)
                mainHandler.postDelayed({ beep(ToneGenerator.TONE_PROP_NACK, 220) }, 260)
                mainHandler.postDelayed({ beep(ToneGenerator.TONE_PROP_NACK, 280) }, 520)
            }
        }
    }


    /* ===========================
       Utilitaires
       =========================== */
    private fun saveScan(code: String) {
        vm.db.collection("scannedCodes")
            .add(mapOf("code" to code, "timestamp" to System.currentTimeMillis()))
            .addOnSuccessListener { Toast.makeText(this, "Scan enregistré", Toast.LENGTH_SHORT).show() }
            .addOnFailureListener { e -> Toast.makeText(this, "Erreur: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        scannedCode = null; canSaveScan = false
    }

    private fun refreshEventStats(name: String?, date: String?) {
        if (name.isNullOrBlank()) return
        vm.controlEventName = name
        vm.controlEventDate = date

        val base = vm.db.collection("generatedEvents").whereEqualTo("name", name)
        val qTotal = if (!date.isNullOrBlank()) base.whereEqualTo("date", date) else base

        qTotal.get()
            .addOnSuccessListener { snap ->
                vm.controlTotal = snap.size()
                vm.controlScanned = snap.documents.count { (it.getLong("nbControle") ?: 0L) > 0L }
            }
    }

    /* ===========================
       Contrôle (sons + stats + 1ère validation)
       =========================== */
    private fun checkTicketByCode(raw: String) {
        if (!raw.startsWith(CUSTOM_SCHEME_PREFIX)) {
            // on ne touche pas aux stats d'événement → elles restent affichées
            vm.controlRef = null
            vm.controlCount = null
            vm.controlFirstValidatedAt = null
            vm.controlMessage = "Não foi possível ler este QR"
            playPattern(ScanSound.INVALID)
            return
        }

        vm.db.collection("generatedEvents").whereEqualTo("code", raw).get()
            .addOnSuccessListener { snap ->
                if (snap.isEmpty) {
                    vm.controlRef = null
                    vm.controlCount = null
                    vm.controlFirstValidatedAt = null
                    vm.controlMessage = "Inválido / Bilhete desconhecido"
                    playPattern(ScanSound.INVALID)
                } else {
                    val doc = snap.documents.first()
                    val ref = doc.getString("ref")
                    val existing = doc.getLong("nbControle")?.toInt() ?: 0
                    val newCount = existing + 1

                    val evName = doc.getString("name")
                    val evDate = doc.getString("date")

                    val update = mutableMapOf<String, Any>(
                        "nbControle" to newCount,
                        "status" to "OK"
                    )

                    if (existing == 0) {
                        update["firstValidatedAt"] = FieldValue.serverTimestamp()
                        update["firstValidatedAtClient"] = System.currentTimeMillis()
                        vm.controlFirstValidatedAt = vm.formatTs(System.currentTimeMillis())
                    } else {
                        val serverTs: Timestamp? = doc.getTimestamp("firstValidatedAt")
                        vm.controlFirstValidatedAt = when {
                            serverTs != null -> vm.formatTs(serverTs.toDate().time)
                            doc.getLong("firstValidatedAtClient") != null ->
                                vm.formatTs(doc.getLong("firstValidatedAtClient"))
                            else -> null
                        }
                    }

                    doc.reference.set(update, SetOptions.merge())

                    if (vm.controlEventName != null && vm.controlEventName == evName &&
                        (vm.controlEventDate.isNullOrBlank() || vm.controlEventDate == evDate)
                    ) {
                        if (existing == 0) vm.controlScanned = (vm.controlScanned ?: 0) + 1
                    } else {
                        refreshEventStats(evName, evDate)
                    }

                    vm.controlRef = ref
                    vm.controlCount = newCount
                    vm.controlMessage = if (existing == 0) "Válido" else "Já controlado ($existing vezes)"
                    playPattern(if (existing == 0) ScanSound.SUCCESS else ScanSound.DUPLICATE)
                }
            }
            .addOnFailureListener { e ->
                vm.controlMessage = "Erreur: ${e.localizedMessage}"
                vm.controlFirstValidatedAt = null
                playPattern(ScanSound.INVALID)
            }
    }

    /* ===========================
       Import CSV (séquentiel & mémoire-safe)
       =========================== */
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
                    val hints = mapOf(EncodeHintType.MARGIN to 1)
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

                            vm.db.collection("generatedEvents").add(
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

                            val temp = File.createTempFile("qr_", ".png", cacheDir)
                            FileOutputStream(temp).use { out ->
                                bmp!!.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            val storageRef = vm.storage.reference.child("qrcodes/$generationDate/$fn")
                            val metadata = StorageMetadata.Builder()
                                .setContentType("image/png")
                                .setCustomMetadata("no", ticketNumber.toString())
                                .setCustomMetadata("ref", ref)
                                .build()
                            storageRef.putFile(Uri.fromFile(temp), metadata).await()
                            temp.delete()

                            bmp!!.recycle()
                            bmp = null

                            count++; ticketNumber++
                            withContext(Dispatchers.Main) { vm.importMessage = "Import: $count" }
                            if (count % 50 == 0) { yield(); delay(5) }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        vm.importMessage = "Import terminé : $count tickets ajoutés"
                    }
                } ?: run {
                    withContext(Dispatchers.Main) { vm.importMessage = "Erreur lecture fichier" }
                }
            } catch (e: OutOfMemoryError) {
                withContext(Dispatchers.Main) { vm.importMessage = "Mémoire insuffisante : ${e.message}" }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { vm.importMessage = "Erreur: ${e.message}" }
            }
        }
    }

    /* ===========================
       Génération (mémoire-safe)
       =========================== */
    private fun generateTickets(form: EventData, saveToCloud: Boolean) {
        val writer = QRCodeWriter()
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val qrSize = 300
        val textHeight = 24
        val totalHeight = qrSize + textHeight
        val dateFormat = SimpleDateFormat("yyyy-MM-dd")
        val generationDate = dateFormat.format(Calendar.getInstance().time)

        vm.isGenerating = true; vm.genProcessed = 0; vm.genTotal = form.quantity; vm.genProgress = 0f

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var ticketNumber = 1

                repeat(form.quantity) {
                    val ref = UUID.randomUUID().toString().takeLast(8)
                    val token = newToken()
                    val payload = buildDeepLink(token)

                    vm.db.collection("generatedEvents").add(
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
                        val storageRef = vm.storage.reference.child("qrcodes/$generationDate/$fn")
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

                    bmp!!.recycle(); bmp = null
                    withContext(Dispatchers.Main) {
                        vm.genProcessed++
                        vm.genProgress = if (vm.genTotal > 0) vm.genProcessed.toFloat() / vm.genTotal else 0f
                    }
                    if (ticketNumber % 50 == 0) { yield(); delay(5) }
                    ticketNumber++
                }

                withContext(Dispatchers.Main) {
                    vm.isGenerating = false
                    Toast.makeText(this@MainActivity, "Créé ${form.quantity} tickets", Toast.LENGTH_LONG).show()
                }
            } catch (e: OutOfMemoryError) {
                withContext(Dispatchers.Main) {
                    vm.isGenerating = false
                    Toast.makeText(this@MainActivity, "Mémoire insuffisante : ${e.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    vm.isGenerating = false
                    Toast.makeText(this@MainActivity, "Erreur génération: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

enum class Page { Scan, Generate, Control, Import, Visualise }

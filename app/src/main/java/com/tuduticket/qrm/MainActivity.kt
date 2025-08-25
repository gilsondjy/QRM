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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.tuduticket.qrm.ui.theme.QRMTheme
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var storage: FirebaseStorage
    private var currentPage by mutableStateOf(Page.Scan)

    private var scannedCode by mutableStateOf<String?>(null)
    private var canSaveScan by mutableStateOf(false)

    private var controlRef by mutableStateOf<String?>(null)
    private var controlMessage by mutableStateOf<String?>(null)
    private var controlCount by mutableStateOf<Int?>(null)

    private var importMessage by mutableStateOf<String?>(null)

    private val scanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        try {
            result.contents?.let {
                scannedCode = it
                canSaveScan = true
            } ?: run {
                Toast.makeText(this, "Scan annulé", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur lors du scan: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val controlLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        try {
            val raw = result.contents
            if (raw.isNullOrBlank()) {
                controlMessage = "Scan annulé"
                playTone(false)
            } else {
                checkTicketByCode(raw)
            }
        } catch (e: Exception) {
            controlMessage = "Erreur: ${e.message}"
            playTone(false)
        }
    }

    private val csvPicker = registerForActivityResult(GetContent()) { uri: Uri? ->
        try {
            uri?.let { importCsv(it) }
        } catch (e: Exception) {
            importMessage = "Erreur lors de l'import: ${e.message}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        try {
            db = FirebaseFirestore.getInstance()
            storage = FirebaseStorage.getInstance()
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur d'initialisation Firebase: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }

        setContent {
            QRMTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            navigationIcon = {
                                Image(
                                    painter = painterResource(R.drawable.tuduticket_logo),
                                    contentDescription = "Logo",
                                    modifier = Modifier
                                        .padding(start = 12.dp)
                                        .size(70.dp)
                                )
                            },
                            title = { Text("") },
                            colors = TopAppBarDefaults.smallTopAppBarColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                titleContentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            actions = {
                                TextButton(onClick = { currentPage = Page.Scan }) { Text("Scanner") }
                                TextButton(onClick = { currentPage = Page.Generate }) { Text("Générer") }
                                TextButton(onClick = { currentPage = Page.Control }) { Text("Contrôler") }
                                TextButton(onClick = { currentPage = Page.Visualise }) { Text("Visualiser") }
                            }
                        )
                    }
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        when (currentPage) {
                            Page.Scan -> ScanScreen(
                                scanned = scannedCode,
                                canSave = canSaveScan,
                                onScan = { opts -> scanLauncher.launch(opts) },
                                onSave = { code -> saveScan(code) }
                            )
                            Page.Generate -> GenerateScreen { data, saveToCloud ->
                                generateTickets(data, saveToCloud)
                            }
                            Page.Control -> ControlScreen(
                                ref = controlRef,
                                count = controlCount,
                                message = controlMessage,
                                onScan = { opts -> controlLauncher.launch(opts) }
                            )
                            Page.Import -> ImportScreen(
                                message = importMessage,
                                onPickCsv = { csvPicker.launch("text/csv") }
                            )
                            Page.Visualise -> VisualiseScreen(storage)
                        }
                    }
                }
            }
        }
    }

    private fun saveScan(code: String) {
        try {
            db.collection("scannedCodes")
                .add(mapOf("code" to code, "timestamp" to System.currentTimeMillis()))
                .addOnSuccessListener {
                    Toast.makeText(this, "Scan enregistré", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Erreur: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            scannedCode = null
            canSaveScan = false
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur lors de l'enregistrement: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun playTone(success: Boolean) {
        try {
            val tone = if (success) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_NACK
            ToneGenerator(AudioManager.STREAM_MUSIC, 100).startTone(tone, 200)
        } catch (e: Exception) {
            // Silent fail for tone generation
        }
    }

    private fun checkTicketByCode(raw: String) {
        try {
            db.collection("generatedEvents")
                .whereEqualTo("code", raw)
                .get()
                .addOnSuccessListener { snap ->
                    if (snap.isEmpty) {
                        controlRef = null
                        controlCount = null
                        controlMessage = "Non valide / Ticket inconnu"
                        playTone(false)
                    } else {
                        val doc = snap.documents.first()
                        val ref = doc.getString("ref")
                        val existing = doc.getLong("nbControle")?.toInt() ?: 0
                        val newCount = existing + 1

                        doc.reference.set(
                            mapOf(
                                "nbControle" to newCount,
                                "status" to "OK"
                            ),
                            SetOptions.merge()
                        )

                        controlRef = ref
                        controlCount = newCount
                        controlMessage = if (existing == 0) "Validé" else "Déjà contrôlé ($existing fois)"
                        playTone(true)
                    }
                }
                .addOnFailureListener { e ->
                    controlMessage = "Erreur: ${e.localizedMessage}"
                    playTone(false)
                }
        } catch (e: Exception) {
            controlMessage = "Erreur: ${e.message}"
            playTone(false)
        }
    }

    private fun importCsv(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val reader = BufferedReader(InputStreamReader(stream))
                reader.readLine() // header : ref;name;date;start;end;place
                var count = 0
                reader.lineSequence()
                    .filter { it.isNotBlank() }
                    .forEach { line ->
                        val c = line.split(';')
                        if (c.size >= 6) {
                            val ref = c[0].trim()
                            val name = c[1].trim()
                            val date = c[2].trim()
                            val start = c[3].trim()
                            val end = c[4].trim()
                            val place = c[5].trim()

                            val payload =
                                "Name: $name  " +
                                        "Date: $date  " +
                                        "Time: $start - $end  " +
                                        "Place: $place  " +
                                        "Ref.:$ref"

                            db.collection("generatedEvents")
                                .add(
                                    mapOf(
                                        "code" to payload,
                                        "ref" to ref,
                                        "name" to name,
                                        "date" to date,
                                        "start" to start,
                                        "end" to end,
                                        "place" to place,
                                        "timestamp" to System.currentTimeMillis()
                                    )
                                )

                            val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 300, 300)
                            val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.RGB_565).apply {
                                for (x in 0 until 300) for (y in 0 until 300)
                                    setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                            }

                            val fn = "imported_$ref.png"
                            val vals = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, fn)
                                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/QRM")
                                put(MediaStore.Images.Media.IS_PENDING, 1)
                            }
                            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, vals)
                                ?.also { outUri ->
                                    contentResolver.openOutputStream(outUri)?.use { out ->
                                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                                    }
                                    vals.clear()
                                    vals.put(MediaStore.Images.Media.IS_PENDING, 0)
                                    contentResolver.update(outUri, vals, null, null)
                                }

                            count++
                        }
                    }
                importMessage = "Import terminé : $count tickets ajoutés"
            } ?: run {
                importMessage = "Erreur lecture fichier"
            }
        } catch (e: Exception) {
            importMessage = "Erreur: ${e.message}"
        }
    }

    private fun generateTickets(form: EventData, saveToCloud: Boolean) {
        try {
            val writer = QRCodeWriter()
            val qrSize = 300
            val textHeight = 50
            val totalHeight = qrSize + textHeight
            var ticketNumber = 1
            val dateFormat = SimpleDateFormat("yyyy-MM-dd")
            val generationDate = dateFormat.format(Calendar.getInstance().time)

            repeat(form.quantity) {
                val ref = UUID.randomUUID().toString().takeLast(8)
                val payload =
                    "Name: ${form.name}  " +
                            "Date: ${form.date}  " +
                            "Time: ${form.start} - ${form.end}  " +
                            "Place: ${form.place}  " +
                            "Ref.:$ref"

                db.collection("generatedEvents")
                    .add(
                        mapOf(
                            "code" to payload,
                            "ref" to ref,
                            "name" to form.name,
                            "date" to form.date,
                            "start" to form.start,
                            "end" to form.end,
                            "place" to form.place,
                            "timestamp" to System.currentTimeMillis()
                        )
                    )

                val matrix = writer.encode(payload, BarcodeFormat.QR_CODE, qrSize, qrSize)
                val bmp = Bitmap.createBitmap(qrSize, totalHeight, Bitmap.Config.RGB_565)
                val canvas = Canvas(bmp)
                val paint = Paint().apply {
                    color = Color.parseColor("#1E90FF") // Bleu foncé pour le texte du QR
                    textSize = 20f
                    textAlign = Paint.Align.CENTER
                }

                // Remplir le fond en blanc pour le QR
                canvas.drawColor(Color.WHITE)

                // Dessiner le QR code
                for (x in 0 until qrSize) for (y in 0 until qrSize)
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)

                // Dessiner le texte en bas du QR
                val text = "Ref.:$ref, No: $ticketNumber"
                canvas.drawText(text, qrSize / 2f, (qrSize + 30f), paint)

                val fn = "ticket_$ref.png"

                if (saveToCloud) {
                    try {
                        val storageRef = storage.reference.child("qrcodes/$generationDate/$fn")
                        val baos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                        val data = baos.toByteArray()
                        val uploadTask = storageRef.putBytes(data)
                        uploadTask.addOnSuccessListener {
                            Toast.makeText(this, "QR code uploaded to cloud: $generationDate/$fn", Toast.LENGTH_SHORT).show()
                        }.addOnFailureListener { e ->
                            if (e is StorageException) {
                                Toast.makeText(this, "Upload failed: ${e.message} (Code: ${e.errorCode})", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "Erreur lors de l'upload: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    val vals = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fn)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/QRM/$generationDate")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, vals)
                        ?.also { uriOut ->
                            contentResolver.openOutputStream(uriOut)?.use { out ->
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            vals.clear()
                            vals.put(MediaStore.Images.Media.IS_PENDING, 0)
                            contentResolver.update(uriOut, vals, null, null)
                        } ?: run {
                        Toast.makeText(this, "Erreur lors de la sauvegarde en galerie", Toast.LENGTH_LONG).show()
                    }
                }

                ticketNumber++
            }

            Toast.makeText(
                this,
                "Créé ${form.quantity} tickets au format exact",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur lors de la génération: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

enum class Page { Scan, Generate, Control, Import, Visualise }
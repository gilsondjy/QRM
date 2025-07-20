package com.tuduticket.qrm

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import com.journeyapps.barcodescanner.*

class MainActivity : ComponentActivity() {

    private lateinit var db: FirebaseFirestore
    private var scannedCode by mutableStateOf<String?>(null)
    private var canSave by mutableStateOf(false)

    private val barcodeLauncher =
        registerForActivityResult(ScanContract()) { result ->
            result.contents?.let {
                scannedCode = it
                canSave = true
            } ?: run {
                Toast.makeText(this, "Scan annulé", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = FirebaseFirestore.getInstance()

        setContent {
            Scaffold { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = {
                            val options = ScanOptions().apply {
                                setBeepEnabled(true)
                                setOrientationLocked(false)
                            }
                            barcodeLauncher.launch(options)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Scan QR Code")
                    }

                    Text(
                        text = scannedCode ?: "Aucun code scanné",
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            scannedCode?.let { code ->
                                val data = mapOf(
                                    "code" to code,
                                    "timestamp" to System.currentTimeMillis()
                                )
                                db.collection("scannedCodes")
                                    .add(data)
                                    .addOnSuccessListener {
                                        Toast
                                            .makeText(
                                                this@MainActivity,
                                                "Enregistré dans Firestore !",
                                                Toast.LENGTH_SHORT
                                            )
                                            .show()
                                        scannedCode = null
                                        canSave = false
                                    }
                                    .addOnFailureListener { e ->
                                        Toast
                                            .makeText(
                                                this@MainActivity,
                                                "Erreur : ${e.localizedMessage}",
                                                Toast.LENGTH_LONG
                                            )
                                            .show()
                                    }
                            }
                        },
                        enabled = canSave,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Enregistrer dans la base")
                    }
                }
            }
        }
    }
}

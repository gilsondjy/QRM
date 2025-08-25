package com.tuduticket.qrm

import android.graphics.pdf.PdfDocument
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.util.Date

@Composable
fun VisualiseScreen(storage: FirebaseStorage) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var folders by remember { mutableStateOf(listOf<String>()) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var images by remember { mutableStateOf(listOf<String>()) } // URLs de téléchargement

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            try {
                val listResult = storage.reference.child("qrcodes").listAll().await()
                folders = listResult.prefixes.map { it.name }
            } catch (e: Exception) {
                Toast.makeText(context, "Erreur lors du chargement des dossiers: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Dossiers disponibles (dates) :")
        LazyColumn {
            items(folders) { folder ->
                Card(onClick = {
                    selectedFolder = folder
                    coroutineScope.launch {
                        try {
                            val folderRef = storage.reference.child("qrcodes/$folder")
                            val listResult = folderRef.listAll().await()
                            images = listResult.items.mapNotNull { item ->
                                item.downloadUrl.await().toString()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Erreur lors du chargement des images: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) {
                    Text(folder, modifier = Modifier.padding(16.dp))
                }
            }
        }

        if (selectedFolder != null) {
            Text("QR codes dans $selectedFolder :")
            LazyColumn {
                items(images) { url ->
                    Image(
                        painter = rememberAsyncImagePainter(url),
                        contentDescription = "QR Code",
                        modifier = Modifier.size(300.dp)
                    )
                }
            }

            Button(onClick = {
                coroutineScope.launch {
                    exportToPdf(context, selectedFolder!!, images)
                }
            }) {
                Text("Exporter en PDF")
            }
        }
    }
}

private suspend fun exportToPdf(context: android.content.Context, folder: String, imageUrls: List<String>) {
    val pdf = PdfDocument()
    try {
        imageUrls.forEachIndexed { index, url ->
            // Télécharger le bitmap depuis l'URL
            val inputStream = java.net.URL(url).openStream()
            val bitmap = BitmapFactory.decodeStream(inputStream)

            val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
            val page = pdf.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            pdf.finishPage(page)
        }

        // Sauvegarder le PDF dans les téléchargements
        val pdfFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "qrcodes_$folder.pdf")
        FileOutputStream(pdfFile).use { output ->
            pdf.writeTo(output)
        }

        Toast.makeText(context, "PDF exporté dans Downloads: qrcodes_$folder.pdf", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Erreur lors de l'export PDF: ${e.message}", Toast.LENGTH_LONG).show()
    } finally {
        pdf.close()
    }
}
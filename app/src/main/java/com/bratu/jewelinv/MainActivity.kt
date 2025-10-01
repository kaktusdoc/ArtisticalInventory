package com.bratu.jewelinv

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.Locale
import kotlin.math.round

private const val ORG_ID = "bratu-studio"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}

/* ---------------- Root ---------------- */

@Composable
fun AppRoot() {
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    var signedIn by remember { mutableStateOf(auth.currentUser != null) }

    if (!signedIn) {
        LoginScreen(onSignedIn = { signedIn = true }, auth = auth)
    } else {
        HomeScreen(onSignOut = { auth.signOut(); signedIn = false }, db = db, auth = auth)
    }
}

/* ---------------- Login ---------------- */

@Composable
fun LoginScreen(onSignedIn: () -> Unit, auth: FirebaseAuth) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Not signed in") }

    Column(Modifier.padding(16.dp).fillMaxWidth()) {
        Text("Sign in", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                status = "Signing in…"
                auth.signInWithEmailAndPassword(email.trim(), password)
                    .addOnCompleteListener { t ->
                        status = if (t.isSuccessful) { onSignedIn(); "Signed in ✔" }
                        else "Sign-in error: ${t.exception?.message}"
                    }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Sign in") }
        Spacer(Modifier.height(8.dp))
        Text(status)
    }
}

/* ---------------- Home ---------------- */

@Composable
fun HomeScreen(onSignOut: () -> Unit, db: FirebaseFirestore, auth: FirebaseAuth) {
    var showAdd by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }

    when {
        showAdd -> {
            AddProductScreen(db = db, auth = auth, onClose = { showAdd = false })
        }
        showList -> {
            ItemsListScreen(db = db, onBack = { showList = false })
        }
        else -> {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("ArtisticalInventory", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))

                Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Add Product")
                }
                Spacer(Modifier.height(8.dp))

                OutlinedButton(onClick = { showList = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("View Items")
                }
                Spacer(Modifier.height(8.dp))

                OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign out")
                }
            }
        }
    }
}


/* ------------- Add Product (with gallery/camera) ------------- */

@Composable
fun AddProductScreen(db: FirebaseFirestore, auth: FirebaseAuth, onClose: () -> Unit) {
    val uid = auth.currentUser?.uid ?: ""
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    // Required
    var title by remember { mutableStateOf("") }
    val categories = listOf("Necklace","Earrings","Bracelet","Ring","Pendant","Set","Component")
    val typeCodes = mapOf(
        "Necklace" to "NK", "Earrings" to "ER", "Bracelet" to "BR",
        "Ring" to "RG", "Pendant" to "PD", "Set" to "ST", "Component" to "CP"
    )
    var category by remember { mutableStateOf(categories.last()) } // default Component
    var materialsText by remember { mutableStateOf("") }
    var priceMode by remember { mutableStateOf("manual") } // manual | formula
    var manualPriceText by remember { mutableStateOf("") }

    // Optional helpers (for SKU)
    var metalCode by remember { mutableStateOf("") } // e.g., SIL
    var gemCode by remember { mutableStateOf("") }   // e.g., CRY

    // Formula fields (optional)
    var materialCostText by remember { mutableStateOf("") }
    var laborHoursText by remember { mutableStateOf("") }
    var hourlyRateText by remember { mutableStateOf("25") }
    var markupText by remember { mutableStateOf("2.2") }

    // Photo pick/capture
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> pickedUri = uri }

    var tempPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success -> if (success) pickedUri = tempPhotoUri }

    var statusMsg by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    fun computePrice(): Double =
        if (priceMode == "manual") {
            manualPriceText.toDoubleOrNull() ?: 0.0
        } else {
            val mat = materialCostText.toDoubleOrNull() ?: 0.0
            val hrs = laborHoursText.toDoubleOrNull() ?: 0.0
            val rate = hourlyRateText.toDoubleOrNull() ?: 25.0
            val mk = markupText.toDoubleOrNull() ?: 2.2
            round((mat * mk + hrs * rate) * 100) / 100.0
        }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Add Product", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(title, { title = it }, label = { Text("Title *") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))

        // Simple dropdown
        var catMenu by remember { mutableStateOf(false) }
        Box {
            OutlinedTextField(
                value = category,
                onValueChange = {},
                readOnly = true,
                label = { Text("Category *") },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { catMenu = true }
            )
            DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                categories.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(c) },
                        onClick = { category = c; catMenu = false }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            materialsText, { materialsText = it },
            label = { Text("Materials (comma-separated) *") },
            singleLine = false, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = priceMode == "manual",
                    onClick = { priceMode = "manual" }
                )
                Text("Manual price")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = priceMode == "formula",
                    onClick = { priceMode = "formula" }
                )
                Text("Formula")
            }
        }

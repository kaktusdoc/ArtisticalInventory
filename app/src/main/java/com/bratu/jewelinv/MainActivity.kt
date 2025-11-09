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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
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

// Coroutines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// Compose lifecycle (for rememberCoroutineScope)
import androidx.compose.runtime.rememberCoroutineScope


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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onSignedIn: () -> Unit, auth: FirebaseAuth) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Not signed in") }

    Column(
        Modifier
            .padding(16.dp)
            .fillMaxWidth()
    ) {
        Text("Sign in", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                status = "Signing in…"
                auth.signInWithEmailAndPassword(email.trim(), password).addOnCompleteListener { t ->
                        status = if (t.isSuccessful) {
                            onSignedIn(); "Signed in ✔"
                        } else "Sign-in error: ${t.exception?.message}"
                    }
            }, modifier = Modifier.fillMaxWidth()
        ) { Text("Sign in") }
        Spacer(Modifier.height(8.dp))
        Text(status)
    }
}

/* ---------------- Home (routing) ---------------- */

@Composable
fun HomeScreen(onSignOut: () -> Unit, db: FirebaseFirestore, auth: FirebaseAuth) {
    var showAdd by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showEdit by remember { mutableStateOf(false) }

    when {
        showAdd -> AddProductScreen(db = db, auth = auth, onClose = { showAdd = false })

        showEdit && selectedId != null -> EditItemScreen(
            db = db,
            id = selectedId!!,
            onBack = { showEdit = false },
            onSaved = { showEdit = false })

        selectedId != null -> ItemDetailScreen(
            db = db,
            id = selectedId!!,
            onBack = { selectedId = null; showList = true },
            onEdit = { showEdit = true })

        showList -> ItemsListScreen(
            db = db,
            onBack = { showList = false },
            onOpen = { id -> selectedId = id })

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

/* ---------------- Add Product (gallery + camera + upload) ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProductScreen(db: FirebaseFirestore, auth: FirebaseAuth, onClose: () -> Unit) {
    val uid = auth.currentUser?.uid ?: ""
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    // Required
    var title by remember { mutableStateOf("") }
    val categories =
        listOf("Necklace", "Earrings", "Bracelet", "Ring", "Pendant", "Set", "Component")
    val typeCodes = mapOf(
        "Necklace" to "NK",
        "Earrings" to "ER",
        "Bracelet" to "BR",
        "Ring" to "RG",
        "Pendant" to "PD",
        "Set" to "ST",
        "Component" to "CP"
    )
    var category by remember { mutableStateOf(categories.last()) }
    var materialsText by remember { mutableStateOf("") }
    var priceMode by remember { mutableStateOf("manual") } // manual | formula
    var manualPriceText by remember { mutableStateOf("") }

    // Optional helpers (for SKU)
    var metalCode by remember { mutableStateOf("") }
    var gemCode by remember { mutableStateOf("") }

    // Formula fields (optional)
    var materialCostText by remember { mutableStateOf("") }
    var laborHoursText by remember { mutableStateOf("") }
    var hourlyRateText by remember { mutableStateOf("25") }
    var markupText by remember { mutableStateOf("2.2") }

    // --- MULTI-PHOTO STATE ---
    var pickedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var tempPhotoUri by remember { mutableStateOf<Uri?>(null) }

    val pickImages = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            // Append and de-dup
            pickedUris = (pickedUris + uris).distinct()
        }
    }

    val takePhoto = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempPhotoUri != null) {
            pickedUris = pickedUris + tempPhotoUri!!
        }
    }

    var statusMsg by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    fun computePrice(): Double = if (priceMode == "manual") {
        manualPriceText.toDoubleOrNull() ?: 0.0
    } else {
        val mat = materialCostText.toDoubleOrNull() ?: 0.0
        val hrs = laborHoursText.toDoubleOrNull() ?: 0.0
        val rate = hourlyRateText.toDoubleOrNull() ?: 25.0
        val mk = markupText.toDoubleOrNull() ?: 2.2
        kotlin.math.round((mat * mk + hrs * rate) * 100) / 100.0
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Add Product", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            title,
            { title = it },
            label = { Text("Title *") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        // Category (dropdown)
        var catMenu by remember { mutableStateOf(false) }
        Box {
            OutlinedTextField(
                value = category,
                onValueChange = {},
                readOnly = true,
                label = { Text("Category *") },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { catMenu = true })
            DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                categories.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(c) },
                        onClick = { category = c; catMenu = false })
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            materialsText,
            { materialsText = it },
            label = { Text("Materials (comma-separated) *") },
            singleLine = false,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(
                selected = priceMode == "manual",
                onClick = { priceMode = "manual" },
                label = { Text("Manual price") })
            FilterChip(
                selected = priceMode == "formula",
                onClick = { priceMode = "formula" },
                label = { Text("Formula") })
        }
        Spacer(Modifier.height(8.dp))

        if (priceMode == "manual") {
            OutlinedTextField(
                manualPriceText,
                { manualPriceText = it },
                label = { Text("Price (manual) *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            OutlinedTextField(
                materialCostText,
                { materialCostText = it },
                label = { Text("Material cost") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                laborHoursText,
                { laborHoursText = it },
                label = { Text("Labor hours") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    hourlyRateText,
                    { hourlyRateText = it },
                    label = { Text("Hourly rate") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    markupText,
                    { markupText = it },
                    label = { Text("Markup ×") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text("Computed price: $${"%.2f".format(computePrice())}")
        }
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                pickImages.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }) {
                Text("Add photos")
            }

            Button(onClick = {
                val uri = createImageUri(context)
                tempPhotoUri = uri
                takePhoto.launch(uri)
            }) {
                Text("Take photo")
            }

            if (pickedUris.isNotEmpty()) {
                Text("${pickedUris.size} photo(s) selected")
            }
        }


        if (pickedUris.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("${pickedUris.size} photo(s) selected")
            Spacer(Modifier.height(6.dp))
            // thumbnails with remove
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pickedUris.size) { idx ->
                    val u = pickedUris[idx]
                    Column {
                        AsyncImage(
                            model = u,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(96.dp)
                        )
                        OutlinedButton(
                            onClick = {
                                pickedUris = pickedUris.toMutableList().also { it.removeAt(idx) }
                            }, modifier = Modifier.width(96.dp)
                        ) { Text("Remove") }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            enabled = !saving && title.isNotBlank() && materialsText.isNotBlank() && ((priceMode == "manual" && manualPriceText.isNotBlank()) || priceMode == "formula"),
            onClick = {
                saving = true
                statusMsg = "Saving…"

                val typeCode = typeCodes[category] ?: "PD"
                val year = Calendar.getInstance().get(Calendar.YEAR)
                val counterRef = db.collection("counters").document("$year-$typeCode")

                db.runTransaction { tx ->
                    val snap = tx.get(counterRef)
                    val next = (snap.getLong("nextSeq") ?: 1L).toInt()
                    tx.set(counterRef, mapOf("nextSeq" to next + 1), SetOptions.merge())
                    next
                }.addOnSuccessListener { seq ->
                    val sku = generateSku(
                        typeCode = typeCode,
                        metal = metalCode.ifBlank { "NON" },
                        gem = gemCode.ifBlank { "NON" },
                        year = year,
                        seq = seq
                    )
                    val priceComputed = computePrice()

                    val base = hashMapOf(
                        "orgId" to ORG_ID,
                        "type" to "product",
                        "title" to title.trim(),
                        "sku" to sku,
                        "category" to category,
                        "tags" to listOf<String>(),
                        "materials" to materialsText.split(",").map { it.trim() }
                            .filter { it.isNotEmpty() },
                        "gemstones" to listOf<String>(),
                        "metal" to metalCode.ifBlank { null },
                        "gem" to gemCode.ifBlank { null },
                        "status" to "in_progress",
                        "pricing" to mapOf(
                            "mode" to priceMode,
                            "manualPrice" to (if (priceMode == "manual") manualPriceText.toDoubleOrNull() else null),
                            "formula" to mapOf(
                                "materialCost" to (materialCostText.toDoubleOrNull() ?: 0.0),
                                "laborHours" to (laborHoursText.toDoubleOrNull() ?: 0.0),
                                "hourlyRate" to (hourlyRateText.toDoubleOrNull() ?: 25.0),
                                "markup" to (markupText.toDoubleOrNull() ?: 2.2)
                            )
                        ),
                        "priceComputed" to priceComputed,
                        "availableForSale" to true,
                        "stock" to 1,
                        "createdAt" to System.currentTimeMillis(),
                        "updatedAt" to System.currentTimeMillis(),
                        "ownerUid" to uid
                    )

                    db.collection("items").add(base).addOnSuccessListener { ref ->
                        val uris = pickedUris
                        if (uris.isEmpty()) {
                            statusMsg = "Saved: ${ref.id}    SKU: $sku"
                            saving = false
                            return@addOnSuccessListener
                        }
                        // upload all photos sequentially
                        val uploaded = mutableListOf<Map<String, Any>>()

                        fun uploadNext(i: Int) {
                            if (i >= uris.size) {
                                // write photos array
                                ref.update(
                                    mapOf(
                                        "photos" to uploaded,
                                        "updatedAt" to System.currentTimeMillis()
                                    )
                                ).addOnSuccessListener {
                                    statusMsg = "Saved + ${uploaded.size} photo(s) ✔"
                                    saving = false
                                }.addOnFailureListener { e ->
                                    statusMsg = "Saved but linking photos failed: ${e.message}"
                                    saving = false
                                }
                                return
                            }
                            try {
                                val (bytes, size) = compressForUpload(
                                    contentResolver = contentResolver,
                                    uri = uris[i],
                                    longEdge = 1600,
                                    jpegQuality = 88
                                )
                                val path = "images/$uid/items/${ref.id}/${i + 1}.jpg"
                                val objRef = FirebaseStorage.getInstance().reference.child(path)
                                objRef.putBytes(bytes).addOnSuccessListener {
                                        objRef.downloadUrl.addOnSuccessListener { dl ->
                                            uploaded += mapOf(
                                                "cloudUrl" to dl.toString(),
                                                "w" to size.first,
                                                "h" to size.second,
                                                "index" to (i + 1)
                                            )
                                            uploadNext(i + 1)
                                        }
                                    }.addOnFailureListener { e ->
                                        statusMsg = "Saved but photo ${i + 1} failed: ${e.message}"
                                        saving = false
                                    }
                            } catch (e: Exception) {
                                statusMsg = "Saved but reading photo ${i + 1} failed: ${e.message}"
                                saving = false
                            }
                        }

                        uploadNext(0)
                    }.addOnFailureListener { e ->
                        statusMsg = "Save error: ${e.message}"
                        saving = false
                    }
                }.addOnFailureListener { e ->
                    statusMsg = "Counter error: ${e.message}"
                    saving = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (saving) "Saving…" else "Save") }

        Spacer(Modifier.height(12.dp))
        Text(statusMsg)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}


/* ---------------- Items List ---------------- */

@Composable
fun ItemsListScreen(
    db: FirebaseFirestore, onBack: () -> Unit, onOpen: (String) -> Unit
) {
    var rows by remember { mutableStateOf<List<Map<String, Any?>>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        db.collection("items").orderBy("createdAt", Query.Direction.DESCENDING).limit(100).get()
            .addOnSuccessListener { snap ->
                rows = snap.documents.map { d ->
                    val photos = d.get("photos") as? List<*>
                    val thumb = (photos?.firstOrNull() as? Map<*, *>)?.get("cloudUrl") as? String
                    mapOf(
                        "id" to d.id,
                        "title" to (d.getString("title") ?: ""),
                        "sku" to (d.getString("sku") ?: ""),
                        "price" to (d.getDouble("priceComputed") ?: 0.0),
                        "thumb" to thumb,
                        "createdAt" to (d.getLong("createdAt") ?: 0L)
                    )
                }
                loading = false
            }.addOnFailureListener { e ->
                error = e.message
                loading = false
            }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Items", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.height(8.dp))

        when {
            loading -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            error != null -> {
                Text("Error: $error", color = MaterialTheme.colorScheme.error)
            }

            else -> {
                val list = rows ?: emptyList()
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(list) { row ->
                        val title = row["title"] as String
                        val sku = row["sku"] as String
                        val price = row["price"] as Double
                        val thumb = row["thumb"] as? String
                        val id = row["id"] as String

                        Row(Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(id) }
                            .padding(vertical = 6.dp)) {
                            if (thumb != null) {
                                AsyncImage(
                                    model = thumb,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(56.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.titleMedium)
                                Text("SKU: $sku — $${"%.2f".format(price)}")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/* ---------------- Item Details (minimal) ---------------- */

@Composable
fun ItemDetailScreen(
    db: FirebaseFirestore, id: String, onBack: () -> Unit, onEdit: () -> Unit
) {
    var data by remember { mutableStateOf<Map<String, Any>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // delete dialog state
    var showConfirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteMsg by remember { mutableStateOf<String?>(null) }

    // Load the document
    LaunchedEffect(id) {
        db.collection("items").document(id).get().addOnSuccessListener { snap ->
                data = snap.data
                loading = false
            }.addOnFailureListener { e ->
                error = e.message
                loading = false
            }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header with Back + Edit + Delete
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) { Text("Edit") }
                OutlinedButton(
                    enabled = !deleting && !loading && error == null,
                    onClick = { showConfirmDelete = true }) { Text("Delete") }
            }
        }
        Spacer(Modifier.height(8.dp))

        when {
            loading -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            error != null -> {
                Text("Error: $error", color = MaterialTheme.colorScheme.error)
            }

            else -> {
                val d = data ?: emptyMap()
                val title = (d["title"] as? String).orEmpty()
                val sku = (d["sku"] as? String).orEmpty()
                val category = (d["category"] as? String).orEmpty()
                val price = (d["priceComputed"] as? Double) ?: 0.0
                val materials = (d["materials"] as? List<*>)?.joinToString(", ") ?: ""
                val ownerUid = (d["ownerUid"] as? String).orEmpty()

                Text("Item Details", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (sku.isNotBlank()) Text("SKU: $sku")
                if (category.isNotBlank()) Text("Category: $category")
                Text("Price: $${"%.2f".format(price)}")
                if (materials.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Materials:")
                    Text(materials)
                }

                if (deleteMsg != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(deleteMsg!!)
                }

                // --- Confirm Delete Dialog ---
                if (showConfirmDelete) {
                    AlertDialog(
                        onDismissRequest = { if (!deleting) showConfirmDelete = false },
                        title = { Text("Delete item?") },
                        text = { Text("This will permanently delete the item and its photo(s).") },
                        confirmButton = {
                            TextButton(
                                enabled = !deleting, onClick = {
                                    // Perform deletion
                                    deleting = true
                                    deleteMsg = "Deleting…"

                                    // 1) delete the document
                                    db.collection("items").document(id).delete()
                                        .addOnSuccessListener {
                                            // 2) best-effort: delete Storage folder images/<uid>/items/<id>/
                                            if (ownerUid.isNotBlank()) {
                                                val base =
                                                    com.google.firebase.storage.FirebaseStorage.getInstance().reference.child(
                                                            "images/$ownerUid/items/$id"
                                                        )

                                                base.listAll().addOnSuccessListener { list ->
                                                        // delete all files in folder
                                                        val ops = list.items.map { it.delete() }
                                                        if (ops.isEmpty()) {
                                                            deleteMsg = "Deleted ✔"
                                                            deleting = false
                                                            showConfirmDelete = false
                                                            onBack() // go back to list
                                                        } else {
                                                            // when last file finishes, return
                                                            var remaining = ops.size
                                                            ops.forEach { t ->
                                                                t.addOnCompleteListener {
                                                                    remaining--
                                                                    if (remaining == 0) {
                                                                        deleteMsg = "Deleted ✔"
                                                                        deleting = false
                                                                        showConfirmDelete = false
                                                                        onBack()
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }.addOnFailureListener {
                                                        // storage cleanup failed, but doc is gone
                                                        deleteMsg =
                                                            "Deleted (photos may remain in Storage)"
                                                        deleting = false
                                                        showConfirmDelete = false
                                                        onBack()
                                                    }
                                            } else {
                                                deleteMsg = "Deleted ✔"
                                                deleting = false
                                                showConfirmDelete = false
                                                onBack()
                                            }
                                        }.addOnFailureListener { e ->
                                            deleteMsg = "Delete failed: ${e.message}"
                                            deleting = false
                                            showConfirmDelete = false
                                        }
                                }) { Text(if (deleting) "Deleting…" else "Delete") }
                        },
                        dismissButton = {
                            TextButton(
                                enabled = !deleting,
                                onClick = { showConfirmDelete = false }) { Text("Cancel") }
                        })
                }
            }
        }
    }
}


/* ---------------- Edit Item (Title, Category, Materials, Price) ---------------- */

@Composable
fun EditItemScreen(
    db: FirebaseFirestore, id: String, onBack: () -> Unit, onSaved: () -> Unit
) {
    // ---- shared stuff ----
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

    // ---- editable text fields ----
    var title by remember { mutableStateOf("") }

    // category + materials
    val categories =
        listOf("Necklace", "Earrings", "Bracelet", "Ring", "Pendant", "Set", "Component")
    var category by remember { mutableStateOf("") }
    var catMenu by remember { mutableStateOf(false) }
    var materialsText by remember { mutableStateOf("") } // comma-separated

    // pricing
    var priceMode by remember { mutableStateOf("manual") } // "manual" | "formula"
    var manualPriceText by remember { mutableStateOf("") }

    // (optional formula fields, shown only if you choose to use later)
    var materialCostText by remember { mutableStateOf("") }
    var laborHoursText by remember { mutableStateOf("") }
    var hourlyRateText by remember { mutableStateOf("25") }
    var markupText by remember { mutableStateOf("2.2") }

    // ---- photo state / launchers ----
    var currentPhotoUrl by remember { mutableStateOf<String?>(null) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var tempPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var photoUploading by remember { mutableStateOf(false) }
    var photoMsg by remember { mutableStateOf<String?>(null) }

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> pickedUri = uri }

    val takePhoto = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success -> if (success) pickedUri = tempPhotoUri }

    // ---- general ui state ----
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saveMsg by remember { mutableStateOf<String?>(null) }

    // ---- load existing item ----
    LaunchedEffect(id) {
        db.collection("items").document(id).get().addOnSuccessListener { doc ->
                val d = doc.data ?: emptyMap()

                title = (d["title"] as? String).orEmpty()
                category = (d["category"] as? String).orEmpty()

                // materials array -> comma text
                val mats = (d["materials"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                materialsText = mats.joinToString(", ")

                // pricing
                val pricing = d["pricing"] as? Map<*, *>
                priceMode = (pricing?.get("mode") as? String) ?: "manual"
                manualPriceText = ((pricing?.get("manualPrice") as? Number)?.toDouble()
                    ?: (d["priceComputed"] as? Number)?.toDouble() ?: 0.0).let { "%.2f".format(it) }

                // (optional formula – we load if they exist)
                materialCostText =
                    ((pricing?.get("formula") as? Map<*, *>)?.get("materialCost") as? Number)?.toString()
                        ?: ""
                laborHoursText =
                    ((pricing?.get("formula") as? Map<*, *>)?.get("laborHours") as? Number)?.toString()
                        ?: ""
                hourlyRateText =
                    ((pricing?.get("formula") as? Map<*, *>)?.get("hourlyRate") as? Number)?.toString()
                        ?: "25"
                markupText =
                    ((pricing?.get("formula") as? Map<*, *>)?.get("markup") as? Number)?.toString()
                        ?: "2.2"

                // photo
                val photos = d["photos"] as? List<*>
                currentPhotoUrl = (photos?.firstOrNull() as? Map<*, *>)?.get("cloudUrl") as? String

                loading = false
            }.addOnFailureListener { e ->
                error = e.message
                loading = false
            }
    }

    fun computeFormulaPrice(): Double {
        val mat = materialCostText.toDoubleOrNull() ?: 0.0
        val hrs = laborHoursText.toDoubleOrNull() ?: 0.0
        val rate = hourlyRateText.toDoubleOrNull() ?: 25.0
        val mk = markupText.toDoubleOrNull() ?: 2.2
        return kotlin.math.round((mat * mk + hrs * rate) * 100) / 100.0
    }

    fun uploadNewPhoto(uri: Uri) {
        if (uid.isBlank()) {
            photoMsg = "You must be signed in."; return
        }
        photoUploading = true; photoMsg = "Uploading…"
        try {
            val (bytes, size) = compressForUpload(
                contentResolver, uri, longEdge = 1600, jpegQuality = 88
            )
            val path = "images/$uid/items/$id/1.jpg"
            val storageRef = FirebaseStorage.getInstance().reference.child(path)
            storageRef.putBytes(bytes).addOnSuccessListener {
                    storageRef.downloadUrl.addOnSuccessListener { dl ->
                        val photos = listOf(
                            mapOf(
                                "cloudUrl" to dl.toString(), "w" to size.first, "h" to size.second
                            )
                        )
                        db.collection("items").document(id).update(
                                mapOf(
                                    "photos" to photos, "updatedAt" to System.currentTimeMillis()
                                )
                            ).addOnSuccessListener {
                                currentPhotoUrl = dl.toString()
                                pickedUri = null
                                photoUploading = false
                                photoMsg = "Photo replaced ✔"
                            }.addOnFailureListener { e ->
                                photoUploading = false
                                photoMsg = "Upload ok, link failed: ${e.message}"
                            }
                    }
                }.addOnFailureListener { e ->
                    photoUploading = false
                    photoMsg = "Upload failed: ${e.message}"
                }
        } catch (e: Exception) {
            photoUploading = false
            photoMsg = "Image read failed: ${e.message}"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Edit Item", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.height(8.dp))

        when {
            loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            error != null -> Text("Error: $error", color = MaterialTheme.colorScheme.error)
            else -> {
                // --- Photo preview + actions ---
                val previewModel = pickedUri ?: currentPhotoUrl
                if (previewModel != null) {
                    AsyncImage(
                        model = previewModel,
                        contentDescription = "photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) { Text("Replace photo") }
                    OutlinedButton(onClick = {
                        val u = createImageUri(context)
                        tempPhotoUri = u
                        takePhoto.launch(u)
                    }) { Text("Take new photo") }
                }
                if (pickedUri != null) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = !photoUploading,
                        onClick = { uploadNewPhoto(pickedUri!!) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (photoUploading) "Uploading…" else "Upload new photo") }
                }
                if (!photoMsg.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(photoMsg!!)
                }

                Spacer(Modifier.height(16.dp))

                // --- Title ---
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                // --- Category dropdown ---
                Box {
                    OutlinedTextField(
                        value = if (category.isBlank()) "(select)" else category,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { catMenu = true })
                    DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                        categories.forEach { c ->
                            DropdownMenuItem(text = { Text(c) }, onClick = {
                                category = c; catMenu = false
                            })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // --- Materials (comma-separated) ---
                OutlinedTextField(
                    value = materialsText,
                    onValueChange = { materialsText = it },
                    label = { Text("Materials (comma-separated)") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                // --- Pricing mode toggle ---
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = priceMode == "manual",
                        onClick = { priceMode = "manual" },
                        label = { Text("Manual price") })
                    FilterChip(
                        selected = priceMode == "formula",
                        onClick = { priceMode = "formula" },
                        label = { Text("Formula") })
                }
                Spacer(Modifier.height(8.dp))

                if (priceMode == "manual") {
                    OutlinedTextField(
                        value = manualPriceText,
                        onValueChange = { manualPriceText = it },
                        label = { Text("Price (manual)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    // keep them simple for now (not required to save)
                    OutlinedTextField(
                        materialCostText,
                        { materialCostText = it },
                        label = { Text("Material cost") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        laborHoursText,
                        { laborHoursText = it },
                        label = { Text("Labor hours") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            hourlyRateText,
                            { hourlyRateText = it },
                            label = { Text("Hourly rate") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            markupText,
                            { markupText = it },
                            label = { Text("Markup ×") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Computed: $${"%.2f".format(computeFormulaPrice())}")
                }

                Spacer(Modifier.height(16.dp))

                // --- Save button ---
                Button(
                    enabled = !saving && title.isNotBlank(), onClick = {
                        saving = true; saveMsg = "Saving…"

                        // Build updates map
                        val updates = mutableMapOf<String, Any?>(
                            "title" to title.trim(),
                            "category" to category.ifBlank { null },
                            "materials" to materialsText.split(',').map { it.trim() }
                                .filter { it.isNotEmpty() },
                            "updatedAt" to System.currentTimeMillis()
                        )

                        val pricing = mutableMapOf<String, Any?>("mode" to priceMode)
                        if (priceMode == "manual") {
                            val mp = manualPriceText.toDoubleOrNull()
                            pricing["manualPrice"] = mp
                            if (mp != null) updates["priceComputed"] = mp
                        } else {
                            val formula = mapOf(
                                "materialCost" to (materialCostText.toDoubleOrNull() ?: 0.0),
                                "laborHours" to (laborHoursText.toDoubleOrNull() ?: 0.0),
                                "hourlyRate" to (hourlyRateText.toDoubleOrNull() ?: 25.0),
                                "markup" to (markupText.toDoubleOrNull() ?: 2.2)
                            )
                            pricing["formula"] = formula
                            updates["priceComputed"] = computeFormulaPrice()
                        }
                        updates["pricing"] = pricing

                        db.collection("items").document(id).update(updates).addOnSuccessListener {
                                saveMsg = "Saved ✔"
                                saving = false
                                onSaved()
                            }.addOnFailureListener { e ->
                                saveMsg = "Save failed: ${e.message}"
                                saving = false
                            }
                    }, modifier = Modifier.fillMaxWidth()
                ) { Text(if (saving) "Saving…" else "Save") }

                if (!saveMsg.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(saveMsg!!)
                }
            }
        }
    }
}


/* ---------------- Helpers (top-level) ---------------- */

suspend fun compressUriIO(
    resolver: ContentResolver,
    uri: Uri,
    longEdge: Int = 1600,
    jpegQuality: Int = 88
): Pair<ByteArray, Pair<Int, Int>> = withContext(Dispatchers.IO) {
    compressForUpload(resolver, uri, longEdge, jpegQuality)
}

fun generateSku(typeCode: String, metal: String?, gem: String?, year: Int, seq: Int): String {
    val yy = (year % 100).toString().padStart(2, '0')
    val seqStr = seq.toString().padStart(4, '0')
    val m = (metal ?: "NON").uppercase(Locale.US)
    val g = (gem ?: "NON").uppercase(Locale.US)
    return "$typeCode-$m-$g-$yy-$seqStr"
}

fun compressForUpload(
    contentResolver: ContentResolver, uri: Uri, longEdge: Int = 1600, jpegQuality: Int = 88
): Pair<ByteArray, Pair<Int, Int>> {
    contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Cannot open image stream" }
        val original = BitmapFactory.decodeStream(input)
        val w = original.width
        val h = original.height
        val scale = if (w >= h) longEdge.toFloat() / w else longEdge.toFloat() / h
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        val scaled = original.scale(newW, newH, true)
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, bos)
        return Pair(bos.toByteArray(), Pair(newW, newH))
    }
}

fun createImageUri(context: Context): Uri {
    val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
    if (!dir.exists()) dir.mkdirs()
    val file = File(dir, "AI_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
}

private fun deleteItemAndPhotos(
    db: FirebaseFirestore,
    docId: String,
    photoUrls: List<String>,
    done: (ok: Boolean, message: String) -> Unit
) {
    // Best-effort delete photos first
    val storage = FirebaseStorage.getInstance()
    if (photoUrls.isNotEmpty()) {
        var remaining = photoUrls.size
        var anyFail = false
        photoUrls.forEach { url ->
            try {
                storage.getReferenceFromUrl(url).delete().addOnCompleteListener {
                        if (!it.isSuccessful) anyFail = true
                        remaining -= 1
                        if (remaining == 0) {
                            // Now delete Firestore document
                            db.collection("items").document(docId).delete().addOnSuccessListener {
                                    val note =
                                        if (anyFail) "Item deleted, some photos could not be removed."
                                        else "Item and photos deleted."
                                    done(true, note)
                                }.addOnFailureListener { e ->
                                    done(false, "Failed deleting item: ${e.message}")
                                }
                        }
                    }
            } catch (e: Exception) {
                // Malformed URL or other issue; count as a failure but keep going
                anyFail = true
                remaining -= 1
                if (remaining == 0) {
                    db.collection("items").document(docId).delete().addOnSuccessListener {
                            val note = "Item deleted, some photos could not be removed."
                            done(true, note)
                        }.addOnFailureListener { ex ->
                            done(false, "Failed deleting item: ${ex.message}")
                        }
                }
            }
        }
    } else {
        // No photos; just delete the doc
        db.collection("items").document(docId).delete()
            .addOnSuccessListener { done(true, "Item deleted.") }
            .addOnFailureListener { e -> done(false, "Failed deleting item: ${e.message}") }
    }
}

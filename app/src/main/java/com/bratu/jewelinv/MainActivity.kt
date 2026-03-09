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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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

/* ---------------- constants ---------------- */

private const val ORG_ID = "bratu-studio"

private val categoryOptions = listOf("All", "Necklace", "Earrings", "Bracelet", "Ring", "Pendant", "Set", "Component")

data class Item(
    val name: String,
    val category: String,
    val price: Double,
    val status: String,
    val notes: String
)

/* ---------------- activity ---------------- */

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

/* ---------------- Home / simple nav ---------------- */

private enum class Screen { HOME, LIST, DETAIL, EDIT, ADD }

@Composable
fun HomeScreen(onSignOut: () -> Unit, db: FirebaseFirestore, auth: FirebaseAuth) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val localItems = remember { mutableStateListOf<Item>() }

    when (screen) {
        Screen.ADD -> AddItemScreen(
            onClose = { screen = Screen.HOME },
            onSave = { item ->
                localItems.add(item)
                screen = Screen.LIST
            }
        )

        Screen.EDIT -> EditItemScreen(
            db = db, id = selectedId!!,
            onBack = { screen = Screen.DETAIL },
            onSaved = { screen = Screen.DETAIL }
        )

        Screen.DETAIL -> ItemDetailScreen(
            db = db, id = selectedId!!,
            onBack = { screen = Screen.LIST },
            onEdit = { screen = Screen.EDIT }
        )

        Screen.LIST -> ItemsListScreen(
            db = db,
            localItems = localItems,
            onBack = { screen = Screen.HOME },
            onOpen = { id -> selectedId = id; screen = Screen.DETAIL }
        )

        Screen.HOME -> {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("ArtisticalInventory", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))

                Button(onClick = { screen = Screen.ADD }, modifier = Modifier.fillMaxWidth()) {
                    Text("Add Product")
                }
                Spacer(Modifier.height(8.dp))

                OutlinedButton(onClick = { screen = Screen.LIST }, modifier = Modifier.fillMaxWidth()) {
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddItemScreen(onClose: () -> Unit, onSave: (Item) -> Unit) {
    var itemName by rememberSaveable { mutableStateOf("") }
    val addableCategories = categoryOptions
    var category by rememberSaveable { mutableStateOf(addableCategories.firstOrNull() ?: "") }
    var priceText by rememberSaveable { mutableStateOf("") }
    val statusOptions = listOf("Available", "Sold", "Reserved")
    var status by rememberSaveable { mutableStateOf(statusOptions.first()) }
    var notes by rememberSaveable { mutableStateOf("") }

    var categoryMenuOpen by remember { mutableStateOf(false) }
    var statusMenuOpen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Add Item", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = itemName,
            onValueChange = { itemName = it },
            label = { Text("Item name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        ExposedDropdownMenuBox(
            expanded = categoryMenuOpen,
            onExpandedChange = { categoryMenuOpen = !categoryMenuOpen }
        ) {
            OutlinedTextField(
                value = category,
                onValueChange = {},
                readOnly = true,
                label = { Text("Category") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenuOpen) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = categoryMenuOpen,
                onDismissRequest = { categoryMenuOpen = false }
            ) {
                addableCategories.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            category = option
                            categoryMenuOpen = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = priceText,
            onValueChange = { priceText = it },
            label = { Text("Price") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        ExposedDropdownMenuBox(
            expanded = statusMenuOpen,
            onExpandedChange = { statusMenuOpen = !statusMenuOpen }
        ) {
            OutlinedTextField(
                value = status,
                onValueChange = {},
                readOnly = true,
                label = { Text("Status") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = statusMenuOpen) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = statusMenuOpen,
                onDismissRequest = { statusMenuOpen = false }
            ) {
                statusOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            status = option
                            statusMenuOpen = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
        )

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                val newItem = Item(
                    name = itemName.trim(),
                    category = category,
                    price = priceText.toDoubleOrNull() ?: 0.0,
                    status = status,
                    notes = notes.trim()
                )
                onSave(newItem)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save")
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}
/* ------------- Add Product ------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProductScreen(db: FirebaseFirestore, auth: FirebaseAuth, onClose: () -> Unit) {
    val uid = auth.currentUser?.uid ?: ""
    val context = LocalContext.current
    val resolver = context.contentResolver

    // Required
    var title by remember { mutableStateOf("") }
    val categories = listOf("Necklace","Earrings","Bracelet","Ring","Pendant","Set","Component")
    val typeCodes = mapOf(
        "Necklace" to "NK", "Earrings" to "ER", "Bracelet" to "BR",
        "Ring" to "RG", "Pendant" to "PD", "Set" to "ST", "Component" to "CP"
    )
    var category by remember { mutableStateOf(categories.last()) }
    var materialsText by remember { mutableStateOf("") }
    var priceMode by remember { mutableStateOf("manual") }
    var manualPriceText by remember { mutableStateOf("") }

    // Optional helpers (for SKU)
    var metalCode by remember { mutableStateOf("") } // e.g., SIL
    var gemCode by remember { mutableStateOf("") }   // e.g., CRY

    // Formula fields (optional)
    var materialCostText by remember { mutableStateOf("") }
    var laborHoursText by remember { mutableStateOf("") }
    var hourlyRateText by remember { mutableStateOf("25") }
    var markupText by remember { mutableStateOf("2.2") }

    // One photo (first)
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("Add Product", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(title, { title = it }, label = { Text("Title *") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))

        // Category dropdown (tap field to open)
        var catMenu by remember { mutableStateOf(false) }
        Box {
            OutlinedTextField(
                value = category,
                onValueChange = {},
                readOnly = true,
                label = { Text("Category *") },
                modifier = Modifier.fillMaxWidth().clickable { catMenu = true }
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

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = priceMode == "manual", onClick = { priceMode = "manual" }, label = { Text("Manual price") })
            FilterChip(selected = priceMode == "formula", onClick = { priceMode = "formula" }, label = { Text("Formula") })
        }
        Spacer(Modifier.height(8.dp))

        if (priceMode == "manual") {
            OutlinedTextField(
                manualPriceText, { manualPriceText = it },
                label = { Text("Price (manual) *") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            OutlinedTextField(materialCostText, { materialCostText = it }, label = { Text("Material cost") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(laborHoursText, { laborHoursText = it }, label = { Text("Labor hours") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(hourlyRateText, { hourlyRateText = it }, label = { Text("Hourly rate") },
                    singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(markupText, { markupText = it }, label = { Text("Markup ×") },
                    singleLine = true, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Text("Computed price: $${"%.2f".format(computePrice())}")
        }
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(metalCode, { metalCode = it }, label = { Text("Metal code (e.g., SIL)") },
                singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(gemCode, { gemCode = it }, label = { Text("Gem code (e.g., CRY)") },
                singleLine = true, modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Add photo") }

            Button(onClick = {
                val uri = createImageUri(context)
                tempPhotoUri = uri
                takePhoto.launch(uri)
            }) { Text("Take photo") }

            if (pickedUri != null) Text("1 photo selected")
        }
        Spacer(Modifier.height(8.dp))
        if (pickedUri != null) {
            AsyncImage(
                model = pickedUri,
                contentDescription = "preview",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(180.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        Button(
            enabled = !saving && title.isNotBlank() && materialsText.isNotBlank() &&
                    ((priceMode == "manual" && manualPriceText.isNotBlank()) || priceMode == "formula"),
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
                        "materials" to materialsText.split(",").map { it.trim() }.filter { it.isNotEmpty() },
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
                        val uri = pickedUri
                        if (uri == null) {
                            statusMsg = "Saved: ${ref.id}    SKU: $sku"
                            saving = false
                            return@addOnSuccessListener
                        }
                        try {
                            val (bytes, size) = compressForUpload(
                                contentResolver = resolver,
                                uri = uri, longEdge = 1600, jpegQuality = 88
                            )
                            val path = "images/$uid/items/${ref.id}/1.jpg"
                            FirebaseStorage.getInstance().reference.child(path)
                                .putBytes(bytes)
                                .addOnSuccessListener {
                                    FirebaseStorage.getInstance().reference.child(path)
                                        .downloadUrl.addOnSuccessListener { dl ->
                                            val photos = listOf(
                                                mapOf("cloudUrl" to dl.toString(), "w" to size.first, "h" to size.second)
                                            )
                                            ref.update(mapOf("photos" to photos, "updatedAt" to System.currentTimeMillis()))
                                                .addOnSuccessListener {
                                                    statusMsg = "Saved: ${ref.id}  SKU: $sku  + Photo uploaded ✔"
                                                    saving = false
                                                }
                                                .addOnFailureListener { e ->
                                                    statusMsg = "Saved but photo link failed: ${e.message}"
                                                    saving = false
                                                }
                                        }
                                }
                                .addOnFailureListener { e ->
                                    statusMsg = "Saved but photo upload failed: ${e.message}"
                                    saving = false
                                }
                        } catch (e: Exception) {
                            statusMsg = "Saved but image read failed: ${e.message}"
                            saving = false
                        }
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

/* ---------------- Items List (search + back) ---------------- */

/* ---------------- Items List (search + filters) ---------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemsListScreen(
    db: FirebaseFirestore,
    localItems: List<Item>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit
) {
    var rows by remember { mutableStateOf<List<Map<String, Any?>>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Search text
    var searchText by rememberSaveable { mutableStateOf("") }

    // Category filter
    var activeCategory by rememberSaveable { mutableStateOf(categoryOptions.first()) }

    // Sort options
    val sortOptions = listOf("Newest", "Oldest", "Price ↑", "Price ↓")
    var sortMode by rememberSaveable { mutableStateOf("Newest") }

    // Price filter options
    val priceFilterOptions = listOf("All prices", "< \$50", "\$50–\$200", "> \$200")
    var priceFilterMode by rememberSaveable { mutableStateOf("All prices") }

    // Status filter options
    val statusFilterOptions = listOf("All statuses", "Available", "Reserved", "Sold")
    var statusFilterMode by rememberSaveable { mutableStateOf("All statuses") }

    // Load items once
    LaunchedEffect(Unit) {
        db.collection("items")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(500)
            .get()
            .addOnSuccessListener { snap ->
                rows = snap.documents.map { d ->
                    val photos = d.get("photos") as? List<*>
                    val thumb = (photos?.firstOrNull() as? Map<*, *>)?.get("cloudUrl") as? String
                    mapOf(
                        "id" to d.id,
                        "title" to (d.getString("title") ?: ""),
                        "sku" to (d.getString("sku") ?: ""),
                        "price" to (d.getDouble("priceComputed") ?: 0.0),
                        "category" to (d.getString("category") ?: ""),
                        "status" to (d.getString("status") ?: ""),
                        "thumb" to thumb,
                        "createdAt" to (d.getLong("createdAt") ?: 0L)
                    )
                }
                loading = false
            }
            .addOnFailureListener { e ->
                error = e.message
                loading = false
            }
    }

    // Apply search, category, status, price filter, and sort
    val displayedItems = remember(
        rows,
        localItems,
        searchText,
        activeCategory,
        sortMode,
        priceFilterMode,
        statusFilterMode
    ) {
        val remoteBase = rows ?: emptyList()
        val localRows = localItems.mapIndexed { index, item ->
            mapOf(
                "id" to "local-$index-${item.name}",
                "title" to item.name,
                "sku" to "LOCAL",
                "price" to item.price,
                "category" to item.category,
                "status" to item.status,
                "thumb" to null,
                "createdAt" to Long.MAX_VALUE - index
            )
        }
        val base = localRows + remoteBase

        val statusCode: String? = when (statusFilterMode) {
            "Available" -> "available"
            "Reserved" -> "reserved"
            "Sold" -> "sold"
            else -> null
        }

        val filtered = base.filter { row ->
            val title = (row["title"] as? String) ?: ""
            val sku = (row["sku"] as? String) ?: ""
            val category = (row["category"] as? String) ?: ""
            val statusRaw = (row["status"] as? String) ?: ""

            val statusNorm = when (statusRaw.lowercase()) {
                "in_progress" -> "available"
                "" -> "available"
                else -> statusRaw.lowercase()
            }

            val matchesSearch =
                searchText.isBlank() ||
                        title.contains(searchText, ignoreCase = true) ||
                        sku.contains(searchText, ignoreCase = true) ||
                        category.contains(searchText, ignoreCase = true)

            val matchesCategory =
                activeCategory == "All" || category.equals(activeCategory, ignoreCase = true)

            val matchesStatus =
                statusCode == null || statusNorm.equals(statusCode, ignoreCase = true)

            matchesSearch && matchesCategory && matchesStatus
        }

        val priceFiltered = filtered.filter { row ->
            val price = (row["price"] as? Double) ?: 0.0
            when (priceFilterMode) {
                "< \$50" -> price < 50.0
                "\$50–\$200" -> price in 50.0..200.0
                "> \$200" -> price > 200.0
                else -> true
            }
        }

        when (sortMode) {
            "Newest" -> priceFiltered.sortedByDescending { (it["createdAt"] as? Long) ?: 0L }
            "Oldest" -> priceFiltered.sortedBy { (it["createdAt"] as? Long) ?: 0L }
            "Price ↑" -> priceFiltered.sortedBy { (it["price"] as? Double) ?: 0.0 }
            "Price ↓" -> priceFiltered.sortedByDescending { (it["price"] as? Double) ?: 0.0 }
            else -> priceFiltered
        }
    }

    val totalCount = (rows?.size ?: 0) + localItems.size
    val visibleCount = displayedItems.size
    val listBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentPadding = PaddingValues(bottom = listBottomPadding)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Items", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }

        item {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search") },
                singleLine = true
            )
        }

        item { Spacer(Modifier.height(8.dp)) }

        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categoryOptions.forEach { cat ->
                    FilterChip(
                        selected = activeCategory == cat,
                        onClick = { activeCategory = cat },
                        modifier = Modifier.width(IntrinsicSize.Max),
                        label = { Text(cat, maxLines = 1, softWrap = false) }
                    )
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }

        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                priceFilterOptions.forEach { mode ->
                    FilterChip(
                        selected = priceFilterMode == mode,
                        onClick = { priceFilterMode = mode },
                        modifier = Modifier.wrapContentWidth(),
                        label = { Text(mode) }
                    )
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }

        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                statusFilterOptions.forEach { mode ->
                    FilterChip(
                        selected = statusFilterMode == mode,
                        onClick = { statusFilterMode = mode },
                        modifier = Modifier.wrapContentWidth(),
                        label = { Text(mode) }
                    )
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }

        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sortOptions.forEach { mode ->
                    FilterChip(
                        selected = sortMode == mode,
                        onClick = { sortMode = mode },
                        modifier = Modifier.wrapContentWidth(),
                        label = { Text(mode) }
                    )
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Showing $visibleCount of $totalCount items",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(
                    onClick = {
                        searchText = ""
                        activeCategory = "All"
                        priceFilterMode = "All prices"
                        statusFilterMode = "All statuses"
                        sortMode = "Newest"
                    }
                ) {
                    Text("Clear filters")
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }

        when {
            loading -> {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }

            error != null -> {
                item { Text("Error: $error", color = MaterialTheme.colorScheme.error) }
            }

            else -> {
                items(displayedItems) { row ->
                    val title = row["title"] as String
                    val sku = row["sku"] as String
                    val price = row["price"] as Double
                    val thumb = row["thumb"] as? String
                    val category = row["category"] as String
                    val statusRaw = (row["status"] as? String) ?: ""
                    val id = row["id"] as String

                    val statusLabel = when (statusRaw.lowercase()) {
                        "sold" -> "Sold"
                        "reserved" -> "Reserved"
                        "available", "" -> "Available"
                        "in_progress" -> "Available"
                        else -> statusRaw
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(id) }
                            .padding(vertical = 6.dp)
                    ) {
                        if (thumb != null) {
                            AsyncImage(
                                model = thumb,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text("SKU: $sku - $${"%.2f".format(price)}")
                            Text("Category: $category")
                            Text("Status: $statusLabel")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
/* ---------------- Item Detail (minimal) ---------------- */

@Composable
fun ItemDetailScreen(
    db: FirebaseFirestore,
    id: String,
    onBack: () -> Unit,
    onEdit: () -> Unit
) {
    var data by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        db.collection("items").document(id).get()
            .addOnSuccessListener { snap -> data = snap.data; loading = false }
            .addOnFailureListener { e -> error = e.message; loading = false }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Item Details", style = MaterialTheme.typography.titleLarge)
            Row {
                OutlinedButton(onClick = onBack) { Text("Back") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onEdit) { Text("Edit") }
            }
        }
        Spacer(Modifier.height(8.dp))

        when {
            loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            error != null -> Text("Error: $error", color = MaterialTheme.colorScheme.error)
            else -> {
                val d = data ?: return@Column
                Text(d["title"] as? String ?: "")
                Text("SKU: ${d["sku"] ?: ""}")
                Text("Category: ${d["category"] ?: ""}")
                Text("Price: $${"%.2f".format((d["priceComputed"] as? Double) ?: 0.0)}")
                Text("Materials:\n${(d["materials"] as? List<*>)?.joinToString() ?: ""}")
            }
        }
    }
}

/* ---------------- Edit Item (title & price quick) ---------------- */

@Composable
fun EditItemScreen(
    db: FirebaseFirestore,
    id: String,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var saveMsg by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        db.collection("items").document(id).get()
            .addOnSuccessListener { doc ->
                val d = doc.data ?: emptyMap()
                title = (d["title"] as? String) ?: ""
                priceText = ((d["priceComputed"] as? Double) ?: 0.0).let { "%.2f".format(it) }
                loading = false
            }
            .addOnFailureListener { e -> error = e.message; loading = false }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Edit Item", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.height(8.dp))

        when {
            loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            error != null -> Text("Error: $error", color = MaterialTheme.colorScheme.error)
            else -> {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Title") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = priceText, onValueChange = { priceText = it },
                    label = { Text("Price (computed)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                Button(
                    enabled = !saving && title.isNotBlank(),
                    onClick = {
                        saving = true
                        saveMsg = "Saving…"
                        val price = priceText.toDoubleOrNull() ?: 0.0
                        db.collection("items").document(id)
                            .update(
                                mapOf(
                                    "title" to title.trim(),
                                    "priceComputed" to price,
                                    "updatedAt" to System.currentTimeMillis()
                                )
                            )
                            .addOnSuccessListener {
                                saveMsg = "Saved ✔"
                                saving = false
                                onSaved()
                            }
                            .addOnFailureListener { e ->
                                saveMsg = "Save failed: ${e.message}"
                                saving = false
                            }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (saving) "Saving…" else "Save") }

                if (saveMsg.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(saveMsg)
                }
            }
        }
    }
}

/* ---------------- Helpers ---------------- */

fun generateSku(typeCode: String, metal: String?, gem: String?, year: Int, seq: Int): String {
    val yy = (year % 100).toString().padStart(2, '0')
    val seqStr = seq.toString().padStart(4, '0')
    val m = (metal ?: "NON").uppercase(Locale.US)
    val g = (gem ?: "NON").uppercase(Locale.US)
    return "$typeCode-$m-$g-$yy-$seqStr"
}

fun compressForUpload(
    contentResolver: ContentResolver,
    uri: Uri,
    longEdge: Int = 1600,
    jpegQuality: Int = 88
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
        context,
        "${context.packageName}.fileprovider",
        file
    )
}

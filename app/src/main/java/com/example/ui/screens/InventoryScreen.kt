package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.UiState
import com.example.data.model.WarehouseProduct
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.ErrorStateCard
import com.example.ui.components.StatusPill
import com.example.ui.components.SyncStatusBadge
import com.example.ui.components.formatRupiah
import com.example.ui.theme.Crimson100
import com.example.ui.theme.Crimson600
import com.example.ui.theme.Emerald100
import com.example.ui.theme.Emerald600

@Composable
fun InventoryScreen(
    productsState: UiState<List<WarehouseProduct>>,
    isAdmin: Boolean,
    onAdjustStock: (WarehouseProduct, Long) -> Unit,
    onSaveProduct: (
        existingId: String?,
        sku: String,
        name: String,
        category: String,
        rackLocation: String,
        systemStock: Long,
        minThreshold: Long,
        unitPrice: Double
    ) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedRack by remember { mutableStateOf("SEMUA") }
    var onlyLowStock by remember { mutableStateOf(false) }
    var editingProduct by remember { mutableStateOf<WarehouseProduct?>(null) }
    var showProductDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingProduct = null
                    showProductDialog = true
                },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier.testTag("add_product_button")
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Tambah Produk SKU")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Cari SKU, nama produk, atau rak...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("inventory_search_input")
            )
            Spacer(modifier = Modifier.height(10.dp))

            when (productsState) {
                is UiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is UiState.Error -> {
                    ErrorStateCard(productsState.message)
                }
                is UiState.Success -> {
                    val allProducts = productsState.data
                    val racks = remember(allProducts) {
                        listOf("SEMUA") + allProducts.map { it.rackLocation }.distinct().sorted()
                    }
                    val filtered = remember(allProducts, searchQuery, selectedRack, onlyLowStock) {
                        allProducts.filter { p ->
                            val matchesSearch = searchQuery.isBlank() ||
                                p.name.contains(searchQuery, ignoreCase = true) ||
                                p.sku.contains(searchQuery, ignoreCase = true) ||
                                p.rackLocation.contains(searchQuery, ignoreCase = true)
                            val matchesRack = selectedRack == "SEMUA" || p.rackLocation == selectedRack
                            val matchesLow = !onlyLowStock || p.isLowStock
                            matchesSearch && matchesRack && matchesLow
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = onlyLowStock,
                            onClick = { onlyLowStock = !onlyLowStock },
                            label = {
                                Text("Stok Kritis (${allProducts.count { it.isLowStock }})")
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                        racks.forEach { rack ->
                            FilterChip(
                                selected = selectedRack == rack,
                                onClick = { selectedRack = rack },
                                label = { Text(if (rack == "SEMUA") "Semua Rak" else rack) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (filtered.isEmpty()) {
                        EmptyStateCard(
                            icon = Icons.Filled.Inventory2,
                            title = "Belum Ada Data Barang",
                            subtitle = "Tambahkan produk SKU secara manual atau hubungkan akun Jubelio untuk menarik stok otomatis.",
                            actionLabel = "Tambah Produk Pertama",
                            onAction = {
                                editingProduct = null
                                showProductDialog = true
                            }
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 88.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(filtered, key = { it.id }) { product ->
                                ProductInventoryCard(
                                    product = product,
                                    isAdmin = isAdmin,
                                    onQuickAdjust = { delta -> onAdjustStock(product, delta) },
                                    onEditClick = {
                                        editingProduct = product
                                        showProductDialog = true
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showProductDialog) {
        ProductEditorDialog(
            initial = editingProduct,
            isAdmin = isAdmin,
            onDismiss = { showProductDialog = false },
            onConfirm = { sku, name, category, rack, stock, minThreshold, price ->
                onSaveProduct(
                    editingProduct?.id,
                    sku,
                    name,
                    category,
                    rack,
                    stock,
                    minThreshold,
                    price
                )
                showProductDialog = false
            }
        )
    }
}

@Composable
private fun ProductInventoryCard(
    product: WarehouseProduct,
    isAdmin: Boolean,
    onQuickAdjust: (Long) -> Unit,
    onEditClick: () -> Unit
) {
    val borderColor = if (product.isLowStock) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (product.isLowStock) 1.5.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusPill(
                            text = product.rackLocation,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                        Text(
                            text = product.sku,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${product.category} • ${formatRupiah(product.unitPrice)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SyncStatusBadge(product.syncStatusEnum)
                    if (product.isLowStock) {
                        StatusPill(
                            text = "STOK KRITIS",
                            containerColor = Crimson100,
                            contentColor = Crimson600
                        )
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Tersedia: ${product.availableStock}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (product.isLowStock) Crimson600 else Emerald600
                        )
                        Text(
                            text = "Fisik ${product.systemStock} • Dipesan ${product.reservedStock} • Min ${product.minThreshold}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { onQuickAdjust(-1L) },
                            enabled = product.systemStock > 0L,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .size(48.dp)
                                .testTag("dec_stock_${product.sku}")
                        ) {
                            Icon(Icons.Filled.Remove, contentDescription = "Kurangi 1 stok")
                        }
                        Button(
                            onClick = { onQuickAdjust(1L) },
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .size(48.dp)
                                .testTag("inc_stock_${product.sku}")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Tambah 1 stok")
                        }
                        IconButton(
                            onClick = onEditClick,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = if (isAdmin) "Ubah SKU & Ambang Minimum" else "Ubah Detail Barang"
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductEditorDialog(
    initial: WarehouseProduct?,
    isAdmin: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (
        sku: String,
        name: String,
        category: String,
        rackLocation: String,
        systemStock: Long,
        minThreshold: Long,
        unitPrice: Double
    ) -> Unit
) {
    var sku by remember { mutableStateOf(initial?.sku.orEmpty()) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var category by remember { mutableStateOf(initial?.category ?: "Umum") }
    var rack by remember { mutableStateOf(initial?.rackLocation ?: "RAK-A1") }
    var stockStr by remember { mutableStateOf((initial?.systemStock ?: 0L).toString()) }
    var minThresholdStr by remember { mutableStateOf((initial?.minThreshold ?: 5L).toString()) }
    var priceStr by remember { mutableStateOf((initial?.unitPrice?.toLong() ?: 0L).toString()) }

    val canSave = sku.isNotBlank() && name.isNotBlank() && rack.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initial == null) "Tambah Produk SKU" else "Edit Produk ${initial.sku}",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = sku,
                    onValueChange = { sku = it.uppercase() },
                    label = { Text("Kode SKU") },
                    placeholder = { Text("Misal: KOP-ARB-250") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("product_sku_input")
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nama Produk") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("product_name_input")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rack,
                        onValueChange = { rack = it.uppercase() },
                        label = { Text("Lokasi Rak") },
                        placeholder = { Text("RAK-A1") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("product_rack_input")
                    )
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("Kategori") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = stockStr,
                        onValueChange = { stockStr = it.filter(Char::isDigit) },
                        label = { Text("Stok Fisik") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("product_stock_input")
                    )
                    OutlinedTextField(
                        value = minThresholdStr,
                        onValueChange = { minThresholdStr = it.filter(Char::isDigit) },
                        label = { Text(if (isAdmin) "Ambang Min" else "Min Stok") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("product_threshold_input")
                    )
                }
                OutlinedTextField(
                    value = priceStr,
                    onValueChange = { priceStr = it.filter(Char::isDigit) },
                    label = { Text("Harga Satuan (Rp)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        sku,
                        name,
                        category,
                        rack,
                        stockStr.toLongOrNull() ?: 0L,
                        minThresholdStr.toLongOrNull() ?: 5L,
                        priceStr.toDoubleOrNull() ?: 0.0
                    )
                },
                enabled = canSave,
                modifier = Modifier.testTag("save_product_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) {
                Text("Simpan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}

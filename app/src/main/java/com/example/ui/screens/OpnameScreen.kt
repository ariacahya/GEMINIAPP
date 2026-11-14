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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.StockOpnameRecord
import com.example.data.model.UiState
import com.example.data.model.WarehouseProduct
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.ErrorStateCard
import com.example.ui.components.StatusPill
import com.example.ui.theme.Crimson100
import com.example.ui.theme.Crimson600
import com.example.ui.theme.Emerald100
import com.example.ui.theme.Emerald600
import com.example.ui.theme.SafetyOrange100
import com.example.ui.theme.SafetyOrange600
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun OpnameScreen(
    productsState: UiState<List<WarehouseProduct>>,
    opnamesState: UiState<List<StockOpnameRecord>>,
    isBusy: Boolean,
    jubelioConnected: Boolean,
    onSubmitOpname: (
        rackLocation: String,
        physicalCounts: Map<String, Long>,
        productsInRack: List<WarehouseProduct>,
        notes: String
    ) -> Unit
) {
    val products = (productsState as? UiState.Success)?.data.orEmpty()
    val opnames = (opnamesState as? UiState.Success)?.data.orEmpty()
    val racks = remember(products) { products.map { it.rackLocation }.distinct().sorted() }
    var selectedRack by remember(racks) { mutableStateOf(racks.firstOrNull().orEmpty()) }
    var notes by remember { mutableStateOf("") }
    var showConfirmDialog by remember { mutableStateOf(false) }

    val countsMap = remember { mutableStateMapOf<String, String>() }
    val productsInRack = remember(products, selectedRack) {
        products.filter { it.rackLocation == selectedRack }
    }

    LaunchedEffect(selectedRack, productsInRack) {
        productsInRack.forEach { prod ->
            if (!countsMap.containsKey(prod.id)) {
                countsMap[prod.id] = prod.systemStock.toString()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))
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
                if (products.isEmpty()) {
                    EmptyStateCard(
                        icon = Icons.Filled.FactCheck,
                        title = "Belum Ada Rak untuk Opname",
                        subtitle = "Tambahkan produk beserta kode raknya di tab Stok terlebih dahulu sebelum memulai stok opname."
                    )
                } else {
                    Text(
                        text = "1. Pilih Rak / Lokasi Penyimpanan",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        racks.forEach { rack ->
                            FilterChip(
                                selected = selectedRack == rack,
                                onClick = { selectedRack = rack },
                                label = { Text("$rack (${products.count { it.rackLocation == rack }})") },
                                modifier = Modifier.testTag("opname_rack_$rack")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val parsedCounts = productsInRack.associate { p ->
                        p.id to (countsMap[p.id]?.toLongOrNull() ?: p.systemStock)
                    }
                    val totalVariance = productsInRack.sumOf { p ->
                        (parsedCounts[p.id] ?: p.systemStock) - p.systemStock
                    }

                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "Opname $selectedRack",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Text(
                                            text = "${productsInRack.size} SKU dihitung",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                        )
                                    }
                                    val varianceLabel = when {
                                        totalVariance == 0L -> "Selisih: 0"
                                        totalVariance > 0L -> "Selisih: +$totalVariance"
                                        else -> "Selisih: $totalVariance"
                                    }
                                    StatusPill(
                                        text = varianceLabel,
                                        containerColor = when {
                                            totalVariance == 0L -> Emerald100
                                            totalVariance > 0L -> SafetyOrange100
                                            else -> Crimson100
                                        },
                                        contentColor = when {
                                            totalVariance == 0L -> Emerald600
                                            totalVariance > 0L -> SafetyOrange600
                                            else -> Crimson600
                                        }
                                    )
                                }
                            }
                        }

                        items(productsInRack, key = { it.id }) { prod ->
                            val currentStr = countsMap[prod.id] ?: prod.systemStock.toString()
                            val physical = currentStr.toLongOrNull() ?: 0L
                            val diff = physical - prod.systemStock
                            OpnameProductRow(
                                product = prod,
                                countText = currentStr,
                                diff = diff,
                                onCountChange = { newVal ->
                                    countsMap[prod.id] = newVal.filter(Char::isDigit).take(7)
                                },
                                onStep = { delta ->
                                    val updated = (physical + delta).coerceAtLeast(0L)
                                    countsMap[prod.id] = updated.toString()
                                }
                            )
                        }

                        item {
                            OutlinedTextField(
                                value = notes,
                                onValueChange = { notes = it },
                                label = { Text("Catatan Opname (opsional)") },
                                placeholder = { Text("Misal: Barang rusak 1 pcs di rak bawah") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { showConfirmDialog = true },
                                enabled = !isBusy && productsInRack.isNotEmpty(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .testTag("submit_opname_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                    contentColor = MaterialTheme.colorScheme.onSecondary
                                )
                            ) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (jubelioConnected) {
                                        "Simpan Opname & Sinkron Jubelio"
                                    } else {
                                        "Simpan Hasil Opname Rak"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (opnames.isNotEmpty()) {
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Riwayat Stok Opname Terbaru",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            items(opnames.take(10), key = { it.id }) { record ->
                                OpnameHistoryCard(record)
                            }
                        }
                    }

                    if (showConfirmDialog) {
                        AlertDialog(
                            onDismissRequest = { showConfirmDialog = false },
                            title = { Text("Konfirmasi Simpan Opname") },
                            text = {
                                Text(
                                    "Anda akan memperbarui stok fisik untuk ${productsInRack.size} SKU di $selectedRack dengan total selisih $totalVariance unit."
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showConfirmDialog = false
                                        onSubmitOpname(
                                            selectedRack,
                                            parsedCounts,
                                            productsInRack,
                                            notes
                                        )
                                        notes = ""
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary
                                    )
                                ) {
                                    Text("Ya, Simpan")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showConfirmDialog = false }) {
                                    Text("Periksa Lagi")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OpnameProductRow(
    product: WarehouseProduct,
    countText: String,
    diff: Long,
    onCountChange: (String) -> Unit,
    onStep: (Long) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = product.sku,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Stok Sistem: ${product.systemStock}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val (bg, fg, label) = when {
                    diff == 0L -> Triple(Emerald100, Emerald600, "Cocok 0")
                    diff > 0L -> Triple(SafetyOrange100, SafetyOrange600, "+$diff")
                    else -> Triple(Crimson100, Crimson600, "$diff")
                }
                StatusPill(text = label, containerColor = bg, contentColor = fg)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onStep(-1L) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(48.dp)
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = "Kurangi hitungan")
                }

                OutlinedTextField(
                    value = countText,
                    onValueChange = onCountChange,
                    label = { Text("Stok Fisik") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("opname_input_${product.sku}")
                )

                Button(
                    onClick = { onStep(1L) },
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(48.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Tambah hitungan")
                }
            }
        }
    }
}

@Composable
private fun OpnameHistoryCard(record: StockOpnameRecord) {
    val dateLabel = record.createdAt?.toDate()?.let {
        SimpleDateFormat("dd MMM yyyy • HH:mm", Locale("id", "ID")).format(it)
    } ?: "Baru saja"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${record.rackLocation} • ${record.totalItemsCounted} SKU",
                    style = MaterialTheme.typography.titleSmall
                )
                StatusPill(
                    text = if (record.syncedToJubelio) "Jubelio Sync" else "Tersimpan",
                    containerColor = if (record.syncedToJubelio) Emerald100 else SafetyOrange100,
                    contentColor = if (record.syncedToJubelio) Emerald600 else SafetyOrange600
                )
            }
            Text(
                text = "Oleh ${record.performedByName} • $dateLabel • Selisih total: ${record.totalVariance}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!record.notes.isNullOrBlank()) {
                Text(
                    text = "Catatan: ${record.notes}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

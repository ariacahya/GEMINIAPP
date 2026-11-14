package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.UiState
import com.example.data.model.WarehouseProduct
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.StatusPill
import com.example.ui.components.formatRupiah
import com.example.ui.theme.Crimson100
import com.example.ui.theme.Crimson600
import com.example.ui.theme.Emerald100
import com.example.ui.theme.Emerald600
import com.example.ui.theme.SafetyOrange600
import com.example.ui.theme.SlateNavy900
import com.example.ui.viewmodel.AnalyticsSummary

@Composable
fun AnalyticsScreen(
    summary: AnalyticsSummary,
    productsState: UiState<List<WarehouseProduct>>,
    onNavigateToInventory: () -> Unit,
    onNavigateToJubelio: () -> Unit
) {
    val products = (productsState as? UiState.Success)?.data.orEmpty()
    val lowStockProducts = products.filter { it.isLowStock }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (lowStockProducts.isNotEmpty()) {
            item {
                Surface(
                    color = Crimson100,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Crimson600),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = "Peringatan Stok Minimum",
                                tint = Crimson600
                            )
                            Column {
                                Text(
                                    text = "${lowStockProducts.size} SKU Mencapai Ambang Minimum",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Crimson600
                                )
                                Text(
                                    text = "Segera lakukan restok atau sinkronisasi Jubelio.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        TextButton(onClick = onNavigateToInventory) {
                            Text("Lihat Stok", color = Crimson600, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // KPI Bento Grid (2x2)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KpiCard(
                    title = "Total Omzet",
                    value = formatRupiah(summary.totalRevenue),
                    subtitle = "${summary.totalOrders} pesanan",
                    highlight = false,
                    modifier = Modifier.weight(1f)
                )
                KpiCard(
                    title = "Pemenuhan Order",
                    value = "${summary.fulfillmentRatePercent}%",
                    subtitle = "${summary.completedOrders} selesai • ${summary.activeOrders} proses",
                    highlight = false,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KpiCard(
                    title = "Total Unit Stok",
                    value = "${summary.totalUnits}",
                    subtitle = "${summary.totalSkus} SKU aktif",
                    highlight = false,
                    modifier = Modifier.weight(1f)
                )
                KpiCard(
                    title = "Antre Sinkron Jubelio",
                    value = "${summary.pendingSyncCount}",
                    subtitle = "${summary.pendingDispatchOrders} order tunggu arahan",
                    highlight = summary.pendingSyncCount > 0,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Channel Revenue Canvas Chart
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Performa Penjualan per Channel",
                            style = MaterialTheme.typography.titleMedium
                        )
                        TextButton(onClick = onNavigateToJubelio) {
                            Text("Sync Jubelio")
                        }
                    }

                    if (summary.channelBreakdown.isEmpty()) {
                        EmptyStateCard(
                            icon = Icons.Filled.Assessment,
                            title = "Belum Ada Data Penjualan",
                            subtitle = "Catat order masuk atau tarik data pesanan dari Jubelio untuk melihat grafik performa."
                        )
                    } else {
                        val maxRev = summary.channelBreakdown.maxOf { it.second }.coerceAtLeast(1.0)
                        val primaryBarColor = SlateNavy900
                        val accentBarColor = SafetyOrange600
                        val trackColor = MaterialTheme.colorScheme.surfaceVariant

                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                        ) {
                            val count = summary.channelBreakdown.size
                            val slotWidth = size.width / count.coerceAtLeast(1)
                            val barWidth = (slotWidth * 0.52f).coerceAtMost(72f)
                            summary.channelBreakdown.forEachIndexed { index, (_, amount) ->
                                val ratio = (amount / maxRev).toFloat().coerceIn(0.06f, 1f)
                                val x = slotWidth * index + (slotWidth - barWidth) / 2f
                                val barHeight = size.height * ratio
                                val y = size.height - barHeight

                                drawRoundRect(
                                    color = trackColor,
                                    topLeft = Offset(x, 0f),
                                    size = Size(barWidth, size.height),
                                    cornerRadius = CornerRadius(12f, 12f)
                                )
                                drawRoundRect(
                                    color = if (index == 0) accentBarColor else primaryBarColor,
                                    topLeft = Offset(x, y),
                                    size = Size(barWidth, barHeight),
                                    cornerRadius = CornerRadius(12f, 12f)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            summary.channelBreakdown.forEach { (channel, amount) ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = channel,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = formatRupiah(amount),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Top Selling Products
        if (summary.topProducts.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Produk Terlaris",
                            style = MaterialTheme.typography.titleMedium
                        )
                        summary.topProducts.forEachIndexed { idx, (name, qty) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${idx + 1}. $name",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                StatusPill(
                                    text = "$qty terjual",
                                    containerColor = Emerald100,
                                    contentColor = Emerald600
                                )
                            }
                            if (idx < summary.topProducts.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        }

        // Low-stock threshold list
        if (lowStockProducts.isNotEmpty()) {
            item {
                Text(
                    text = "Daftar Produk di Bawah Ambang Minimum",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(lowStockProducts, key = { it.id }) { prod ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, Crimson600),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(prod.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${prod.sku} • ${prod.rackLocation}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatusPill(
                            text = "Sisa ${prod.availableStock} / Min ${prod.minThreshold}",
                            containerColor = Crimson100,
                            contentColor = Crimson600
                        )
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun KpiCard(
    title: String,
    value: String,
    subtitle: String,
    highlight: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            1.dp,
            if (highlight) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

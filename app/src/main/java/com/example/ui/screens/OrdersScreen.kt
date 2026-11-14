package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.OrderInstruction
import com.example.data.model.OrderLineItem
import com.example.data.model.OrderStatus
import com.example.data.model.UiState
import com.example.data.model.WarehouseProduct
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.ErrorStateCard
import com.example.ui.components.StatusPill
import com.example.ui.components.formatRupiah
import com.example.ui.theme.Amber100
import com.example.ui.theme.Amber600
import com.example.ui.theme.Emerald100
import com.example.ui.theme.Emerald600
import com.example.ui.theme.SafetyOrange100
import com.example.ui.theme.SafetyOrange600
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate600

private enum class OrderFilter(val label: String) {
    SEMUA("Semua"),
    MENUNGGU("Menunggu Arahan"),
    PROSES("Proses Gudang"),
    SELESAI("Dikirim / Selesai")
}

@Composable
fun OrdersScreen(
    ordersState: UiState<List<OrderInstruction>>,
    productsState: UiState<List<WarehouseProduct>>,
    isAdmin: Boolean,
    onCreateOrder: (
        orderNumber: String,
        customerName: String,
        channel: String,
        items: List<OrderLineItem>,
        adminInstruction: String,
        dispatchImmediately: Boolean
    ) -> Unit,
    onDispatchInstruction: (OrderInstruction, String) -> Unit,
    onToggleItemPicked: (OrderInstruction, Int) -> Unit,
    onAdvanceStatus: (OrderInstruction, OrderStatus, String, String) -> Unit
) {
    var selectedFilter by remember { mutableStateOf(OrderFilter.SEMUA) }
    var selectedOrderId by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val products = (productsState as? UiState.Success)?.data.orEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (isAdmin && selectedOrderId == null) {
                FloatingActionButton(
                    onClick = { showCreateDialog = true },
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.testTag("create_order_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Buat Pesanan Baru")
                }
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isWideScreen = maxWidth >= 720.dp

            when (ordersState) {
                is UiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is UiState.Error -> {
                    Box(modifier = Modifier.padding(16.dp)) {
                        ErrorStateCard(ordersState.message)
                    }
                }
                is UiState.Success -> {
                    val orders = ordersState.data
                    val filtered = remember(orders, selectedFilter) {
                        orders.filter { ord ->
                            when (selectedFilter) {
                                OrderFilter.SEMUA -> true
                                OrderFilter.MENUNGGU -> ord.statusEnum == OrderStatus.MENUNGGU_ARAHAN
                                OrderFilter.PROSES -> ord.statusEnum in listOf(
                                    OrderStatus.SIAP_DIKEMAS,
                                    OrderStatus.DIKEMAS,
                                    OrderStatus.SIAP_KIRIM
                                )
                                OrderFilter.SELESAI -> ord.statusEnum in listOf(
                                    OrderStatus.DIKIRIM,
                                    OrderStatus.SELESAI
                                )
                            }
                        }
                    }

                    val activeOrder = orders.firstOrNull { it.id == selectedOrderId }
                        ?: if (isWideScreen) filtered.firstOrNull() else null

                    if (isWideScreen) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(0.45f)
                                    .fillMaxHeight()
                            ) {
                                OrderFilterRow(selectedFilter) { selectedFilter = it }
                                OrderListPane(
                                    orders = filtered,
                                    selectedOrderId = activeOrder?.id,
                                    onSelectOrder = { selectedOrderId = it.id },
                                    onEmptyAction = if (isAdmin) ({ showCreateDialog = true }) else null
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(0.55f)
                                    .fillMaxHeight()
                            ) {
                                if (activeOrder != null) {
                                    OrderDetailPane(
                                        order = activeOrder,
                                        isAdmin = isAdmin,
                                        showBackButton = false,
                                        onBack = {},
                                        onDispatchInstruction = { note ->
                                            onDispatchInstruction(activeOrder, note)
                                        },
                                        onToggleItemPicked = { idx ->
                                            onToggleItemPicked(activeOrder, idx)
                                        },
                                        onAdvanceStatus = { next, courier, tracking ->
                                            onAdvanceStatus(activeOrder, next, courier, tracking)
                                        }
                                    )
                                } else {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "Pilih pesanan untuk melihat arahan & pelacakan.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        if (activeOrder != null) {
                            BackHandler { selectedOrderId = null }
                            OrderDetailPane(
                                order = activeOrder,
                                isAdmin = isAdmin,
                                showBackButton = true,
                                onBack = { selectedOrderId = null },
                                onDispatchInstruction = { note ->
                                    onDispatchInstruction(activeOrder, note)
                                },
                                onToggleItemPicked = { idx ->
                                    onToggleItemPicked(activeOrder, idx)
                                },
                                onAdvanceStatus = { next, courier, tracking ->
                                    onAdvanceStatus(activeOrder, next, courier, tracking)
                                }
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp)
                            ) {
                                OrderFilterRow(selectedFilter) { selectedFilter = it }
                                OrderListPane(
                                    orders = filtered,
                                    selectedOrderId = null,
                                    onSelectOrder = { selectedOrderId = it.id },
                                    onEmptyAction = if (isAdmin) ({ showCreateDialog = true }) else null
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateOrderDialog(
            products = products,
            onDismiss = { showCreateDialog = false },
            onConfirm = { ordNo, customer, channel, items, instruction, dispatchNow ->
                onCreateOrder(ordNo, customer, channel, items, instruction, dispatchNow)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun OrderFilterRow(
    selectedFilter: OrderFilter,
    onSelect: (OrderFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OrderFilter.entries.forEach { filter ->
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onSelect(filter) },
                label = { Text(filter.label) }
            )
        }
    }
}

@Composable
private fun OrderListPane(
    orders: List<OrderInstruction>,
    selectedOrderId: String?,
    onSelectOrder: (OrderInstruction) -> Unit,
    onEmptyAction: (() -> Unit)?
) {
    if (orders.isEmpty()) {
        EmptyStateCard(
            icon = Icons.Filled.ReceiptLong,
            title = "Belum Ada Pesanan",
            subtitle = "Order masuk dari Jubelio atau input Admin akan muncul di sini beserta arahan penyiapan untuk Gudang.",
            actionLabel = if (onEmptyAction != null) "Buat Order Masuk" else null,
            onAction = onEmptyAction
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(orders, key = { it.id }) { order ->
            val isSelected = order.id == selectedOrderId
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectOrder(order) }
                    .testTag("order_card_${order.orderNumber}"),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                ),
                border = BorderStroke(
                    if (isSelected) 1.5.dp else 1.dp,
                    if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = order.orderNumber,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        OrderStatusBadge(order.statusEnum)
                    }
                    Text(
                        text = "${order.customerName} • ${order.channel} • ${order.items.sumOf { it.qty }} pcs",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (order.adminInstruction.isNotBlank()) {
                        Text(
                            text = "Arahan: ${order.adminInstruction}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatRupiah(order.totalAmount),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (order.trackingNumber.isNotBlank()) {
                            Text(
                                text = "${order.courier}: ${order.trackingNumber}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderDetailPane(
    order: OrderInstruction,
    isAdmin: Boolean,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onDispatchInstruction: (String) -> Unit,
    onToggleItemPicked: (Int) -> Unit,
    onAdvanceStatus: (OrderStatus, String, String) -> Unit
) {
    var instructionDraft by remember(order.id, order.adminInstruction) {
        mutableStateOf(order.adminInstruction)
    }
    var showShippingDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showBackButton) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali ke daftar order")
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = order.orderNumber,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "${order.customerName} • ${order.channel} • ${formatRupiah(order.totalAmount)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OrderStatusBadge(order.statusEnum)
        }

        // Progress tracker bar
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Pelacakan Status Gudang hingga Pelanggan",
                    style = MaterialTheme.typography.titleSmall
                )
                LinearProgressIndicator(
                    progress = { (order.statusEnum.stepIndex + 1) / 6f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = "Tahap ${order.statusEnum.stepIndex + 1}/6: ${order.statusEnum.label}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
                if (order.trackingNumber.isNotBlank()) {
                    Text(
                        text = "Kurir: ${order.courier} • No. Resi: ${order.trackingNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        // Admin Instruction Box
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Arahan Admin untuk Bagian Gudang",
                    style = MaterialTheme.typography.titleSmall
                )
                if (isAdmin) {
                    OutlinedTextField(
                        value = instructionDraft,
                        onValueChange = { instructionDraft = it },
                        placeholder = { Text("Tulis arahan produk, prioritas, atau catatan packing...") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onDispatchInstruction(instructionDraft) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("dispatch_instruction_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (order.statusEnum == OrderStatus.MENUNGGU_ARAHAN) {
                                "Kirim Arahan & Notifikasi Gudang"
                            } else {
                                "Perbarui Arahan Gudang"
                            }
                        )
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = order.adminInstruction.ifBlank { "Siapkan semua produk di bawah sesuai lokasi rak." },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }

        // Items Pick Checklist
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Checklist Pengambilan Barang (${order.items.count { it.picked }}/${order.items.size})",
                    style = MaterialTheme.typography.titleSmall
                )
                order.items.forEachIndexed { idx, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = order.statusEnum != OrderStatus.SELESAI) {
                                onToggleItemPicked(idx)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = item.picked,
                            onCheckedChange = { onToggleItemPicked(idx) },
                            enabled = order.statusEnum != OrderStatus.SELESAI,
                            modifier = Modifier.testTag("pick_item_$idx")
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${item.name} (${item.qty}x)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "SKU: ${item.sku} • Lokasi: ${item.rackLocation}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (idx < order.items.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }

        // Next Status Progression CTA
        val nextAction = when (order.statusEnum) {
            OrderStatus.MENUNGGU_ARAHAN -> null
            OrderStatus.SIAP_DIKEMAS -> OrderStatus.DIKEMAS to "Mulai Kemas Pesanan"
            OrderStatus.DIKEMAS -> OrderStatus.SIAP_KIRIM to "Tandai Siap Kirim"
            OrderStatus.SIAP_KIRIM -> OrderStatus.DIKIRIM to "Input Resi & Kirim Kurir"
            OrderStatus.DIKIRIM -> OrderStatus.SELESAI to "Konfirmasi Diterima Pelanggan"
            OrderStatus.SELESAI -> null
        }

        if (nextAction != null) {
            val (nextStatus, ctaLabel) = nextAction
            Button(
                onClick = {
                    if (nextStatus == OrderStatus.DIKIRIM) {
                        showShippingDialog = true
                    } else {
                        onAdvanceStatus(nextStatus, order.courier, order.trackingNumber)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("advance_order_status_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) {
                Icon(
                    imageVector = if (nextStatus == OrderStatus.DIKIRIM) Icons.Filled.LocalShipping else Icons.Filled.CheckCircle,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(ctaLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }

        // Timeline log
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Riwayat Pelacakan",
                    style = MaterialTheme.typography.titleSmall
                )
                order.timelineEvents.asReversed().forEach { event ->
                    Text(
                        text = "• $event",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showShippingDialog) {
        var courier by remember { mutableStateOf(order.courier.ifBlank { "JNE" }) }
        var tracking by remember { mutableStateOf(order.trackingNumber) }
        AlertDialog(
            onDismissRequest = { showShippingDialog = false },
            title = { Text("Input Kurir & Nomor Resi") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = courier,
                        onValueChange = { courier = it },
                        label = { Text("Ekspedisi / Kurir") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tracking,
                        onValueChange = { tracking = it.uppercase() },
                        label = { Text("Nomor Resi (AWB)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("tracking_number_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showShippingDialog = false
                        onAdvanceStatus(OrderStatus.DIKIRIM, courier, tracking)
                    },
                    enabled = courier.isNotBlank() && tracking.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Kirim & Sinkron")
                }
            },
            dismissButton = {
                TextButton(onClick = { showShippingDialog = false }) { Text("Batal") }
            }
        )
    }
}

@Composable
private fun OrderStatusBadge(status: OrderStatus) {
    val (bg, fg) = when (status) {
        OrderStatus.MENUNGGU_ARAHAN -> Amber100 to Amber600
        OrderStatus.SIAP_DIKEMAS, OrderStatus.DIKEMAS, OrderStatus.SIAP_KIRIM -> SafetyOrange100 to SafetyOrange600
        OrderStatus.DIKIRIM -> Slate200 to Slate600
        OrderStatus.SELESAI -> Emerald100 to Emerald600
    }
    StatusPill(text = status.label, containerColor = bg, contentColor = fg)
}

@Composable
private fun CreateOrderDialog(
    products: List<WarehouseProduct>,
    onDismiss: () -> Unit,
    onConfirm: (
        orderNumber: String,
        customerName: String,
        channel: String,
        items: List<OrderLineItem>,
        adminInstruction: String,
        dispatchImmediately: Boolean
    ) -> Unit
) {
    val randomNum = remember { "ORD-${(1000..9999).random()}" }
    var orderNumber by remember { mutableStateOf(randomNum) }
    var customerName by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf("Shopee") }
    var instruction by remember { mutableStateOf("Segera ambil dari rak dan kemas rapi.") }
    var dispatchNow by remember { mutableStateOf(true) }
    var manualSku by remember { mutableStateOf("") }
    var manualQty by remember { mutableStateOf("1") }
    var manualPrice by remember { mutableStateOf("50000") }
    val selectedLines = remember { mutableStateListOf<OrderLineItem>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Catat Order Masuk") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = orderNumber,
                    onValueChange = { orderNumber = it.uppercase() },
                    label = { Text("Nomor Pesanan") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = customerName,
                    onValueChange = { customerName = it },
                    label = { Text("Nama Pelanggan") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("order_customer_input")
                )
                OutlinedTextField(
                    value = channel,
                    onValueChange = { channel = it },
                    label = { Text("Channel / Marketplace") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (products.isNotEmpty()) {
                    Text("Pilih Produk dari Stok:", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        products.take(8).forEach { prod ->
                            val already = selectedLines.any { it.sku == prod.sku }
                            FilterChip(
                                selected = already,
                                onClick = {
                                    if (already) {
                                        selectedLines.removeAll { it.sku == prod.sku }
                                    } else {
                                        selectedLines.add(
                                            OrderLineItem(
                                                sku = prod.sku,
                                                name = prod.name,
                                                rackLocation = prod.rackLocation,
                                                qty = 1L,
                                                unitPrice = prod.unitPrice
                                            )
                                        )
                                    }
                                },
                                label = { Text("${prod.sku} (${prod.availableStock})") }
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = manualSku,
                        onValueChange = { manualSku = it.uppercase() },
                        label = { Text("SKU Produk") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = manualQty,
                            onValueChange = { manualQty = it.filter(Char::isDigit) },
                            label = { Text("Qty") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = manualPrice,
                            onValueChange = { manualPrice = it.filter(Char::isDigit) },
                            label = { Text("Harga (Rp)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    label = { Text("Arahan untuk Gudang") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Langsung kirim notifikasi ke Gudang", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = dispatchNow, onCheckedChange = { dispatchNow = it })
                }
            }
        },
        confirmButton = {
            val finalItems = if (selectedLines.isNotEmpty()) {
                selectedLines.toList()
            } else if (manualSku.isNotBlank()) {
                listOf(
                    OrderLineItem(
                        sku = manualSku,
                        name = manualSku,
                        rackLocation = "RAK-A1",
                        qty = (manualQty.toLongOrNull() ?: 1L).coerceAtLeast(1L),
                        unitPrice = manualPrice.toDoubleOrNull() ?: 0.0
                    )
                )
            } else {
                emptyList()
            }
            Button(
                onClick = {
                    onConfirm(orderNumber, customerName, channel, finalItems, instruction, dispatchNow)
                },
                enabled = orderNumber.isNotBlank() && customerName.isNotBlank() && finalItems.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Simpan Order")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        }
    )
}

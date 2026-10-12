package com.opnavigation.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opnavigation.app.NavViewModel
import com.opnavigation.app.data.CommaClient
import com.opnavigation.app.data.CommaDevice
import com.opnavigation.app.data.Favorite
import com.opnavigation.app.data.formatLatLon
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: NavViewModel,
    onOpenSettings: () -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Navigate")
                        val dongle = CommaClient.normalizeDongle(viewModel.dongleId)
                        if (dongle.isNotEmpty()) {
                            Text(dongle, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                },
                actions = {
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            if (viewModel.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (CommaClient.normalizeToken(viewModel.token).isEmpty() ||
                    !CommaClient.normalizeDongle(viewModel.dongleId).matches(Regex("^[0-9a-f]{16}$"))
                ) {
                    TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("Add your comma JWT and device in Settings")
                    }
                }
                viewModel.favorites.forEachIndexed { index, favorite ->
                    FavoriteButton(
                        favorite = favorite,
                        index = index,
                        enabled = !viewModel.busy,
                        onClick = { viewModel.sendFavorite(index) },
                        onLongClick = { editing = index },
                    )
                }
            }
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Address or lat, lon") },
                    singleLine = true,
                    enabled = !viewModel.busy,
                )
                Button(
                    onClick = {
                        viewModel.sendAddress(address) { address = "" }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !viewModel.busy,
                ) {
                    Text("Go")
                }
                OutlinedButton(
                    onClick = viewModel::clearDestination,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !viewModel.busy,
                ) {
                    Text("Clear destination")
                }
            }
        }
    }

    editing?.let { index ->
        val favorite = viewModel.favorites[index]
        EditFavoriteDialog(
            index = index,
            favorite = favorite,
            onDismiss = { editing = null },
            onSave = { name, query -> viewModel.saveFavorite(index, name, query) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteButton(
    favorite: Favorite,
    index: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val container = if (favorite.isSet) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (favorite.isSet) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val latitude = favorite.latitude
    val longitude = favorite.longitude
    val subtitle = when {
        favorite.query.isNotBlank() -> favorite.query
        latitude != null && longitude != null -> formatLatLon(latitude, longitude)
        else -> "Long-press to set"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "Edit",
                role = Role.Button,
            ),
        shape = RoundedCornerShape(20.dp),
        color = container,
        contentColor = content,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = favorite.label(index),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EditFavoriteDialog(
    index: Int,
    favorite: Favorite,
    onDismiss: () -> Unit,
    onSave: suspend (name: String, query: String) -> String?,
) {
    var name by remember(favorite) { mutableStateOf(favorite.name) }
    var query by remember(favorite) { mutableStateOf(favorite.query) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Favorite ${index + 1}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !saving,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Address or lat, lon") },
                    supportingText = { Text("Paste 32.73, -117.19 or a maps link") },
                    enabled = !saving,
                    minLines = 2,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (saving) {
                    CircularProgressIndicator()
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    scope.launch {
                        saving = true
                        error = null
                        val result = onSave(name, query)
                        if (result == null) {
                            onDismiss()
                        } else {
                            error = result
                            saving = false
                        }
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: NavViewModel,
    onBack: () -> Unit,
) {
    var showToken by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Sign in at jwt.comma.ai with the same account you use for Connect, then paste the token.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = viewModel.token,
                onValueChange = viewModel::updateToken,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("comma JWT") },
                visualTransformation = if (showToken) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                minLines = 2,
            )
            TextButton(onClick = { showToken = !showToken }) {
                Text(if (showToken) "Hide token" else "Show token")
            }
            OutlinedTextField(
                value = viewModel.dongleId,
                onValueChange = viewModel::updateDongleId,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Dongle ID") },
                singleLine = true,
                supportingText = { Text("16 characters, from Connect or the list below") },
            )
            Button(
                onClick = viewModel::loadDevices,
                enabled = !viewModel.devicesLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (viewModel.devicesLoading) "Loading devices…" else "List my devices")
            }
            viewModel.devicesError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            val selected = CommaClient.normalizeDongle(viewModel.dongleId)
            viewModel.devices.forEach { device ->
                DeviceRow(
                    device = device,
                    selected = device.dongleId == selected,
                    onClick = { viewModel.selectDevice(device) },
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: CommaDevice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.alias.ifBlank { device.dongleId },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(device.dongleId, style = MaterialTheme.typography.bodySmall)
                Text(deviceStatus(device.lastAthenaPingSeconds), style = MaterialTheme.typography.bodySmall)
            }
            if (selected) {
                Text("Selected", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun deviceStatus(lastPingSeconds: Long?): String {
    if (lastPingSeconds == null) return "No athena ping yet — probably offline"
    val ageSeconds = (System.currentTimeMillis() / 1000L) - lastPingSeconds
    if (ageSeconds < 120) return "Online · pinged ${ageSeconds.coerceAtLeast(0)}s ago"
    val age = when {
        ageSeconds < 3600 -> "${ageSeconds / 60} min ago"
        ageSeconds < 86_400 -> "${ageSeconds / 3600} hr ago"
        else -> "${ageSeconds / 86_400} days ago"
    }
    return "Offline · last ping $age"
}

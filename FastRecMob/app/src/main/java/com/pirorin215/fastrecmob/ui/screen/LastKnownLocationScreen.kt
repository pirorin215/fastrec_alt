package com.pirorin215.fastrecmob.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pirorin215.fastrecmob.viewModel.LastKnownLocationViewModel
import com.pirorin215.fastrecmob.viewModel.LastKnownLocationViewModelFactory
import com.pirorin215.fastrecmob.data.LastKnownLocationRepository
import android.app.Application
import android.content.Intent // Add this
import android.net.Uri // Add this
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LastKnownLocationScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lastKnownLocationRepository = LastKnownLocationRepository(context.applicationContext as Application)
    val viewModelFactory = LastKnownLocationViewModelFactory(lastKnownLocationRepository)
    val viewModel: LastKnownLocationViewModel = viewModel(factory = viewModelFactory)
    val lastKnownLocation by viewModel.lastKnownLocation.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("最後の位置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            lastKnownLocation?.let { location ->
                Text(
                    text = "最終更新: ${SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date(location.timestamp))}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "緯度: %.5f, 経度: %.5f".format(location.latitude, location.longitude),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Button(
                    onClick = {
                        val gmmIntentUri = Uri.parse("geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}(Last Known Device Location)")
                        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                        mapIntent.setPackage("com.google.android.apps.maps") // Try to open with Google Maps app first
                        if (mapIntent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(mapIntent)
                        } else {
                            // If Google Maps app is not installed, open in browser
                            val webIntentUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=${location.latitude},${location.longitude}")
                            val webIntent = Intent(Intent.ACTION_VIEW, webIntentUri)
                            context.startActivity(webIntent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("地図で表示")
                }

            } ?: run {
                Text(
                    text = "最後にBLE通信できた位置情報がありません。",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

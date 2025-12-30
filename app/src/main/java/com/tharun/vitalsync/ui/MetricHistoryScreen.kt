package com.tharun.vitalsync.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.entry.entryOf
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricHistoryScreen(
    metricType: MetricType,
    navController: NavController,
    viewModel: MainViewModel
) {
    val historyData by viewModel.historyState.collectAsState()
    var selectedTimeRange by remember { mutableStateOf("Today") }

    // This is now the single source of truth for triggering a history load.
    // It runs only when metricType or selectedTimeRange changes.
    LaunchedEffect(metricType, selectedTimeRange) {
        viewModel.loadHistory(metricType, selectedTimeRange)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "${metricType.name.lowercase().replaceFirstChar { it.uppercase() }} History") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) {
        padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            TabRow(selectedTabIndex = when(selectedTimeRange) {
                "Today" -> 0
                "7 Days" -> 1
                "30 Days" -> 2
                else -> 0
            }) {
                Tab(selected = selectedTimeRange == "Today", onClick = { selectedTimeRange = "Today" }) {
                    Text("Today", modifier = Modifier.padding(16.dp))
                }
                Tab(selected = selectedTimeRange == "7 Days", onClick = { selectedTimeRange = "7 Days" }) {
                    Text("7 Days", modifier = Modifier.padding(16.dp))
                }
                Tab(selected = selectedTimeRange == "30 Days", onClick = { selectedTimeRange = "30 Days" }) {
                    Text("30 Days", modifier = Modifier.padding(16.dp))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (historyData.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No history data available for this period.")
                }
            } else {
                // Chart
                val chartModelProducer = ChartEntryModelProducer(historyData.mapIndexed { index, data ->
                    val value = when(metricType) {
                        MetricType.HEART_RATE -> data.heartRate ?: 0f
                        MetricType.STEPS -> data.steps?.toFloat() ?: 0f
                        MetricType.CALORIES -> data.calories ?: 0f
                        MetricType.DISTANCE -> data.distance ?: 0f
                        MetricType.HEART_POINTS -> data.heartPoints?.toFloat() ?: 0f
                        MetricType.SLEEP -> data.sleepDuration?.toFloat() ?: 0f
                    }
                    entryOf(index.toFloat(), value)
                })
                Chart(chart = lineChart(), chartModelProducer = chartModelProducer, startAxis = rememberStartAxis(), bottomAxis = rememberBottomAxis())

                Spacer(modifier = Modifier.height(16.dp))

                // History List
                LazyColumn {
                    items(historyData) {
                        data ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(text = SimpleDateFormat("EEE, d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(data.timestamp)))
                            Spacer(modifier = Modifier.weight(1f))
                            val valueText = when(metricType) {
                                MetricType.HEART_RATE -> data.heartRate?.let { "$it bpm" }
                                MetricType.STEPS -> data.steps?.let { "$it steps" }
                                MetricType.CALORIES -> data.calories?.let { "$it kcal" }
                                MetricType.DISTANCE -> data.distance?.let { "$it km" }
                                MetricType.HEART_POINTS -> data.heartPoints?.let { "$it pts" }
                                MetricType.SLEEP -> data.sleepDuration?.let { "${it / 60}h ${it % 60}m" }
                            }
                            Text(text = valueText ?: "", fontWeight = FontWeight.Bold)
                        }
                        Divider()
                    }
                }
            }
        }
    }
}

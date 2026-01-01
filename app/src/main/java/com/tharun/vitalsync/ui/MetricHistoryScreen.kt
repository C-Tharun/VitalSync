package com.tharun.vitalsync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.column.columnChart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.style.ProvideChartStyle
import com.patrykandpatrick.vico.core.chart.column.ColumnChart
import com.patrykandpatrick.vico.core.chart.line.LineChart
import com.patrykandpatrick.vico.compose.component.shape.shader.verticalGradient
import com.patrykandpatrick.vico.core.axis.AxisPosition
import com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter
import com.patrykandpatrick.vico.core.component.shape.LineComponent
import com.patrykandpatrick.vico.core.component.shape.Shapes
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.entry.entryOf
import com.tharun.vitalsync.data.HealthData
import com.tharun.vitalsync.ui.theme.rememberChartStyle
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricHistoryScreen(
    metricType: MetricType,
    navController: NavController,
    viewModel: MainViewModel
) {
    val historyData by viewModel.historyState.collectAsState()
    val heartRateHistory by viewModel.heartRateHistory.collectAsState()
    val stepsHistory by viewModel.stepsHistory.collectAsState()
    var selectedDate by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(metricType, selectedDate) {
        viewModel.loadHistory(metricType, selectedDate)
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            DateSelector(selectedDate = selectedDate, onDateSelected = { selectedDate = it })
            Spacer(modifier = Modifier.height(16.dp))

            when (metricType) {
                MetricType.HEART_RATE -> {
                    if (heartRateHistory.dailySummary == null && heartRateHistory.hourlyData.isEmpty() && heartRateHistory.rawData.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No heart rate data available for this period.")
                        }
                    } else {
                        LazyColumn {
                            item {
                                heartRateHistory.dailySummary?.let { summary ->
                                    DailyHeartRateSummary(summary)
                                }
                            }
                            item {
                                if (heartRateHistory.hourlyData.isNotEmpty()) {
                                    HourlyHeartRateChart(heartRateHistory.hourlyData)
                                }
                            }
                            items(heartRateHistory.rawData) {
                                data ->
                                Row(modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp)) {
                                    Text(
                                        text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(data.timestamp)),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(
                                        text = "${data.heartRate?.toInt()} bpm",
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
                MetricType.STEPS -> {
                    if (stepsHistory.totalSteps == 0 && stepsHistory.chartData.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No step data available for this period.")
                        }
                    } else {
                        LazyColumn {
                            item {
                                Text("Total Steps: ${stepsHistory.totalSteps}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                            item {
                                if (stepsHistory.chartData.isNotEmpty()) {
                                    StepsBarChart(stepsHistory.chartData)
                                }
                            }
                            items(stepsHistory.listData) {
                                data ->
                                Row(modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp)) {
                                    Text(
                                        text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(data.timestamp)),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(
                                        text = "${data.steps} steps",
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
                MetricType.SLEEP -> {
                    val totalSleep = historyData.sumOf { it.sleepDuration?.toInt() ?: 0 }
                    if (totalSleep == 0 && historyData.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No sleep data available for this period.")
                        }
                    } else {
                        // Filter for selected date
                        val cal = Calendar.getInstance()
                        cal.timeInMillis = selectedDate
                        cal.set(Calendar.HOUR_OF_DAY, 0)
                        cal.set(Calendar.MINUTE, 0)
                        cal.set(Calendar.SECOND, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        val dayStart = cal.timeInMillis
                        cal.add(Calendar.DATE, 1)
                        val dayEnd = cal.timeInMillis
                        val selectedDateSleepData = historyData.filter { it.timestamp in dayStart until dayEnd }
                        LazyColumn {
                            item {
                                val selectedTotalSleep = selectedDateSleepData.sumOf { it.sleepDuration?.toInt() ?: 0 }
                                Text("Total Sleep: ${selectedTotalSleep / 60}h ${selectedTotalSleep % 60}m", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                            item {
                                if (historyData.isNotEmpty()) {
                                    val chartModelProducer = ChartEntryModelProducer(historyData.mapIndexed { index, data ->
                                        entryOf(index.toFloat(), (data.sleepDuration?.toFloat() ?: 0f) / 60f) // hours
                                    })
                                    val bottomAxisValueFormatter = AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
                                        try {
                                            val dataPoint = historyData[value.toInt()]
                                            SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(dataPoint.timestamp))
                                        } catch (_: IndexOutOfBoundsException) {
                                            ""
                                        }
                                    }
                                    ProvideChartStyle(rememberChartStyle()) {
                                        val primaryColor = MaterialTheme.colorScheme.primary
                                        Chart(
                                            chart = lineChart(
                                                lines = listOf(
                                                    LineChart.LineSpec(
                                                        lineColor = primaryColor.toArgb(),
                                                        lineBackgroundShader = verticalGradient(
                                                            arrayOf(
                                                                primaryColor.copy(alpha = 0.5f),
                                                                primaryColor.copy(alpha = 0f)
                                                            )
                                                        )
                                                    )
                                                )
                                            ),
                                            chartModelProducer = chartModelProducer,
                                            startAxis = rememberStartAxis(),
                                            bottomAxis = rememberBottomAxis(valueFormatter = bottomAxisValueFormatter)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                            }
                            items(selectedDateSleepData) { data ->
                                Row(modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp)) {
                                    val format = "EEE, d MMM"
                                    Text(
                                        text = SimpleDateFormat(format, Locale.getDefault()).format(Date(data.timestamp)),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    val valueText = data.sleepDuration?.let { "${it / 60}h ${it % 60}m" } ?: ""
                                    Text(text = valueText, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
                else -> {
                    if (historyData.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No history data available for this period.")
                        }
                    } else {
                        val chartModelProducer = ChartEntryModelProducer(historyData.mapIndexed { index, data ->
                            val value = when(metricType) {
                                MetricType.CALORIES -> data.calories ?: 0f
                                MetricType.DISTANCE -> data.distance ?: 0f
                                MetricType.SLEEP -> (data.sleepDuration?.toFloat() ?: 0f) / 60f // Show hours in chart
                                else -> 0f
                            }
                            entryOf(index.toFloat(), value)
                        })

                        val bottomAxisValueFormatter = AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
                            try {
                                val dataPoint = historyData[value.toInt()]
                                val format = when(metricType) {
                                    MetricType.SLEEP -> "d MMM"
                                    else -> "h a"
                                }
                                SimpleDateFormat(format, Locale.getDefault()).format(Date(dataPoint.timestamp))
                            } catch (_: IndexOutOfBoundsException) {
                                ""
                            }
                        }

                        ProvideChartStyle(rememberChartStyle()) {
                            val primaryColor = MaterialTheme.colorScheme.primary
                            Chart(
                                chart = lineChart(
                                    lines = listOf(
                                        LineChart.LineSpec(
                                            lineColor = primaryColor.toArgb(),
                                            lineBackgroundShader = verticalGradient(
                                                arrayOf(
                                                    primaryColor.copy(alpha = 0.5f),
                                                    primaryColor.copy(alpha = 0f)
                                                )
                                            )
                                        )
                                    )
                                ),
                                chartModelProducer = chartModelProducer,
                                startAxis = rememberStartAxis(),
                                bottomAxis = rememberBottomAxis(valueFormatter = bottomAxisValueFormatter)
                            )
                        }


                        Spacer(modifier = Modifier.height(16.dp))

                        LazyColumn {
                            items(historyData) {
                                data ->
                                Row(modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp)) {
                                    val format = when (metricType) {
                                        MetricType.SLEEP -> "EEE, d MMM"
                                        else -> "h:mm a"
                                    }
                                    Text(
                                        text = SimpleDateFormat(format, Locale.getDefault()).format(Date(data.timestamp)),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    val valueText = when(metricType) {
                                        MetricType.CALORIES -> data.calories?.let { "$it kcal" }
                                        MetricType.DISTANCE -> data.distance?.let { "${String.format(Locale.US, "%.2f", it)} km" }
                                        MetricType.SLEEP -> data.sleepDuration?.let { "${it / 60}h ${it % 60}m" }
                                        else -> ""
                                    }
                                    Text(text = valueText ?: "", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DateSelector(selectedDate: Long, onDateSelected: (Long) -> Unit) {
    val dates = (0..30).map {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DATE, -it)
        cal.timeInMillis
    }.asReversed()

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = dates.size - 1)

    LazyRow(state = listState, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        items(dates) { date ->
            val cal = Calendar.getInstance()
            cal.timeInMillis = date
            val isSelected = cal.get(Calendar.DAY_OF_YEAR) == Calendar.getInstance().apply { timeInMillis = selectedDate }.get(Calendar.DAY_OF_YEAR) &&
                    cal.get(Calendar.YEAR) == Calendar.getInstance().apply { timeInMillis = selectedDate }.get(Calendar.YEAR)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clickable { onDateSelected(date) }
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .padding(8.dp)
            ) {
                Text(
                    text = SimpleDateFormat("d", Locale.getDefault()).format(Date(date)),
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    text = SimpleDateFormat("EEE", Locale.getDefault()).format(Date(date)),
                    fontSize = 12.sp,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun DailyHeartRateSummary(summary: HeartRateDailySummary) {
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Daily Summary", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("${summary.min.toInt()}-${summary.max.toInt()} bpm", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (summary.max - summary.min) / (220f - 40f) }, // Example range
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun HourlyHeartRateChart(hourlyData: List<HourlyHeartRateData>) {
    val chartModelProducer = ChartEntryModelProducer(
        hourlyData.mapIndexed { index, data -> entryOf(index.toFloat(), data.min) },
        hourlyData.mapIndexed { index, data -> entryOf(index.toFloat(), data.max - data.min) }
    )

    val bottomAxisValueFormatter = AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
        try {
            val dataPoint = hourlyData[value.toInt()]
            SimpleDateFormat("h a", Locale.getDefault()).format(Date(dataPoint.timestamp))
        } catch (_: IndexOutOfBoundsException) {
            ""
        }
    }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Min/max heart rate per hour", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            ProvideChartStyle(rememberChartStyle()) {
                Chart(
                    chart = columnChart(
                        columns = listOf(
                            LineComponent(
                                color = Color.Transparent.toArgb(),
                                thicknessDp = 8f
                            ),
                            LineComponent(
                                color = Color(0xFFF9844A).toArgb(), // Orange color similar to image
                                thicknessDp = 8f,
                                shape = Shapes.roundedCornerShape(allPercent = 50)
                            )
                        ),
                        mergeMode = ColumnChart.MergeMode.Stack
                    ),
                    chartModelProducer = chartModelProducer,
                    startAxis = rememberStartAxis(),
                    bottomAxis = rememberBottomAxis(valueFormatter = bottomAxisValueFormatter),
                )
            }
        }
    }
}

@Composable
fun StepsBarChart(chartData: List<HealthData>) {
    val chartModelProducer = ChartEntryModelProducer(
        chartData.mapIndexed { index, data ->
            entryOf(index.toFloat(), data.steps?.toFloat() ?: 0f)
        }
    )

    val bottomAxisValueFormatter = AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
        try {
            val dataPoint = chartData[value.toInt()]
            val cal = Calendar.getInstance()
            cal.timeInMillis = dataPoint.timestamp
            if (cal.get(Calendar.MINUTE) == 0) {
                SimpleDateFormat("h a", Locale.getDefault()).format(Date(dataPoint.timestamp))
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Steps per interval", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            ProvideChartStyle(rememberChartStyle()) {
                Chart(
                    chart = columnChart(
                        columns = listOf(
                            LineComponent(
                                color = Color(0xFF4361EE).toArgb(), // Blue color
                                thicknessDp = 8f,
                                shape = Shapes.roundedCornerShape(topRightPercent = 50, topLeftPercent = 50)
                            )
                        )
                    ),
                    chartModelProducer = chartModelProducer,
                    startAxis = rememberStartAxis(),
                    bottomAxis = rememberBottomAxis(valueFormatter = bottomAxisValueFormatter),
                )
            }
        }
    }
}

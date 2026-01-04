package com.tharun.vitalmind.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.patrykandpatrick.vico.compose.style.ChartStyle

@Composable
fun rememberChartStyle(): ChartStyle {
    val axisLabelColor = MaterialTheme.colorScheme.onBackground
    val axisGuidelineColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f)
    val axisLineColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)

    return remember(axisLabelColor, axisGuidelineColor, axisLineColor) {
        ChartStyle(
            axis = ChartStyle.Axis(
                axisLabelColor = axisLabelColor,
                axisGuidelineColor = axisGuidelineColor,
                axisLineColor = axisLineColor,
            ),
            columnChart = ChartStyle.ColumnChart(
                columns = emptyList(),
            ),
            lineChart = ChartStyle.LineChart(
                lines = emptyList(),
            ),
            marker = ChartStyle.Marker(),
            elevationOverlayColor = Color.Transparent
        )
    }
}

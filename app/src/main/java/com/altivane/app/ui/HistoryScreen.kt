package com.altivane.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.altivane.app.R
import com.altivane.app.RideApp
import com.altivane.app.data.RideEntity
import com.altivane.app.domain.Coach
import com.altivane.app.domain.SportType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun fmtDate(t: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(t))

private fun sportOf(r: RideEntity): SportType =
    try { SportType.valueOf(r.sportType) } catch (_: Exception) { SportType.RIDE }

@Composable
fun HistoryScreen(app: RideApp, onOpen: (Long) -> Unit, onBack: () -> Unit = {}) {
    val rides by app.db.dao().rides().collectAsState(initial = emptyList())
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                androidx.compose.material3.Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, null,
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                stringResource(R.string.history_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        if (rides.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 22.sp
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(rides, key = { it.id }) { r ->
                    RideCard(r) { onOpen(r.id) }
                }
            }
        }
    }
}

@Composable
private fun RideCard(r: RideEntity, onClick: () -> Unit) {
    val st = sportOf(r)
    val accent = when (st) {
        SportType.RIDE -> AvColors.cyan
        SportType.HIKE -> AvColors.green
    }
    GlassCard(Modifier.clickable(onClick = onClick), accent = accent) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .padding(0.dp)
                        .size(6.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                        .background(accent)
                )
                Text(
                    "  " + stringResource(R.string.fmt_sport_date, stringResource(st.labelRes), fmtDate(r.startTime)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.padding(top = 6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "%.1f".format(r.distanceM / 1000.0),
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 34.sp),
                    color = AvColors.onColor(accent)
                )
                Text(
                    " km",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${Coach.fmtDur(r.movingS * 1000L)}  ·  %.1f km/h  ·  %d kcal".format(r.avgKmh, r.kcal.toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

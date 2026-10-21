@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.altivane.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import com.altivane.app.R
import com.altivane.app.RideApp
import com.altivane.app.domain.CardType

/**
 * 编辑卡片：已添加（长按拖动排序，− 移除）/ 未添加（+ 添加）两区，双排小卡。
 */
@Composable
fun EditCardsScreen(app: RideApp, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val s by app.session.state.collectAsState()
    var order by remember { mutableStateOf(loadCards(ctx, s.sport)) }

    // 拖动排序状态
    var dragKey by remember { mutableStateOf<CardType?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val gridState = rememberLazyGridState()

    val unadded = CardType.entries.filter { it !in order }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null,
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Column {
                Text(stringResource(R.string.fmt_edit_cards, stringResource(s.sport.labelRes)), style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground)
                Text(stringResource(R.string.edit_cards_sub),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // ---- 已添加 ----
            item(span = { GridItemSpan(2) }, key = "h_add") {
                SectionHeader(
                    "已添加 · ${order.size}",
                    "长按卡片拖到目标位置即可排序",
                    AvColors.cyan
                )
            }
            items(order, key = { it.name }) { t ->
                val dragging = dragKey == t
                val base = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .then(if (dragging) Modifier.zIndex(2f) else Modifier)
                    .graphicsLayer {
                        if (dragging) {
                            translationX = dragOffset.x
                            translationY = dragOffset.y
                            scaleX = 1.05f
                            scaleY = 1.05f
                        }
                    }
                    .pointerInput(t) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragKey = t
                                dragOffset = Offset.Zero
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { ch, amt ->
                                ch.consume()
                                val me = gridState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.key == t.name }
                                    ?: return@detectDragGesturesAfterLongPress
                                dragOffset += amt
                                val cx = me.offset.x + me.size.width / 2f + dragOffset.x
                                val cy = me.offset.y + me.size.height / 2f + dragOffset.y
                                val names = order.map { it.name }.toSet()
                                val tgt = gridState.layoutInfo.visibleItemsInfo.firstOrNull { v ->
                                    v.key is String && v.key != t.name && v.key in names &&
                                            cx.toInt() in v.offset.x..(v.offset.x + v.size.width) &&
                                            cy.toInt() in v.offset.y..(v.offset.y + v.size.height)
                                }
                                if (tgt != null) {
                                    val tgtOff = tgt.offset
                                    val from = order.indexOfFirst { it.name == t.name }
                                    val to = order.indexOfFirst { it.name == tgt.key }
                                    if (from >= 0 && to >= 0) {
                                        order = order.toMutableList().apply { add(to, removeAt(from)) }
                                        saveCards(ctx, s.sport, order)
                                        // 修正位移保持视觉连续
                                        dragOffset += Offset(
                                            (me.offset.x - tgtOff.x).toFloat(),
                                            (me.offset.y - tgtOff.y).toFloat()
                                        )
                                    }
                                }
                            },
                            onDragEnd = { dragKey = null; dragOffset = Offset.Zero },
                            onDragCancel = { dragKey = null; dragOffset = Offset.Zero }
                        )
                    }
                DashboardCell(
                    t, on = true,
                    modifier = base,
                    onToggle = {
                        order = order.filter { it != t }
                        saveCards(ctx, s.sport, order)
                    },
                    value = cardValue(t, s),
                    hint = "长按拖动"
                )
            }

            // ---- 未添加 ----
            item(span = { GridItemSpan(2) }, key = "h_rest") {
                SectionHeader(
                    "未添加 · ${unadded.size}",
                    "点 + 号添加到首页看板",
                    AvColors.orange
                )
            }
            items(unadded, key = { "u_${it.name}" }) { t ->
                DashboardCell(
                    t, on = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                    onToggle = {
                        order = order + t
                        saveCards(ctx, s.sport, order)
                    },
                    value = cardValue(t, s),
                    hint = ""
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, sub: String, accent: androidx.compose.ui.graphics.Color) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(width = 18.dp, height = 3.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(accent)
        )
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.width(8.dp))
        Text(sub, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 看板小卡：色条 + 名称 + 预览值 + 右上角 +/− */
@Composable
private fun DashboardCell(
    t: CardType,
    on: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    value: Pair<String, String>,
    hint: String
) {
    val accent = accentOf(t)
    Box(
        modifier
            .alpha(if (on) 1f else 0.55f)
            .glassCard(isDark(), null)
    ) {
        // 右上角 +/−
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (on) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                    else AvColors.cyan
                )
                .border(
                    0.5.dp,
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    CircleShape
                )
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (on) "−" else "+",
                fontSize = 16.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Box(
                Modifier
                    .size(width = 16.dp, height = 3.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(accent)
            )
            Text(
                stringResource(t.labelRes),
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                if (value.first.isEmpty()) "实时渲染" else value.first,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1
            )
            Text(
                when {
                    t == CardType.COMPASS || t == CardType.TRACK || t == CardType.PROFILE -> "首页卡片实时渲染"
                    hint.isNotEmpty() -> hint
                    else -> value.second
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

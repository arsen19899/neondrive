package com.neondrive.launcher.ui.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.neondrive.launcher.apps.AppEntry
import com.neondrive.launcher.apps.AppRepository
import com.neondrive.launcher.ui.common.NeonScreenScaffold
import com.neondrive.launcher.ui.theme.Neon
import com.neondrive.launcher.ui.theme.neonGlow

@Composable
fun AllAppsScreen(accent: Color, accent2: Color, onBack: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { apps = AppRepository.load(context) }

    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter { it.label.contains(query, ignoreCase = true) }
    }

    NeonScreenScaffold(
        title = "Приложения",
        subtitle = "${apps.size} установлено",
        accent = accent,
        onBack = onBack,
        actions = {
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x660C1424))
                    .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 11.dp)
                    .size(width = 220.dp, height = 20.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text("Поиск…", color = Neon.TextLow, fontSize = 14.sp)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Neon.TextHi, fontSize = 14.sp),
                    cursorBrush = SolidColor(accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(filtered, key = { it.packageName }) { app ->
                AppTile(app, accent, accent2) { AppRepository.launch(context, app.packageName) }
            }
        }
    }
}

@Composable
private fun AppTile(app: AppEntry, accent: Color, accent2: Color, onClick: () -> Unit) {
    val bitmap = remember(app.packageName) {
        runCatching { app.icon?.toBitmap(144, 144)?.asImageBitmap() }.getOrNull()
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(62.dp)
                .neonGlow(if (app.system) accent else accent2, 18.dp, 0.16f, 10.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF0B1220))
                .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = app.label,
                    modifier = Modifier.size(42.dp)
                )
            } else {
                Text(app.label.take(1).uppercase(), color = accent, fontSize = 22.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            app.label,
            color = Neon.TextMid,
            fontSize = 11.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 13.sp
        )
    }
}

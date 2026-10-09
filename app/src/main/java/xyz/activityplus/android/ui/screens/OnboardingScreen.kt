package xyz.activityplus.android.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import xyz.activityplus.android.ui.theme.BrandEnd
import xyz.activityplus.android.ui.theme.BrandStart
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.ui.Actions
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.rememberUsageAccess
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric

@Composable
fun OnboardingScreen(onRequestNotifications: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val access = rememberUsageAccess()
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        // painterResource cannot load adaptive icons, so the icon is rebuilt from its layers.
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(BrandStart, BrandEnd))),
        ) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        Text(stringResource(R.string.onb_title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.onb_body), style = MaterialTheme.typography.bodyLarge, color = LocalSurfaces.current.muted)
        Card {
            Bullet(stringResource(R.string.onb_point_apps))
            Bullet(stringResource(R.string.onb_point_history))
            Bullet(stringResource(R.string.onb_point_statusbar))
            Bullet(stringResource(R.string.onb_point_private))
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.onb_access_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (access) Icon(Icons.Outlined.CheckCircle, null, tint = Metric.Energy)
            }
            Note(stringResource(R.string.access_body))
            if (!access) {
                OutlinedButton(onClick = { Actions.usageAccess(context) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.access_button))
                }
            }
        }
        Card {
            Text(stringResource(R.string.onb_notif_title), style = MaterialTheme.typography.titleMedium)
            Note(stringResource(R.string.onb_notif_body))
            OutlinedButton(onClick = onRequestNotifications, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.onb_notif_button))
            }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.onb_start))
        }
        Note(stringResource(R.string.onb_later), Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text("•", color = Metric.Cpu, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

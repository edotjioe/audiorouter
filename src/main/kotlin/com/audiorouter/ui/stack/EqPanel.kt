package com.audiorouter.ui.stack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiorouter.model.EqSettings
import com.audiorouter.ui.theme.Glass3
import com.audiorouter.ui.theme.GlassFill
import com.audiorouter.ui.theme.GlassStroke
import com.audiorouter.ui.theme.TextFaint
import com.audiorouter.ui.theme.TextHi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqPanel(
    settings: EqSettings,
    hue: Color,
    onSettingsChanged: (EqSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(GlassFill, RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
            .border(1.dp, GlassStroke, RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Header row: "EQ" label, enable toggle, flat button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        ) {
            Text(
                "EQUALIZER",
                color = TextFaint,
                fontSize = 9.5.sp,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = settings.enabled,
                onCheckedChange = { onSettingsChanged(settings.copy(enabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TextHi,
                    checkedTrackColor = hue.copy(alpha = 0.6f),
                    uncheckedThumbColor = TextFaint,
                    uncheckedTrackColor = Glass3
                ),
                modifier = Modifier.height(20.dp)
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { onSettingsChanged(settings.flat()) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text("Flat", color = TextFaint, fontSize = 10.sp)
            }
        }

        // 10 band sliders
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            EqSettings.BAND_LABELS.forEachIndexed { i, label ->
                EqBandSlider(
                    label = label,
                    gain = settings.gains.getOrElse(i) { 0f },
                    enabled = settings.enabled,
                    hue = hue,
                    onGainChange = { db -> onSettingsChanged(settings.withGain(i, db)) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqBandSlider(
    label: String,
    gain: Float,
    enabled: Boolean,
    hue: Color,
    onGainChange: (Float) -> Unit
) {
    // Local state for smooth drag feedback — the audio engine is only notified on release.
    var localGain by remember { mutableStateOf(gain) }
    LaunchedEffect(gain) { localGain = gain }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(52.dp)
    ) {
        // dB value (reflects live drag position)
        Text(
            text = if (localGain == 0f) "0" else "%+.1f".format(localGain).trimEnd('0').trimEnd('.'),
            color = if (enabled) TextHi else TextFaint,
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)
        )

        Slider(
            value = localGain,
            onValueChange = { localGain = it },               // smooth UI, no audio rebuild
            onValueChangeFinished = { onGainChange(localGain) }, // one rebuild on release
            valueRange = EqSettings.GAIN_MIN..EqSettings.GAIN_MAX,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = if (enabled) hue else TextFaint,
                activeTrackColor = hue.copy(alpha = if (enabled) 0.8f else 0.3f),
                inactiveTrackColor = Glass3
            ),
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(2.dp),
                    colors = SliderDefaults.colors(
                        activeTrackColor = hue.copy(alpha = if (enabled) 0.8f else 0.3f),
                        inactiveTrackColor = Glass3
                    )
                )
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
        )

        // Frequency label
        Text(
            text = label,
            color = TextFaint,
            fontSize = 8.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
        )
    }
}

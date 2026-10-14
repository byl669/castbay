package com.weenas.castbay.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weenas.castbay.R
import com.weenas.castbay.dlna.DlnaSender
import com.weenas.castbay.service.StreamInfo

/**
 * What is casting, at a glance: the CastBay mark and name with a "connected" dot, then how
 * (AirPlay or DLNA) and from what ("eason的iPhone", or the app for DLNA). With several phones
 * or apps able to cast to several TVs, it tells which one is on this screen. At most
 * [maxWidth] wide, the second line: a longer sender name scrolls.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CastingBadge(stream: StreamInfo, maxWidth: Dp, modifier: Modifier = Modifier) {
    val protocol = if (stream.isDlna) "DLNA" else "AirPlay"
    val sender = if (stream.isDlna) dlnaSenderLabel(stream.sender) else stream.sender
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_castbay_mark), contentDescription = null, modifier = Modifier.size(36.dp))
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.app_name), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.size(10.dp))
            Spacer(modifier = Modifier.size(11.dp).clip(CircleShape).background(CONNECTED_GREEN))
        }
        Row(modifier = Modifier.widthIn(max = maxWidth), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (stream.isDlna) MediaIcons.Tv else MediaIcons.AirPlay,
                contentDescription = null,
                tint = SECONDARY,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                if (sender.isEmpty()) stringResource(R.string.casting_via, protocol)
                else stringResource(R.string.casting_via_from, protocol, sender),
                color = SECONDARY,
                fontSize = 19.sp,
                maxLines = 1,
                modifier = Modifier.basicMarquee(
                    iterations = Int.MAX_VALUE,
                    initialDelayMillis = 2000,
                    repeatDelayMillis = 2000,
                    velocity = 40.dp
                )
            )
        }
    }
}

/** The casting app's name, in the TV's language, from its [DlnaSender] id; "" if unknown. */
@Composable
private fun dlnaSenderLabel(id: String): String = DlnaSender.nameRes(id)?.let { stringResource(it) }.orEmpty()

private val CONNECTED_GREEN = Color(0xFF34C759)
/** Translucent white, like the music screen's other secondary text. */
private val SECONDARY = Color.White.copy(alpha = 0.72f)

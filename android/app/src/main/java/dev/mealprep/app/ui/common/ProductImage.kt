package dev.mealprep.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.mealprep.app.R

/**
 * A product photo (Coil, disk-cached) on a white tile (the photos are cut out on white), or a placeholder when
 * there is none (carts built before 0.12.0) or it can't load. [description] = the product's name for TalkBack;
 * null when the text beside it already says it. [dim] greys an out-of-stock product.
 */
@Composable
fun ProductImage(url: String?, description: String?, modifier: Modifier = Modifier, dim: Boolean = false,
                 contentScale: ContentScale = ContentScale.Fit) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(if (url != null && !failed) Color.White else MaterialTheme.colorScheme.surfaceVariant)
        .alpha(if (dim) 0.4f else 1f), contentAlignment = Alignment.Center) {
        if (url == null || failed) {
            Icon(painterResource(R.drawable.ic_cart), contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        } else {
            AsyncImage(url, contentDescription = description, modifier = Modifier.fillMaxSize().padding(2.dp),
                contentScale = contentScale, onError = { failed = true })
        }
    }
}

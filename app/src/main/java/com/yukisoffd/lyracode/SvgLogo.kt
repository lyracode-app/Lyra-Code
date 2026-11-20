package com.yukisoffd.lyracode

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
internal fun themedAiLogoRes(@DrawableRes logoRes: Int): Int {
    if (!LocalDarkTheme.current) return logoRes
    return when (logoRes) {
        R.drawable.ai_service_anthropic -> R.drawable.ai_service_anthropic_dark
        R.drawable.ai_service_moonshot -> R.drawable.ai_service_moonshot_dark
        R.drawable.ai_service_openai -> R.drawable.ai_service_openai_dark
        R.drawable.ai_service_opencode -> R.drawable.ai_service_opencode_dark
        R.drawable.ai_service_stepfun -> R.drawable.ai_service_stepfun_dark
        R.drawable.ai_service_xai -> R.drawable.ai_service_xai_dark
        R.drawable.ai_service_xiaomimimo -> R.drawable.ai_service_xiaomimimo_dark
        R.drawable.ai_service_zai -> R.drawable.ai_service_zai_dark
        R.drawable.ai_model_grok -> R.drawable.ai_model_grok_dark
        R.drawable.ai_model_kimi -> R.drawable.ai_model_kimi_dark
        R.drawable.ai_model_longcat -> R.drawable.ai_model_longcat_dark
        R.drawable.ai_model_openai -> R.drawable.ai_model_openai_dark
        R.drawable.ai_model_stepfun -> R.drawable.ai_model_stepfun_dark
        R.drawable.ai_model_xiaomimimo -> R.drawable.ai_model_xiaomimimo_dark
        R.drawable.ai_model_zeroone -> R.drawable.ai_model_zeroone_dark
        else -> logoRes
    }
}

@Composable
internal fun AiLogoBadge(
    @DrawableRes logoRes: Int?,
    fallback: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (logoRes == null) {
            Text(
                fallback.trim().firstOrNull()?.uppercase() ?: "?",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            Image(
                painter = painterResource(themedAiLogoRes(logoRes)),
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize()
                    .padding(3.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

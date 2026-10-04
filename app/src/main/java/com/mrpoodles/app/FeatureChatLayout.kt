@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.mrpoodles.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The caller owns conversations and actions; this layout never submits or merges feature data. */
@Composable internal fun ContextChatLayout(
    modifier: Modifier = Modifier,
    keyboardVisible: Boolean = WindowInsets.isImeVisible,
    showContextBorder: Boolean = false,
    context: @Composable () -> Unit,
    compactContext: @Composable () -> Unit = context,
    conversation: @Composable BoxScope.() -> Unit,
    composer: @Composable () -> Unit
) {
    val contextScroll = rememberScrollState()
    val compactScroll = rememberScrollState()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = keyboardVisible || maxHeight < 400.dp
        val contextHeight = if (compact) (maxHeight * .2f).coerceAtLeast(64.dp) else maxHeight * .35f
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().heightIn(max = contextHeight)
                .verticalScroll(if (compact) compactScroll else contextScroll).testTag("feature_context")) {
                if (compact) compactContext() else context()
            }
            if (showContextBorder) HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                thickness = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(Modifier.weight(1f).fillMaxWidth(), content = conversation)
            composer()
        }
    }
}

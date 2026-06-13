package com.tangotori.app.ui.sentence

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tangotori.app.data.billing.BillingRepository
import com.tangotori.app.ui.sentence.SentenceViewModel.KeySaveState

/**
 * Action that opens the usage-limits screen, provided at the top of
 * SentenceScreen so the deeply nested limit notice (inside the word cards)
 * can trigger it without threading a callback through six composables.
 */
val LocalOpenUsageInfo = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * The red one-time "daily free limit reached" notice shown in the slot where
 * an AI result (in-context meaning / compound meaning) would have appeared,
 * with a tappable "Learn more" that opens [UsageLimitScreen].
 */
@Composable
fun DailyLimitNotice(modifier: Modifier = Modifier) {
    val openUsageInfo = LocalOpenUsageInfo.current
    val text = buildAnnotatedString {
        append(
            "Due to the fact that API usage for this feature costs money and " +
                "I am paying for it out of pocket, there is a limit on daily " +
                "free usage. ",
        )
        withLink(LinkAnnotation.Clickable("learn_more") { openUsageInfo() }) {
            withStyle(
                SpanStyle(
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline,
                ),
            ) {
                append("Learn more")
            }
        }
    }
    Text(
        text,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}

/** Walks ContextWrappers to the host Activity (needed by the Play billing flow). */
private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Full-screen "AI usage & limits" screen: explains the free daily limit and
 * offers the two ways past it — bring-your-own Anthropic API key, or a
 * one-time usage-boost purchase (8x / 20x).
 */
@Composable
fun UsageLimitScreen(
    viewModel: SentenceViewModel,
    onDismiss: () -> Unit,
) {
    val hasKey by viewModel.hasUserApiKey.collectAsStateWithLifecycle()
    val keySaveState by viewModel.keySaveState.collectAsStateWithLifecycle()
    val tier by viewModel.usageTier.collectAsStateWithLifecycle()
    val ownsBase8x by viewModel.ownsBase8x.collectAsStateWithLifecycle()
    val products by viewModel.billingProducts.collectAsStateWithLifecycle()
    val billingMessage by viewModel.billingMessage.collectAsStateWithLifecycle()
    val aiEnabled by viewModel.aiEnabled.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()

    // Billing messages (purchase success / failure) auto-dismiss after a beat.
    LaunchedEffect(billingMessage) {
        if (billingMessage != null) {
            kotlinx.coroutines.delay(5_000)
            viewModel.consumeBillingMessage()
        }
    }

    // Rendered as an in-window overlay (not a Dialog) so WindowInsets propagate
    // and systemBarsPadding clears the nav bar — Compose Dialogs don't reliably
    // dispatch insets, which clipped the scroll bottom under the system bars.
    BackHandler(onBack = onDismiss)
    Surface(
        // clickable swallows taps so they don't fall through to the dictionary
        // behind this opaque full-screen overlay.
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
                // ── Header ────────────────────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                ) {
                    Text(
                        "AI usage & limits",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(4.dp))

                // ── AI features master switch (privacy opt-out) ───────────
                OptionCard(title = "AI features") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (aiEnabled) {
                                "On: looked-up sentences are sent for AI meanings"
                            } else {
                                "Off: the app makes no network requests; " +
                                    "everything stays on this device"
                            },
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.material3.Switch(
                            checked = aiEnabled,
                            onCheckedChange = viewModel::setAiEnabled,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Turning AI features off disables in-context meanings and " +
                            "compound interpretation, but the full offline dictionary " +
                            "and Anki integration keep working as usual.",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }

                // Everything below the master switch is moot when AI is off,
                // so collapse it away (and reveal it again) with an animation.
                AnimatedVisibility(
                    visible = aiEnabled,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                  Column {
                Spacer(Modifier.height(16.dp))

                Text(
                    "Several features are powered by Anthropic's Claude Haiku 4.5 " +
                        "model: glossing Chinese compound words, breaking Japanese " +
                        "idioms and compounds into their parts, and the in-context " +
                        "meaning of a word as used in your specific sentence.",
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "The first two are the same for everyone, so I can cache and " +
                        "reuse their results, which keeps them free and unlimited. " +
                        "In-context meanings are different: each one is unique to your " +
                        "exact sentence, so it can't be reused and needs a fresh API " +
                        "call every time. Those calls cost money, and since Tango Tori " +
                        "is a free, open-source project that I pay for out of pocket, " +
                        "I can only offer a limited amount of free in-context usage " +
                        "each day, which resets at midnight UTC.",
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "To get more in-context meanings beyond the daily free limit, " +
                        "there are two options:",
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                )

                Spacer(Modifier.height(16.dp))

                // ── Option 1: bring your own API key ──────────────────────
                OptionCard(title = "Option 1 · Use your own API key") {
                    Text(
                        "Add your own Anthropic API key to use the AI features " +
                            "without limits, paying Anthropic directly for your " +
                            "own usage.",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(12.dp))

                    if (hasKey) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Your API key is active. Usage is unlimited.",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = viewModel::removeUserApiKey) {
                                Text("Remove")
                            }
                        }
                    } else {
                        var keyInput by remember { mutableStateOf("") }
                        var showKey by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it },
                            label = { Text("Anthropic API key") },
                            placeholder = { Text("sk-ant-…") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = if (showKey) VisualTransformation.None
                                else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showKey = !showKey }) {
                                    Icon(
                                        if (showKey) Icons.Default.VisibilityOff
                                        else Icons.Default.Visibility,
                                        contentDescription = if (showKey) "Hide key" else "Show key",
                                    )
                                }
                            },
                            isError = keySaveState == KeySaveState.INVALID,
                            supportingText = {
                                when (keySaveState) {
                                    KeySaveState.INVALID -> Text("That key was rejected by Anthropic. Double-check it.")
                                    KeySaveState.NETWORK_ERROR -> Text("Couldn't verify the key. Check your connection and try again.")
                                    else -> Text("Stored encrypted on this device only; never backed up or shared.")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.saveUserApiKey(keyInput) },
                            enabled = keyInput.isNotBlank() && keySaveState != KeySaveState.CHECKING,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (keySaveState == KeySaveState.CHECKING) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Verifying…")
                            } else {
                                Text("Verify & save key")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── Option 2: one-time usage boosts ───────────────────────
                OptionCard(title = "Option 2 · Boost your daily limit") {
                    Text(
                        "Increase your daily limit with a one-time purchase, " +
                            "which also helps support the app.",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(12.dp))

                    if (tier > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${tier}× usage boost active (~${if (tier == 8) 40 else 100} calls/day).",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    when {
                        tier >= 20 -> { /* maxed out — nothing left to buy */ }
                        ownsBase8x -> BoostRow(
                            title = "Upgrade to 20× usage",
                            subtitle = "~100 calls/day · you only pay the difference",
                            price = products[BillingRepository.PRODUCT_UPGRADE_20X]
                                ?.oneTimePurchaseOfferDetails?.formattedPrice ?: "$5",
                            onBuy = {
                                activity?.let {
                                    viewModel.purchaseBoost(it, BillingRepository.PRODUCT_UPGRADE_20X)
                                }
                            },
                        )
                        else -> {
                            BoostRow(
                                title = "8× usage",
                                subtitle = "~40 calls/day · one-time",
                                price = products[BillingRepository.PRODUCT_8X]
                                    ?.oneTimePurchaseOfferDetails?.formattedPrice ?: "$5",
                                onBuy = {
                                    activity?.let {
                                        viewModel.purchaseBoost(it, BillingRepository.PRODUCT_8X)
                                    }
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                            BoostRow(
                                title = "20× usage",
                                subtitle = "~100 calls/day · one-time",
                                price = products[BillingRepository.PRODUCT_20X]
                                    ?.oneTimePurchaseOfferDetails?.formattedPrice ?: "$10",
                                onBuy = {
                                    activity?.let {
                                        viewModel.purchaseBoost(it, BillingRepository.PRODUCT_20X)
                                    }
                                },
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "If you buy 8× and later upgrade to 20×, you'll " +
                                    "only pay the $5 difference, so don't worry " +
                                    "about wasting money.",
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }

                    billingMessage?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            it,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = viewModel::restorePurchases) {
                            Text("Restore purchases")
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                  }
                }
            }
        }
}

@Composable
private fun OptionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun BoostRow(
    title: String,
    subtitle: String,
    price: String,
    onBuy: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        OutlinedButton(onClick = onBuy) {
            Text(price)
        }
    }
}

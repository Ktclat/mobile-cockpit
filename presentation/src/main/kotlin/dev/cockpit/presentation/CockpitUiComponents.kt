package dev.cockpit.presentation

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import dev.cockpit.projection.model.MessageProjection
import dev.cockpit.projection.model.MessageRoleProjection

@Composable
internal fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    val translator = LocalCockpitTranslator.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = translator.text(title),
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineMedium,
        )
        actions()
    }
}

@Composable
internal fun SectionHeader(
    title: String,
    supportingText: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val translator = LocalCockpitTranslator.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = translator.text(title),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium,
            )
            if (supportingText != null) {
                Text(
                    text = translator.text(supportingText),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(translator.text(actionLabel))
            }
        }
    }
}

@Composable
internal fun DetailHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
) {
    val translator = LocalCockpitTranslator.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = translator.text("Navigate up"),
                modifier = Modifier.size(24.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        ) {
            Text(
                text = translator.text(title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = translator.text(subtitle),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        actions()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun AgentAvatar(
    name: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    avatarRef: String? = null,
) {
    val size = if (large) 64.dp else 44.dp
    val bitmap = remember(avatarRef) {
        avatarRef?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
    }
    Surface(
        modifier = modifier
            .size(size)
            .clip(CircleShape),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = CircleShape,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = name.trim().firstOrNull()?.uppercase() ?: "A",
                    style = if (large) {
                        MaterialTheme.typography.headlineMedium
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
internal fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.Neutral,
) {
    val translator = LocalCockpitTranslator.current
    val colors = when (tone) {
        StatusTone.Positive ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        StatusTone.Warning ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        StatusTone.Neutral ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        color = colors.first,
        contentColor = colors.second,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = translator.text(text),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

internal enum class StatusTone { Positive, Warning, Neutral }

@Composable
internal fun AgentSummaryCard(
    name: String,
    supportingText: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    avatarRef: String? = null,
    actionIcon: ImageVector? = null,
    actionContentDescription: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val translator = LocalCockpitTranslator.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { this.contentDescription = translator.text(contentDescription) }
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentAvatar(name, avatarRef = avatarRef)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = translator.text(supportingText),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (actionIcon != null && actionContentDescription != null && onAction != null) {
                IconButton(onClick = onAction, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = translator.text(actionContentDescription),
                        modifier = Modifier.size(24.dp),
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun ConversationSummaryCard(
    label: String,
    archived: Boolean,
    onOpen: () -> Unit,
    onRestore: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val translator = LocalCockpitTranslator.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !archived, onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = if (archived) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (archived) {
                            Icons.Rounded.Refresh
                        } else {
                            ImageVector.vectorResource(R.drawable.ic_chat_outline)
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = translator.text(label),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = translator.text(if (archived) "Archived conversation" else "Ready to continue"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (archived && onRestore != null) {
                TextButton(onClick = onRestore) {
                    Text(translator.text("Restore"))
                }
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun InfoBanner(
    title: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.Neutral,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val translator = LocalCockpitTranslator.current
    val container = when (tone) {
        StatusTone.Positive -> MaterialTheme.colorScheme.primaryContainer
        StatusTone.Warning -> MaterialTheme.colorScheme.tertiaryContainer
        StatusTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (tone) {
        StatusTone.Positive -> MaterialTheme.colorScheme.onPrimaryContainer
        StatusTone.Warning -> MaterialTheme.colorScheme.onTertiaryContainer
        StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = translator.text(title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!body.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(text = translator.text(body), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onAction) {
                    Text(translator.text(actionLabel))
                }
            }
        }
    }
}

@Composable
internal fun EmptyContentCard(
    icon: ImageVector,
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val translator = LocalCockpitTranslator.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Text(
                text = translator.text(title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
            )
            if (!body.isNullOrBlank()) {
                Text(
                    text = translator.text(body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) {
                    Text(translator.text(actionLabel))
                }
            }
        }
    }
}

@Composable
internal fun HeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val translator = LocalCockpitTranslator.current
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(48.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = translator.text(contentDescription),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
internal fun MessageBubble(
    message: MessageProjection,
    agentName: String,
    modifier: Modifier = Modifier,
    statusLabel: String? = null,
) {
    val translator = LocalCockpitTranslator.current
    val user = message.role == MessageRoleProjection.USER
    val role = translator.text(when (message.role) {
        MessageRoleProjection.USER -> "You"
        MessageRoleProjection.AGENT -> agentName
        MessageRoleProjection.SYSTEM -> "System"
    })
    val alignment = if (user) Alignment.CenterEnd else Alignment.CenterStart
    val container = when (message.role) {
        MessageRoleProjection.USER -> MaterialTheme.colorScheme.primaryContainer
        MessageRoleProjection.AGENT -> MaterialTheme.colorScheme.surface
        MessageRoleProjection.SYSTEM -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val content = when (message.role) {
        MessageRoleProjection.USER -> MaterialTheme.colorScheme.onPrimaryContainer
        MessageRoleProjection.AGENT -> MaterialTheme.colorScheme.onSurface
        MessageRoleProjection.SYSTEM -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    val shape = if (user) {
        RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
    } else {
        RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = translator.choose(
                    "$role message, ${statusLabel ?: message.status.name.lowercase()}",
                    "$role 消息，${translator.text(statusLabel ?: message.status.name.lowercase().replaceFirstChar { it.uppercase() })}",
                )
            },
        contentAlignment = alignment,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .widthIn(max = 560.dp),
            color = container,
            contentColor = content,
            shape = shape,
            border = if (message.role == MessageRoleProjection.AGENT) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            } else {
                null
            },
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = role,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(text = message.text, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(5.dp))
                Text(
                    text = translator.text(
                        statusLabel
                            ?: message.status.name.lowercase().replaceFirstChar { it.uppercase() },
                    ),
                    color = content.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
internal fun SecondaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val translator = LocalCockpitTranslator.current
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(translator.text(label))
    }
}

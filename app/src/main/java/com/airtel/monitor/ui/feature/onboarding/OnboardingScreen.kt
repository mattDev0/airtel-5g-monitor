@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.airtel.monitor.ui.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airtel.monitor.R
import com.airtel.monitor.core.network.GatewayDetector
import com.airtel.monitor.ui.designsystem.SectionCard
import com.airtel.monitor.ui.theme.AirtelTheme

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshGateway(GatewayDetector.detect(context))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.xl, vertical = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.lg)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Surface(
                shape = AirtelTheme.shapes.chip,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Router,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.onboarding_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.onboarding_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!uiState.isWifiConnected) {
            Surface(
                shape = AirtelTheme.shapes.tile,
                color = colors.warning.copy(alpha = 0.14f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WifiOff,
                        contentDescription = null,
                        tint = colors.warning
                    )
                    Text(
                        text = stringResource(R.string.onboarding_wifi_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.warning
                    )
                }
            }
        } else if (!uiState.detectedGatewayIp.isNullOrBlank()) {
            Surface(
                shape = AirtelTheme.shapes.tile,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Wifi,
                        contentDescription = null,
                        tint = colors.success
                    )
                    Text(
                        text = stringResource(R.string.onboarding_gateway_hint_fmt, uiState.detectedGatewayIp!!),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (uiState.routerHost.trim() != uiState.detectedGatewayIp) {
                        TextButton(onClick = viewModel::applyDetectedGateway) {
                            Text(stringResource(R.string.onboarding_use_detected_gw))
                        }
                    }
                }
            }
        }

        SectionCard(title = stringResource(R.string.onboarding_title)) {
            OutlinedTextField(
                value = uiState.routerHost,
                onValueChange = viewModel::updateHost,
                label = { Text(stringResource(R.string.onboarding_host_label)) },
                singleLine = true,
                enabled = !uiState.isTesting,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.username,
                onValueChange = viewModel::updateUsername,
                label = { Text(stringResource(R.string.onboarding_user_label)) },
                singleLine = true,
                enabled = !uiState.isTesting,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.password,
                onValueChange = viewModel::updatePassword,
                label = { Text(stringResource(R.string.onboarding_pass_label)) },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = stringResource(R.string.cd_toggle_reveal)
                        )
                    }
                },
                singleLine = true,
                enabled = !uiState.isTesting,
                modifier = Modifier.fillMaxWidth()
            )

            when (val status = uiState.testStatus) {
                OnboardingTestStatus.Idle -> Unit
                OnboardingTestStatus.Testing -> Unit
                is OnboardingTestStatus.Connected -> {
                    Surface(
                        shape = AirtelTheme.shapes.tile,
                        color = colors.success.copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = colors.success
                            )
                            Text(
                                text = stringResource(R.string.onboarding_connected_fmt, status.deviceLabel),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.success,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                is OnboardingTestStatus.WrongPassword -> {
                    StatusFeedbackBanner(message = status.message)
                }
                is OnboardingTestStatus.Unreachable -> {
                    StatusFeedbackBanner(message = status.message)
                }
            }

            FilledTonalButton(
                onClick = viewModel::testConnection,
                enabled = !uiState.isTesting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isTesting) {
                    LoadingIndicator(modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Text(stringResource(R.string.onboarding_testing_btn))
                } else {
                    Text(stringResource(R.string.onboarding_test_btn))
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f, fill = false))

        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Button(
                onClick = { viewModel.saveAndContinue(onFinished) },
                enabled = !uiState.isTesting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.onboarding_save_continue))
            }
            OutlinedButton(
                onClick = { viewModel.skipForNow(onFinished) },
                enabled = !uiState.isTesting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.onboarding_skip_btn))
            }
        }
    }
}

@Composable
private fun StatusFeedbackBanner(message: String) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    Surface(
        shape = AirtelTheme.shapes.tile,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = colors.error
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error
            )
        }
    }
}

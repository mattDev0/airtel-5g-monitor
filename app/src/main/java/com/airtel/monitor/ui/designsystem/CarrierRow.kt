package com.airtel.monitor.ui.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.airtel.monitor.R
import com.airtel.monitor.domain.CarrierComponent
import com.airtel.monitor.domain.CarrierTech
import com.airtel.monitor.ui.theme.AirtelTheme
import com.airtel.monitor.ui.theme.TabularMonospace

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarrierRow(
    carrier: CarrierComponent,
    modifier: Modifier = Modifier
) {
    val spacing = AirtelTheme.spacing
    val roleLabel = when {
        carrier.tech == CarrierTech.NR && carrier.isPrimary -> stringResource(R.string.carrier_role_pscell)
        carrier.isPrimary -> stringResource(R.string.carrier_role_pcc)
        else -> stringResource(R.string.carrier_role_scc)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AirtelTheme.shapes.tile,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Surface(
                shape = AirtelTheme.shapes.chip,
                color = if (carrier.isPrimary) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                }
            ) {
                Text(
                    text = roleLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (carrier.isPrimary) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier.padding(horizontal = spacing.sm, vertical = 2.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = carrier.band.ifEmpty { carrier.tech.name },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (carrier.earfcn.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                if (carrier.tech == CarrierTech.NR) R.string.carrier_arfcn_fmt else R.string.carrier_earfcn_fmt,
                                carrier.earfcn
                            ),
                            style = MaterialTheme.typography.bodySmall.merge(TabularMonospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (carrier.pci.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.carrier_pci_fmt, carrier.pci),
                            style = MaterialTheme.typography.bodySmall.merge(TabularMonospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (carrier.bandwidthMHz != null) {
                Text(
                    text = stringResource(R.string.carrier_bw_fmt, carrier.bandwidthMHz),
                    style = MaterialTheme.typography.labelMedium.merge(TabularMonospace),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

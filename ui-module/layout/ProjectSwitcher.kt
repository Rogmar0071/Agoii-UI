package agoii.ui.layout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import agoii.ui.core.ProjectDescriptor
import agoii.ui.theme.AgoiiColors
import agoii.ui.theme.AgoiiSpacing
import agoii.ui.theme.AgoiiTypography

/**
 * ProjectSwitcher — horizontal selector for multi-project support (UI-04).
 *
 * Displays available projects as tappable chips.
 * Selection callback MUST route through the module's state management
 * to trigger a new replayState() load for the selected project.
 *
 * UI-02 ENFORCEMENT: No derivation. Project list is provided externally.
 *
 * @param projects        List of available projects (provided, not derived)
 * @param selectedProjectId ID of the currently active project
 * @param onSelect        Callback when a project is selected
 * @param modifier        Optional Compose modifier
 */
@Composable
fun ProjectSwitcher(
    projects: List<ProjectDescriptor>,
    selectedProjectId: String,
    onSelect: (ProjectDescriptor) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Projects",
            style = AgoiiTypography.LabelLarge,
            color = AgoiiColors.TextSecondary
        )

        Spacer(modifier = Modifier.height(AgoiiSpacing.Small))

        LazyRow {
            items(projects) { project ->
                ProjectChip(
                    project = project,
                    isSelected = project.id == selectedProjectId,
                    onClick = { onSelect(project) }
                )
                Spacer(modifier = Modifier.width(AgoiiSpacing.Small))
            }
        }

        Spacer(modifier = Modifier.height(AgoiiSpacing.Default))
    }
}

/**
 * Individual project chip.
 */
@Composable
private fun ProjectChip(
    project: ProjectDescriptor,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(AgoiiSpacing.ButtonCornerRadius),
        color = if (isSelected) AgoiiColors.GovernancePrimary else AgoiiColors.SurfaceVariant,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                horizontal = AgoiiSpacing.Medium,
                vertical = AgoiiSpacing.Small
            )
        ) {
            Text(
                text = project.name,
                style = AgoiiTypography.LabelMedium,
                color = if (isSelected) AgoiiColors.Surface else AgoiiColors.TextPrimary
            )
        }
    }
}

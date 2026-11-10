/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.music.bitchord.ui.screens.calibration

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.ui.components.LocalLiquidGlassEnabled
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.ui.components.lightweightLiquidGlass
import com.music.bitchord.ui.icons.SfIcons
import kotlinx.coroutines.launch

/**
 * Headphone and IEM Catalog Picker Sheet.
 *
 * Provides:
 * - Search bar with instant clear.
 * - Brand filter chips (All, Moondrop, Tangzu, Truthear, Sennheiser, Sony, Apple, etc.).
 * - Catalog items with 56x56dp placeholder vector silhouettes, model name, brand, target curve badge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelCatalogPickerSheet(
    selectedModel: HeadphoneModel,
    onSelectModel: (HeadphoneModel) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedBrand by remember { mutableStateOf("All") }

    val closeAfter: (() -> Unit) -> Unit = { action ->
        if (!closing) {
            closing = true
            action()
            scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
        }
    }

    val isGlass = LocalLiquidGlassEnabled.current && isGlassSupported()

    val filteredModels = remember(searchQuery, selectedBrand) {
        if (searchQuery.isBlank() && selectedBrand == "All") {
            HeadphoneCatalog.MODELS
        } else {
            val brandFilter = if (selectedBrand == "All") null else selectedBrand
            val rankedAcoustic = com.music.bitchord.playback.calibration.AcousticModelCatalog.search(
                query = searchQuery,
                brandFilter = brandFilter
            )
            val modelMap = HeadphoneCatalog.MODELS.associateBy { it.id }
            val ranked = rankedAcoustic.mapNotNull { modelMap[it.id] }
            if (ranked.isNotEmpty()) {
                ranked
            } else {
                HeadphoneCatalog.MODELS.filter { model ->
                    val matchesBrand = selectedBrand == "All" || model.brand.equals(selectedBrand, ignoreCase = true)
                    val matchesSearch = searchQuery.isBlank() ||
                            model.name.contains(searchQuery, ignoreCase = true) ||
                            model.brand.contains(searchQuery, ignoreCase = true) ||
                            model.targetBaseline.contains(searchQuery, ignoreCase = true)
                    matchesBrand && matchesSearch
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = if (isGlass) {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            // Drag Indicator Pill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            }

            // Sheet Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.model_catalog_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.model_catalog_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(onClick = { closeAfter {} }) {
                    Icon(
                        imageVector = SfIcons.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Search Bar
            CatalogSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onClear = { searchQuery = "" },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            // Brand Filter Chips
            BrandFilterChips(
                brands = HeadphoneCatalog.BRANDS,
                selectedBrand = selectedBrand,
                onSelectBrand = { selectedBrand = it },
                modifier = Modifier.padding(vertical = 10.dp),
            )

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )

            // Model List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                items(filteredModels, key = { it.id }) { model ->
                    val isSelected = model.id == selectedModel.id
                    ModelListItem(
                        model = model,
                        isSelected = isSelected,
                        onClick = {
                            closeAfter {
                                onSelectModel(model)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val shape = RoundedCornerShape(12.dp)

    val backgroundModifier = if (isGlass) {
        Modifier.lightweightLiquidGlass(
            shape = shape,
            fallbackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    } else {
        Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(backgroundModifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.search_models_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AnimatedVisibility(
            visible = query.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Icon(
                imageVector = SfIcons.Close,
                contentDescription = stringResource(R.string.clear_search),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClear),
            )
        }
    }
}

@Composable
private fun BrandFilterChips(
    brands: List<String>,
    selectedBrand: String,
    onSelectBrand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isGlass = LocalLiquidGlassEnabled.current && isGlassSupported()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        brands.forEach { brand ->
            val isSelected = brand.equals(selectedBrand, ignoreCase = true)
            val shape = RoundedCornerShape(10.dp)

            val chipModifier = if (isSelected) {
                Modifier
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.primary)
            } else if (isGlass) {
                Modifier.lightweightLiquidGlass(
                    shape = shape,
                    fallbackColor = MaterialTheme.colorScheme.surfaceContainer,
                )
            } else {
                Modifier
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            }

            Box(
                modifier = chipModifier
                    .clickable { onSelectBrand(brand) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = brand,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelListItem(
    model: HeadphoneModel,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val isGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val shape = RoundedCornerShape(14.dp)

    val itemModifier = if (isSelected) {
        if (isGlass) {
            Modifier.lightweightLiquidGlass(
                shape = shape,
                fallbackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.20f),
            )
        } else {
            Modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
        }
    } else {
        Modifier
            .clip(shape)
            .clickable(onClick = onClick)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .then(itemModifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Standardized 56x56dp placeholder vector container
        DeviceSilhouette(
            formFactor = model.formFactor,
            isSelected = isSelected,
            size = 56.dp,
        )

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = model.brand,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                TargetBaselineBadge(text = model.targetBaseline)
            }
        }

        Spacer(Modifier.width(8.dp))

        if (isSelected) {
            Icon(
                imageVector = SfIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TargetBaselineBadge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

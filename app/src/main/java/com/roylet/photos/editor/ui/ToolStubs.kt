package com.roylet.photos.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.save.SaveMode
import com.roylet.photos.editor.save.SaveProgress

@Composable fun CropPanel(session: EditorSession, recipe: State<Recipe>, modifier: Modifier) { Box(modifier) }
@Composable fun CropOverlay(session: EditorSession, recipe: State<Recipe>, modifier: Modifier) { Box(modifier) }
@Composable fun CurvePanel(session: EditorSession, recipe: State<Recipe>, modifier: Modifier) { Box(modifier) }
@Composable fun HslPanel(session: EditorSession, recipe: State<Recipe>, modifier: Modifier) { Box(modifier) }
@Composable fun FiltersPanel(session: EditorSession, recipe: State<Recipe>, modifier: Modifier) { Box(modifier) }
@Composable fun SaveSheet(visible: Boolean, onDismiss: () -> Unit, onSave: (SaveMode, Boolean) -> Unit) {}
@Composable fun SaveProgressPanel(progress: SaveProgress?, onBackground: () -> Unit, onDismissError: () -> Unit) {}

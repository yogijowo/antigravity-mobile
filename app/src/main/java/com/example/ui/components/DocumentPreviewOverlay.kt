package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.documents.DocumentPreviewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DocumentPreviewOverlay(
    state: DocumentPreviewState,
    onDownloadSave: (File, String, String) -> Unit,
    onOpenExternal: (File, String) -> Unit,
    onShare: (File, String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state !is DocumentPreviewState.Hidden,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.fillMaxSize(),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .testTag("document_preview_overlay"),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (state) {
                is DocumentPreviewState.Hidden -> Unit

                is DocumentPreviewState.Loading -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        PreviewTopBar(
                            title = state.fileName,
                            onDismiss = onDismiss,
                        )
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = stringResource(R.string.doc_preview_loading),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                is DocumentPreviewState.Pdf -> {
                    PdfDocumentViewer(
                        file = state.file,
                        fileName = state.fileName,
                        mimeType = state.mimeType,
                        onDownloadSave = onDownloadSave,
                        onOpenExternal = onOpenExternal,
                        onShare = onShare,
                        onDismiss = onDismiss,
                    )
                }

                is DocumentPreviewState.Image -> {
                    ImageDocumentViewer(
                        file = state.file,
                        fileName = state.fileName,
                        mimeType = state.mimeType,
                        onDownloadSave = onDownloadSave,
                        onOpenExternal = onOpenExternal,
                        onShare = onShare,
                        onDismiss = onDismiss,
                    )
                }

                is DocumentPreviewState.Text -> {
                    TextDocumentViewer(
                        content = state.content,
                        file = state.file,
                        fileName = state.fileName,
                        mimeType = state.mimeType,
                        onDownloadSave = onDownloadSave,
                        onOpenExternal = onOpenExternal,
                        onShare = onShare,
                        onDismiss = onDismiss,
                    )
                }

                is DocumentPreviewState.ExternalReady -> {
                    ExternalDocumentCard(
                        file = state.file,
                        fileName = state.fileName,
                        mimeType = state.mimeType,
                        launchedExternally = state.launchedExternally,
                        onDownloadSave = onDownloadSave,
                        onOpenExternal = onOpenExternal,
                        onShare = onShare,
                        onDismiss = onDismiss,
                    )
                }

                is DocumentPreviewState.Error -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        PreviewTopBar(
                            title = "Pratinjau Dokumen",
                            onDismiss = onDismiss,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Card(
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(36.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.doc_preview_error, state.message),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center,
                                    )
                                    Button(onClick = onDismiss) {
                                        Text(text = stringResource(R.string.action_close))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewTopBar(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("doc_preview_close_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.doc_preview_close),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
            )
            actions()
        }
    }
}

@Composable
private fun PdfDocumentViewer(
    file: File,
    fileName: String,
    mimeType: String,
    onDownloadSave: (File, String, String) -> Unit,
    onOpenExternal: (File, String) -> Unit,
    onShare: (File, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pages by remember(file) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var renderError by remember(file) { mutableStateOf<String?>(null) }
    var isRendering by remember(file) { mutableStateOf(true) }
    var zoomScale by remember(file) { mutableFloatStateOf(1f) }

    LaunchedEffect(file) {
        isRendering = true
        renderError = null
        val rendered = withContext(Dispatchers.IO) {
            runCatching {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd.use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        val count = renderer.pageCount.coerceAtMost(MAX_RENDERED_PDF_PAGES)
                        val bitmaps = ArrayList<Bitmap>(count)
                        for (i in 0 until count) {
                            renderer.openPage(i).use { page ->
                                val targetWidth = 1440
                                val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                                val width = targetWidth
                                val height = (page.height * scale).toInt().coerceIn(100, 3200)
                                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                Canvas(bmp).drawColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bitmaps.add(bmp)
                            }
                        }
                        bitmaps
                    }
                }
            }
        }
        rendered.onSuccess { pages = it }
            .onFailure { renderError = it.localizedMessage ?: "File PDF tidak valid." }
        isRendering = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PreviewTopBar(
            title = fileName,
            onDismiss = onDismiss,
            actions = {
                IconButton(
                    onClick = { zoomScale = (zoomScale - 0.25f).coerceAtLeast(1f) },
                    enabled = zoomScale > 1f,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ZoomOut,
                        contentDescription = stringResource(R.string.doc_preview_zoom_out),
                    )
                }
                IconButton(
                    onClick = { zoomScale = (zoomScale + 0.25f).coerceAtMost(2.5f) },
                    enabled = zoomScale < 2.5f,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ZoomIn,
                        contentDescription = stringResource(R.string.doc_preview_zoom_in),
                    )
                }
                IconButton(
                    onClick = { onDownloadSave(file, fileName, mimeType) },
                    modifier = Modifier.testTag("doc_preview_download_pdf"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = stringResource(R.string.doc_preview_download),
                    )
                }
                IconButton(onClick = { onShare(file, mimeType) }) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = stringResource(R.string.doc_preview_share),
                    )
                }
                IconButton(onClick = { onOpenExternal(file, mimeType) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = stringResource(R.string.doc_preview_open_external),
                    )
                }
            },
        )

        when {
            isRendering -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            renderError != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.doc_preview_error, renderError.orEmpty()),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = { onOpenExternal(file, mimeType) }) {
                            Text(text = stringResource(R.string.doc_preview_open_external))
                        }
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(pages.indices.toList()) { index ->
                        val bitmap = pages[index]
                        val aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                ) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = stringResource(
                                            R.string.doc_preview_page_indicator,
                                            index + 1,
                                            pages.size,
                                        ),
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(aspect)
                                            .graphicsLayer(
                                                scaleX = zoomScale,
                                                scaleY = zoomScale,
                                            ),
                                    )
                                }
                                Text(
                                    text = stringResource(
                                        R.string.doc_preview_page_indicator,
                                        index + 1,
                                        pages.size,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .align(Alignment.End)
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageDocumentViewer(
    file: File,
    fileName: String,
    mimeType: String,
    onDownloadSave: (File, String, String) -> Unit,
    onOpenExternal: (File, String) -> Unit,
    onShare: (File, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        offset = if (scale > 1f) offset + panChange else Offset.Zero
    }

    LaunchedEffect(file) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PreviewTopBar(
            title = fileName,
            onDismiss = onDismiss,
            actions = {
                IconButton(
                    onClick = { onDownloadSave(file, fileName, mimeType) },
                    modifier = Modifier.testTag("doc_preview_download_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = stringResource(R.string.doc_preview_download),
                    )
                }
                IconButton(onClick = { onShare(file, mimeType) }) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = stringResource(R.string.doc_preview_share),
                    )
                }
                IconButton(onClick = { onOpenExternal(file, mimeType) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = stringResource(R.string.doc_preview_open_external),
                    )
                }
            },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .transformable(state = transformState),
            contentAlignment = Alignment.Center,
        ) {
            val loaded = bitmap
            if (loaded != null) {
                Image(
                    bitmap = loaded.asImageBitmap(),
                    contentDescription = fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
            } else {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun TextDocumentViewer(
    content: String,
    file: File,
    fileName: String,
    mimeType: String,
    onDownloadSave: (File, String, String) -> Unit,
    onOpenExternal: (File, String) -> Unit,
    onShare: (File, String) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PreviewTopBar(
            title = fileName,
            onDismiss = onDismiss,
            actions = {
                IconButton(
                    onClick = { onDownloadSave(file, fileName, mimeType) },
                    modifier = Modifier.testTag("doc_preview_download_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = stringResource(R.string.doc_preview_download),
                    )
                }
                IconButton(onClick = { onShare(file, mimeType) }) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = stringResource(R.string.doc_preview_share),
                    )
                }
                IconButton(onClick = { onOpenExternal(file, mimeType) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = stringResource(R.string.doc_preview_open_external),
                    )
                }
            },
        )
        SelectionContainer(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = content,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun ExternalDocumentCard(
    file: File,
    fileName: String,
    mimeType: String,
    launchedExternally: Boolean,
    onDownloadSave: (File, String, String) -> Unit,
    onOpenExternal: (File, String) -> Unit,
    onShare: (File, String) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PreviewTopBar(
            title = fileName,
            onDismiss = onDismiss,
            actions = {
                IconButton(
                    onClick = { onDownloadSave(file, fileName, mimeType) },
                    modifier = Modifier.testTag("doc_preview_download_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = stringResource(R.string.doc_preview_download),
                    )
                }
            },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            if (launchedExternally) R.string.doc_preview_opened_external else R.string.doc_preview_no_app,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = { onDownloadSave(file, fileName, mimeType) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("doc_preview_save_folder_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.doc_preview_download))
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { onShare(file, mimeType) }) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = stringResource(R.string.doc_preview_share))
                        }
                        OutlinedButton(onClick = { onOpenExternal(file, mimeType) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = stringResource(R.string.doc_preview_open_external))
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_RENDERED_PDF_PAGES = 40

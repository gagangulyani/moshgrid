package com.bitchat.android.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.core.ui.component.button.CloseButton
import com.bitchat.android.core.ui.component.sheet.LocalSheetDismiss
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.services.VerificationService
import com.bitchat.android.ui.mosh.MoshAvatar
import com.bitchat.android.ui.mosh.MoshShapes
import com.bitchat.android.ui.mosh.MoshTitle
import com.bitchat.android.ui.mosh.Why
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Why a scan didn't lead to an add. Shown in place of the viewfinder until "Scan again". */
private enum class ScanProblem { BadCode, OwnCode, NotNearby }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerificationSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    if (!isPresented) return

    var selectedTab by remember { mutableStateOf(0) } // 0 = My Code, 1 = Scan
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val npub = remember { viewModel.getCurrentNpub() }

    val qrString = remember(nickname, npub) {
        viewModel.buildMyQRString(nickname, npub)
    }
    // Our own noise key, read back from our own code, so scanning it can say so instead of failing silently.
    val myNoiseKey = remember(qrString) {
        VerificationService.VerificationQR.fromUrlString(qrString)?.noiseKeyHex
    }

    var scanned by remember { mutableStateOf<VerificationService.VerificationQR?>(null) }
    var problem by remember { mutableStateOf<ScanProblem?>(null) }
    var problemName by remember { mutableStateOf("") }
    var addedName by remember { mutableStateOf<String?>(null) }

    BitchatBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            MoshTitle("Add a friend") {
                val dismiss = LocalSheetDismiss.current
                CloseButton(onClick = { dismiss?.invoke() ?: onDismiss() })
            }

            CodeToggle(
                selected = selectedTab,
                onSelect = { selectedTab = it },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Crossfade(
                targetState = selectedTab,
                label = "VerificationTabCrossfade",
                modifier = Modifier.weight(1f)
            ) { tab ->
                when (tab) {
                    0 -> MyQrTabContent(
                        qrString = qrString,
                        nickname = nickname,
                        addedName = addedName
                    )
                    1 -> {
                        val pending = scanned
                        val currentProblem = problem
                        when {
                            pending != null -> ConfirmAdd(
                                qr = pending,
                                onAdd = {
                                    scanned = null
                                    if (viewModel.beginQRVerification(pending)) {
                                        addedName = pending.nickname
                                        selectedTab = 0
                                    } else {
                                        problemName = pending.nickname
                                        problem = ScanProblem.NotNearby
                                    }
                                },
                                onCancel = { scanned = null }
                            )
                            currentProblem != null -> ScanProblemContent(
                                problem = currentProblem,
                                name = problemName,
                                onScanAgain = { problem = null },
                                onShowMine = { problem = null; selectedTab = 0 }
                            )
                            else -> ScanTabContent(
                                onShowMine = { selectedTab = 0 },
                                onScan = { code ->
                                    val qr = VerificationService.verifyScannedQR(code)
                                    when {
                                        qr == null -> problem = ScanProblem.BadCode
                                        myNoiseKey != null && qr.noiseKeyHex.equals(myNoiseKey, ignoreCase = true) ->
                                            problem = ScanProblem.OwnCode
                                        else -> scanned = qr
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Unverify Action
            val peerID by viewModel.selectedPrivateChatPeer.collectAsStateWithLifecycle()
            val fingerprints by viewModel.verifiedFingerprints.collectAsStateWithLifecycle()

            if (peerID != null) {
                val fingerprint = viewModel.getMeshPeerFingerprint(peerID!!)
                if (fingerprint != null && fingerprints.contains(fingerprint)) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { viewModel.unverifyFingerprint(peerID!!) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = "Remove the verified mark",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

/** Two-way pill toggle between showing my code and scanning a friend's. */
@Composable
private fun CodeToggle(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(3.dp)
            .selectableGroup()
    ) {
        listOf("My code", "Scan").forEachIndexed { index, label ->
            val on = selected == index
            Box(
                Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MyQrTabContent(
    qrString: String?,
    nickname: String,
    addedName: String?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!qrString.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(18.dp) // Quiet zone
                    .semantics { contentDescription = "Your MoshGrid code" }
            ) {
                QRCodeImage(data = qrString, size = 240.dp)
            }
        } else {
            Box(
                modifier = Modifier
                    .size(276.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Your code isn't ready yet",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        Text(
            text = nickname,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Text(
            text = if (addedName != null) "Now let $addedName scan this." else "Your friend scans this. Then you scan theirs.",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Why(
            question = "Why scan both ways?",
            points = listOf(
                "Each of you checks it's really the other's phone.",
                "Works over Bluetooth. Stand close.",
                "Your code changes every few minutes, so an old screenshot won't work."
            )
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun ScanTabContent(
    onShowMine: () -> Unit,
    onScan: (String) -> Unit
) {
    val permissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (permissionState.status.isGranted) {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MoshShapes.Tile)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                ScannerView(onScan = onScan)
                // Viewfinder frame
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.68f)
                        .border(3.dp, Color.White, RoundedCornerShape(16.dp))
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Point at your friend's code",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Stand within a few metres.",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        } else {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Camera not allowed",
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "MoshGrid needs the camera to scan. Or let your friend scan your code.",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            PrimaryPill("Allow camera") { permissionState.launchPermissionRequest() }
            SecondaryLink("Open phone settings") {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            SecondaryLink("Show my code instead", onShowMine)
        }
    }
}

/** "Add <name>?" after a good scan. Nothing is sent until Add is tapped. */
@Composable
private fun ConfirmAdd(
    qr: VerificationService.VerificationQR,
    onAdd: () -> Unit,
    onCancel: () -> Unit
) {
    val name = qr.nickname.ifBlank { "this friend" }
    CenteredState {
        MoshAvatar(name = name, key = qr.noiseKeyHex, size = 88.dp)
        Spacer(Modifier.height(8.dp))
        Text("Add $name?", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        Text(
            "Then $name scans your code back.",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        PrimaryPill("Add $name", onAdd)
        SecondaryLink("Cancel", onCancel)
    }
}

@Composable
private fun ScanProblemContent(
    problem: ScanProblem,
    name: String,
    onScanAgain: () -> Unit,
    onShowMine: () -> Unit
) {
    val who = name.ifBlank { "your friend" }
    CenteredState {
        when (problem) {
            ScanProblem.BadCode -> {
                ProblemText("That code didn't work", "Scan it again from your friend's phone, not a photo.")
                Why(
                    question = "Why didn't it work?",
                    points = listOf(
                        "It may not be a MoshGrid code.",
                        "Codes stop working after 5 minutes.",
                        "A changed or damaged code is never saved."
                    )
                )
            }
            ScanProblem.OwnCode -> ProblemText("That's your own code", "Point the camera at your friend's phone.")
            ScanProblem.NotNearby -> ProblemText("Can't reach $who yet", "Stand close with Bluetooth on, then scan again.")
        }
        Spacer(Modifier.height(8.dp))
        PrimaryPill("Scan again", onScanAgain)
        SecondaryLink("Show my code instead", onShowMine)
    }
}

@Composable
private fun ProblemText(title: String, line: String) {
    Text(title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
    Text(line, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
private fun CenteredState(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
    ) { content() }
}

@Composable
private fun PrimaryPill(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = MoshShapes.Pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        modifier = Modifier.fillMaxWidth().height(56.dp)
    ) {
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SecondaryLink(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(48.dp)) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ScannerView(
    onScan: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lastValid by remember { mutableStateOf<String?>(null) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val surfaceRequests = remember { MutableStateFlow<SurfaceRequest?>(null) }
    val surfaceRequest by surfaceRequests.collectAsState(initial = null)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val onCodeState = rememberUpdatedState(onScan)
    val analyzer = remember {
        QRCodeAnalyzer { text ->
            mainHandler.post {
                if (text == lastValid) return@post
                lastValid = text
                onCodeState.value(text)
            }
        }
    }

    DisposableEffect(Unit) {
        val executor = ContextCompat.getMainExecutor(context)
        var cameraProvider: ProcessCameraProvider? = null

        cameraProviderFuture.addListener(
            {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider { request -> surfaceRequests.value = request }
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, analyzer) }

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }.onFailure {
                    Log.w("VerificationSheet", "Failed to bind camera: ${it.message}")
                }
            },
            executor
        )

        onDispose {
            surfaceRequests.value = null
            runCatching { cameraProvider?.unbindAll() }
            cameraExecutor.shutdown()
        }
    }

    surfaceRequest?.let { request ->
        CameraXViewfinder(
            surfaceRequest = request,
            implementationMode = ImplementationMode.EMBEDDED,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun QRCodeImage(data: String, size: Dp) {
    val sizePx = with(LocalDensity.current) { size.toPx().toInt() }
    val bitmap = remember(data, sizePx) { generateQrBitmap(data, sizePx) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size)
        )
    }
}

private fun generateQrBitmap(data: String, sizePx: Int): Bitmap? {
    if (data.isBlank() || sizePx <= 0) return null
    return try {
        val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, sizePx, sizePx)
        bitmapFromMatrix(matrix)
    } catch (_: Exception) {
        null
    }
}

private fun bitmapFromMatrix(matrix: BitMatrix): Bitmap {
    val width = matrix.width
    val height = matrix.height
    val bitmap = createBitmap(width, height)
    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap[x, y] =
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return bitmap
}

/**
 * QR analyzer on ZXing (Apache-2.0) instead of ML Kit, so the app has no proprietary Google
 * libraries. Reads the luminance plane only; QR decoding does not depend on frame rotation.
 */
internal class QRCodeAnalyzer(
    private val onCode: (String) -> Unit
) : ImageAnalysis.Analyzer {

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        try {
            val image = imageProxy.image ?: return
            val plane = image.planes[0]
            val width = image.width
            val height = image.height
            val buffer = plane.buffer
            val luminance = ByteArray(width * height)
            if (plane.pixelStride == 1 && plane.rowStride == width) {
                buffer.position(0); buffer.get(luminance, 0, luminance.size)
            } else {
                for (row in 0 until height) for (col in 0 until width) {
                    luminance[row * width + col] = buffer.get(row * plane.rowStride + col * plane.pixelStride)
                }
            }
            val text = decodeQrLuminance(luminance, width, height)
            if (!text.isNullOrBlank()) onCode(text)
        } catch (_: Exception) {
            // A bad frame is skipped; the next one is tried.
        } finally {
            imageProxy.close()
        }
    }
}

/** Decodes a QR code from an 8-bit luminance frame, normal or inverted. Null when none is found. */
internal fun decodeQrLuminance(luminance: ByteArray, width: Int, height: Int): String? {
    val hints = mapOf(
        com.google.zxing.DecodeHintType.TRY_HARDER to true,
        com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
    )
    val source = com.google.zxing.PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
    for (candidate in listOf(source, source.invert())) {
        try {
            val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(candidate))
            return com.google.zxing.qrcode.QRCodeReader().decode(bitmap, hints).text
        } catch (_: com.google.zxing.ReaderException) {
            // try the next candidate
        }
    }
    return null
}

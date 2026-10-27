package com.dhasharma.cutlab.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable fun ExportScreen(progress: Float?,uri: Uri?,thumbnail: Bitmap?,onCancel: () -> Unit,onDone: () -> Unit,onError: (String)->Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(view) { val old = view.keepScreenOn; view.keepScreenOn = true; onDispose { view.keepScreenOn = old } }
    fun launch(action: String) {
        try {
            val video = requireNotNull(uri)
            val intent = if(action == Intent.ACTION_SEND) Intent(action).setType("video/mp4").putExtra(Intent.EXTRA_STREAM,video)
                else Intent(action).setDataAndType(video,"video/mp4")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(if(action == Intent.ACTION_SEND) Intent.createChooser(intent,"Share video") else intent)
        } catch(e: Exception) { onError("No compatible video app is available: ${e.message}") }
    }
    Dialog(onDismissRequest = { if(uri == null) onCancel() else onDone() },properties = DialogProperties(usePlatformDefaultWidth = false,decorFitsSystemWindows = false)) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
    val ratio = thumbnail?.let { it.width.toFloat()/it.height.coerceAtLeast(1) } ?: (16f/9f)
    val imageWidth = minOf(maxWidth*.8f,280.dp*ratio)
    Column(Modifier.fillMaxSize(),horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(50.dp),verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if(uri == null) onCancel() else onDone() }) { Icon(Icons.Outlined.ChevronLeft,if(uri == null) "Cancel export" else "Back to editor",tint = Color.White) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = if(uri == null) onCancel else onDone) { Text(if(uri == null) "Cancel export" else "Done",color = Color.White) }
        }
        Spacer(Modifier.height(10.dp))
        Text(if(uri == null) "Exporting…" else "Ready to share",fontSize = 28.sp,fontWeight = FontWeight.SemiBold,color = Color.White)
        Text(if(uri == null) "Keep CutLab open while your video is saved." else "Saved to Movies/CutLab",color = Muted,modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(52.dp))
        Box(Modifier.width(imageWidth).height(imageWidth/ratio).clip(RoundedCornerShape(6.dp)).background(Color(0xFF191919))
            .then(if(uri != null) Modifier.clickable { launch(Intent.ACTION_VIEW) } else Modifier),contentAlignment = Alignment.Center) {
            thumbnail?.let { Image(it.asImageBitmap(),"Export preview",Modifier.fillMaxSize()) }
            if(uri == null) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)))
                CircularProgressIndicator(progress = { progress ?: 0f },modifier = Modifier.size(80.dp),color = Accent,strokeWidth = 3.dp)
                Text("${((progress ?: 0f)*100).roundToInt()}%",fontSize = 24.sp,fontWeight = FontWeight.Bold,color = Color.White)
            } else Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .45f)),contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.PlayArrow,"Play exported video",Modifier.size(28.dp),tint = Color.White)
            }
        }
        Spacer(Modifier.height(44.dp))
        if(uri != null) {
            Button(onClick = { launch(Intent.ACTION_SEND) },modifier = Modifier.fillMaxWidth().height(40.dp),shape = RoundedCornerShape(6.dp),colors = ButtonDefaults.buttonColors(containerColor = Color.White,contentColor = Color.Black)) {
                Icon(Icons.Outlined.Share,null); Spacer(Modifier.width(10.dp)); Text("Share video",fontSize = 16.sp)
            }
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = { launch(Intent.ACTION_VIEW) },modifier = Modifier.fillMaxWidth()) { Text("Play exported video") }
        } else LinearProgressIndicator(progress = { progress ?: 0f },modifier = Modifier.fillMaxWidth(),color = Accent)
        Spacer(Modifier.weight(1f))
    }
    }
    }
}

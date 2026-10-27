package com.dhasharma.cutlab.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dhasharma.cutlab.model.*
import kotlin.math.roundToInt

fun clockLabel(us: Long): String = "%02d:%02d".format(us.coerceAtLeast(0)/60_000_000,us.coerceAtLeast(0)/1_000_000%60)

@Composable fun EditorHeader(resolution: Int, settings: Boolean, canExport: Boolean, onBack: () -> Unit, onSettings: () -> Unit, onExport: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(46.dp).padding(start = 12.dp,end = 16.dp),verticalAlignment = Alignment.CenterVertically) {
        if(settings) Text("video",fontSize = 16.sp,fontWeight = FontWeight.SemiBold,modifier = Modifier.weight(1f))
        else {
            IconButton(onClick = onBack,modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Close,"Back to projects",Modifier.size(27.dp)) }
            IconButton(onClick = onMore,modifier = Modifier.padding(start = 8.dp).size(32.dp)) { Icon(Icons.Outlined.MoreHoriz,"Project options",Modifier.size(25.dp)) }
            Spacer(Modifier.weight(1f))
        }
        key(resolution) {
            Button(onClick = onSettings,colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29292B),contentColor = Color.White),shape = RoundedCornerShape(8.dp),contentPadding = PaddingValues(0.dp),modifier = Modifier.width(73.dp).height(32.dp)) {
                Text("${resolution}P",fontSize = 14.sp,fontWeight = FontWeight.Bold,color = Color.White,modifier = Modifier.graphicsLayer())
                Icon(if(settings) Icons.Outlined.ArrowDropUp else Icons.Outlined.ArrowDropDown,"Export settings",Modifier.size(14.dp))
            }
        }

        Spacer(Modifier.width(10.dp))
        Button(onClick = onExport,enabled = canExport,shape = RoundedCornerShape(8.dp),contentPadding = PaddingValues(0.dp),modifier = Modifier.height(32.dp).width(64.dp)) { Text("Export",fontSize = 14.sp,fontWeight = FontWeight.SemiBold,color = Color(0xFF071A1F)) }
    }
}

@Composable private fun Tool(label: String, icon: ImageVector, action: () -> Unit) {
    Column(Modifier.width(60.dp).fillMaxHeight().clickable(onClick = action).padding(top = 12.dp),horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon,null,Modifier.size(25.dp),tint = Color(0xFFCCCCCC))
        Text(label,fontSize = 10.sp,color = Color(0xFFCCCCCC),modifier = Modifier.padding(top = 7.dp),maxLines = 2)
    }
}

@Composable fun EditorToolBar(tab: String, clip: Clip?, recording: Boolean, onBack: () -> Unit, onTool: (String) -> Unit, onText: () -> Unit, onImport: (Boolean) -> Unit, onRecord: () -> Unit, onSplit: () -> Unit, onDelete: () -> Unit, onDuplicate: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(72.dp).background(if(tab.isEmpty() && clip == null) Panel else Color(0xFF262626))) {
        if(tab.isNotEmpty() || clip != null) Surface(onClick = onBack,shape = RoundedCornerShape(4.dp),color = Color(0xFF383838),modifier = Modifier.padding(start = 10.dp,top = 6.dp,bottom = 6.dp).width(32.dp).fillMaxHeight()) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.ChevronLeft,"Back to tools",Modifier.size(28.dp)) } }
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 8.dp)) {
            when {
                tab == "Audio" -> {
                    Tool("Sounds",Icons.Outlined.LibraryMusic) { onImport(false) }
                    Tool(if(recording) "Stop voice" else "Record",Icons.Outlined.Mic,onRecord)
                    Tool("Volume",Icons.Outlined.VolumeUp) { onTool("Volume") }
                }
                tab == "Text" -> { Tool("Add text",Icons.Outlined.TextIncrease,onText) }
                tab == "Overlay" -> { Tool("Add overlay",Icons.Outlined.AddToPhotos) { onImport(true) } }
                tab.isNotEmpty() && tab != "Edit" -> {
                    Tool("Done",Icons.Outlined.Check,onBack)
                    if(tab == "Text style") { Tool("Add text",Icons.Outlined.TextIncrease,onText); Tool("Transform",Icons.Outlined.OpenWith) { onTool("Transform") } }
                }
                clip != null -> {
                    if(clip.text != null) Tool("Edit text",Icons.Outlined.TextFields) { onTool("Text style") }
                    Tool("Split",Icons.Outlined.VerticalSplit,onSplit)
                    Tool("Speed",Icons.Outlined.Speed) { onTool("Speed") }
                    Tool("Volume",Icons.Outlined.VolumeUp) { onTool("Volume") }
                    Tool("Delete",Icons.Outlined.DeleteOutline,onDelete)
                    Tool("Duplicate",Icons.Outlined.ContentCopy,onDuplicate)
                    Tool("Transform",Icons.Outlined.CropRotate) { onTool("Transform") }
                    Tool("Adjust",Icons.Outlined.Tune) { onTool("Color") }
                    Tool("Mask",Icons.Outlined.CropFree) { onTool("Mask") }
                    Tool("Transition",Icons.Outlined.Compare) { onTool("Transition") }
                    Tool("Keyframes",Icons.Outlined.Diamond) { onTool("Keyframes") }
                    Tool("Filters",Icons.Outlined.FilterVintage) { onTool("Filters") }
                    Tool("Effects",Icons.Outlined.AutoAwesome) { onTool("Effects") }
                    Tool("Order",Icons.Outlined.SwapHoriz) { onTool("Trim") }
                    Tool("Trim",Icons.Outlined.ContentCut) { onTool("Trim") }
                }
                else -> {
                    Tool("Edit",Icons.Outlined.ContentCut) { onTool("Edit") }
                    Tool("Audio",Icons.Outlined.MusicNote) { onTool("Audio") }
                    Tool("Text",Icons.Outlined.Title) { onTool("Text") }
                    Tool("Overlay",Icons.Outlined.Filter) { onTool("Overlay") }
                    Tool("Adjust",Icons.Outlined.Tune) { onTool("Color") }
                    Tool("Filters",Icons.Outlined.FilterVintage) { onTool("Filters") }
                    Tool("Effects",Icons.Outlined.AutoAwesome) { onTool("Effects") }
                    Tool("Ratio",Icons.Outlined.AspectRatio) { onTool("Ratio") }
                    Tool("Add media",Icons.Outlined.Add) { onImport(false) }
                }
            }
        }
    }
}

@Composable fun RatioPanel(project: Project, onRatio: (Int,Int) -> Unit,onBackground: (Long)->Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF262626)).padding(vertical = 12.dp)) {
        Choices(listOf("9:16","16:9","1:1","4:5")) { value -> val (w,h) = when(value) { "9:16" -> 1080 to 1920; "16:9" -> 1920 to 1080; "1:1" -> 1080 to 1080; else -> 1080 to 1350 }; onRatio(w,h) }
        Choices(listOf("Black","White","Cream","Blue","Pink")) { name -> onBackground(when(name) { "White" -> 0xFFFFFFFF; "Cream" -> 0xFFFFE8C2; "Blue" -> 0xFF173354; "Pink" -> 0xFFEDC7CB; else -> 0xFF000000 }) }
        Text("${project.width} × ${project.height}",color = Muted,fontSize = 12.sp,modifier = Modifier.padding(16.dp))
    }
}

@Composable fun ExportOptions(project: Project, onChange: (ExportSettings) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 15.dp,end = 15.dp,top = 26.dp,bottom = 12.dp)) {
        ExportScale("Resolution",listOf(720,1080),project.export.height,"p") { onChange(project.export.copy(height = it)) }
        Spacer(Modifier.height(25.dp))
        ExportScale("Frame rate",listOf(24,25,30,50,60),project.export.fps,"") { onChange(project.export.copy(fps = it)) }
        HorizontalDivider(Modifier.padding(top = 10.dp),color = Color(0xFF2D2D2D))
        val bytes = (project.export.bitrate + 128_000L) * project.durationUs / 8_000_000.0
        Text("Estimated file size: %.1f MB".format(bytes/1_000_000),color = Muted,fontSize = 10.sp,lineHeight = 14.sp,modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ExportScale(label: String, values: List<Int>, selected: Int, suffix: String, changed: (Int) -> Unit) {
    Text(label,fontSize = 14.sp)
    var draft by remember(selected) { mutableFloatStateOf(values.indexOf(selected).coerceAtLeast(0).toFloat()) }
    Slider(draft,{ draft = it },onValueChangeFinished = { changed(values[draft.roundToInt()]) },valueRange = 0f..values.lastIndex.toFloat(),steps = (values.size-2).coerceAtLeast(0),modifier = Modifier.fillMaxWidth().height(32.dp),colors = SliderDefaults.colors(thumbColor = Color.White,activeTrackColor = Accent,inactiveTrackColor = Color(0xFF383838)),thumb = { Box(Modifier.size(16.dp).background(Color.White,androidx.compose.foundation.shape.CircleShape)) },track = { state -> SliderDefaults.Track(state,modifier = Modifier.height(2.dp),colors = SliderDefaults.colors(activeTrackColor = Accent,inactiveTrackColor = Color(0xFF383838)),thumbTrackGapSize = 0.dp) })
    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceBetween) { values.forEach { Text("$it$suffix",color = Muted,fontSize = 11.sp,lineHeight = 14.sp) } }
}

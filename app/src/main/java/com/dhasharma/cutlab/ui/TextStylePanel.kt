package com.dhasharma.cutlab.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dhasharma.cutlab.model.*

@Composable fun TextStylePanel(clip: Clip,update: (String,(TextStyle)->TextStyle)->Unit) {
    val style = clip.text ?: return
    var text by remember(clip.id,style.text) { mutableStateOf(style.text) }
    var section by remember(clip.id) { mutableStateOf("Fonts") }
    var effect by remember(clip.id) { mutableStateOf("Color") }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp,vertical = 4.dp),verticalAlignment = Alignment.CenterVertically) {
        TextField(text,{ text = it },placeholder = { Text("Enter text") },maxLines = 3,shape = RoundedCornerShape(8.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF383838),unfocusedContainerColor = Color(0xFF383838),focusedIndicatorColor = Color.Transparent,unfocusedIndicatorColor = Color.Transparent),modifier = Modifier.weight(1f))
        IconButton(onClick = { update("Text") { it.copy(text = text) } }) { Icon(Icons.Outlined.Check,"Apply text") }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        listOf("Fonts","Style","Animation").forEach { label ->
            Column(Modifier.clickable { section = label }.padding(top = 8.dp),horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label,color = if(section == label) Color.White else Muted,fontSize = 13.sp)
                Box(Modifier.padding(top = 10.dp).width(28.dp).height(2.dp).background(if(section == label) Accent else Color.Transparent))
            }
        }
    }
    when(section) {
        "Fonts" -> {
            val fonts = linkedMapOf("Sans" to "sans-serif","Serif" to "serif","Mono" to "monospace","Condensed" to "sans-serif-condensed")
            Choices(fonts.keys.toList(),fonts.entries.find { it.value == style.fontFamily }?.key) { label -> update("Font") { it.copy(fontFamily = fonts.getValue(label)) } }
            Choices(listOf("Regular","Bold","Italic"),if(style.italic) "Italic" else if(style.bold) "Bold" else "Regular") { label -> update("Font weight") { it.copy(bold = label == "Bold",italic = label == "Italic") } }
            Adjustment("Font size",style.fontSize,16f..240f) { v -> update("Font size") { it.copy(fontSize = v) } }
            Choices(listOf("left","center","right"),style.alignment) { value -> update("Alignment") { it.copy(alignment = value) } }
        }
        "Style" -> {
            Choices(listOf("Color","Outline","Shadow","Background"),effect) { effect = it }
            val colors = linkedMapOf("White" to 0xFFFFFFFF,"Black" to 0xFF000000,"Yellow" to 0xFFFFD54F,"Red" to 0xFFFF5252,"Teal" to 0xFF00C1D4,"Blue" to 0xFF448AFF,"Pink" to 0xFFFF80AB)
            key(effect) { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if(effect != "Color") TextButton(onClick = { update("Remove $effect") { when(effect) { "Outline" -> it.copy(outlineWidth = 0f); "Shadow" -> it.copy(shadowRadius = 0f); else -> it.copy(background = 0) } } }) { Text("None") }
                colors.forEach { (name,color) ->
                    val description = "$effect $name"
                    val chosen = when(effect) { "Outline" -> style.outlineColor; "Shadow" -> style.shadowColor; "Background" -> style.background; else -> style.color }
                    Box(Modifier.size(32.dp).border(if(chosen == color) 2.dp else 0.dp,if(chosen == color) Accent else Color.Transparent,CircleShape).padding(4.dp).background(Color(color),CircleShape)
                        .semantics { contentDescription = description }.clickable { update("Text $effect") { when(effect) { "Outline" -> it.copy(outlineColor = color,outlineWidth = it.outlineWidth.takeIf { w -> w>0 } ?: 4f); "Shadow" -> it.copy(shadowColor = color,shadowRadius = it.shadowRadius.takeIf { r -> r>0 } ?: 8f); "Background" -> it.copy(background = color); else -> it.copy(color = color) } } })
                }
            }
            }
            if(effect == "Outline") Adjustment("Outline width",style.outlineWidth,0f..16f) { v -> update("Outline width") { it.copy(outlineWidth = v) } }
            if(effect == "Shadow") Adjustment("Shadow blur",style.shadowRadius,0f..24f) { v -> update("Shadow blur") { it.copy(shadowRadius = v) } }
        }
        else -> {
            var direction by remember(clip.id) { mutableStateOf("In") }
            Choices(listOf("In","Out"),direction) { direction = it }
            val animations = linkedMapOf("None" to TextAnimation.NONE,"Fade" to TextAnimation.FADE,"Pop" to TextAnimation.POP,"Slide up" to TextAnimation.SLIDE_UP)
            Choices(animations.keys.toList(),animations.entries.find { it.value == if(direction == "In") style.entrance else style.exit }?.key) { label -> update("Text animation") { if(direction == "In") it.copy(entrance = animations.getValue(label)) else it.copy(exit = animations.getValue(label)) } }
            Adjustment("Animation duration",style.animationDurationUs/1e6f,.1f..1f,"s") { v -> update("Animation duration") { it.copy(animationDurationUs = (v*1e6).toLong()) } }
        }
    }
}

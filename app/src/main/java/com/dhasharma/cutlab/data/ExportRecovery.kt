package com.dhasharma.cutlab.data

import android.content.Context
import android.content.ContentUris
import android.provider.MediaStore

/** Called once when the app starts, before a new export can be started. */
object ExportRecovery {
    fun cleanInterrupted(context: Context): Int {
        var removed = 0
        @Suppress("DEPRECATION")
        val uri = MediaStore.setIncludePending(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        context.contentResolver.query(uri,arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.IS_PENDING}=1 AND ${MediaStore.Video.Media.OWNER_PACKAGE_NAME}=?",arrayOf(context.packageName),null)?.use { cursor ->
            while(cursor.moveToNext()) removed += context.contentResolver.delete(ContentUris.withAppendedId(uri,cursor.getLong(0)),null,null)
        }
        context.cacheDir.listFiles().orEmpty().filter { file ->
            file.name.startsWith("cutlab-video-") || file.name.startsWith("cutlab-audio-") || file.name.startsWith(".cutlab-export-") || file.name.startsWith("cutlab-export-working-")
        }.forEach { if(it.isFile && it.delete()) removed++ }
        return removed
    }
}

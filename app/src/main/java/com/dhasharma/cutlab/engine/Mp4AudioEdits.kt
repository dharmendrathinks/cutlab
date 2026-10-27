package com.dhasharma.cutlab.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/** Applies one precise audio edit to our own completed, nonfragmented MediaMuxer output.
 * AAC packets (including decoder warmup) stay intact. Existing mdat positions never move.
 */
internal object Mp4AudioEdits {
    private data class Atom(val type:String,val payload:ByteArray) {
        fun bytes():ByteArray = ByteBuffer.allocate(payload.size+8).order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size+8).put(type.toByteArray(Charsets.US_ASCII)).put(payload).array()
    }
    private fun atoms(bytes:ByteArray):List<Atom> {
        val result=mutableListOf<Atom>(); var offset=0
        while(offset<bytes.size) {
            check(bytes.size-offset>=8) { "Truncated MP4 metadata" }
            val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val length=buffer.getInt(offset).toLong() and 0xffffffffL
            val header=if(length==1L) 16 else 8
            val size=if(length==1L) { check(bytes.size-offset>=16); buffer.getLong(offset+8) } else if(length==0L) (bytes.size-offset).toLong() else length
            check(size>=header && size<=bytes.size-offset) { "Invalid MP4 metadata atom" }
            result+=Atom(String(bytes,offset+4,4,Charsets.US_ASCII),bytes.copyOfRange(offset+header,offset+size.toInt()))
            offset+=size.toInt()
        }
        return result
    }
    private fun combine(atoms:List<Atom>):ByteArray=ByteArrayOutputStream().apply { atoms.forEach { write(it.bytes()) } }.toByteArray()
    private fun timescale(header:Atom):Long {
        val b=ByteBuffer.wrap(header.payload).order(ByteOrder.BIG_ENDIAN)
        return b.getInt(if(header.payload[0].toInt()==1) 20 else 12).toLong() and 0xffffffffL
    }
    private fun duration(header:Atom,track:Boolean):Long {
        val b=ByteBuffer.wrap(header.payload).order(ByteOrder.BIG_ENDIAN)
        val version=header.payload[0].toInt()
        val offset=if(track) { if(version==1) 28 else 20 } else { if(version==1) 24 else 16 }
        return if(version==1) b.getLong(offset) else b.getInt(offset).toLong() and 0xffffffffL
    }
    private fun duration(header:Atom,track:Boolean,value:Long):Atom {
        val bytes=header.payload.copyOf(); val b=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val version=bytes[0].toInt()
        val offset=if(track) { if(version==1) 28 else 20 } else { if(version==1) 24 else 16 }
        if(version==1) b.putLong(offset,value) else { check(value<=0xffffffffL); b.putInt(offset,value.toInt()) }
        return header.copy(payload=bytes)
    }
    fun trim(file:File,delaySamples:Int,rate:Int,durationUs:Long) {
        require(delaySamples>=0 && rate>0 && durationUs>0)
        RandomAccessFile(file,"rw").use { io ->
            var cursor=0L; var moovOffset=-1L; var moovSize=0L; var moovHeader=8; var hasMedia=false
            while(cursor<io.length()) {
                io.seek(cursor); check(io.length()-cursor>=8)
                var size=io.readInt().toLong() and 0xffffffffL
                val typeBytes=ByteArray(4); io.readFully(typeBytes); val type=String(typeBytes,Charsets.US_ASCII)
                val header=if(size==1L) 16 else 8
                if(size==1L) size=io.readLong() else if(size==0L) size=io.length()-cursor
                check(size>=header && size<=io.length()-cursor)
                check(type!="moof") { "Fragmented MP4 editing is unsupported" }
                if(type=="moov") { check(moovOffset<0); moovOffset=cursor; moovSize=size; moovHeader=header }
                if(type=="mdat") hasMedia=true
                cursor+=size
            }
            check(hasMedia && moovOffset>=0 && moovSize<=16*1024*1024) { "Unexpected MediaMuxer layout" }
            io.seek(moovOffset+moovHeader)
            val original=ByteArray((moovSize-moovHeader).toInt()); io.readFully(original)
            val children=atoms(original).toMutableList()
            val movieHeader=children.first { it.type=="mvhd" }; val movieRate=timescale(movieHeader)
            val desiredDuration=(durationUs*movieRate+500_000)/1_000_000
            var movieDuration=desiredDuration; var edited=false
            for(index in children.indices) {
                val track=children[index]; if(track.type!="trak") continue
                val parts=atoms(track.payload).toMutableList(); val tkhd=parts.first { it.type=="tkhd" }
                val media=atoms(parts.first { it.type=="mdia" }.payload)
                val handler=media.first { it.type=="hdlr" }
                val audio=String(handler.payload,8,4,Charsets.US_ASCII)=="soun"
                if(!audio) { movieDuration=max(movieDuration,duration(tkhd,true)); continue }
                check(!edited) { "Expected one mixed audio track" }; edited=true
                val mediaRate=timescale(media.first { it.type=="mdhd" })
                val mediaStart=delaySamples.toLong()*mediaRate/rate
                val entry=ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN)
                    .putInt(0x01000000).putInt(1).putLong(desiredDuration).putLong(mediaStart).putShort(1.toShort()).putShort(0.toShort())
                // version1 fullbox: 8-byte prefix + one20-byte entry.
                val elst=Atom("elst",entry.array())
                parts.removeAll { it.type=="edts" }
                parts[parts.indexOfFirst { it.type=="tkhd" }]=duration(tkhd,true,desiredDuration)
                parts.add(1,Atom("edts",elst.bytes()))
                children[index]=track.copy(payload=combine(parts))
            }
            check(edited)
            children[children.indexOfFirst { it.type=="mvhd" }]=duration(movieHeader,false,movieDuration)
            val replacement=Atom("moov",combine(children)).bytes()
            if(moovOffset+moovSize==io.length()) {
                io.seek(moovOffset); io.write(replacement); io.setLength(moovOffset+replacement.size)
            } else {
                // Android often reserves moov at the front. Append its replacement and turn
                // the old box into same-sized free space; absolute chunk offsets remain valid.
                io.seek(io.length()); io.write(replacement)
                io.seek(moovOffset+4); io.write("free".toByteArray(Charsets.US_ASCII))
            }
            io.fd.sync()
        }
    }
}

package com.dhasharma.cutlab.model

class EditorHistory(initial: Project) {
    private val past = ArrayDeque<Project>()
    private val future = ArrayDeque<Project>()
    var project: Project = initial; private set
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    var lastLabel: String = ""; private set
    fun apply(label: String, edit: (Project) -> Project) {
        val next = edit(project)
        if (next == project) return
        past.addLast(project)
        if (past.size > 100) past.removeFirst()
        future.clear()
        project = next.copy(modifiedAt = System.currentTimeMillis())
        lastLabel = label
    }
    fun undo() { if (canUndo) { future.addLast(project); project = past.removeLast() } }
    fun redo() { if (canRedo) { past.addLast(project); project = future.removeLast() } }
}

object EditorOps {
    private fun sequence(clips: List<Clip>): List<Clip> {
        var previous: Clip? = null
        return clips.map { clip ->
            val overlap = if (clip.transition.kind == TransitionKind.NONE || previous == null) 0L
                else minOf(clip.transition.durationUs.coerceAtLeast(0),previous!!.durationUs(),clip.durationUs())
            val start = previous?.let { it.startUs+it.durationUs()-overlap } ?: 0L
            clip.copy(startUs = start).also { previous = it }
        }
    }
    /** Explicit ripple operation; callers select the primary sequence, never overlay tracks. */
    fun reflow(project: Project, trackId: String): Project = project.copy(tracks = project.tracks.map { track ->
        if (track.id == trackId) track.copy(clips = sequence(track.clips.sortedBy { it.startUs })) else track
    })
    fun updateClip(project: Project, id: String, edit: (Clip) -> Clip): Project = project.copy(tracks = project.tracks.map { track ->
        track.copy(clips = track.clips.map { if (it.id == id) edit(it) else it })
    })
    fun remove(project: Project, id: String) = project.copy(tracks = project.tracks.map { it.copy(clips = it.clips.filterNot { clip -> clip.id == id }) })
    fun duplicate(project: Project, id: String) = project.copy(tracks = project.tracks.map { track ->
        val original = track.clips.find { it.id == id }
        if (original == null) track else {
            val insertion = original.startUs + original.durationUs()
            track.copy(clips = track.clips.flatMap { clip -> when {
                clip.id == id -> listOf(clip,clip.copy(id = newId(),startUs = insertion))
                clip.startUs >= insertion -> listOf(clip.copy(startUs = clip.startUs+original.durationUs()))
                else -> listOf(clip)
            } })
        }
    })
    fun move(project: Project, id: String, startUs: Long, targetTrackId: String? = null): Project {
        val clip = project.tracks.flatMap { it.clips }.find { it.id == id } ?: return project
        if (targetTrackId == null) return updateClip(project,id) { it.copy(startUs = startUs.coerceAtLeast(0)) }
        require(project.tracks.any { it.id == targetTrackId }) { "Target track does not exist" }
        return remove(project,id).let { p -> p.copy(tracks = p.tracks.map { track ->
            if (track.id == targetTrackId) track.copy(clips = (track.clips + clip.copy(startUs = startUs.coerceAtLeast(0))).sortedBy { it.startUs }) else track
        }) }
    }
    fun reorder(project: Project, trackId: String, clipId: String, newIndex: Int): Project = project.copy(tracks = project.tracks.map { track ->
        if (track.id != trackId) track else {
            val ordered = track.clips.sortedBy { it.startUs }.toMutableList()
            val clip = ordered.find { it.id == clipId }
            if (clip == null) track else {
                ordered.remove(clip); ordered.add(newIndex.coerceIn(0,ordered.size),clip)
                track.copy(clips = sequence(ordered))
            }
        }
    })
    private fun sliced(clip: Clip, fromUs: Long, toUs: Long, offset: Long, duration: Long): Clip = clip.copy(
        inUs = fromUs, outUs = toUs, speedCurve = clip.sliceCurve(fromUs,toUs),
        transform = clip.transformAt(offset),
        keyframes = (listOf(Keyframe(0,clip.transformAt(offset))) + clip.keyframes.filter { it.timeUs > offset && it.timeUs < offset+duration }
            .map { it.copy(timeUs = it.timeUs-offset) } + Keyframe(duration,clip.transformAt(offset+duration))).distinctBy { it.timeUs },
        fadeInUs = (clip.fadeInUs-offset).coerceIn(0,duration),
        fadeOutUs = (clip.fadeOutUs-(clip.durationUs()-offset-duration)).coerceIn(0,duration)
    )
    fun split(project: Project, id: String, atTimelineUs: Long): Project = project.copy(tracks = project.tracks.map { track ->
        track.copy(clips = track.clips.flatMap { clip ->
            val local = atTimelineUs-clip.startUs
            val duration = clip.durationUs()
            if (clip.id != id || local <= 0 || local >= duration) listOf(clip) else {
                require(clip.transition.kind == TransitionKind.NONE || local >= minOf(clip.transition.durationUs,duration)) {
                    "Move the playhead beyond the incoming transition before splitting"
                }
                if (clip.freezeUs != null) {
                    val left = sliced(clip,clip.inUs,clip.inUs+(local.toDouble()/duration*(clip.outUs-clip.inUs)).toLong(),0,local)
                    val right = sliced(clip,left.outUs,clip.outUs,local,duration-local)
                    listOf(left.copy(freezeUs = clip.freezeUs),right.copy(id = newId(),startUs = atTimelineUs,freezeUs = clip.freezeUs,transition = Transition(TransitionKind.NONE)))
                } else {
                    val boundary = clip.sourceBoundaryAt(local)
                    val left = if (clip.reverse) sliced(clip,boundary,clip.outUs,0,local) else sliced(clip,clip.inUs,boundary,0,local)
                    val right = if (clip.reverse) sliced(clip,clip.inUs,boundary,local,duration-local) else sliced(clip,boundary,clip.outUs,local,duration-local)
                    listOf(left,right.copy(id = newId(),startUs = atTimelineUs,transition = Transition(TransitionKind.NONE)))
                }
            }
        })
    })
    /** Insert a still at the playhead and ripple only its sequence; overlays remain explicitly timed. */
    fun insertFreeze(project: Project,id: String,atUs: Long,lengthUs: Long = 3_000_000): Project {
        require(lengthUs > 0)
        val track = project.tracks.firstOrNull { t -> t.clips.any { it.id == id } } ?: return project
        val c = track.clips.first { it.id == id }
        require(project.assets.any { it.id == c.assetId && it.kind == AssetKind.VIDEO }) { "Choose a video clip to freeze" }
        val local = (atUs-c.startUs).coerceIn(0,c.durationUs())
        val still = c.copy(id = newId(),startUs = c.startUs+local,inUs = 0,outUs = lengthUs,speed = 1f,speedCurve = emptyList(),reverse = false,
            freezeUs = c.sourceTimeUs(local),transform = c.transformAt(local),keyframes = emptyList(),muted = true,transition = if(local == 0L) c.transition else Transition())
        val pieces = if(local == 0L) listOf(still,c.copy(startUs = c.startUs+lengthUs,transition = Transition()))
            else if(local == c.durationUs()) listOf(c,still)
            else {
                val split = split(project,id,c.startUs+local).tracks.first { it.id == track.id }.clips
                val before = split.first { it.id == id }; val after = split.first { candidate -> track.clips.none { it.id == candidate.id } }
                listOf(before,still,after.copy(startUs = after.startUs+lengthUs))
            }
        val following = track.clips.sortedBy { it.startUs }.dropWhile { it.id != id }.drop(1).map { it.id }.toSet()
        return project.copy(tracks = project.tracks.map { t -> if(t.id != track.id) t else t.copy(clips = t.clips.flatMap {
            when { it.id == id -> pieces; it.id in following -> listOf(it.copy(startUs = it.startUs+lengthUs)); else -> listOf(it) }
        }) })
    }
    /** Primary drops reorder into a gapless sequence; overlays/audio retain absolute placement. */
    fun drop(project: Project,id: String,timeUs: Long): Project {
        val track = project.tracks.firstOrNull { t -> t.clips.any { it.id == id } } ?: return project
        if(track.id != project.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.id) return move(project,id,timeUs)
        val rest = track.clips.filterNot { it.id == id }.sortedBy { it.startUs }
        val index = rest.count { timeUs > it.startUs+it.durationUs()/2 }
        return reorder(project,track.id,id,index)
    }
    fun trim(project: Project, id: String, newInUs: Long, newOutUs: Long): Project = updateClip(project,id) { clip ->
        val asset = project.assets.find { it.id == clip.assetId }
        val maximum = if (asset != null && asset.kind != AssetKind.IMAGE && clip.freezeUs == null) asset.durationUs else 24L*60*60*1_000_000
        require(newInUs >= 0 && newOutUs <= maximum && newOutUs > newInUs) { "Trim bounds must be inside the source media" }
        val span = (clip.outUs-clip.inUs).toDouble()
        val before = if (clip.freezeUs != null) (newInUs-clip.inUs)/span*clip.durationUs() else clip.elapsedAtSourceFraction((newInUs-clip.inUs)/span)
        val after = if (clip.freezeUs != null) (newOutUs-clip.inUs)/span*clip.durationUs() else clip.elapsedAtSourceFraction((newOutUs-clip.inUs)/span)
        val offset = (if (clip.reverse) clip.durationUs()-after else before).toLong()
        sliced(clip,newInUs,newOutUs,offset,(after-before).toLong())
    }
}

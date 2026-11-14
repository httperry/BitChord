package com.music.bitchord.ui.player

import com.music.bitchord.data.lyrics.LyricLine

/** Keep every unfinished vocal visible, including overlaps spanning more than two rows. */
internal fun activeLyricRows(lines: List<LyricLine>, positionMs: Long): List<Int> {
    val latest = lines.indexOfLast { it.timeMs <= positionMs }
    if (latest < 0) return emptyList()
    var count = 0
    for (i in 0 until latest) {
        val line = lines[i]
        if (!line.isGap &&
            (line.hasKnownEnd || line.background?.hasKnownEnd == true) &&
            line.timeMs <= positionMs && positionMs < line.endMs
        ) {
            count++
        }
    }
    if (count == 0) return listOf(latest)
    val result = ArrayList<Int>(count + 1)
    for (i in 0 until latest) {
        val line = lines[i]
        if (!line.isGap &&
            (line.hasKnownEnd || line.background?.hasKnownEnd == true) &&
            line.timeMs <= positionMs && positionMs < line.endMs
        ) {
            result.add(i)
        }
    }
    result.add(latest)
    return result
}

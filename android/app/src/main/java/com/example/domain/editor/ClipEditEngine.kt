package com.example.domain.editor

import com.example.domain.model.ClipEditState
import java.io.File

/** Android Core boundary used by the editor; implementations may be local or remote. */
interface ClipEditEngine {
    suspend fun renderClip(
        clipId: Long,
        editState: ClipEditState,
        onProgress: (Int) -> Unit = {}
    ): Result<File>
}

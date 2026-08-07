package com.lucasta.edit

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun readText(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        checkNotNull(context.contentResolver.openInputStream(uri)) { "no input stream" }
            .use { it.readBytes().decodeToString() }
    }
}

suspend fun writeText(context: Context, uri: Uri, text: String): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        // "wt" truncates. Plain "w" leaves any trailing bytes of a longer
        // previous version behind on some providers.
        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "no output stream" }
            .use { it.write(text.encodeToByteArray()) }
    }
}

fun displayName(context: Context, uri: Uri): String {
    val name = context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
    return name ?: uri.lastPathSegment ?: uri.toString()
}

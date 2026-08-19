package com.lucasta.edit

import android.content.Context
import android.net.Uri

private val magics = listOf(
    "age-encryption.org/v1".toByteArray(Charsets.US_ASCII),
    "-----BEGIN AGE ENCRYPTED FILE-----".toByteArray(Charsets.US_ASCII),
)

internal fun looksEncrypted(header: ByteArray): Boolean = magics.any { header.startsWith(it) }

/**
 * An encrypted document keeps its own model: decrypted whole, held whole in memory, and no piece of
 * it pointing into a file. Nothing is written anywhere, temporaries included, that is not
 * ciphertext. It is [Content.of] over a byte array and never over a [Snapshot].
 */
suspend fun readEncryptedDocument(context: Context, uri: Uri): Result<Content> =
    Result.failure(NotSupported("encrypted files cannot be opened yet"))

suspend fun writeEncryptedDocument(context: Context, uri: Uri, content: Content): Result<Unit> =
    Result.failure(NotSupported("encrypted files cannot be saved yet"))

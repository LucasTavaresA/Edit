package com.lucasta.edit

import android.content.Context
import android.net.Uri

internal const val ENCRYPTED_HEADER_BYTES = 64

private val magics = listOf(
    "age-encryption.org/v1".toByteArray(Charsets.US_ASCII),
    "-----BEGIN AGE ENCRYPTED FILE-----".toByteArray(Charsets.US_ASCII),
)

internal fun looksEncrypted(header: ByteArray): Boolean = magics.any { header.startsWith(it) }

suspend fun readEncryptedDocument(context: Context, uri: Uri): Result<TextDocument> =
    Result.failure(NotSupported("encrypted files cannot be opened yet"))

suspend fun writeEncryptedDocument(context: Context, uri: Uri, document: TextDocument): Result<Unit> =
    Result.failure(NotSupported("encrypted files cannot be saved yet"))

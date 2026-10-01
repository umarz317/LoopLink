package com.shilapi.xcertplay.airplay

internal fun concatBytes(vararg arrays: ByteArray): ByteArray {
    val output = ByteArray(arrays.sumOf { it.size })
    var offset = 0
    for (array in arrays) {
        array.copyInto(output, offset)
        offset += array.size
    }
    return output
}

internal fun String.asciiBytes(): ByteArray = toByteArray(Charsets.US_ASCII)

internal fun ByteArray.toHexString(): String {
    val alphabet = "0123456789abcdef"
    val output = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xff
        output[index * 2] = alphabet[value ushr 4]
        output[index * 2 + 1] = alphabet[value and 0x0f]
    }
    return String(output)
}

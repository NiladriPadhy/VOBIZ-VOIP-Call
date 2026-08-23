package com.enetro.vobizvoip.data

/** A single dialable device contact (name + number), used for search and name lookup. */
data class Contact(
    val id: String,
    val name: String,
    val number: String,
    val photoUri: String?,
)

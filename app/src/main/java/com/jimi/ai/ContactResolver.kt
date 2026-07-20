package com.jimi.ai

import android.content.Context
import android.provider.ContactsContract

object ContactResolver {

    /** Phone book me naam se dhoondh kar number return karta hai (E.164-ish, jo mile wahi). */
    fun findPhoneNumberByName(context: Context, name: String): String? {
        val resolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$name%")

        resolver.query(uri, projection, selection, args, null)?.use { cursor ->
            val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (cursor.moveToFirst()) {
                return cursor.getString(numberIdx)?.replace(" ", "")?.replace("-", "")
            }
        }
        return null
    }
}

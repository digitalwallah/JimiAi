package com.jimi.ai

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.*
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import kotlinx.coroutines.runBlocking

/**
 * Jimi ka Autofill service. Non-sensitive fields turant fill ho sakte hain (saved value ho toh);
 * sensitive fields HAMESHA ek confirm tap maangte hain, kabhi silently nahi bharte. Koi field
 * auto-submit nahi hota, sensitive values Gemini ko kabhi nahi bheji jaati.
 */
class JimiAutofillService : AutofillService() {

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) { callback.onSuccess(null); return }

        val matches = FieldMatcher.findFillableFields(structure)
        if (matches.isEmpty()) { callback.onSuccess(null); return }

        val savedFields = runBlocking {
            try { JimiDatabase.getInstance(applicationContext).userFieldDao().getAllEnabled() }
            catch (e: Exception) { emptyList() }
        }.associateBy { it.fieldType }

        val responseBuilder = FillResponse.Builder()
        var addedAny = false

        for (match in matches) {
            val saved = savedFields[match.fieldType]
            when {
                match.isSensitive -> {
                    responseBuilder.addDataset(buildAuthDataset(match, saved != null))
                    addedAny = true
                }
                saved != null && saved.plainValue.isNotBlank() -> {
                    val presentation = simplePresentation(match.label, saved.plainValue.take(40))
                    val dataset = Dataset.Builder(presentation)
                        .setValue(match.autofillId, AutofillValue.forText(saved.plainValue))
                        .build()
                    responseBuilder.addDataset(dataset)
                    addedAny = true
                }
                else -> {
                    responseBuilder.addDataset(buildAuthDataset(match, false))
                    addedAny = true
                }
            }
        }

        if (!addedAny) { callback.onSuccess(null); return }
        callback.onSuccess(responseBuilder.build())
    }

    private fun buildAuthDataset(match: FieldMatch, hasSavedValue: Boolean): Dataset {
        val chipText = if (hasSavedValue) "🔒 ${match.label} bharo" else "➕ ${match.label} add karo"
        val presentation = simplePresentation(chipText, if (match.confident) "" else "Pehchana nahi gaya")

        val intent = Intent(this, AutofillConfirmActivity::class.java).apply {
            putExtra("field_type", match.fieldType)
            putExtra("field_label", match.label)
            putExtra("is_sensitive", match.isSensitive)
            putExtra("confident", match.confident)
            match.requiredHint?.let { putExtra("required_hint", it) }
            putExtra("autofill_id_extra", match.autofillId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, match.autofillId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Dataset.Builder(presentation)
            .setValue(match.autofillId, null, presentation)
            .setAuthentication(pendingIntent.intentSender)
            .build()
    }

    private fun simplePresentation(title: String, subtitle: String): RemoteViews {
        val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_2)
        presentation.setTextViewText(android.R.id.text1, title)
        presentation.setTextViewText(android.R.id.text2, subtitle)
        return presentation
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // Jimi ka apna save-flow AutofillConfirmActivity se explicit user action ke saath hota
        // hai, isliye system ke generic "Save this?" prompt ki zaroorat nahi.
        callback.onSuccess()
    }
}

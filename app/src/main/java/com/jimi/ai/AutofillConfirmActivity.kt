package com.jimi.ai

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Sensitive fields fill karne se pehle confirm, ya naye/unrecognized field ke liye basic
 * Hinglish explanation + value lene ka screen. */
class AutofillConfirmActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val fieldType = intent.getStringExtra("field_type") ?: "UNKNOWN"
        val fieldLabel = intent.getStringExtra("field_label") ?: "Ye field"
        val isSensitive = intent.getBooleanExtra("is_sensitive", false)
        val confident = intent.getBooleanExtra("confident", false)
        val requiredHint = if (intent.hasExtra("required_hint")) intent.getBooleanExtra("required_hint", false) else null
        val originalAutofillId = intent.getParcelableExtra<AutofillId>("autofill_id_extra")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "Jimi — $fieldLabel"
            textSize = 20f
            setPadding(0, 0, 0, 16)
        })

        root.addView(TextView(this).apply {
            text = buildExplanation(fieldLabel, isSensitive, confident, requiredHint)
            textSize = 14f
            setPadding(0, 0, 0, 24)
        })

        val input = EditText(this).apply {
            hint = fieldLabel
            if (isSensitive) {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }
        root.addView(input)

        CoroutineScope(Dispatchers.Main).launch {
            val existing = if (isSensitive) {
                SecureCredentialStore.get(this@AutofillConfirmActivity, fieldType)
            } else {
                try { JimiDatabase.getInstance(applicationContext).userFieldDao().findByType(fieldType)?.plainValue }
                catch (e: Exception) { null }
            }
            if (!existing.isNullOrBlank()) input.setText(existing)
        }

        val markRequired = CheckBox(this).apply {
            text = "Ye field mere liye important/required hai"
            isChecked = requiredHint == true
        }
        root.addView(markRequired)

        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 32, 0, 0) }

        buttonRow.addView(Button(this).apply {
            text = "Skip / Daalna nahi"
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        })

        buttonRow.addView(Button(this).apply {
            text = "Save & Fill karo"
            setOnClickListener {
                val value = input.text.toString()
                if (value.isBlank()) { setResult(RESULT_CANCELED); finish(); return@setOnClickListener }

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val dao = JimiDatabase.getInstance(applicationContext).userFieldDao()
                        val existingRecord = dao.findByType(fieldType)
                        if (isSensitive) {
                            SecureCredentialStore.save(this@AutofillConfirmActivity, fieldType, value)
                            dao.insert(
                                (existingRecord ?: UserField(fieldType = fieldType, label = fieldLabel, isSensitive = true))
                                    .copy(isSensitive = true, requiresConfirm = true, isEnabled = true, updatedAt = System.currentTimeMillis())
                            )
                        } else {
                            dao.insert(
                                (existingRecord ?: UserField(fieldType = fieldType, label = fieldLabel))
                                    .copy(plainValue = value, isEnabled = true, updatedAt = System.currentTimeMillis())
                            )
                        }
                    } catch (e: Exception) { }

                    runOnUiThread {
                        val replyIntent = Intent()
                        if (originalAutofillId != null) {
                            val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1)
                            presentation.setTextViewText(android.R.id.text1, fieldLabel)
                            val dataset = Dataset.Builder(presentation)
                                .setValue(originalAutofillId, AutofillValue.forText(value))
                                .build()
                            replyIntent.putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
                        }
                        setResult(RESULT_OK, replyIntent)
                        finish()
                    }
                }
            }
        })
        root.addView(buttonRow)

        setContentView(root)
    }

    private fun buildExplanation(label: String, isSensitive: Boolean, confident: Boolean, requiredHint: Boolean?): String {
        val sb = StringBuilder()
        sb.append(if (!confident) "Is field ka naam '$label' hai — Jimi ko is field ka exact type pehchan nahi aaya. "
                   else "Ye '$label' field lagta hai. ")
        if (isSensitive) sb.append("Ye SENSITIVE information hai — Jimi ise kabhi bina tumhare confirm kiye nahi bharta, aur kabhi online nahi bhejta. ")
        when (requiredHint) {
            true -> sb.append("Ye field is form mein REQUIRED (zaroori) dikh raha hai.")
            false -> sb.append("Ye field OPTIONAL lagta hai — khaali bhi chhod sakte ho.")
            null -> sb.append("Jimi ko pata nahi chal paya ki ye zaroori hai ya optional — submit karke dekh sakte ho, zaroori hoga toh error aayega.")
        }
        return sb.toString()
    }
}

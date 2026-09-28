package com.jimi.ai

import android.app.assist.AssistStructure
import android.view.View
import android.view.autofill.AutofillId

data class FieldMatch(
    val autofillId: AutofillId,
    val fieldType: String,
    val label: String,
    val isSensitive: Boolean,
    val confident: Boolean,
    val requiredHint: Boolean?
)

/** Android ke standard AutofillHints se shuru karta hai (sabse reliable), phir fallback mein
 * resource-id/hint-text keyword matching karta hai un apps ke liye jo autofill hints set nahi
 * karte — isse "duniya bhar ke" apps cover ho jaate hain, chahe properly configured ho ya nahi. */
object FieldMatcher {

    private val SENSITIVE_TYPES = setOf(
        "PASSWORD", "NEW_PASSWORD", "CONFIRM_PASSWORD", "OTP", "PIN",
        "CREDIT_CARD_NUMBER", "CREDIT_CARD_CVV", "CREDIT_CARD_EXPIRY",
        "BANK_ACCOUNT_NUMBER", "BANK_IFSC", "BANK_ROUTING_NUMBER", "BANK_SWIFT",
        "SSN", "AADHAAR", "PAN", "PASSPORT_NUMBER", "DRIVING_LICENSE", "NATIONAL_ID"
    )

    private val KEYWORD_MAP: List<Pair<String, List<String>>> = listOf(
        "FULL_NAME" to listOf("fullname", "full_name", "yourname", "name"),
        "FIRST_NAME" to listOf("firstname", "first_name", "fname", "givenname"),
        "LAST_NAME" to listOf("lastname", "last_name", "lname", "surname", "familyname"),
        "EMAIL" to listOf("email", "e-mail", "mail"),
        "PHONE" to listOf("phone", "mobile", "contact number", "cell"),
        "USERNAME" to listOf("username", "user_name", "userid", "loginid"),
        "PASSWORD" to listOf("password", "passwd", "pwd"),
        "NEW_PASSWORD" to listOf("newpassword", "new_password", "createpassword"),
        "CONFIRM_PASSWORD" to listOf("confirmpassword", "confirm_password", "retypepassword", "repeatpassword"),
        "OTP" to listOf("otp", "one time password", "verification code", "authcode"),
        "PIN" to listOf("pin", "mpin"),
        "ADDRESS_LINE" to listOf("address", "street", "addr"),
        "CITY" to listOf("city", "town"),
        "STATE" to listOf("state", "province", "region"),
        "POSTAL_CODE" to listOf("zip", "zipcode", "postal", "pincode", "postcode"),
        "COUNTRY" to listOf("country", "nation"),
        "DATE_OF_BIRTH" to listOf("dob", "birthdate", "birthday", "dateofbirth"),
        "GENDER" to listOf("gender", "sex"),
        "COMPANY" to listOf("company", "organization", "employer"),
        "JOB_TITLE" to listOf("jobtitle", "designation", "occupation"),
        "WEBSITE" to listOf("website", "url", "webpage"),
        "CREDIT_CARD_NUMBER" to listOf("cardnumber", "card_number", "ccnumber"),
        "CREDIT_CARD_EXPIRY" to listOf("expiry", "expdate", "exp_date", "validthru"),
        "CREDIT_CARD_CVV" to listOf("cvv", "cvc", "securitycode", "card_verification"),
        "CREDIT_CARD_NAME" to listOf("nameoncard", "cardholder"),
        "BANK_ACCOUNT_NUMBER" to listOf("accountnumber", "account_no", "acctno"),
        "BANK_IFSC" to listOf("ifsc"),
        "BANK_ROUTING_NUMBER" to listOf("routingnumber", "routing_no", "aba"),
        "BANK_SWIFT" to listOf("swift", "bic"),
        "SSN" to listOf("ssn", "socialsecurity"),
        "AADHAAR" to listOf("aadhaar", "aadhar", "uidai"),
        "PAN" to listOf("pancard", "pan_number", "pan number"),
        "PASSPORT_NUMBER" to listOf("passport"),
        "DRIVING_LICENSE" to listOf("drivinglicense", "dlnumber", "license number"),
        "NATIONAL_ID" to listOf("nationalid", "national_id", "govtid", "governmentid")
    )

    private val AUTOFILL_HINT_TO_TYPE = mapOf(
        View.AUTOFILL_HINT_EMAIL_ADDRESS to "EMAIL",
        View.AUTOFILL_HINT_PHONE to "PHONE",
        View.AUTOFILL_HINT_USERNAME to "USERNAME",
        View.AUTOFILL_HINT_PASSWORD to "PASSWORD",
        View.AUTOFILL_HINT_NAME to "FULL_NAME",
        View.AUTOFILL_HINT_POSTAL_ADDRESS to "ADDRESS_LINE",
        View.AUTOFILL_HINT_POSTAL_CODE to "POSTAL_CODE",
        View.AUTOFILL_HINT_CREDIT_CARD_NUMBER to "CREDIT_CARD_NUMBER",
        View.AUTOFILL_HINT_CREDIT_CARD_SECURITY_CODE to "CREDIT_CARD_CVV",
        View.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_DATE to "CREDIT_CARD_EXPIRY"
    )

    private val LABELS = mapOf(
        "FULL_NAME" to "Poora naam", "FIRST_NAME" to "Pehla naam", "LAST_NAME" to "Aakhri naam",
        "EMAIL" to "Email address", "PHONE" to "Phone number", "USERNAME" to "Username",
        "PASSWORD" to "Password", "NEW_PASSWORD" to "Naya password", "CONFIRM_PASSWORD" to "Password (confirm)",
        "OTP" to "OTP", "PIN" to "PIN", "ADDRESS_LINE" to "Address", "CITY" to "City",
        "STATE" to "State", "POSTAL_CODE" to "PIN/ZIP code", "COUNTRY" to "Country",
        "DATE_OF_BIRTH" to "Date of birth", "GENDER" to "Gender", "COMPANY" to "Company/Organization",
        "JOB_TITLE" to "Job title", "WEBSITE" to "Website", "CREDIT_CARD_NUMBER" to "Card number",
        "CREDIT_CARD_EXPIRY" to "Card expiry", "CREDIT_CARD_CVV" to "Card CVV",
        "CREDIT_CARD_NAME" to "Card holder ka naam", "BANK_ACCOUNT_NUMBER" to "Bank account number",
        "BANK_IFSC" to "IFSC code", "BANK_ROUTING_NUMBER" to "Routing number", "BANK_SWIFT" to "SWIFT/BIC code",
        "SSN" to "Social Security Number", "AADHAAR" to "Aadhaar number", "PAN" to "PAN number",
        "PASSPORT_NUMBER" to "Passport number", "DRIVING_LICENSE" to "Driving license number",
        "NATIONAL_ID" to "National ID"
    )

    fun findFillableFields(structure: AssistStructure): List<FieldMatch> {
        val results = mutableListOf<FieldMatch>()
        for (i in 0 until structure.windowNodeCount) {
            walk(structure.getWindowNodeAt(i).rootViewNode, results)
        }
        return results
    }

    private fun walk(node: AssistStructure.ViewNode, out: MutableList<FieldMatch>) {
        if (node.autofillId != null && node.autofillType != View.AUTOFILL_TYPE_NONE) {
            matchNode(node)?.let { out.add(it) }
        }
        for (i in 0 until node.childCount) walk(node.getChildAt(i), out)
    }

    private fun matchNode(node: AssistStructure.ViewNode): FieldMatch? {
        val autofillId = node.autofillId ?: return null

        node.autofillHints?.forEach { hint ->
            AUTOFILL_HINT_TO_TYPE[hint]?.let { type ->
                return FieldMatch(autofillId, type, LABELS[type] ?: type, type in SENSITIVE_TYPES, true, readRequiredHint(node))
            }
        }

        val haystack = buildString {
            append(node.idEntry ?: ""); append(" "); append(node.hint ?: "")
        }.lowercase()

        if (haystack.isNotBlank()) {
            for ((type, keywords) in KEYWORD_MAP) {
                if (keywords.any { haystack.contains(it) }) {
                    return FieldMatch(autofillId, type, LABELS[type] ?: type, type in SENSITIVE_TYPES, true, readRequiredHint(node))
                }
            }
        }

        val rawLabel = node.hint?.takeIf { it.isNotBlank() } ?: node.idEntry?.takeIf { it.isNotBlank() }
        if (rawLabel != null) {
            return FieldMatch(autofillId, "UNKNOWN", rawLabel, false, false, readRequiredHint(node))
        }
        return null
    }

    /** Web forms mein HTML "required" attribute check karte hain agar mile. Native app forms
     * mein Android ke paas koi reliable flag nahi hota — tab null, matlab "pata nahi". */
    private fun readRequiredHint(node: AssistStructure.ViewNode): Boolean? {
        val attrs = node.htmlInfo?.attributes ?: return null
        for (pair in attrs) if (pair.first.equals("required", ignoreCase = true)) return true
        return null
    }
}

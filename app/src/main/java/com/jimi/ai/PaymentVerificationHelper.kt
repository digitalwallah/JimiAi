package com.jimi.ai

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

object PaymentVerificationHelper {

    private val db = FirebaseFirestore.getInstance()
    private const val COLLECTION = "payment_requests"
    private const val TRIAL_COLLECTION = "trial_usage"

    data class PaymentRequest(
        val phone: String = "",
        val utr: String = "",
        val plan: String = "",
        val amount: Int = 0,
        val status: String = "pending",
        val timestamp: Long = System.currentTimeMillis(),
        val expiryTimestamp: Long = 0L
    )

    fun submitPaymentRequest(
        phone: String,
        utr: String,
        plan: String,
        amount: Int,
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val request = PaymentRequest(
            phone = phone,
            utr = utr,
            plan = plan,
            amount = amount,
            status = "pending",
            timestamp = System.currentTimeMillis()
        )

        db.collection(COLLECTION)
            .add(request)
            .addOnSuccessListener { docRef ->
                onSuccess(docRef.id)
            }
            .addOnFailureListener { e ->
                onFailure(e)
            }
    }

    fun listenForApproval(
        docId: String,
        onApproved: (PaymentRequest) -> Unit,
        onRejected: () -> Unit,
        onStillPending: () -> Unit
    ): ListenerRegistration {
        return db.collection(COLLECTION).document(docId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) {
                    return@addSnapshotListener
                }
                val request = snapshot.toObject(PaymentRequest::class.java)
                when (request?.status) {
                    "approved" -> onApproved(request)
                    "rejected" -> onRejected()
                    else -> onStillPending()
                }
            }
    }

    fun checkPendingRequestByPhone(
        phone: String,
        onFound: (docId: String, request: PaymentRequest) -> Unit,
        onNotFound: () -> Unit
    ) {
        db.collection(COLLECTION)
            .whereEqualTo("phone", phone)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .addOnSuccessListener { docs ->
                if (docs.isEmpty) {
                    onNotFound()
                } else {
                    val doc = docs.documents[0]
                    val request = doc.toObject(PaymentRequest::class.java)
                    if (request != null) onFound(doc.id, request) else onNotFound()
                }
            }
            .addOnFailureListener { onNotFound() }
    }

    /** Trial ke liye eligibility check — phone number AUR device ID dono ke against
     * dekhta hai. Agar dono mein se koi bhi ek pehle trial use kar chuka hai, trial
     * dobara nahi milega — chahe naya number ho ya naya device. */
    fun checkTrialEligibility(
        phone: String,
        deviceId: String,
        onEligible: () -> Unit,
        onAlreadyUsed: () -> Unit
    ) {
        db.collection(TRIAL_COLLECTION).document(deviceId).get()
            .addOnSuccessListener { deviceDoc ->
                if (deviceDoc.exists()) {
                    onAlreadyUsed()
                } else {
                    db.collection(TRIAL_COLLECTION).document(phone).get()
                        .addOnSuccessListener { phoneDoc ->
                            if (phoneDoc.exists()) onAlreadyUsed() else onEligible()
                        }
                        .addOnFailureListener { onEligible() }
                }
            }
            .addOnFailureListener { onEligible() }
    }

    /** Trial approve hone ke baad, dono (phone + deviceId) ko "used" mark kar deta hai
     * taaki dobara koi bhi ek use karke trial na le sake. */
    fun markTrialUsed(phone: String, deviceId: String) {
        val data = mapOf("usedAt" to System.currentTimeMillis())
        db.collection(TRIAL_COLLECTION).document(deviceId).set(data)
        db.collection(TRIAL_COLLECTION).document(phone).set(data)
    }
}

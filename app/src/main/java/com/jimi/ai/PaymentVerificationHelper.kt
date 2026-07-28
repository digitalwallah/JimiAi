package com.yourpackage.jimi

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

object PaymentVerificationHelper {

    private val db = FirebaseFirestore.getInstance()
    private const val COLLECTION = "payment_requests"

    data class PaymentRequest(
        val phone: String = "",
        val utr: String = "",
        val plan: String = "",       // "1_month" or "4_month"
        val amount: Int = 0,
        val status: String = "pending", // pending / approved / rejected
        val timestamp: Long = System.currentTimeMillis(),
        val expiryTimestamp: Long = 0L
    )

    /**
     * Submits a payment request to Firestore after user enters UTR + phone.
     */
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

    /**
     * Real-time listener — fires instantly when you mark status "approved"
     * in the Firebase console. No polling needed.
     * Call this after submitPaymentRequest and keep the ListenerRegistration
     * to remove it in onDestroy().
     */
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

    /**
     * Optional: check by phone number in case app restarted before approval
     * came in (e.g. user closed app, reopens later).
     */
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
}

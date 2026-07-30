package com.jimi.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.ListenerRegistration
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class PaymentQRActivity : AppCompatActivity() {

    private val UPI_ID = "mdyasin7860@ybl"
    private val PAYEE_NAME = "Jimi App"

    private var selectedPlan = "1_month"
    private var selectedAmount = 69
    private var currentDocId: String? = null
    private var listenerRegistration: ListenerRegistration? = null

    private val deviceId: String by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment_qr)

        val cardTrial = findViewById<android.widget.LinearLayout>(R.id.cardTrial)
        val cardOneMonth = findViewById<android.widget.LinearLayout>(R.id.cardOneMonth)
        val cardFourMonth = findViewById<android.widget.LinearLayout>(R.id.cardFourMonth)
        val qrImageView = findViewById<android.widget.ImageView>(R.id.qrImageView)
        val etUtr = findViewById<android.widget.EditText>(R.id.etUtr)
        val etPhone = findViewById<android.widget.EditText>(R.id.etPhone)
        val btnSubmit = findViewById<android.widget.Button>(R.id.btnSubmit)
        val tvStatus = findViewById<android.widget.TextView>(R.id.tvStatus)

        val allCards = listOf(cardTrial, cardOneMonth, cardFourMonth)

        fun refreshQr() {
            val upiUri = "upi://pay?pa=$UPI_ID&pn=${PAYEE_NAME.replace(" ", "%20")}" +
                    "&am=$selectedAmount&cu=INR&tn=JimiPremium_$selectedPlan"
            qrImageView.setImageBitmap(generateQrBitmap(upiUri))
        }

        fun selectCard(selected: android.widget.LinearLayout) {
            allCards.forEach { card ->
                card.setBackgroundResource(
                    if (card == selected) R.drawable.bg_plan_card_selected
                    else R.drawable.bg_plan_card_unselected
                )
            }
        }

        cardTrial.setOnClickListener {
            val phone = etPhone.text.toString().trim()
            if (phone.length != 10) {
                Toast.makeText(this, "Trial ke liye pehle apna 10-digit phone number daalo", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            PaymentVerificationHelper.checkTrialEligibility(
                phone = phone,
                deviceId = deviceId,
                onEligible = {
                    selectedPlan = "7_day_trial"
                    selectedAmount = 5
                    selectCard(cardTrial)
                    refreshQr()
                },
                onAlreadyUsed = {
                    Toast.makeText(this, "Is number ya device pe trial pehle hi use ho chuka hai 🚫", Toast.LENGTH_LONG).show()
                }
            )
        }

        cardOneMonth.setOnClickListener {
            selectedPlan = "1_month"
            selectedAmount = 69
            selectCard(cardOneMonth)
            refreshQr()
        }

        cardFourMonth.setOnClickListener {
            selectedPlan = "4_month"
            selectedAmount = 229
            selectCard(cardFourMonth)
            refreshQr()
        }

        selectCard(cardOneMonth)
        refreshQr()

        btnSubmit.setOnClickListener {
            val utr = etUtr.text.toString().trim()
            val phone = etPhone.text.toString().trim()

            if (utr.length < 6) {
                Toast.makeText(this, "Sahi UTR/Transaction ID daalo", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (phone.length != 10) {
                Toast.makeText(this, "Sahi 10-digit phone number daalo", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSubmit.isEnabled = false
            tvStatus.text = "Submitting..."

            // Trial plan ke liye final safety-check submit se theek pehle bhi (race-condition
            // se bachne ke liye, jaise ek hi phone se do baar submit dabana).
            if (selectedPlan == "7_day_trial") {
                PaymentVerificationHelper.checkTrialEligibility(
                    phone = phone,
                    deviceId = deviceId,
                    onEligible = {
                        submitRequest(phone, utr, tvStatus, btnSubmit)
                    },
                    onAlreadyUsed = {
                        btnSubmit.isEnabled = true
                        tvStatus.text = "Is number ya device pe trial pehle hi use ho chuka hai."
                    }
                )
            } else {
                submitRequest(phone, utr, tvStatus, btnSubmit)
            }
        }
    }

    private fun submitRequest(
        phone: String,
        utr: String,
        tvStatus: android.widget.TextView,
        btnSubmit: android.widget.Button
    ) {
        PaymentVerificationHelper.submitPaymentRequest(
            phone = phone,
            utr = utr,
            plan = selectedPlan,
            amount = selectedAmount,
            onSuccess = { docId ->
                currentDocId = docId
                tvStatus.text = "Verification pending... payment approve hote hi yahan turant update aa jaayega."
                startListening(docId, phone, tvStatus)
            },
            onFailure = {
                btnSubmit.isEnabled = true
                tvStatus.text = "Submit fail ho gaya, dobara try karo."
            }
        )
    }

    private fun startListening(docId: String, phone: String, tvStatus: android.widget.TextView) {
        listenerRegistration = PaymentVerificationHelper.listenForApproval(
            docId = docId,
            onApproved = { request ->
                LicenseActivator.activatePremium(this, request.plan)
                if (request.plan == "7_day_trial") {
                    PaymentVerificationHelper.markTrialUsed(phone, deviceId)
                }
                tvStatus.text = "Payment approved! Premium activated."
                Toast.makeText(this, "Premium unlocked", Toast.LENGTH_LONG).show()
                finish()
            },
            onRejected = {
                tvStatus.text = "Payment reject hua. Sahi UTR check karke dobara submit karo."
            },
            onStillPending = {
                tvStatus.text = "Verification pending..."
            }
        )
    }

    private fun generateQrBitmap(content: String, size: Int = 600): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    override fun onDestroy() {
        super.onDestroy()
        listenerRegistration?.remove()
    }
}

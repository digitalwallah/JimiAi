package com.jimi.ai

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(private val messages: MutableList<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: LinearLayout = view as LinearLayout
        val sender: TextView = view.findViewById(R.id.senderLabel)
        val text: TextView = view.findViewById(R.id.messageText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = messages[position]
        val isUser = msg.sender == "You"

        holder.sender.text = msg.sender
        holder.text.text = msg.text

        // User ka message right side, Jimi ka left side - jaisa WhatsApp/ChatGPT mein hota hai.
        holder.root.gravity = if (isUser) Gravity.END else Gravity.START
        holder.text.background = holder.root.context.getDrawable(
            if (isUser) R.drawable.bubble_user else R.drawable.bubble_jimi
        )
        holder.text.setTextColor(
            holder.root.context.getColor(if (isUser) R.color.jimi_text else android.R.color.white)
        )
    }

    override fun getItemCount() = messages.size

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }
}

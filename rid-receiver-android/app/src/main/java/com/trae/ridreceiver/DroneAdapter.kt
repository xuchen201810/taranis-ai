package com.trae.ridreceiver

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class DroneAdapter(
    private val onClick: (Drone) -> Unit
) : RecyclerView.Adapter<DroneAdapter.VH>() {

    private var items: List<Drone> = emptyList()

    fun submit(list: List<Drone>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_drone, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dot: TextView = itemView.findViewById(R.id.dot)
        private val title: TextView = itemView.findViewById(R.id.title)
        private val sub: TextView = itemView.findViewById(R.id.sub)
        private val dist: TextView = itemView.findViewById(R.id.dist)
        private val meta: TextView = itemView.findViewById(R.id.meta)

        fun bind(d: Drone) {
            dot.setTextColor(ContextCompat.getColor(itemView.context, d.statusColorRes()))
            title.text = d.title()

            val source = if (d.source == RidSource.BLE) "BLE" else "Wi-Fi"
            sub.text = listOf(d.uaTypeText, source, d.statusLabel())
                .filter { it.isNotBlank() }
                .joinToString(" · ")

            val distance = Format.distance(d.distanceM)
            dist.text = distance ?: "${d.rssi} dBm"

            val alt = d.altGeo ?: d.height
            meta.text = if (alt != null) {
                String.format(Locale.US, "%.1f m/s · %.0f m", d.speedH, alt)
            } else {
                String.format(Locale.US, "%.1f m/s · %s dBm", d.speedH, d.rssi)
            }

            itemView.setOnClickListener { onClick(d) }
        }
    }
}

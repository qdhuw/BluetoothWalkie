package com.bluete.walkie

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

@SuppressLint("MissingPermission")
class DeviceAdapter(
    private val devices: List<BluetoothDevice>,
    private val onDeviceClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    inner class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
        val ivIcon: ImageView = view.findViewById(R.id.ivDeviceIcon)
        val tvBondState: TextView = view.findViewById(R.id.tvBondState)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        val name = device.name?.takeIf { it.isNotBlank() } ?: "未知设备"
        holder.tvName.text = name
        holder.tvAddress.text = device.address

        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                holder.tvBondState.text = "已配对"
                holder.tvBondState.setTextColor(
                    holder.itemView.context.getColor(R.color.state_connected)
                )
                holder.ivIcon.setImageResource(R.drawable.ic_bluetooth_paired)
            }
            BluetoothDevice.BOND_BONDING -> {
                holder.tvBondState.text = "配对中…"
                holder.tvBondState.setTextColor(
                    holder.itemView.context.getColor(R.color.state_connecting)
                )
                holder.ivIcon.setImageResource(R.drawable.ic_bluetooth)
            }
            else -> {
                holder.tvBondState.text = "未配对"
                holder.tvBondState.setTextColor(
                    holder.itemView.context.getColor(R.color.state_disconnected)
                )
                holder.ivIcon.setImageResource(R.drawable.ic_bluetooth)
            }
        }

        holder.itemView.setOnClickListener { onDeviceClick(device) }
    }

    override fun getItemCount() = devices.size
}

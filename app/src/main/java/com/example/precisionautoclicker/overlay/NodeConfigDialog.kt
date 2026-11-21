package com.example.precisionautoclicker.overlay

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.example.precisionautoclicker.R
import com.example.precisionautoclicker.model.ActionNode

object NodeConfigDialog {

    fun show(
        context: Context,
        node: ActionNode,
        onSave: (ActionNode) -> Unit,
        onDelete: (ActionNode) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_node_config, null)

        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val etStartX = view.findViewById<EditText>(R.id.etStartX)
        val etStartY = view.findViewById<EditText>(R.id.etStartY)
        val layoutEndCoords = view.findViewById<LinearLayout>(R.id.layoutEndCoords)
        val etEndX = view.findViewById<EditText>(R.id.etEndX)
        val etEndY = view.findViewById<EditText>(R.id.etEndY)

        val etOrder = view.findViewById<EditText>(R.id.etOrderIndex)
        val etRepeat = view.findViewById<EditText>(R.id.etRepeatCount)

        val etHoldMs = view.findViewById<EditText>(R.id.etHoldDurationMs)
        val btnHoldMinus1 = view.findViewById<Button>(R.id.btnHoldMinus1)
        val btnHoldPlus1 = view.findViewById<Button>(R.id.btnHoldPlus1)
        val etHoldRandMs = view.findViewById<EditText>(R.id.etHoldRandomMs)

        val etDelayMs = view.findViewById<EditText>(R.id.etNextDelayMs)
        val etDelayRandMs = view.findViewById<EditText>(R.id.etNextDelayRandomMs)

        val etRandX = view.findViewById<EditText>(R.id.etRandomXPx)
        val etRandY = view.findViewById<EditText>(R.id.etRandomYPx)

        val tvProbValue = view.findViewById<TextView>(R.id.tvProbabilityValue)
        val seekProb = view.findViewById<SeekBar>(R.id.seekProbability)
        val btnProbMinus1 = view.findViewById<Button>(R.id.btnProbMinus1)
        val btnProbPlus1 = view.findViewById<Button>(R.id.btnProbPlus1)

        val cbRetry = view.findViewById<CheckBox>(R.id.cbRetryOnCancel)

        tvTitle.text = if (node.isDrag) {
            "#${node.orderIndex} 드래그 노드 세부 설정"
        } else {
            "#${node.orderIndex} 클릭 노드 세부 설정"
        }

        etStartX.setText(node.startX.toInt().toString())
        etStartY.setText(node.startY.toInt().toString())
        if (node.isDrag) {
            layoutEndCoords.visibility = View.VISIBLE
            etEndX.setText(node.endX.toInt().toString())
            etEndY.setText(node.endY.toInt().toString())
        } else {
            layoutEndCoords.visibility = View.GONE
        }

        etOrder.setText(node.orderIndex.toString())
        etRepeat.setText(node.repeatCount.toString())
        etHoldMs.setText(node.holdDurationMs.toString())
        etHoldRandMs.setText(node.holdRandomMs.toString())
        etDelayMs.setText(node.nextDelayMs.toString())
        etDelayRandMs.setText(node.nextDelayRandomMs.toString())
        etRandX.setText(node.randomXRangePx.toString())
        etRandY.setText(node.randomYRangePx.toString())
        cbRetry.isChecked = node.retryOnCancel

        btnHoldMinus1.setOnClickListener {
            val cur = etHoldMs.text.toString().toLongOrNull() ?: 50L
            etHoldMs.setText((cur - 1L).coerceAtLeast(1L).toString())
        }
        btnHoldPlus1.setOnClickListener {
            val cur = etHoldMs.text.toString().toLongOrNull() ?: 50L
            etHoldMs.setText((cur + 1L).toString())
        }

        val initProb = node.triggerProbabilityPct.coerceIn(0, 100)
        seekProb.max = 100
        seekProb.progress = initProb
        tvProbValue.text = "${initProb}%"

        seekProb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvProbValue.text = "${progress}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnProbMinus1.setOnClickListener {
            val next = (seekProb.progress - 1).coerceIn(0, 100)
            seekProb.progress = next
            tvProbValue.text = "${next}%"
        }
        btnProbPlus1.setOnClickListener {
            val next = (seekProb.progress + 1).coerceIn(0, 100)
            seekProb.progress = next
            tvProbValue.text = "${next}%"
        }

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setView(view)
            .setPositiveButton("저장") { _, _ ->
                val sx = etStartX.text.toString().toFloatOrNull() ?: node.startX
                val sy = etStartY.text.toString().toFloatOrNull() ?: node.startY
                val ex = if (node.isDrag) (etEndX.text.toString().toFloatOrNull() ?: node.endX) else sx
                val ey = if (node.isDrag) (etEndY.text.toString().toFloatOrNull() ?: node.endY) else sy

                val updated = node.copy(
                    startX = sx.coerceAtLeast(1f),
                    startY = sy.coerceAtLeast(1f),
                    endX = ex.coerceAtLeast(1f),
                    endY = ey.coerceAtLeast(1f),
                    orderIndex = (etOrder.text.toString().toIntOrNull() ?: node.orderIndex).coerceAtLeast(1),
                    repeatCount = (etRepeat.text.toString().toIntOrNull() ?: node.repeatCount).coerceAtLeast(1),
                    holdDurationMs = (etHoldMs.text.toString().toLongOrNull() ?: node.holdDurationMs).coerceAtLeast(1L),
                    holdRandomMs = (etHoldRandMs.text.toString().toLongOrNull() ?: 0L).coerceAtLeast(0L),
                    nextDelayMs = (etDelayMs.text.toString().toLongOrNull() ?: node.nextDelayMs).coerceAtLeast(0L),
                    nextDelayRandomMs = (etDelayRandMs.text.toString().toLongOrNull() ?: 0L).coerceAtLeast(0L),
                    randomXRangePx = (etRandX.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0),
                    randomYRangePx = (etRandY.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0),
                    triggerProbabilityPct = seekProb.progress.coerceIn(0, 100),
                    retryOnCancel = cbRetry.isChecked
                )
                onSave(updated)
            }
            .setNeutralButton("삭제") { _, _ ->
                onDelete(node)
            }
            .setNegativeButton("취소", null)
            .create()

        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        )
        dialog.show()
    }
}

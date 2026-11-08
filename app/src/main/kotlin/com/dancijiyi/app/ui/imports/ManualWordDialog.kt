package com.dancijiyi.app.ui.imports

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import com.dancijiyi.domain.model.WordDraft

/**
 * 权限被拒后的降级手段：手动输入单词。
 *
 * 为什么这是最合适的降级：
 * 用户之所以进不来文件，往往就是「不给权限」。此时再弹权限说明只会让人更烦；
 * 直接给一条「不用任何权限也能继续用」的路径，才不会把用户挡在核心功能之外。
 */
object ManualWordDialog {

    fun show(
        activity: Activity,
        onSave: (WordDraft) -> Unit,
    ) {
        val padding = (16 * activity.resources.displayMetrics.density).toInt()

        val spellingInput = EditText(activity).apply {
            hint = "单词（必填）"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val meaningInput = EditText(activity).apply {
            hint = "释义（必填）"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(spellingInput)
            addView(meaningInput)
        }

        AlertDialog.Builder(activity)
            .setTitle("手动添加单词")
            .setMessage("无法读取文件时，可以逐个添加，不需要任何权限。")
            .setView(container)
            .setPositiveButton("添加") { _, _ ->
                val spelling = spellingInput.text.toString().trim()
                val meaning = meaningInput.text.toString().trim()
                if (spelling.isEmpty() || meaning.isEmpty()) {
                    android.widget.Toast.makeText(
                        activity,
                        "单词和释义都不能为空",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    onSave(WordDraft(spelling = spelling, meaning = meaning))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

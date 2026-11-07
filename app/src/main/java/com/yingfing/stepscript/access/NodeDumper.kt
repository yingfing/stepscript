package com.yingfing.stepscript.access

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 布局序列化：把节点树转成两种格式——
 *  1) XML：结构完整，给布局分析器和脚本修改用
 *  2) 压缩文本：一行一节点，给 AI（脚本医生）用，省 token
 */
object NodeDumper {

    /** 压缩文本格式：每行 `[序号] 类型 bounds "文本" id=… desc=… clickable` */
    fun toText(root: AccessibilityNodeInfo?): String {
        if (root == null) return "（无法获取界面：无障碍服务未开启或目标应用禁止读取）"
        val sb = StringBuilder()
        var index = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
            if (r.width() == 0 && r.height() == 0) return
            val cls = n.className?.toString()?.substringAfterLast('.') ?: "View"
            val text = n.text?.toString()?.take(40) ?: ""
            val id = n.viewIdResourceName ?: ""
            val desc = n.contentDescription?.toString()?.take(30) ?: ""
            val flags = buildString {
                if (n.isClickable) append(" 可点")
                if (n.isEditable) append(" 可编辑")
            }
            sb.append("[$index] ${"  ".repeat(depth)}$cls (${r.left},${r.top},${r.width()}x${r.height()})")
            if (text.isNotEmpty()) sb.append(" \"$text\"")
            if (id.isNotEmpty()) sb.append(" id=$id")
            if (desc.isNotEmpty()) sb.append(" desc=$desc")
            sb.append(flags).append('\n')
            index++
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return sb.toString()
    }

    /** 结构化 XML（uiautomator dump 风格，简化版）。 */
    fun toXml(root: AccessibilityNodeInfo?): String {
        if (root == null) return "<!-- 无法获取界面 -->"
        val sb = StringBuilder()
        fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
            val cls = n.className?.toString() ?: "android.view.View"
            sb.append("${"    ".repeat(depth)}<$cls")
            sb.append(" bounds=\"[${r.left},${r.top}][${r.right},${r.bottom}]\"")
            n.text?.let { sb.append(" text=\"${esc(it.toString())}\"") }
            n.viewIdResourceName?.let { sb.append(" resource-id=\"$it\"") }
            n.contentDescription?.let { sb.append(" content-desc=\"${esc(it.toString())}\"") }
            if (n.isClickable) sb.append(" clickable=\"true\"")
            if (n.isEditable) sb.append(" editable=\"true\"")
            if (n.childCount == 0) {
                sb.append(" />\n")
            } else {
                sb.append(">\n")
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
                sb.append("${"    ".repeat(depth)}</$cls>\n")
            }
        }
        sb.append("<?xml version='1.0' encoding='UTF-8'?>\n<hierarchy>\n")
        walk(root, 1)
        sb.append("</hierarchy>\n")
        return sb.toString()
    }
}

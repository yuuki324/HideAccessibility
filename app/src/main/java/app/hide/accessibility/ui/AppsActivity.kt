/*
 * Copyright (C) 2026 yuuki324 <https://github.com/yuuki324/HideAccessibility>
 * Copyright (C) Nitsuya <https://github.com/Nitsuya/DoNotTryAccessibility>
 *
 * This file is part of HideAccessibility, a port of DoNotTryAccessibility.
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this
 * program. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package app.hide.accessibility.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import app.hide.accessibility.R
import app.hide.accessibility.data.ConfigStore
import app.hide.accessibility.utils.AppCatalog
import app.hide.accessibility.utils.AppRow
import java.util.concurrent.Executors

/**
 * 应用列表：勾选谁，谁就会以为本机没开无障碍。
 *
 * 应用列表直接在模块自己进程里用 PackageManager 枚举，不经 system_server 跨进程回传，
 * 少一条通道也少一份权限面。导入/导出使用 XML 格式。
 */
class AppsActivity : BaseActivity() {

    private lateinit var adapter: AppsAdapter
    private lateinit var listView: ListView
    private lateinit var progress: ProgressBar
    private lateinit var empty: TextView
    private lateinit var countText: TextView

    private val executor = Executors.newSingleThreadExecutor()

    private var filter = AppCatalog.Filter.ALL
    private var keyword = ""
    private var reloadScheduled = false
    private var firstResume = true

    override fun onCreate() {
        setContentView(R.layout.activity_apps)
        title = getString(R.string.title_apps)

        listView = findViewById(R.id.list_apps)
        progress = findViewById(R.id.progress)
        empty = findViewById(R.id.text_empty)
        countText = findViewById(R.id.text_count)

        adapter = AppsAdapter(this) { row -> toggle(row) }
        listView.adapter = adapter

        findViewById<TextView>(R.id.button_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.button_refresh).setOnClickListener { reload(force = true) }
        findViewById<TextView>(R.id.button_filter).setOnClickListener { showFilterDialog() }
        findViewById<TextView>(R.id.button_export).setOnClickListener { exportConfig() }
        findViewById<TextView>(R.id.button_import).setOnClickListener { importConfig() }

        val search = findViewById<EditText>(R.id.edit_search)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                keyword = s?.toString().orEmpty()
                reload()
            }
        })
        search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                keyword = v.text?.toString().orEmpty()
                reload()
                true
            } else false
        }

        reload()
    }

    override fun onResume() {
        super.onResume()
        // 关键：首次进入会弹「读取应用列表」授权对话框。授权结果没有可靠的回调时机
        // （不同 ROM 行为不一，Android 11+ 还可能是"仅此一次"），但对话框关闭后
        // 本 Activity 必然回到前台 —— 所以在这里重载，授权完就能立刻看到列表，
        // 不需要"退出再重进"。
        if (firstResume) {
            firstResume = false
        } else {
            reload(force = true)
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 数据

    /**
     * 重新枚举应用列表。
     *
     * @param force 忽略"已在加载中"的节流。列表重排（勾选后已选项前置）和回到前台
     *   都必须能强制刷新，否则会看到过期内容。
     */
    private fun reload(force: Boolean = false) {
        if (reloadScheduled && !force) return
        reloadScheduled = true
        progress.visibility = View.VISIBLE
        empty.visibility = View.GONE
        countText.text = getString(R.string.loading)

        val currentFilter = filter
        val currentKeyword = keyword
        executor.execute {
            val rows = runCatching { AppCatalog.load(this, currentFilter, currentKeyword) }.getOrElse { emptyList() }
            runOnUiThread {
                reloadScheduled = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.submit(rows)
                progress.visibility = View.GONE
                empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                listView.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
                countText.text = getString(R.string.count_result, rows.size)
            }
        }
    }

    private fun toggle(row: AppRow) {
        val blocked = ConfigStore.read(this).toMutableSet()
        val nowBlocked = if (blocked.contains(row.packageName)) {
            blocked.remove(row.packageName)
            false
        } else {
            blocked.add(row.packageName)
            true
        }
        // write 返回 false = 只写进了本地兜底文件，system_server 读不到。
        // 这种情况必须明说，不能提示"已隐藏"却根本没生效。
        val delivered = ConfigStore.write(this, blocked) && ConfigStore.read(this).contains(row.packageName) == nowBlocked
        Toast.makeText(
            this,
            when {
                !delivered -> getString(R.string.blocked_not_delivered)
                nowBlocked -> getString(R.string.blocked_toast, row.label)
                else -> getString(R.string.unblocked_toast, row.label)
            },
            if (delivered) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
        ).show()
        reload()
    }

    private fun showFilterDialog() {
        val options = arrayOf(
            getString(R.string.filter_all),
            getString(R.string.filter_user),
            getString(R.string.filter_system),
        )
        val checked = when (filter) {
            AppCatalog.Filter.ALL -> 0
            AppCatalog.Filter.USER -> 1
            AppCatalog.Filter.SYSTEM -> 2
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_filter)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                filter = when (which) {
                    1 -> AppCatalog.Filter.USER
                    2 -> AppCatalog.Filter.SYSTEM
                    else -> AppCatalog.Filter.ALL
                }
                dialog.dismiss()
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ 导入导出

    private val requestCreate = 1001
    private val requestOpen = 1002

    private fun exportConfig() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/xml"
            putExtra(Intent.EXTRA_TITLE, "hideaccessibility.xml")
        }
        runCatching { startActivityForResult(intent, requestCreate) }
            .onFailure { toast(getString(R.string.export_failed)) }
    }

    private fun importConfig() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/xml", "text/xml"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivityForResult(Intent.createChooser(intent, getString(R.string.action_import)), requestOpen) }
            .onFailure { toast(getString(R.string.import_failed)) }
    }

    @Deprecated("framework Activity 没有 ActivityResult API，这里必须用 onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            requestCreate -> toast(getString(if (writeTo(uri)) R.string.export_success else R.string.export_failed))
            requestOpen -> {
                val ok = readFrom(uri)
                toast(getString(if (ok) R.string.import_success else R.string.import_failed))
                if (ok) reload()
            }
        }
    }

    private fun writeTo(uri: Uri): Boolean = runCatching {
        val xml = ConfigStore.exportXml(ConfigStore.read(this))
        contentResolver.openOutputStream(uri)?.use { it.write(xml.toByteArray(Charsets.UTF_8)) }
            ?: return false
        true
    }.getOrDefault(false)

    private fun readFrom(uri: Uri): Boolean = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val parser = ConfigStore.newParser().apply { setInput(input, "UTF-8") }
            val imported = ConfigStore.parseXml(parser)
            ConfigStore.write(this, imported)
            true
        } ?: false
    }.getOrDefault(false)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

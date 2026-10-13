package app.hide.accessibility.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import app.hide.accessibility.R
import app.hide.accessibility.utils.AppRow

/** 应用列表适配器（纯 framework View，不依赖 androidx / material） */
class AppsAdapter(
    private val context: Context,
    private val onToggle: (AppRow) -> Unit,
) : BaseAdapter() {

    private val rows = ArrayList<AppRow>()
    private val iconCache = HashMap<String, android.graphics.drawable.Drawable?>()

    fun submit(list: List<AppRow>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = rows.size
    override fun getItem(position: Int): Any = rows[position]
    override fun getItemId(position: Int): Long = rows[position].packageName.hashCode().toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_app, parent, false)
        val row = rows[position]

        val icon = view.findViewById<ImageView>(R.id.app_icon)
        val name = view.findViewById<TextView>(R.id.app_name)
        val pkg = view.findViewById<TextView>(R.id.app_package)
        val check = view.findViewById<CheckBox>(R.id.app_check)

        name.text = row.label
        pkg.text = row.packageName
        check.isChecked = row.blocked
        icon.setImageDrawable(iconOf(row.packageName))

        // 整行点击都由 Activity 处理，避免 CheckBox 自己抢走焦点导致点击无反应
        view.setOnClickListener { onToggle(row) }
        check.setOnClickListener { onToggle(row) }
        return view
    }

    private fun iconOf(packageName: String): android.graphics.drawable.Drawable? =
        iconCache.getOrPut(packageName) {
            runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
        }
}

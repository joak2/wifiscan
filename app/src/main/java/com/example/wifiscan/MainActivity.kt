package com.example.wifiscan

import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.View
import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class MainActivity : Activity() {

    private val items = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var status: TextView
    private lateinit var button: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        button = Button(this).apply { text = "Scanner le réseau" }
        status = TextView(this).apply {
            text = "Prêt. Le scan dure environ 20 secondes."
            setPadding(0, pad / 2, 0, pad / 2)
        }
        val list = ListView(this)
        val vPad = (14 * resources.displayMetrics.density).toInt()
		adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_list_item_1, items) {
			override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
				val tv = super.getView(position, convertView, parent) as TextView
				val text = getItem(position) ?: ""
				val end = text.indexOf("  -  ").let { if (it < 0) text.length else it }
				val s = SpannableString(text)
				s.setSpan(StyleSpan(Typeface.BOLD), 0, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
				tv.text = s
				tv.setPadding(0, vPad, 0, vPad)
				return tv
			}
		}
		list.adapter = adapter
		list.divider = ColorDrawable(0x33000000)
		list.dividerHeight = 2

        root.addView(button)
        root.addView(status)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(b.left + pad, b.top + pad, b.right + pad, b.bottom + pad)
            WindowInsets.CONSUMED
        }
        setContentView(root)
        button.setOnClickListener { startScan() }
    }

    private fun startScan() {
        button.isEnabled = false
        items.clear()
        adapter.notifyDataSetChanged()
        status.text = "Scan en cours..."
        Thread {
            val res = Scanner(applicationContext).scan { done, total ->
                runOnUiThread { status.text = "Scan en cours : $done/$total adresses testées" }
            }
            runOnUiThread {
                button.isEnabled = true
                if (res.error != null) {
                    status.text = res.error
                } else {
                    status.text = "${res.devices.size} appareil(s) sur ${res.subnet}.x. Types déduits, donc approximatifs."
                    res.devices.forEach { items.add(format(it)) }
                    adapter.notifyDataSetChanged()
                }
            }
        }.start()
    }

    private fun format(d: Device): String {
        val sb = StringBuilder(d.ip + "  -  " + d.type)
        val names = (listOf(d.hostname) + d.friendly).filter { it.isNotBlank() }.distinct()
        if (names.isNotEmpty()) sb.append("\nNom : ").append(names.joinToString(", "))
        if (d.services.isNotEmpty()) {
            sb.append("\nServices : ").append(d.services.sorted().joinToString(", ") {
                it.removePrefix("_").substringBefore("._")
            })
        }
        if (d.ports.isNotEmpty()) sb.append("\nPorts ouverts : ").append(d.ports.sorted().joinToString(", "))
        return sb.toString()
    }
}

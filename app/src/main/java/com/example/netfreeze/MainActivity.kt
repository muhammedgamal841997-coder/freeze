package com.example.netfreeze

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {
    private val apps = mutableListOf<AppInfo>()
    private lateinit var adapter: AppAdapter
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) startVpnServiceDirect()
        else {
            apps.forEach { it.isFrozen = false }
            prefs.edit().putStringSet(KEY_FROZEN, emptySet()).apply()
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "لازم توافق على اتصال VPN المحلي لتفعيل الحظر", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createLayout())
        adapter = AppAdapter(apps) { app, freeze -> onFreezeChanged(app, freeze) }
        findViewById<RecyclerView>(R.id.appsList).adapter = adapter
        if (!PinManager.hasPin(this)) showCreatePinDialog()
        loadInstalledApps()
        requestVpnPermissionIfNeeded()
    }

    private fun createLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(12))
            setBackgroundColor(0xFFF5F7FB.toInt())
        }
        val title = TextView(this).apply {
            text = "❄  Net Freeze"
            textSize = 26f
            setTextColor(0xFF172238.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(title)
        val subtitle = TextView(this).apply {
            text = "اختار التطبيقات اللي عايز تمنع عنها الإنترنت"
            textSize = 14f
            setTextColor(0xFF65718A.toInt())
            setPadding(0, dp(8), 0, dp(18))
        }
        root.addView(subtitle)
        val list = RecyclerView(this).apply {
            id = R.id.appsList
            layoutManager = LinearLayoutManager(this@MainActivity)
            setHasFixedSize(false)
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val note = TextView(this).apply {
            text = "الحظر يتم عبر VPN محلي. لا يمكن تشغيل VPN آخر بالتزامن مع التطبيق."
            textSize = 12f
            setTextColor(0xFF7B8495.toInt())
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(note)
        return root
    }

    private fun loadInstalledApps() {
        val pm = packageManager
        val frozen = getFrozenAppList()
        apps.clear()
        pm.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != packageName }
            .forEach { info ->
                apps.add(AppInfo(
                    pm.getApplicationLabel(info).toString(), info.packageName,
                    pm.getApplicationIcon(info), frozen.contains(info.packageName)
                ))
            }
        apps.sortBy { it.appName.lowercase() }
        adapter.notifyDataSetChanged()
    }

    fun getFrozenAppList(): Set<String> = prefs.getStringSet(KEY_FROZEN, emptySet())?.toSet() ?: emptySet()

    private fun onFreezeChanged(app: AppInfo, freeze: Boolean) {
        if (freeze) {
            app.isFrozen = true
            saveFrozenApps()
        } else {
            showVerifyPinDialog {
                app.isFrozen = false
                saveFrozenApps()
                adapter.notifyDataSetChanged()
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun saveFrozenApps() {
        val frozen = apps.filter { it.isFrozen }.map { it.packageName }.toSet()
        prefs.edit().putStringSet(KEY_FROZEN, frozen).apply()
        if (frozen.isEmpty()) stopService(Intent(this, NetBlockerVpnService::class.java))
        else startVpnIfNeeded()
    }

    private fun requestVpnPermissionIfNeeded() = startVpnIfNeeded()

    private fun startVpnIfNeeded() {
        if (getFrozenAppList().isEmpty()) return
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) vpnPermission.launch(permissionIntent)
        else startVpnServiceDirect()
    }

    private fun startVpnServiceDirect() {
        if (getFrozenAppList().isEmpty()) return
        val intent = Intent(this, NetBlockerVpnService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun showCreatePinDialog() {
        val input = pinInput("أنشئ رمز PIN من 4 أرقام على الأقل")
        AlertDialog.Builder(this)
            .setTitle("إعداد رمز الأمان")
            .setMessage("الرمز مطلوب قبل إلغاء حظر أي تطبيق.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("حفظ", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val pin = input.text.toString()
                        if (pin.length >= 4 && pin.all { it.isDigit() }) {
                            PinManager.savePin(this, pin)
                            dialog.dismiss()
                        } else input.error = "أدخل 4 أرقام على الأقل"
                    }
                }
                dialog.show()
            }
    }

    private fun showVerifyPinDialog(onSuccess: () -> Unit) {
        val input = pinInput("أدخل رمز PIN")
        AlertDialog.Builder(this)
            .setTitle("تأكيد الهوية")
            .setView(input)
            .setPositiveButton("تأكيد") { _, _ ->
                if (PinManager.verify(this, input.text.toString())) onSuccess()
                else Toast.makeText(this, "رمز PIN غير صحيح", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun pinInput(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        setPadding(dp(20), dp(12), dp(20), dp(12))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS_NAME = "NetFreezePrefs"
        private const val KEY_FROZEN = "frozen_apps"
    }
}

class AppAdapter(
    private val items: List<AppInfo>,
    private val onToggle: (AppInfo, Boolean) -> Unit
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    class Holder(val row: LinearLayout, val icon: ImageView, val name: TextView, val toggle: android.widget.Switch) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding( dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val icon = ImageView(context)
        row.addView(icon, LinearLayout.LayoutParams(dp(context, 42), dp(context, 42)))
        val name = TextView(context).apply {
            textSize = 15f
            setTextColor(0xFF202B40.toInt())
            setPadding(dp(context, 12), 0, dp(context, 8), 0)
        }
        row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = android.widget.Switch(context)
        row.addView(toggle)
        return Holder(row, icon, name, toggle)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = items[position]
        holder.icon.setImageDrawable(app.icon)
        holder.name.text = app.appName
        holder.toggle.setOnCheckedChangeListener(null)
        holder.toggle.isChecked = app.isFrozen
        holder.toggle.setOnCheckedChangeListener { _, checked -> onToggle(app, checked) }
    }

    override fun getItemCount(): Int = items.size

    companion object {
        private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    }
}

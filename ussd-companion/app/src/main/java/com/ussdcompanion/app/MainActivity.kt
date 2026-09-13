package com.ussdcompanion.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusServer: TextView
    private lateinit var statusAccessibility: TextView
    private lateinit var statusSms: TextView
    private lateinit var statusCallPermission: TextView
    private lateinit var statusBattery: TextView
    private lateinit var editApiKey: EditText
    private lateinit var editPort: EditText
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        statusServer = findViewById(R.id.statusServer)
        statusAccessibility = findViewById(R.id.statusAccessibility)
        statusSms = findViewById(R.id.statusSms)
        statusCallPermission = findViewById(R.id.statusCallPermission)
        statusBattery = findViewById(R.id.statusBattery)
        editApiKey = findViewById(R.id.editApiKey)
        editPort = findViewById(R.id.editPort)
        logView = findViewById(R.id.logView)

        editApiKey.setText(prefs.getString(Prefs.API_KEY, ""))
        editPort.setText(prefs.getInt(Prefs.PORT, 8080).toString())

        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            showRestrictedSettingsHintIfNeeded()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnGrantSms).setOnClickListener {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.READ_SMS), 100)
        }

        findViewById<Button>(R.id.btnGrantCallPermission).setOnClickListener {
            val permissions = mutableListOf(
                android.Manifest.permission.CALL_PHONE,
                android.Manifest.permission.READ_PHONE_STATE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                permissions.add(android.Manifest.permission.READ_PHONE_NUMBERS)
            }
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                102
            )
        }

        findViewById<Button>(R.id.btnIgnoreBatteryOptimization).setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveSettingsAndRestartServer()
        }

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            ActivityLog.clear()
            refreshStatus()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        startHttpServerServiceIfConfigured()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun saveSettingsAndRestartServer() {
        val key = editApiKey.text.toString().trim()
        val portText = editPort.text.toString().trim()
        val port = portText.toIntOrNull() ?: 8080

        if (TextUtils.isEmpty(key)) {
            Toast.makeText(this, "الرجاء إدخال مفتاح API أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit()
            .putString(Prefs.API_KEY, key)
            .putInt(Prefs.PORT, port)
            .apply()

        stopService(Intent(this, HttpServerService::class.java))
        startHttpServerServiceIfConfigured()
        Toast.makeText(this, "تم الحفظ، الخادم يعمل الآن على المنفذ $port", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun startHttpServerServiceIfConfigured() {
        val key = prefs.getString(Prefs.API_KEY, "") ?: ""
        if (key.isEmpty()) return
        ContextCompat.startForegroundService(this, Intent(this, HttpServerService::class.java))
    }

    // 🆕 أندرويد 13+ (Restricted Settings): أول مرة يفتح فيها المستخدم تطبيقاً مثبّتاً من خارج
    // متجر (مثلاً عبر نقل ملف APK يدوياً بدل adb install)، يُمنع تفعيل خدمة الوصول تلقائياً دون
    // أي رسالة خطأ واضحة داخل شاشة إعدادات تسهيلات الوصول نفسها. تثبيت adb install لا يتأثر
    // بهذا القيد إطلاقاً (وهذا ما تعتمدونه أصلاً لأجهزة الاختبار)، لكن نُبقي التنبيه هنا لتغطية
    // حالة تثبيت الوكلاء يدوياً على أجهزتهم النهائية.
    private fun showRestrictedSettingsHintIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isAccessibilityServiceEnabled()) {
            Toast.makeText(
                this,
                "إن بقي مفتاح خدمة الوصول رمادياً أو لم يظهر التطبيق في القائمة: افتح إعدادات " +
                    "التطبيق ← النقاط الثلاث (⋮) أعلى اليمين ← \"السماح بالإعداد المقيّد\"، ثم " +
                    "عودوا لهذه الشاشة وفعّلوا الخدمة.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // 🆕 استثناء التطبيق من تحسين البطارية - مهم خصوصاً على سامسونج/One UI التي تقتل الخدمات
    // الخلفية بعدوانية أكثر من AOSP القياسي، وقد توقف خدمة الخادم المحلي أو خدمة الوصول بصمت.
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "التطبيق مستثنى بالفعل من تحسين البطارية", Toast.LENGTH_SHORT).show()
            refreshStatus()
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Exception) {
            // بعض الشركات المصنّعة تمنع هذا الـ intent المباشر أحياناً - نوجّه لصفحة الإعدادات العامة
            Toast.makeText(
                this,
                "الرجاء استثناء التطبيق يدوياً من تحسين البطارية عبر إعدادات الجهاز",
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                ActivityLog.add("تعذّر فتح إعدادات تحسين البطارية: ${e2.message}")
            }
        }
    }

    private fun refreshStatus() {
        val port = prefs.getInt(Prefs.PORT, 8080)
        statusServer.text = "حالة الخادم: يعمل محلياً على المنفذ $port (عبر adb forward فقط)"

        statusAccessibility.text = "خدمة الوصول: " +
            if (isAccessibilityServiceEnabled()) "مفعّلة ✓" else "غير مفعّلة - اضغط الزر أعلاه"

        val hasSms = ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        statusSms.text = "إذن قراءة الرسائل: " + if (hasSms) "ممنوح ✓" else "غير ممنوح"

        val hasCall = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val hasPhoneState = ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasPhoneNumbers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
        } else true
        statusCallPermission.text = "إذن الاتصال وتحديد الشريحة: " + if (hasCall && hasPhoneState && hasPhoneNumbers) "ممنوح ✓" else "غير ممنوح"

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val ignoringBatteryOptimization = pm.isIgnoringBatteryOptimizations(packageName)
        statusBattery.text = "استثناء تحسين البطارية: " +
            if (ignoringBatteryOptimization) "مفعّل ✓" else "غير مفعّل - يُنصح به خصوصاً على سامسونج"

        logView.text = ActivityLog.dump()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }
}

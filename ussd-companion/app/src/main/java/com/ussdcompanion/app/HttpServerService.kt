package com.ussdcompanion.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class HttpServerService : Service() {

    private var server: LocalServer? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()

        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val port = prefs.getInt(Prefs.PORT, 8080)
        val apiKey = prefs.getString(Prefs.API_KEY, "") ?: ""

        if (apiKey.isEmpty()) {
            ActivityLog.add(
                "⚠️ تحذير أمني: لم يُضبط مفتاح API - الخادم المحلي سيعمل بلا أي مصادقة على " +
                    "X-API-Key. يُنصح بضبط مفتاح من الإعدادات قبل الاستخدام الفعلي."
            )
        }

        server = LocalServer(applicationContext, port, apiKey)
        try {
            server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            ActivityLog.add("الخادم المحلي يعمل على 127.0.0.1:$port")
        } catch (e: Exception) {
            ActivityLog.add("تعذّر بدء الخادم على المنفذ $port: ${e.message}")
        }
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // 🆕 أندرويد 14 (targetSdk 34): يُشترط إعلان foregroundServiceType واحد على الأقل في
    // AndroidManifest.xml لهذه الخدمة (اخترنا "specialUse" - راجع الـ Manifest). حسب توثيق
    // أندرويد الرسمي، النظام يقرأ النوع من الـ Manifest تلقائياً عند استدعاء startForeground()
    // بصيغته العادية (بلا معامل نوع)؛ لا حاجة لتغيير هذا الاستدعاء نفسه.
    private fun startForegroundWithNotification() {
        val channelId = "ussd_companion_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "USSD Companion", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("USSD Companion يعمل")
            .setContentText("جاهز لاستقبال أوامر عبر ADB")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    /** يرتبط بـ 127.0.0.1 فقط (لا يُستقبل إلا عبر adb forward)، ويتحقق من X-API-Key في كل طلب. */
    private class LocalServer(
        private val context: Context,
        port: Int,
        private val apiKey: String
    ) : NanoHTTPD("127.0.0.1", port) {

        override fun serve(session: IHTTPSession): Response {
            if (apiKey.isNotEmpty() && session.headers["x-api-key"] != apiKey) {
                return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().put("error", "invalid api key"))
            }

            return try {
                val uri = session.uri
                when {
                    uri == "/status" && session.method == Method.GET -> handleStatus()
                    uri == "/health" && session.method == Method.GET -> handleHealth()
                    uri == "/ussd/send" && session.method == Method.POST -> handleUssdSend(session)
                    uri.startsWith("/ussd/response/") && session.method == Method.GET -> handleUssdResponse(uri)
                    uri.startsWith("/ussd/dismiss/") && session.method == Method.POST -> handleUssdDismiss()
                    uri == "/sms/list" && session.method == Method.GET -> handleSmsList(session)
                    uri == "/sms/delete" && session.method == Method.DELETE -> handleSmsDelete()
                    uri == "/sms/wait" && session.method == Method.POST -> handleSmsWait(session)
                    else -> jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "not found"))
                }
            } catch (e: Exception) {
                jsonResponse(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "unknown"))
            }
        }

        private fun handleStatus(): Response {
            val json = JSONObject()
                .put("busy", UssdSessionState.status != UssdSessionState.STATUS_IDLE)
                .put("signal", 0)
                .put("sim", true)
                .put("battery", getBatteryLevel())
            return jsonResponse(Response.Status.OK, json)
        }

        private fun handleHealth(): Response {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            val accessibilityOn = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
            val smsOn = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

            val json = JSONObject()
                .put("accessibility", accessibilityOn)
                .put("readSms", smsOn)
                .put("notificationListener", true)
                .put("network", true)
                .put("simReady", true)
            return jsonResponse(Response.Status.OK, json)
        }

        private fun handleUssdSend(session: IHTTPSession): Response {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val body = JSONObject(files["postData"] ?: "{}")
            val code = body.optString("code", "")
            val existingSessionId: String? = if (body.isNull("sessionId")) null else body.optString("sessionId")
            val simSlot = body.optInt("simSlot", -1)

            if (code.isEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "code is required"))
            }

            // استكمال جلسة قائمة بانتظار إدخال المستخدم (مثلاً كود PIN/تأكيد بعد أول خطوة USSD)
            if (existingSessionId != null &&
                existingSessionId == UssdSessionState.currentRequestId &&
                UssdSessionState.status == UssdSessionState.STATUS_WAITING_USER_INPUT
            ) {
                ActivityLog.add("[HTTP] تم استقبال كود التأكيد/المتابعة: $code للجلسة: $existingSessionId")

                UssdSessionState.pendingInputToSend.set(code)

                // استدعاء مباشر لخدمة الوصول لتنفيذ الإدخال فوراً دون انتظار حدث تغيّر شاشة جديد
                UssdAccessibilityService.instance?.performPendingActionsDirectly()
                    ?: ActivityLog.add("[HTTP] تنبيه: UssdAccessibilityService غير متوفرة!")

                return jsonResponse(Response.Status.OK, JSONObject().put("requestId", existingSessionId))
            }

            if (UssdSessionState.status != UssdSessionState.STATUS_IDLE) {
                return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "device busy"))
            }

            val requestId = UUID.randomUUID().toString()
            UssdSessionState.startNewSession(requestId)

            val dialIntent = Intent(Intent.ACTION_CALL)
            dialIntent.data = Uri.parse("tel:" + Uri.encode(code))
            dialIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            // إن أُرسل simSlot مع الطلب، نحدد الشريحة برمجياً فلا تظهر نافذة الاختيار إطلاقاً.
            // resolvePhoneAccountForSlot تجرّب 3 مراحل بثقة متدرجة (راجع التوثيق أعلى الدالة في
            // SimSelector.kt): مطابقة دقيقة مؤكدة، ثم مطابقة تخمينية بنمط شائع (تُسجَّل بـ"تنبيه")،
            // ثم تخمين ترتيبي (allowOrdinalFallback=true هنا بقرار واعٍ - مُعطَّل افتراضياً في
            // توقيع الدالة نفسها لأي استدعاء مستقبلي آخر). راجعوا ActivityLog بعد أول طلب فعلي:
            // أي سطر يبدأ بـ"تنبيه" يعني مطابقة غير مؤكدة بعد على جهازكم، ويستحق تحققاً يدوياً
            // (إرسال طلب لكل شريحة على حدة والتأكد من الرصيد الراجع فعلاً) قبل الاعتماد الكامل.
            if (simSlot != -1) {
                val handle = SimSelector.resolvePhoneAccountForSlot(context, simSlot, allowOrdinalFallback = true)
                if (handle != null) {
                    dialIntent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                } else {
                    ActivityLog.add("[HTTP] تعذّر تحديد شريحة $simSlot تلقائياً بثقة - سيُترك الاختيار لنافذة أندرويد الافتراضية")
                }
            }

            context.startActivity(dialIntent)
            ActivityLog.add("تم طلب USSD: $code" + if (simSlot != -1) " (SIM $simSlot)" else "")

            return jsonResponse(Response.Status.OK, JSONObject().put("requestId", requestId))
        }

        private fun handleUssdResponse(uri: String): Response {
            val requestId = uri.substringAfterLast("/")
            if (requestId != UssdSessionState.currentRequestId) {
                return jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "unknown requestId"))
            }
            val json = JSONObject()
                .put("status", UssdSessionState.status)
                .put("message", UssdSessionState.message)
            // 🟢 لا نمسح الحالة هنا فور أول قراءة (كما كان سابقاً). التحرير الفعلي أصبح مسؤولية
            // استدعاء /ussd/dismiss الصريح فقط - هذا يمنع فقدان الرد لو ضاعت استجابة HTTP لهذه
            // القراءة تحديداً في الشبكة (مثلاً انقطاع مؤقت بين adb forward والخادم الخارجي)
            // وأُعيدت محاولة القراءة بنفس requestId بعدها.
            return jsonResponse(Response.Status.OK, json)
        }

       private fun handleUssdDismiss(): Response {
            UssdSessionState.dismissRequested = true

            // 🟢 إجبار خدمة الوصول على تنفيذ أمر الإغلاق فوراً دون انتظار تغير الشاشة. نمرر نتيجة
            // الإغلاق الفعلية (dismissedCleanly) للطرف الخارجي (androidPhoneService.ts) بدل الاكتفاء
            // بـ"ok: true" ثابتة - بلا هذا، كان ذلك الطرف يلجأ دائماً (بلا شرط) لبديل ADB خارجي
            // (ضغط زر الرجوع) حتى في الحالات الناجحة تماماً، وهذا هو ما كان يُغلق تطبيقات/نوافذ
            // أخرى مفتوحة على الهاتف بلا علاقة بجلسة الـ USSD (راجع النقاش السابق).
            // false هنا (بما فيها حالة عدم توفر الخدمة إطلاقاً) تعني: لا يمكن تأكيد الإغلاق من هنا،
            // فمن المعقول أن يلجأ الطرف الخارجي لبديله الاحتياطي الخاص.
            val dismissedCleanly = UssdAccessibilityService.instance?.performPendingActionsDirectly() ?: false

            return jsonResponse(
                Response.Status.OK,
                JSONObject().put("ok", true).put("dismissedCleanly", dismissedCleanly)
            )
        }

        private fun handleSmsList(session: IHTTPSession): Response {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "READ_SMS permission not granted"))
            }
            val limit = (session.parms["limit"]?.toIntOrNull() ?: 10).coerceIn(1, 100)
            val list = JSONArray()
            val uri = Uri.parse("content://sms/inbox")
            val cursor: Cursor? = context.contentResolver.query(
                uri, arrayOf("_id", "address", "body", "date"), null, null, "date DESC LIMIT $limit"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val item = JSONObject()
                        .put("id", it.getInt(0))
                        .put("address", it.getString(1) ?: "")
                        .put("body", it.getString(2) ?: "")
                        .put("date", it.getLong(3).toString())
                    list.put(item)
                }
            }
            return jsonResponse(Response.Status.OK, list)
        }

        private fun handleSmsDelete(): Response {
            // حذف الرسائل يتطلب أن يكون التطبيق هو تطبيق الرسائل الافتراضي على أندرويد.
            // غير مُفعّل عمداً في هذا الإصدار الخفيف - راجع README.
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "sms delete not enabled in this build - see README")
            )
        }

        // =========================================================
        // 🟢 جديد: POST /sms/wait — ينتظر رسالة SMS جديدة عند الطلب الصريح من C#
        // لا يُشغَّل تلقائياً بعد USSD، ولا يحلل نجاح/فشل — يعيد النص الخام فقط.
        // القرار (نجاح/فشل التعبئة) يبقى بالكامل من مسؤولية تطبيق C#.
        // =========================================================
        private fun handleSmsWait(session: IHTTPSession): Response {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                return jsonResponse(
                    Response.Status.OK,
                    JSONObject()
                        .put("status", "failed")
                        .put("body", "READ_SMS permission not granted")
                        .put("sender", "")
                        .put("date", "")
                )
            }

            val files = HashMap<String, String>()
            try {
                session.parseBody(files)
            } catch (e: Exception) {
                // جسم فارغ مقبول (كل القيم افتراضية)
            }
            val body = JSONObject(files["postData"] ?: "{}")
            val senderFilter = body.optString("sender", "").trim()
            val timeoutSeconds = body.optInt("timeout", 50).coerceIn(1, 120)

            val requestReceivedAtMs = System.currentTimeMillis()
            val deadlineMs = requestReceivedAtMs + timeoutSeconds * 1000L
            val pollIntervalMs = 1500L

            ActivityLog.add(
                "[SMS] بدء انتظار رسالة جديدة" +
                    (if (senderFilter.isNotEmpty()) " من: $senderFilter" else "") +
                    " (حتى $timeoutSeconds ث)"
            )

            while (System.currentTimeMillis() < deadlineMs) {
                val newMsg = findNewSmsSince(requestReceivedAtMs, senderFilter)
                if (newMsg != null) {
                    ActivityLog.add("[SMS] وصلت رسالة جديدة من ${newMsg.optString("address")}")
                    return jsonResponse(
                        Response.Status.OK,
                        JSONObject()
                            .put("status", "success")
                            .put("body", newMsg.optString("body"))
                            .put("sender", newMsg.optString("address"))
                            .put("date", newMsg.optString("date"))
                    )
                }
                try {
                    Thread.sleep(pollIntervalMs)
                } catch (e: InterruptedException) {
                    break
                }
            }

            ActivityLog.add("[SMS] انتهت مهلة الانتظار ($timeoutSeconds ث) بدون وصول رسالة جديدة")
            return jsonResponse(
                Response.Status.OK,
                JSONObject().put("status", "timeout").put("body", "").put("sender", "").put("date", "")
            )
        }

        /** يبحث عن أحدث رسالة وصلت بعد sinceMs (بالميلي ثانية)، وتحتوي senderFilter ضمن رقم المرسل إن وُجد. */
        private fun findNewSmsSince(sinceMs: Long, senderFilter: String): JSONObject? {
            val uri = Uri.parse("content://sms/inbox")
            val cursor: Cursor? = context.contentResolver.query(
                uri, arrayOf("_id", "address", "body", "date"), null, null, "date DESC LIMIT 20"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val date = it.getLong(3)
                    if (date <= sinceMs) continue // رسالة قديمة (الترتيب تنازلي، الأحدث أولاً)
                    val address = it.getString(1) ?: ""
                    if (senderFilter.isNotEmpty() && !address.contains(senderFilter, ignoreCase = true)) continue
                    return JSONObject()
                        .put("id", it.getInt(0))
                        .put("address", address)
                        .put("body", it.getString(2) ?: "")
                        .put("date", date.toString())
                }
            }
            return null
        }

        private fun getBatteryLevel(): Int {
            return try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            } catch (e: Exception) {
                -1
            }
        }

        private fun jsonResponse(status: Response.Status, json: Any): Response {
            return newFixedLengthResponse(status, "application/json", json.toString())
        }
    }
}

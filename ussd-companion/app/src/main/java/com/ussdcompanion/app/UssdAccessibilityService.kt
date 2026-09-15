package com.ussdcompanion.app

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * تراقب ظهور نافذة رد الـ USSD النظامية وتتعامل مع الإدخال والإغلاق التلقائي.
 */
class UssdAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile 
        var instance: UssdAccessibilityService? = null

        private val DISMISS_BUTTON_TEXTS = listOf(
            "OK", "Ok", "ok", "موافق", "Cancel", "CANCEL", "إلغاء", "Dismiss", "Close", "إغلاق",
            "ANNULER", "Annuler", "annuler", "Fermer", "fermer"
        )
        private val SEND_BUTTON_TEXTS = listOf(
            "Send", "SEND", "إرسال", "موافق", "OK",
            "ENVOYER", "Envoyer", "envoyer"
        )
        private val STANDARD_DIALOG_BUTTON_IDS = listOf(
            "android:id/button1", "android:id/button2", "android:id/button3"
        )

        // الكلمات المفتاحية لتجاهل نوافذ تحميل الأندرويد المؤقتة
        private val IGNORED_SYSTEM_MESSAGES = listOf(
            "ussd code running",
            "exécution du code ussd",
            "execution du code ussd",
            "رمز ussd قيد التشغيل",
            "يتم تشغيل رمز ussd"
        )

        // ⚠️ نمط تقريبي للتعرف على "إشعار الرصيد سيصل عبر SMS" (استعلام رصيد موبيليس).
        // لم يُتوفّر لدي نص الرسالة الحقيقي بعد - اضبط هذا النمط بدقة بمجرد تنفيذ طلب رصيد فعلي
        // ومراجعة نص الرد الذي سيظهر في سجل الأحداث تحت "رد USSD (نهائي):" قبل هذا التعديل.
        private val BALANCE_VIA_SMS_PATTERN = Regex(
            "(solde|crédit|credit|رصيد).{0,80}(sms)|(sms).{0,80}(solde|crédit|credit|رصيد)",
            RegexOption.IGNORE_CASE
        )
        // استثناء متعمّد: رسالة تأكيد التعبئة "Votre demande est prise en charge, un sms vous sera
        // envoyé" تحتوي أيضاً على كلمة sms، لكنها تخص تدفّق التعبئة (تُعالَج في C# بآلية منفصلة بالفعل)
        // وليست استعلام رصيد - يجب ألا تدخل هذا المسار الجديد إطلاقاً.
        private val RECHARGE_NOTIFICATION_EXCLUSIONS = Regex(
            "recharger|recharge|prise en charge|transaction|transferer|transférer",
            RegexOption.IGNORE_CASE
        )
        // نمط استخراج قيمة الرصيد من نص رسالة الـ SMS الواردة (مبلغ متبوع بـ DA أو دج)
        private val BALANCE_VALUE_PATTERN = Regex("""(\d+[.,]?\d*)\s*(DA|دج)""", RegexOption.IGNORE_CASE)

        private const val BALANCE_SMS_WAIT_MS = 40_000L
        // ⚡ PERF: تم تسريع فحص الرسائل من 2000ms إلى 500ms للاستجابة الفورية
        private const val SMS_POLL_INTERVAL_MS = 500L
        // ⚡ PERF: تقليل مدة الانتظار من 2500ms إلى 1200ms واستخدامه بشكل شرطي
        private const val FINALIZE_SETTLE_MS = 1200L

        // 🛡️ حزمة "android" عامة جداً وتُستخدم أيضاً لنوافذ الأذونات وتنبيهات النظام العادية
        // (غير متعلقة بالـ USSD إطلاقاً). نقبل نوافذ هذه الحزمة فقط إن لم تحتوِ إحدى هذه الكلمات
        // الدالة على نوافذ أذونات/إعدادات شائعة، لتفادي نقر تلقائي غير مقصود على نافذة نظام أخرى.
        private val SYSTEM_DIALOG_EXCLUSION_KEYWORDS = listOf(
            "permission", "allow", "deny", "settings",
            "إذن", "أذونات", "السماح", "رفض", "الإعدادات", "الوصول إلى"
        )

        // 🆕 (أندرويد 13/14 - انتقال لسامسونج One UI): يسجّل آخر مرة سُجّلت فيها كل حزمة غير
        // معروفة بالتشخيص، لمنع إغراق ActivityLog (سقفه 50 سطراً) بنفس الحزمة مراراً خلال جلسة
        // واحدة إن ظهرت نوافذ كثيرة من نفس التطبيق غير المتوقّع.
        private val lastUnmatchedPackageLogTime = ConcurrentHashMap<String, Long>()
        private const val UNMATCHED_PACKAGE_LOG_THROTTLE_MS = 5_000L
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun performPendingActionsDirectly() {
        val root = rootInActiveWindow
        if (root == null) {
            ActivityLog.add("[إدخال] فشل: لا توجد نافذة نشطة (rootInActiveWindow == null)")
            return
        }
        applyPendingActions(root)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        // تجاهل الأحداث الصادرة من تطبيقنا نفسه
        if (event.packageName == packageName) return
        if (UssdSessionState.status == UssdSessionState.STATUS_IDLE) return

        val root = rootInActiveWindow ?: return
        if (root.packageName == packageName) return

        // 🛡️ فلترة الأمان: التأكد من أن النافذة تنتمي للنظام أو تطبيق الاتصال
        val currentPackageName = root.packageName?.toString()?.lowercase() ?: ""
        if (!isTelephonyRelatedWindow(root, currentPackageName)) {
            // 🆕 تشخيص: نسجّل اسم أي حزمة نافذة لم تُطابق أثناء جلسة USSD نشطة (بحد أقصى مرة كل
            // 5 ثوانٍ لكل حزمة)، ليسهل ضبط isTelephonyRelatedWindow بدقة حسب الجهاز الفعلي
            // (خصوصاً هواوي/EMUI وسامسونج/One UI) من أول اختبار حقيقي بدل التخمين.
            logUnmatchedWindowPackageForDiagnostics(currentPackageName)
            return // تجاهل أي نافذة لا تتعلق بالاتصالات
        }

        try {
            handlePossibleUssdDialog(root)
            applyPendingActions(root)
        } catch (e: Exception) {
            ActivityLog.add("خطأ أثناء قراءة نافذة USSD: ${e.message}")
        }
    }

    private fun logUnmatchedWindowPackageForDiagnostics(pkg: String) {
        if (pkg.isEmpty()) return
        val now = System.currentTimeMillis()
        val last = lastUnmatchedPackageLogTime[pkg] ?: 0L
        if (now - last < UNMATCHED_PACKAGE_LOG_THROTTLE_MS) return
        lastUnmatchedPackageLogTime[pkg] = now
        ActivityLog.add("[تشخيص] حزمة نافذة غير معروفة تم تجاهلها أثناء جلسة نشطة: $pkg")
    }

    /**
     * يحدد إن كانت النافذة تتبع فعلاً لتطبيق الهاتف/الاتصالات. الحزم التي تحتوي "telephony" أو
     * "phone" أو "telecom" أو "incallui" أو "dialer" تُقبل مباشرة (التوسعة الأخيرة تغطي حزمة
     * واجهة المكالمة على سامسونج One UI مثل com.samsung.android.incallui، وتطبيقات الاتصال
     * البديلة عند مصنّعين آخرين - كانت النسخة السابقة تقبل فقط "server.telecom" فتفوّت هذه
     * الحالات). أما حزمة "android" العامة فتُقبل فقط إن كان نصها لا يحتوي كلمات دالة على نوافذ
     * أذونات أو إعدادات شائعة - هذه الحزمة تُستخدم لأشياء كثيرة غير متعلقة بالـ USSD إطلاقاً،
     * وقبولها دون تحفظ قد يجعل الخدمة تنقر تلقائياً على زر في نافذة نظام لا علاقة لها بالموضوع.
     */
    private fun isTelephonyRelatedWindow(root: AccessibilityNodeInfo, packageName: String): Boolean {
        val isKnownTelephonyPackage = packageName.contains("telephony") ||
            packageName.contains("phone") ||
            packageName.contains("telecom") ||
            packageName.contains("incallui") ||
            packageName.contains("dialer")
        if (isKnownTelephonyPackage) return true

        if (packageName == "android") {
            val quickText = StringBuilder()
            collectText(root, quickText)
            val lower = quickText.toString().lowercase()
            val looksLikeUnrelatedSystemDialog = SYSTEM_DIALOG_EXCLUSION_KEYWORDS.any { lower.contains(it) }
            return !looksLikeUnrelatedSystemDialog
        }

        return false
    }

    private fun handlePossibleUssdDialog(root: AccessibilityNodeInfo) {
        // أثناء انتظار رسالة الرصيد، الحوار الأصلي مُغلق فعلاً والخيط الخلفي هو من يتحكم بالحالة -
        // يجب ألا تُعاد معالجة أي نافذة أخرى تظهر عرَضياً في هذه الأثناء.
        if (UssdSessionState.status == UssdSessionState.STATUS_COMPLETED ||
            UssdSessionState.status == UssdSessionState.STATUS_WAITING_SMS_BALANCE) return

        val allText = StringBuilder()
        collectText(root, allText)
        val text = allText.toString().trim()
        if (text.isEmpty() || text.length > 2000) return

        // 🟢 فلترة رسائل النظام المؤقتة (التحميل) لتفادي الإنهاء المبكر
        val lowerText = text.lowercase()
        val isSystemMessage = IGNORED_SYSTEM_MESSAGES.any { lowerText.contains(it) }
        val isLoadingProgress = hasProgressIndicator(root)

        if (isSystemMessage || isLoadingProgress) {
            ActivityLog.add("تم تجاهل نافذة تحميل مؤقتة بانتظار الرد الفعلي من الشبكة.")
            return 
        }

        val hasInputField = findEditText(root) != null

        if (hasInputField) {
            // بانتظار إدخال
            if (UssdSessionState.status != UssdSessionState.STATUS_WAITING_USER_INPUT || UssdSessionState.message != text) {
                UssdSessionState.updateStatus(UssdSessionState.STATUS_WAITING_USER_INPUT, text)
                ActivityLog.add("رد USSD (بانتظار إدخال): $text")
            }
            return
        } else {
            val looksLikeBalanceViaSms = BALANCE_VIA_SMS_PATTERN.containsMatchIn(text) &&
                !RECHARGE_NOTIFICATION_EXCLUSIONS.containsMatchIn(text)

            if (looksLikeBalanceViaSms) {
                // إشعار فقط - الرصيد الفعلي سيصل عبر SMS منفصلة، لا نعتبر هذا نهائياً بعد
                UssdSessionState.updateStatus(UssdSessionState.STATUS_WAITING_SMS_BALANCE)
                ActivityLog.add("رد USSD (إشعار رصيد عبر SMS): $text — بانتظار الرسالة حتى ${BALANCE_SMS_WAIT_MS / 1000} ثانية")

                val dismissButton = findDismissButton(root)
                if (dismissButton != null) {
                    val clicked = dismissButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ActivityLog.add(if (clicked) "تم إغلاق حوار الإشعار تلقائياً (بانتظار وصول الرصيد عبر SMS)" else "تعذّر النقر التلقائي على زر إغلاق الإشعار")
                }

                startBalanceSmsWait()
                return
            }

            val dismissButton = findDismissButton(root)
            if (dismissButton != null) {
                // ⚡ PERF: الزر متوفر، لا نطبق أي debounce، إغلاق وتأكيد فوري
                UssdSessionState.updateStatus(UssdSessionState.STATUS_COMPLETED, text)
                ActivityLog.add("رد USSD (نهائي): $text")
                
                val clicked = dismissButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ActivityLog.add(if (clicked) "تم إغلاق حوار USSD تلقائياً" else "تعذّر النقر التلقائي على زر الإغلاق")
            } else {
                // ⚡ PERF: الزر غير متوفر، ننتظر FINALIZE_SETTLE_MS قبل التأكيد لتجنب مشاكل التحميل
                ActivityLog.add("رد USSD (نهائي) - بانتظار $FINALIZE_SETTLE_MS ms لتأكيد النافذة")
                Thread.sleep(FINALIZE_SETTLE_MS)
                
                // إعادة قراءة الشاشة بعد الانتظار
                val finalRoot = rootInActiveWindow ?: root
                val finalDismissButton = findDismissButton(finalRoot)
                val finalTextSb = java.lang.StringBuilder()
                collectText(finalRoot, finalTextSb)
                val finalText = finalTextSb.toString().trim()
                val resultText = if (finalText.isNotEmpty()) finalText else text
                
                UssdSessionState.updateStatus(UssdSessionState.STATUS_COMPLETED, resultText)
                ActivityLog.add("رد USSD (نهائي بعد الانتظار): $resultText")
                
                if (finalDismissButton != null) {
                    val clicked = finalDismissButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ActivityLog.add(if (clicked) "تم إغلاق حوار USSD تلقائياً بعد الانتظار" else "تعذّر النقر التلقائي على زر الإغلاق")
                } else {
                    ActivityLog.add("تم استخراج الرد؛ بانتظار توفر زر إغلاق مناسب")
                }
            }
        }
    }

    /**
     * يبدأ انتظاراً في خيط خلفي لرسالة SMS جديدة تحمل قيمة الرصيد (بعد إشعار "الرصيد سيصل عبر SMS")،
     * لمدة أقصاها BALANCE_SMS_WAIT_MS. عند الوصول أو انتهاء المهلة، يضع الحالة النهائية COMPLETED
     * برسالة تحتوي على القيمة المستخرَجة (أو رسالة توضح عدم الوصول).
     */
    private fun startBalanceSmsWait() {
        val waitStartMs = System.currentTimeMillis()
        val requestIdAtStart = UssdSessionState.currentRequestId

        Thread {
            try {
                val deadline = waitStartMs + BALANCE_SMS_WAIT_MS
                while (System.currentTimeMillis() < deadline) {
                    // إن انتهت الجلسة أو بدأت جلسة أخرى أثناء انتظارنا (مثلاً طلب إلغاء من C#)، توقف فوراً
                    if (UssdSessionState.currentRequestId != requestIdAtStart) return@Thread

                    val newSmsBody = findNewSmsSince(waitStartMs)
                    if (newSmsBody != null) {
                        val balance = extractBalanceValue(newSmsBody)
                        val finalMessage = if (balance != null)
                            "الرصيد: $balance (نص رسالة موبيليس: $newSmsBody)"
                        else
                            "وصلت رسالة SMS لكن تعذّر استخراج قيمة الرصيد منها تلقائياً - النص الكامل: $newSmsBody"

                        ActivityLog.add("رد USSD (نهائي - رصيد عبر SMS): $finalMessage")
                        UssdSessionState.updateStatus(UssdSessionState.STATUS_COMPLETED, finalMessage)
                        return@Thread
                    }

                    Thread.sleep(SMS_POLL_INTERVAL_MS)
                }

                if (UssdSessionState.currentRequestId != requestIdAtStart) return@Thread

                val timeoutMessage = "لم تصل رسالة الرصيد عبر SMS خلال ${BALANCE_SMS_WAIT_MS / 1000} ثانية."
                ActivityLog.add("رد USSD (نهائي - مهلة انتظار الرصيد): $timeoutMessage")
                UssdSessionState.updateStatus(UssdSessionState.STATUS_COMPLETED, timeoutMessage)
            } catch (e: Exception) {
                ActivityLog.add("خطأ أثناء انتظار رسالة الرصيد: ${e.message}")
                if (UssdSessionState.currentRequestId == requestIdAtStart) {
                    UssdSessionState.updateStatus(UssdSessionState.STATUS_COMPLETED, "خطأ أثناء انتظار رسالة الرصيد: ${e.message}")
                }
            }
        }.start()
    }

    /**
     * يُرجع نص أحدث رسالة SMS في الوارد إن كان تاريخها بعد sinceMs **وتحتوي فعلاً على نمط قيمة رصيد**
     * (مبلغ متبوع بـ DA/دج)، أو null إن لم توجد رسالة كهذه بعد. لا نكتفي بكون الرسالة "الأحدث" لأن أي
     * رسالة أخرى غير متعلقة (إشعار تطبيق، رسالة عادية) قد تصل خلال نافذة الانتظار قبل رسالة الرصيد
     * الفعلية؛ رسالة كهذه ستُتجاهل هنا وتستمر الحلقة بالانتظار حتى تصل رسالة تطابق النمط فعلاً أو تنتهي المهلة.
     */
    private fun findNewSmsSince(sinceMs: Long): String? {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val uri = Uri.parse("content://sms/inbox")
            contentResolver.query(uri, arrayOf("body", "date"), null, null, "date DESC LIMIT 1")?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val date = cursor.getLong(1)
                    val body = cursor.getString(0)
                    if (date > sinceMs && body != null && BALANCE_VALUE_PATTERN.containsMatchIn(body)) body else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractBalanceValue(smsBody: String): String? {
        val match = BALANCE_VALUE_PATTERN.find(smsBody) ?: return null
        return "${match.groupValues[1]} ${match.groupValues[2].uppercase()}"
    }

    private fun applyPendingActions(root: AccessibilityNodeInfo) {
        val toSend = UssdSessionState.pendingInputToSend.getAndSet(null)
        if (toSend != null) {
            val field = findEditText(root)
            val sendBtn = findButtonByText(root, SEND_BUTTON_TEXTS)
            
            if (field != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, toSend)
                }
                
                val setTextSuccess = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                ActivityLog.add("[إدخال] نتيجة كتابة النص ('$toSend'): $setTextSuccess")

                if (setTextSuccess && sendBtn != null) {
                    val clickSuccess = sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ActivityLog.add("[إدخال] نتيجة الضغط على زر الإرسال: $clickSuccess")

                    if (clickSuccess) {
                        // updateStatus تُعيد جدولة مهلة PENDING تلقائياً، فلو لم يصل أي رد بعد إرسال
                        // هذا الإدخال أيضاً، لن تبقى الجلسة عالقة إلى الأبد.
                        UssdSessionState.updateStatus(UssdSessionState.STATUS_PENDING)
                    }
                } else if (sendBtn == null) {
                    ActivityLog.add("[إدخال] فشل: لم يُعثر على زر الإرسال (ENVOYER)")
                }
            } else {
                ActivityLog.add("[إدخال] فشل: لم يُعثر على حقل الإدخال (EditText)")
            }
        }

        if (UssdSessionState.dismissRequested) {
            val closeBtn = findDismissButton(root)
            val clicked = closeBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            ActivityLog.add(if (clicked) "تم إغلاق حوار USSD بطلب من البرنامج" else "طلب إغلاق لكن لم يُعثر على زر مناسب")
            UssdSessionState.reset()
        }
    }

    // 🟢 التحقق المادي من وجود مؤشر تحميل (ProgressBar)
    private fun hasProgressIndicator(node: AccessibilityNodeInfo): Boolean {
        if (node.className == "android.widget.ProgressBar") return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasProgressIndicator(child)) return true
        }
        return false
    }

    private fun findDismissButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (viewId in STANDARD_DIALOG_BUTTON_IDS) {
            val found = try {
                root.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull { it.isEnabled }
            } catch (e: Exception) {
                null
            }
            if (found != null) return found
        }

        findButtonByText(root, DISMISS_BUTTON_TEXTS)?.let { return it }

        val buttons = mutableListOf<AccessibilityNodeInfo>()
        collectButtons(root, buttons)
        if (buttons.size == 1) return buttons[0]

        return null
    }

    private fun collectButtons(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.className == "android.widget.Button") out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectButtons(child, out)
        }
    }

    private fun collectText(node: AccessibilityNodeInfo, out: StringBuilder) {
        node.text?.let { if (it.isNotBlank()) out.append(it).append(" ") }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out)
        }
    }

    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className == "android.widget.EditText") return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditText(child)
            if (found != null) return found
        }
        return null
    }

    private fun findButtonByText(node: AccessibilityNodeInfo, options: List<String>): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString()
        if (nodeText != null && options.any { nodeText.equals(it, ignoreCase = true) }) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findButtonByText(child, options)
            if (found != null) return found
        }
        return null
    }

    override fun onInterrupt() {
        ActivityLog.add("تم إيقاف خدمة الوصول")
    }
}

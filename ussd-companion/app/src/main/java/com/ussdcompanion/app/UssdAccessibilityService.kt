package com.ussdcompanion.app

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
            "يتم تشغيل رمز ussd",
            // 🆕 رسائل شبكة عابرة حقيقية (مشاكل اتصال مؤقتة تُعقَب عادة بمحاولة تلقائية من الشبكة
            // نفسها خلال ثوانٍ) - بلا أي نقر أو إغلاق تلقائي، فقط تُسجَّل ويُستمر بالانتظار (تماماً
            // كنوافذ التحميل)، ومهلة PENDING الحالية (30 ثانية) تبقى صمام الأمان النهائي إن لم يصل
            // أي رد فعلي بعدها. ⚠️ هذه منفصلة تماماً عن مشكلة "لا يوجد عرض لهذا الرقم" (تلك تُعالَج
            // عبر FINALIZE_SETTLE_MS أدناه لأنها نص نهائي المظهر وليست رسالة خطأ معروفة).
            "problème de connexion",
            "probleme de connexion",
            "code ihm non valide",
            "code ihm",
            "invalid mmi",
            "connection problem",
            "مشكلة في الاتصال",
            "رمز mmi"
        )

        // ⚠️ نمط التعرف على "إشعار الرصيد سيصل عبر SMS" (مثل بعض حالات استعلام رصيد موبيليس)
        private val BALANCE_VIA_SMS_PATTERN = Regex(
            """(?:(?:par|via|dans\s*un|recevrez\s*un|envoy[eé]\s*par|عبر|في)\s*sms)|(?:sms\s*(?:vous\s*sera|envoy[eé]|يصلك|ستصلك))|(?:(?:solde|cr[ée]dit|رصيد).{0,50}(?:par\s*sms|via\s*sms|عبر\s*sms|عبر\s*رسالة))""",
            RegexOption.IGNORE_CASE
        )
        // استثناء متعمّد: رسائل التعبئة أو عروض المكالمات/الرسائل (مثل VOICE/SMS)
        private val RECHARGE_NOTIFICATION_EXCLUSIONS = Regex(
            "recharger|recharge|prise en charge|transaction|transferer|transférer|voice/sms|voice / sms",
            RegexOption.IGNORE_CASE
        )
        // نمط استخراج قيمة الرصيد من نص رسالة الـ SMS أو رد الـ USSD (مبلغ متبوع بـ DA أو دج)
        private val BALANCE_VALUE_PATTERN = Regex("""(\d+[.,]?\d*)\s*(DA|دج)""", RegexOption.IGNORE_CASE)

        private const val BALANCE_SMS_WAIT_MS = 40_000L
        private const val SMS_POLL_INTERVAL_MS = 2_000L

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

        // 🆕 إصلاح: مهلة استقرار قبل اعتماد أي نص كـ"رد USSD نهائي" فعلي. لوحظ ميدانياً (سجل
        // مستخدم، عملية "جلب العروض") أن بعض تدفقات الشبكة تُظهر نصاً أولياً فورياً بلا أي مؤشر
        // تحميل (مثل "لا يوجد عرض لهذا الرقم...")، ثم تستبدله فعلياً بعد ~1-2 ثانية بالرد الحقيقي
        // عبر تحديث محتوى نفس النافذة (TYPE_WINDOW_CONTENT_CHANGED). الاعتماد الفوري على أول نص
        // بلا مؤشر تحميل كان يجعل البرنامج (C#) يقرأ النص الأولي الخاطئ ويُنهي الجلسة (بما فيه
        // استدعاء /ussd/dismiss) قبل وصول الرد الحقيقي، فتظهر النتيجة الفعلية على شاشة الهاتف فقط
        // دون أي تسليم لها - راجع handleCandidateFinalText لتفاصيل الآلية. القيمة هنا فيها هامش
        // أمان فوق الـ2 ثانية المُلاحَظة ميدانياً؛ عدّلوها لاحقاً حسب اختبارات ميدانية إضافية إن
        // ظهرت حالات أبطأ من هذا (مثلاً شبكة أضعف).
        const val FINALIZE_SETTLE_MS = 1000L
    }

    // حالة "المرشّح النهائي" الحالي بانتظار الاستقرار - راجع handleCandidateFinalText
    @Volatile private var candidateFinalText: String? = null
    @Volatile private var candidateFinalRequestId: String? = null
    @Volatile private var candidateFinalFirstSeenAt: Long = 0L
    private val settleHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        settleHandler.removeCallbacksAndMessages(null)
        instance = null
        super.onDestroy()
    }

    /**
     * @return true إن لم يكن هناك طلب إغلاق معلّق أصلاً، أو تم إغلاق الحوار فعلاً (بنقر ناجح أو
     * لأنه لم يعد ظاهراً أصلاً - أي أُغلق مسبقاً). false فقط حين طُلب الإغلاق صراحة ولا يزال هناك
     * حوار USSD/اتصال ظاهر فعلياً على الشاشة ولم يُعثر على أي زر مناسب فيه - هذه هي الحالة الوحيدة
     * التي يستحق فيها المتصل (HttpServerService → androidPhoneService.ts) اللجوء لبديل خارجي
     * (كضغط زر الرجوع عبر ADB). بلا هذا التمييز، أي طلب /ussd/dismiss يصل بعد إغلاق ناجح تلقائي
     * (عبر finalizeUssdResponse مثلاً) سيُقرأ خطأً كـ"فشل" لمجرد عدم وجود أي زر ليُنقر عليه بعد
     * الآن - رغم أن الجلسة أُغلقت بنجاح فعلاً - فيُفعَّل بديل ADB بلا داعٍ في كل عملية ناجحة.
     */
    fun performPendingActionsDirectly(): Boolean {
        val root = rootInActiveWindow
        if (root == null) {
            ActivityLog.add("[إدخال] فشل: لا توجد نافذة نشطة (rootInActiveWindow == null)")
            // لا يمكن التأكد من أي شيء بلا نافذة نشطة - نُبقي الاحتمال مفتوحاً لبديل خارجي فقط
            // إن كان هناك فعلاً طلب إغلاق معلّق (وإلا لا داعي لإخبار المتصل بأي "فشل").
            return !UssdSessionState.dismissRequested
        }
        return applyPendingActions(root)
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
            if (isSystemMessage) {
                val dismissBtn = findDismissButton(root)
                if (dismissBtn != null) {
                    val clicked = dismissBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) ActivityLog.add("تم إغلاق رسالة النظام العابرة تلقائياً")
                }
            }
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
            // 🟢 إذا كان النص يحتوي بالفعل على رقم الرصيد بالدينار (مثل "Crédit : 78.08DA" أو "500 DA" أو "رصيد: 100 دج")،
            // فهذا رد نهائي مباشر ويجب ألا يُحوّل لانتظار SMS إطلاقاً!
            val hasDirectBalanceValue = BALANCE_VALUE_PATTERN.containsMatchIn(text) ||
                Regex("""(?:cr[ée]dit|solde|رصيد)[^\d]*?\d+""", RegexOption.IGNORE_CASE).containsMatchIn(text)

            val looksLikeBalanceViaSms = !hasDirectBalanceValue &&
                BALANCE_VIA_SMS_PATTERN.containsMatchIn(text) &&
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

            // 🆕 مرشّح نهائي فقط - لا نعتمده فوراً ولا نُغلق النافذة الآن. راجع التوثيق أعلى
            // handleCandidateFinalText: قد يكون هذا النص أولياً مؤقتاً سيُستبدل خلال لحظات
            // بالرد الحقيقي (لوحظ ميدانياً في تدفق "جلب العروض").
            handleCandidateFinalText(text)
        }
    }

    // =========================================================
    // 🆕 إصلاح: إنهاء متسرّع لجلسة USSD قبل استقرار النص الحقيقي
    // =========================================================
    // خلفية المشكلة (من سجل أحداث مستخدم فعلي، عملية "جلب العروض"): فور إرسال كود USSD، ظهرت
    // نافذة النظام بنص فوري ("لا يوجد عرض لهذا الرقم...") بلا أي ProgressBar وبلا أي عبارة من
    // IGNORED_SYSTEM_MESSAGES، فاعتمده المنطق القديم فوراً كرد "نهائي" وأغلق النافذة. لكن بعد
    // نحو 1-2 ثانية، استُبدل هذا النص فعلياً بالعروض الحقيقية عبر تحديث محتوى النافذة. بما أن
    // الجلسة كانت قد أُنهيت (وربما استُدعي /ussd/dismiss/ من C# بعدها فوراً)، فإن
    // onAccessibilityEvent يتجاهل ذلك التحديث اللاحق تماماً (`if (status == IDLE) return`)،
    // فتظهر العروض الحقيقية على شاشة الهاتف فقط دون أي وصول للبرنامج.
    //
    // الحل: لا يُعتمَد أي نص كـ"نهائي" فور ظهوره، بل يُسجَّل كـ"مرشّح" وتُمنح له مهلة استقرار
    // (FINALIZE_SETTLE_MS) دون أي إنهاء للجلسة أو نقر تلقائي على زر الإغلاق خلالها:
    //   - إن وصل نص مختلف قبل انتهاء المهلة (تحديث حقيقي جديد)، يُعاد ضبط المهلة على النص
    //     الجديد فوراً ولا شيء يُفقَد.
    //   - فقط إن بقي نفس النص مستقراً طوال المهلة كاملة، يُعتمَد كرد نهائي فعلي وتُغلق النافذة.
    // الأثر الجانبي المقصود: كل رد USSD يتأخر تسليمه بمقدار المهلة تقريباً - تكلفة مقبولة مقابل
    // تفادي فقدان الرد الحقيقي بالكامل.
    private fun handleCandidateFinalText(text: String) {
        val requestId = UssdSessionState.currentRequestId
        val now = System.currentTimeMillis()

        if (text != candidateFinalText || requestId != candidateFinalRequestId) {
            candidateFinalText = text
            candidateFinalRequestId = requestId
            candidateFinalFirstSeenAt = now
            ActivityLog.add("رد USSD (مرشّح - بانتظار استقرار ${FINALIZE_SETTLE_MS / 1000.0} ث): $text")
            settleHandler.removeCallbacksAndMessages(null)
            settleHandler.postDelayed({ recheckCandidateFinal(text, requestId) }, FINALIZE_SETTLE_MS)
            return
        }

        // نفس النص وصل مجدداً لنفس الجلسة، والمهلة انقضت فعلاً (مثلاً وصل حدث إضافي بعد انتهاء
        // المهلة المجدوَلة أصلاً) - اعتمده نهائياً الآن دون انتظار postDelayed لا لزوم له.
        if (now - candidateFinalFirstSeenAt >= FINALIZE_SETTLE_MS) {
            finalizeUssdResponse(text)
        }
    }

    /** يُنفَّذ بعد FINALIZE_SETTLE_MS من ظهور نص "مرشّح" لأول مرة، للتحقق من استقراره فعلاً قبل اعتماده. */
    private fun recheckCandidateFinal(expectedText: String, expectedRequestId: String?) {
        if (UssdSessionState.currentRequestId != expectedRequestId) return
        // 🆕 إن انتقلت الجلسة فعلاً لانتظار إدخال حقيقي (مثلاً قائمة عروض وصلت بعد النص الوسيط
        // الخاطئ - راجع الحالة الموثّقة ميدانياً: نص "لا يوجد عرض" يليه حوار عروض حقيقي بحقل
        // إدخال)، يجب عدم الكتابة فوقها بالمرشّح القديم المنتهي الصلاحية إطلاقاً.
        if (UssdSessionState.status == UssdSessionState.STATUS_COMPLETED ||
            UssdSessionState.status == UssdSessionState.STATUS_WAITING_SMS_BALANCE ||
            UssdSessionState.status == UssdSessionState.STATUS_WAITING_USER_INPUT ||
            UssdSessionState.status == UssdSessionState.STATUS_IDLE
        ) return
        // المرشّح تغيّر بالفعل (وصل تحديث حقيقي جديد استبدله) - له مهلته الخاصة المجدوَلة من
        // handleCandidateFinalText أصلاً؛ لا شيء نفعله هنا.
        if (candidateFinalText != expectedText || candidateFinalRequestId != expectedRequestId) return

        val root = rootInActiveWindow ?: return
        val currentText = StringBuilder().also { collectText(root, it) }.toString().trim()
        if (currentText == expectedText) {
            finalizeUssdResponse(expectedText)
        }
        // إن اختلف النص الحالي عن المتوقَّع، فهذا يعني أن حدث تغيّر محتوى وصل فعلاً وأعاد ضبط
        // مرشّح جديد من مساره الطبيعي في onAccessibilityEvent - لا حاجة لفعل شيء هنا.
    }

    private fun finalizeUssdResponse(text: String) {
        candidateFinalText = null
        candidateFinalRequestId = null

        UssdSessionState.updateStatus(UssdSessionState.STATUS_COMPLETED, text)
        ActivityLog.add("رد USSD (نهائي): $text")

        val root = rootInActiveWindow
        val dismissButton = root?.let { findDismissButton(it) }
        if (dismissButton != null) {
            val clicked = dismissButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ActivityLog.add(if (clicked) "تم إغلاق حوار USSD تلقائياً" else "تعذّر النقر التلقائي على زر الإغلاق")
        } else {
            ActivityLog.add("تم استخراج الرد؛ بانتظار توفر زر إغلاق مناسب")
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

    /** @return انظر توثيق [performPendingActionsDirectly] لمعنى القيمة المُعادة بدقة. */
    private fun applyPendingActions(root: AccessibilityNodeInfo): Boolean {
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

        if (!UssdSessionState.dismissRequested) return true

        // 🆕 التمييز الحاسم: هل لا تزال هناك نافذة USSD/اتصال ظاهرة فعلياً تستحق إغلاقاً، أم أن
        // الشاشة انتقلت أصلاً لشيء آخر (غالباً لأن الحوار أُغلق بنجاح مسبقاً عبر finalizeUssdResponse
        // التلقائي)؟ إن لم يعد هناك حوار من هذا النوع أصلاً، هذا "نجاح" وليس "فشل" - رغم عدم وجود
        // أي زر لنقره الآن - فلا يجدر إخبار المتصل (TS/C#) بفشل يدفعه للجوء لبديل ADB بلا داعٍ.
        val stillOnTelephonyDialog = isTelephonyRelatedWindow(root, root.packageName?.toString()?.lowercase() ?: "")
        val dismissedSuccessfully = if (!stillOnTelephonyDialog) {
            ActivityLog.add("طلب إغلاق: لا يوجد حوار USSD ظاهر حالياً - اعتُبر مُغلقاً بالفعل")
            true
        } else {
            val closeBtn = findDismissButton(root)
            val clicked = closeBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            ActivityLog.add(if (clicked) "تم إغلاق حوار USSD بطلب من البرنامج" else "طلب إغلاق لكن لم يُعثر على زر مناسب")
            clicked
        }
        UssdSessionState.reset()
        return dismissedSuccessfully
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

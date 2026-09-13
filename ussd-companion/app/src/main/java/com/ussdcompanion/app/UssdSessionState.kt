package com.ussdcompanion.app

import java.util.concurrent.atomic.AtomicReference

/**
 * حالة مشتركة بين UssdAccessibilityService (تقرأ نافذة USSD وتكتب هنا)
 * وHttpServerService (تقرأ من هنا لترد على /ussd/response، وتكتب هنا لتمرير إدخال جديد).
 */
object UssdSessionState {

    const val STATUS_IDLE = "IDLE"
    const val STATUS_PENDING = "PENDING"
    const val STATUS_WAITING_USER_INPUT = "WAITING_USER_INPUT"
    const val STATUS_COMPLETED = "COMPLETED"
    // حالة وسيطة: رد الـ USSD وصل وكان مجرد إشعار بأن الرصيد سيصل عبر SMS منفصلة - الجلسة تبقى
    // نشطة (غير IDLE) بانتظار تلك الرسالة. لا تُطابق شرط C# الخاص بـ COMPLETED/WAITING_USER_INPUT
    // عمداً، فيستمر برنامج السي شارب بالاستقصاء بصبر دون إرسال أي إدخال إضافي عبثاً.
    const val STATUS_WAITING_SMS_BALANCE = "WAITING_SMS_BALANCE"

    // مهلة أمان لحالة PENDING: إن بقيت الجلسة في PENDING أكثر من هذه المدة (مثلاً لم تظهر نافذة USSD
    // إطلاقاً بسبب انقطاع الشبكة أو فشل الاتصال)، تُنهى الجلسة تلقائياً بدل أن تحجب الجهاز للأبد.
    // تُعاد جدولتها في كل مرة تدخل فيها الحالة PENDING من جديد (بما في ذلك بعد إرسال إدخال متابعة).
    const val PENDING_TIMEOUT_MS = 30_000L
    private const val PENDING_TIMEOUT_SEC = PENDING_TIMEOUT_MS / 1000

    // 🆕 مهلة أمان لحالة WAITING_USER_INPUT: بعض التدفقات (مثل "جلب العروض") تكتفي بقراءة هذه
    // الحالة (نص العروض) دون استكمال الإدخال فعلياً - المفروض حينها أن يستدعي الطرف الخارجي
    // (androidPhoneService.ts) /ussd/dismiss فور الانتهاء من القراءة. لكن إن نسي ذلك لأي سبب
    // (خطأ برمجي، توقف مفاجئ، انقطاع شبكة بين الخادم والهاتف...)، تبقى الجلسة عالقة إلى الأبد
    // (لا توجد أي مهلة أخرى تراقب هذه الحالة تحديداً)، فيُرفض أي طلب USSD جديد بـ"device busy"
    // للأبد حتى يُعاد تشغيل الخدمة يدوياً. هذه المهلة أطول من PENDING عمداً لأنها تنتظر قراراً
    // خارجياً (وربما إدخالاً بشرياً فعلياً في تدفقات أخرى)، لا مجرد رد شبكة.
    const val WAITING_INPUT_TIMEOUT_MS = 60_000L
    private const val WAITING_INPUT_TIMEOUT_SEC = WAITING_INPUT_TIMEOUT_MS / 1000

    @Volatile var currentRequestId: String? = null
    @Volatile var status: String = STATUS_IDLE
        private set
    @Volatile var message: String = ""
        private set

    val pendingInputToSend = AtomicReference<String?>(null)
    @Volatile var dismissRequested: Boolean = false

    /**
     * نقطة التحديث الوحيدة لـ status/message معاً بشكل ذري، لتفادي أن يرى قارئ من خيط آخر
     * (خيط خادم HTTP) حالة جديدة مع رسالة قديمة أو العكس. أي كود يريد تغيير الحالة يجب أن
     * يمر من هنا بدل الكتابة المباشرة على الحقول.
     */
    @Synchronized
    fun updateStatus(newStatus: String, newMessage: String = message) {
        status = newStatus
        message = newMessage
        if (newStatus == STATUS_PENDING) {
            armPendingTimeoutWatchdog(currentRequestId)
        } else if (newStatus == STATUS_WAITING_USER_INPUT) {
            armWaitingInputTimeoutWatchdog(currentRequestId)
        }
    }

    @Synchronized
    fun startNewSession(requestId: String) {
        currentRequestId = requestId
        message = ""
        pendingInputToSend.set(null)
        dismissRequested = false
        updateStatus(STATUS_PENDING)
    }

    @Synchronized
    fun reset() {
        currentRequestId = null
        status = STATUS_IDLE
        message = ""
        pendingInputToSend.set(null)
        dismissRequested = false
    }

    /**
     * يراقب في خيط خلفي أن لا تبقى الجلسة عالقة في PENDING (مثلاً لأن نافذة USSD لم تظهر إطلاقاً
     * بسبب تعذّر الاتصال). عند انتهاء المهلة دون أي تقدّم لنفس requestId، تُنهى الجلسة تلقائياً
     * بحالة COMPLETED ورسالة توضيحية، بدل أن تبقى تحجب أي طلب USSD جديد إلى الأبد.
     */
    private fun armPendingTimeoutWatchdog(requestId: String?) {
        if (requestId == null) return
        Thread {
            try {
                Thread.sleep(PENDING_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                return@Thread
            }
            // لا شيء نفعله إن بدأت جلسة أخرى، أو إن تقدّمت هذه الجلسة فعلاً لحالة أخرى
            if (currentRequestId == requestId && status == STATUS_PENDING) {
                ActivityLog.add("انتهت مهلة انتظار رد USSD ($PENDING_TIMEOUT_SEC ث) دون ظهور أي نافذة - إنهاء الجلسة تلقائياً")
                updateStatus(
                    STATUS_COMPLETED,
                    "لم يظهر أي رد USSD خلال $PENDING_TIMEOUT_SEC ثانية. تحقق من تغطية الشبكة أو حاول مجدداً."
                )
            }
        }.start()
    }

    /**
     * يراقب في خيط خلفي أن لا تبقى الجلسة عالقة في WAITING_USER_INPUT إلى الأبد. بعض التدفقات
     * (مثل "جلب العروض") تكتفي بقراءة هذه الحالة دون استكمال إدخال فعلي، والمفروض أن يستدعي
     * الطرف الخارجي /ussd/dismiss فور الانتهاء من القراءة. إن نُسي ذلك لأي سبب، تنتهي المهلة هنا
     * فتُغلق أي حوار ظاهر فعلياً عبر خدمة الوصول (بنفس آلية /ussd/dismiss تماماً) وتُحرر الجهاز -
     * بدل أن يبقى مرفوضاً بـ"device busy" لأي طلب USSD جديد إلى الأبد.
     */
    private fun armWaitingInputTimeoutWatchdog(requestId: String?) {
        if (requestId == null) return
        Thread {
            try {
                Thread.sleep(WAITING_INPUT_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                return@Thread
            }
            if (currentRequestId == requestId && status == STATUS_WAITING_USER_INPUT) {
                ActivityLog.add(
                    "انتهت مهلة انتظار اختيار/إغلاق من الطرف الخارجي ($WAITING_INPUT_TIMEOUT_SEC ث) " +
                        "دون أي إدخال أو استدعاء /ussd/dismiss - إغلاق الحوار وتحرير الجهاز تلقائياً"
                )
                dismissRequested = true
                UssdAccessibilityService.instance?.performPendingActionsDirectly()
                // 🆕 نضمن التحرير دائماً هنا بغض النظر عن نجاح العثور على زر إغلاق مرئي من عدمه -
                // هذه المهلة هي خط الدفاع الأخير، وأولويتها القصوى ألا يبقى الجهاز "مشغولاً" للأبد
                // (مرفوضاً بـ"device busy" لأي طلب USSD جديد)، لا تتبع دقة الإغلاق المرئي فعلياً.
                if (status == STATUS_WAITING_USER_INPUT) {
                    reset()
                }
            }
        }.start()
    }
}

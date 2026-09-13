package com.ussdcompanion.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

object SimSelector {
    /**
     * 🛠️ خلفية هذا الإصلاح: كان هناك تعارض بين تعليق في HttpServerService.kt يوثّق أن التخمين
     * الترتيبي معطّل افتراضياً أمنياً (allowOrdinalFallback=false)، وبين الكود الفعلي (النسخة
     * الأولى) الذي كان يطبّقه دائماً دون شرط. أُصلح ذلك أولاً، ثم تبيّن ميدانياً (تقرير المستخدم)
     * أن التحديد التلقائي كان يعمل على أندرويد 7/8 القديمة ويتوقف على الأجهزة الحديثة، لأن
     * الشركة المصنّعة/ROM هي من تُغيّر تنسيق PhoneAccountHandle.id (وليس رقم إصدار أندرويد بحد
     * ذاته - بعض الأجهزة الحديثة القريبة من AOSP القياسي قد تُبقي id == subId البسيط، فالتحقق
     * مطلوب لكل طراز/ROM على حدة، لا افتراض عام حسب رقم الإصدار).
     *
     * الحل المعتمد الآن (3 مراحل بثقة متدرجة، كل مرحلة تُسجَّل بوضوح حسب درجة يقينها):
     *   1) مطابقة دقيقة مؤكدة (subId/iccId) - تُسجَّل بلا أي تحذير.
     *   2) مطابقة تخمينية بأنماط نصية شائعة عند بعض المصنّعين (slot_N/simN/...) - تُسجَّل بكلمة
     *      "تنبيه" صراحة لأنها غير مؤكدة على جهازكم بعد، وslotIndex رقم صغير (0 أو 1) فاحتمال
     *      تطابقه عرَضياً بجزء غير متعلق بالشريحة إطلاقاً أعلى من تطابق subId شبه الفريد.
     *   3) تخمين ترتيبي (فقط إن فُعّل allowOrdinalFallback صراحة) - يُسجَّل بكلمة "تنبيه" أيضاً.
     * بهذا يصبح أي سطر في ActivityLog يبدأ بـ"تنبيه" إشارة واضحة لمراجعة يدوية قبل الاعتماد
     * الكامل، وأي سطر بلا "تنبيه" يعني ثقة عالية فعلاً - وهذا ما يحتاجه اختبار المعايرة المستقل.
     *
     * ⚠️ التوقيع يبقى آمناً افتراضياً (allowOrdinalFallback = false) لأي استدعاء مستقبلي؛ التفعيل
     * الصريح لـ true محصور فقط في نقطة الاستخدام الفعلي داخل HttpServerService.kt.
     */
    @SuppressLint("MissingPermission")
    fun resolvePhoneAccountForSlot(
        context: Context,
        slotIndex: Int,
        allowOrdinalFallback: Boolean = false
    ): PhoneAccountHandle? {
        val hasPhoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasPhoneNumbers = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
        } else true

        if (!hasPhoneState || !hasPhoneNumbers) {
            ActivityLog.add("تعذّر تحديد الشريحة $slotIndex: يلزم منح إذن READ_PHONE_NUMBERS و READ_PHONE_STATE")
            return null
        }
        return try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val activeList = subscriptionManager.activeSubscriptionInfoList ?: run {
                ActivityLog.add("لا توجد شرائح نشطة في الجهاز")
                return null
            }

            val subscription = activeList.firstOrNull { it.simSlotIndex == slotIndex }
            if (subscription == null) {
                ActivityLog.add("لا توجد شريحة نشطة في الفتحة $slotIndex")
                return null
            }

            val subId = subscription.subscriptionId
            val iccId = subscription.iccId ?: ""
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val phoneAccounts = telecomManager.callCapablePhoneAccounts ?: emptyList()

            // المرحلة 1: مطابقة دقيقة مؤكدة (subId/iccId) - هذه الأنماط شبه فريدة على الجهاز
            var matchedHandle: PhoneAccountHandle? = phoneAccounts.firstOrNull { handle ->
                val handleId = handle.id
                handleId == subId.toString() ||
                (iccId.isNotEmpty() && handleId.contains(iccId)) ||
                handleId.endsWith("_$subId") ||
                handleId.endsWith(":$subId")
            }

            // تشخيص: إن فشلت المطابقة الدقيقة، نسجّل كل معرّفات الحسابات المتاحة فعلياً على هذا
            // الجهاز، لمعرفة التنسيق الحقيقي من أول اختبار بدل تخمين أنماط جديدة في كل مرة.
            if (matchedHandle == null) {
                val availableIds = phoneAccounts.joinToString(", ") { "'${it.id}'" }
                ActivityLog.add("[تشخيص SIM] لم يُعثر على مطابقة دقيقة للفتحة $slotIndex (SubId: $subId, ICCID: $iccId). المعرفات المتاحة: [$availableIds]")
            }

            // المرحلة 2: مطابقة تخمينية بأنماط شائعة عند بعض المصنّعين - غير مؤكدة على جهازكم
            // بعد، لذا تُسجَّل بتحذير صريح ("تنبيه") ولا تُعامَل كمطابقة دقيقة إطلاقاً.
            if (matchedHandle == null) {
                val heuristicMatch = phoneAccounts.firstOrNull { handle ->
                    val id = handle.id
                    id.endsWith("_$slotIndex") ||
                    id.contains("slot_$slotIndex", ignoreCase = true) ||
                    id.contains("sim$slotIndex", ignoreCase = true)
                }
                if (heuristicMatch != null) {
                    matchedHandle = heuristicMatch
                    ActivityLog.add(
                        "تنبيه: مطابقة تخمينية (نمط شائع، غير مؤكدة) للفتحة $slotIndex ← " +
                            "'${heuristicMatch.id}' - يُنصح بتأكيدها ميدانياً (اختبار Slot 0 وSlot 1 " +
                            "كل واحدة على حدة) قبل الاعتماد الكامل عليها"
                    )
                }
            }

            // المرحلة 3: تخمين ترتيبي - فقط عند تفعيل allowOrdinalFallback صراحة من المستدعي.
            if (matchedHandle == null && allowOrdinalFallback && phoneAccounts.isNotEmpty()) {
                val sortedSubscriptions = activeList.sortedBy { it.simSlotIndex }
                val targetIndexInActive = sortedSubscriptions.indexOfFirst { it.simSlotIndex == slotIndex }

                if (targetIndexInActive in phoneAccounts.indices) {
                    matchedHandle = phoneAccounts[targetIndexInActive]
                    ActivityLog.add("تنبيه: تم استخدام المطابقة الترتيبية (غير مؤكدة) للحساب في الفتحة $slotIndex ← '${matchedHandle.id}'")
                }
            }

            if (matchedHandle != null) {
                val label = telecomManager.getPhoneAccount(matchedHandle)?.label ?: matchedHandle.id
                ActivityLog.add("الفتحة $slotIndex ← $label (SubId: $subId)")
            } else {
                // ⚠️ ملاحظة تنفيذية: بُنيت الرسالة بمتغيّر منفصل (suffix) بدل دمج if/else مباشرة
                // داخل تسلسل +، لتفادي خطأ أولوية عمليات حقيقي وقع في مسودة سابقة من هذا الملف
                // (كانت عبارة "لن يُحدَّد PhoneAccountHandle..." تُحسَب ضمن فرع else فقط، فتختفي
                // تماماً من الرسالة كلما كان allowOrdinalFallback = true).
                val fallbackNote = if (!allowOrdinalFallback) " - المطابقة الترتيبية معطّلة" else ""
                ActivityLog.add(
                    "لم يُعثر على حساب اتصال مطابق للفتحة $slotIndex (SubId: $subId) بأي مرحلة$fallbackNote" +
                        " ← لن يُحدَّد PhoneAccountHandle؛ ستظهر نافذة اختيار الشريحة الافتراضية لأندرويد"
                )
            }

            matchedHandle
        } catch (e: Exception) {
            ActivityLog.add("خطأ أثناء تحديد الفتحة $slotIndex: ${e.message}")
            null
        }
    }
}

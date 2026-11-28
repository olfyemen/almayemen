package com.maher.almaher

import java.util.Locale

const val TOOL_VERSION = "2.0"

/**
 * All texts of the tool, so it needs no res/values files in the host app.
 * Arabic when the phone is in Arabic, English otherwise.
 */
object S {
    private val ar: Boolean get() = Locale.getDefault().language == "ar"
    private fun t(a: String, e: String) = if (ar) a else e

    val app_name get() = "حاسبة الماهر"
    val simple get() = t("مبسطة", "Simple")
    val scientific get() = t("علمية", "Scientific")
    val history get() = t("السجل", "History")
    val settings get() = t("الإعدادات", "Settings")
    val about get() = t("حول", "About")
    val back get() = t("رجوع", "Back")
    val share get() = t("مشاركة", "Share")
    val share_title get() = t("حاسبة الماهر – سجل العمليات", "Al-Maher Calculator – history")
    val clear_history get() = t("مسح السجل", "Clear history")
    val clear_history_confirm get() = t("هل تريد حذف جميع العمليات المحفوظة؟ لا يمكن التراجع عن ذلك.", "Delete all saved operations? This cannot be undone.")
    val delete get() = t("حذف", "Delete")
    val cancel get() = t("إلغاء", "Cancel")
    val history_empty get() = t("لا توجد عمليات بعد", "No operations yet")
    fun historyCount(n: Int, max: Int) = if (ar) "$n من $max عملية محفوظة" else "$n of $max operations saved"
    val history_hint get() = t("اضغط لإعادة التعديل · اضغط مطولاً للنسخ", "Tap to edit again · Long press to copy")
    val copied get() = t("تم النسخ", "Copied")
    val error get() = t("خطأ", "Error")
    val example get() = t("مثال", "Example")
    val digits get() = t("شكل الأرقام", "Digits")
    val digits_en get() = t("إنجليزية (123)", "English (123)")
    val digits_ar get() = t("عربية (١٢٣)", "Arabic (١٢٣)")
    val decimal_sep get() = t("الفاصلة العشرية", "Decimal separator")
    val thousands_sep get() = t("فاصل الآلاف", "Thousands separator")
    val sep_dot get() = t("نقطة ( . )", "Dot ( . )")
    val sep_comma get() = t("فاصلة ( , )", "Comma ( , )")
    val sep_space get() = t("مسافة فارغة", "Space")
    val sep_none get() = t("بدون", "None")
    val recurring get() = t("♻️ العملية المتكررة", "♻️ Recurring operation")
    val recurring_hint get() = t(
        "تُضاف مباشرة للعملية عند الضغط على زر ♻️ في الأعلى، مثل ×200 أو +500. يمكنك كتابة * بدل × و / بدل ÷.",
        "Added to the expression when you tap ♻️ at the top, e.g. ×200 or +500. You can type * for × and / for ÷."
    )
    val sizes get() = t("حجم الأرقام", "Number size")
    val key_height get() = t("ارتفاع الأزرار (الواجهة المبسطة)", "Key height (simple mode)")
    val expr_size get() = t("أرقام العمليات", "Expression numbers")
    val result_size get() = t("أرقام النتيجة", "Result numbers")
    val feedback get() = t("الصوت والاهتزاز", "Sound & vibration")
    val sound get() = t("صوت الأزرار", "Key sound")
    val vibration get() = t("الاهتزاز", "Vibration")
    val colors get() = t("ألوان الأزرار", "Key colors")
    val color_plus get() = t("زر الجمع ( + )", "Plus key ( + )")
    val color_ops get() = t("أزرار العمليات", "Operation keys")
    val color_equals get() = t("زر اليساوي ( = )", "Equals key ( = )")
    val color_numbers get() = t("أزرار الأرقام", "Number keys")
    val reset_colors get() = t("استعادة الألوان الافتراضية", "Reset default colors")
    fun version(v: String) = t("الإصدار $v", "Version $v")
    val call get() = t("اتصال", "Call")
    val whatsapp get() = t("واتساب", "WhatsApp")
}

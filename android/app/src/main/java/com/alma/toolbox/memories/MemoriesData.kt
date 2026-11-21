package com.alma.toolbox.memories

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate
import java.util.UUID

@Serializable
data class Memory(
    val id: Long,
    val title: String = "",
    val text: String = "",
    /** yyyy-MM-dd */
    val date: String = LocalDate.now().toString(),
    val mood: String = "",
    val tags: List<String> = emptyList(),
    /** file names inside filesDir/memories */
    val photos: List<String> = emptyList(),
    val favorite: Boolean = false,
    /** time capsule: hidden until this date (yyyy-MM-dd), empty = normal memory */
    val openOn: String = "",
    val prompt: String = "",
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
)

@Serializable
data class MemoriesData(
    val items: List<Memory> = emptyList(),
    val pinHash: String = "",
    val pinLength: Int = 4,
    val lockBio: Boolean = true,
)

fun Memory.locked(today: LocalDate = LocalDate.now()) = openOn.isNotEmpty() && today.isBefore(LocalDate.parse(openOn))

val Moods = listOf("😊", "🥰", "😂", "🤲", "😌", "🤔", "😢", "😡", "😴", "🎉", "🌧️", "☀️")

/** Writing prompts, one suggested per day. */
val Prompts = listOf(
    "ما أجمل شيء حدث لك اليوم؟", "ما الذي تشعر بالامتنان له الآن؟", "من الشخص الذي أسعدك هذا الأسبوع ولماذا؟",
    "صف لحظة ضحكت فيها من قلبك مؤخراً.", "ما الدرس الذي تعلمته اليوم؟", "ما الذي تريد أن تتذكره من هذا اليوم بعد عشر سنوات؟",
    "ما أصعب ما واجهته اليوم وكيف تعاملت معه؟", "اكتب رسالة لنفسك بعد سنة.", "ما الحلم الذي تعمل عليه الآن؟",
    "صف مكاناً تحبه وتشعر فيه بالراحة.", "ما أول ذكرى تتذكرها من طفولتك؟", "ما الكتاب أو الكلمة التي أثّرت فيك مؤخراً؟",
    "ما الشيء الصغير الذي أسعدك اليوم ولم يلاحظه أحد؟", "من تفتقده الآن؟ اكتب له ما لم تقله.", "ما العادة التي تريد أن تبدأها؟",
    "صف وجبة لا تنساها ومع من كانت.", "متى شعرت بالفخر بنفسك آخر مرة؟", "ما الدعاء الذي يتكرر في قلبك هذه الأيام؟",
    "ماذا تقول لنفسك قبل خمس سنوات؟", "ما أجمل نصيحة سمعتها من والدك أو والدتك؟", "صف يومك المثالي من أوله لآخره.",
    "ما الذي تغيّر فيك هذه السنة؟", "ما الموقف الذي ساعدت فيه أحداً وشعرت بالرضا؟", "ما الصوت أو الرائحة التي تعيدك لذكرى قديمة؟",
    "ما الذي تنتظره بشوق في الأيام القادمة؟", "اكتب عن صديق قديم وأين هو الآن.", "ما الخطأ الذي سامحت نفسك عليه؟",
    "ما أجمل رحلة قمت بها؟", "ما الذي يجعلك تشعر بالطمأنينة؟", "لو كان اليوم آخر يوم في السنة، ماذا تكتب عنها؟",
)

fun promptOfDay(d: LocalDate = LocalDate.now()) = Prompts[d.dayOfYear % Prompts.size]

object MemLock {
    var unlocked by mutableStateOf(false)
    fun lock() { unlocked = false }
}

object MemFiles {
    fun dir(ctx: Context) = File(ctx.filesDir, "memories").apply { mkdirs() }
    fun file(ctx: Context, name: String) = File(dir(ctx), name)

    /** Copies a picked photo into private storage, scaled down to at most 1600 px. Returns the file name. */
    fun importPhoto(ctx: Context, uri: Uri): String? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
        val name = UUID.randomUUID().toString() + ".jpg"
        file(ctx, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        name
    }.getOrNull()

    fun thumb(ctx: Context, name: String, size: Int): Bitmap? = runCatching {
        val f = file(ctx, name); if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= size) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()

    /** Removes photo files no memory refers to any more. */
    fun cleanup(ctx: Context, d: MemoriesData) {
        val used = d.items.flatMap { it.photos }.toHashSet()
        dir(ctx).listFiles()?.filter { it.name !in used }?.forEach { it.delete() }
    }
}

package com.alma.toolbox.core

import android.content.Context
import com.alma.toolbox.ledger.LedgerData
import com.alma.toolbox.periodic.PeriodicData
import com.alma.toolbox.pomodoro.PomoData
import com.alma.toolbox.reminder.ReminderData
import com.alma.toolbox.silent.SilentData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

/** Small JSON-file backed store with an observable state. */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<T> get() = _state
    val value: T get() = _state.value

    private fun load(): T = try {
        if (file.exists()) AppJson.decodeFromString(serializer, file.readText()) else default()
    } catch (e: Exception) {
        default()
    }

    @Synchronized
    fun update(f: (T) -> T): T {
        val n = f(_state.value)
        _state.value = n
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(AppJson.encodeToString(serializer, n))
        tmp.renameTo(file)
        return n
    }

    fun exportText(): String = AppJson.encodeToString(serializer, _state.value)
    fun importText(text: String) { val v = AppJson.decodeFromString(serializer, text); update { v } }
}

object Stores {
    lateinit var periodic: JsonStore<PeriodicData>
    lateinit var silent: JsonStore<SilentData>
    lateinit var reminders: JsonStore<ReminderData>
    lateinit var pomodoro: JsonStore<PomoData>
    lateinit var ledger: JsonStore<LedgerData>
    lateinit var memories: JsonStore<com.alma.toolbox.memories.MemoriesData>
    lateinit var notes: JsonStore<com.alma.toolbox.daily.NotesData>
    lateinit var occasions: JsonStore<com.alma.toolbox.daily.OccasionsData>
    lateinit var adhan: JsonStore<com.alma.toolbox.prayer.AdhanData>
    lateinit var transfer: JsonStore<com.alma.toolbox.transfer.TransferData>
    private var ready = false

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        val d = ctx.filesDir
        periodic = JsonStore(File(d, "periodic.json"), PeriodicData.serializer()) { PeriodicData() }
        silent = JsonStore(File(d, "silent.json"), SilentData.serializer()) { SilentData() }
        reminders = JsonStore(File(d, "reminders.json"), ReminderData.serializer()) { ReminderData() }
        pomodoro = JsonStore(File(d, "pomodoro.json"), PomoData.serializer()) { PomoData() }
        ledger = JsonStore(File(d, "ledger.json"), LedgerData.serializer()) { LedgerData() }
        memories = JsonStore(File(d, "memories.json"), com.alma.toolbox.memories.MemoriesData.serializer()) { com.alma.toolbox.memories.MemoriesData() }
        notes = JsonStore(File(d, "notes.json"), com.alma.toolbox.daily.NotesData.serializer()) { com.alma.toolbox.daily.NotesData() }
        occasions = JsonStore(File(d, "occasions.json"), com.alma.toolbox.daily.OccasionsData.serializer()) { com.alma.toolbox.daily.OccasionsData() }
        adhan = JsonStore(File(d, "adhan.json"), com.alma.toolbox.prayer.AdhanData.serializer()) { com.alma.toolbox.prayer.AdhanData() }
        transfer = JsonStore(File(d, "transfer.json"), com.alma.toolbox.transfer.TransferData.serializer()) { com.alma.toolbox.transfer.TransferData() }
        ready = true
    }
}

fun newId(): Long = System.currentTimeMillis() * 10 + (0..9).random()

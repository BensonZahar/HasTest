package com.ynk.virtualdisplay.hassle

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ynk.virtualdisplay.data.model.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
import com.ynk.virtualdisplay.data.model.VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
import com.ynk.virtualdisplay.data.model.VIRTUAL_DISPLAY_FLAG_TRUSTED
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import com.ynk.virtualdisplay.overlay.OverlayService
import kotlinx.coroutines.delay

/** Настройки, перенесённые из ПК-меню hassle_menu.pyw (PKGS, SETUP_APPS, SETUP_GLOBAL). */
object HassleConfig {
    /** Hassle 1..6 -> пакет клона игры */
    val PACKAGES: List<String> = listOf(
        "com.hassle.online",
        "com.hassle.online2",
        "com.hassle.online3",
        "com.hassle.online4",
        "com.hassle.online5",
        "com.hassle.online6",
    )

    /**
     * Префикс имени дисплея. Начинается с "Shizuku_VD_", чтобы сервис признавал дисплей своим.
     * Буква F означает "создан с флагом OWN_FOCUS" (у каждого дисплея свой фокус).
     */
    const val DISPLAY_NAME_PREFIX = "Shizuku_VD_HassleF"

    /** Префикс дисплеев старых версий (без своего фокуса). Такие дисплеи пересоздаются. */
    const val LEGACY_DISPLAY_NAME_PREFIX = "Shizuku_VD_Hassle"

    /**
     * Свой фокус у каждого дисплея: игра на дисплее считается "в фокусе" всегда,
     * даже если вы нажали на окно другого Hassle. Флаг OWN_FOCUS есть только на Android 14+ (API 34).
     */
    val ownFocusSupported: Boolean get() = Build.VERSION.SDK_INT >= 34

    val FOCUS_FLAGS: Int
        get() = if (ownFocusSupported) {
            VIRTUAL_DISPLAY_FLAG_TRUSTED or VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
        } else {
            0
        }

    const val SETUP_GLOBAL =
        "device_config set_sync_disabled_for_tests persistent; " +
            "device_config put activity_manager max_phantom_processes 2147483647; " +
            "settings put global settings_enable_monitor_phantom_procs false; " +
            "settings put global stay_on_while_plugged_in 3; " +
            "settings put global enable_freeform_support 1; " +
            "settings put global force_resizable_activities 1"

    fun setupApps(packages: String): String =
        "for p in $packages; do " +
            "cmd deviceidle whitelist +\$p; " +
            "cmd appops set \$p RUN_IN_BACKGROUND allow; " +
            "cmd appops set \$p RUN_ANY_IN_BACKGROUND allow; " +
            "am set-standby-bucket \$p active; " +
            "done"

    /** На Android 14+ имя с буквой F (дисплей со своим фокусом), на старых Android имя прежнее. */
    fun displayName(slot: Int): String =
        (if (ownFocusSupported) DISPLAY_NAME_PREFIX else LEGACY_DISPLAY_NAME_PREFIX) + slot

    fun legacyDisplayName(slot: Int): String = "$LEGACY_DISPLAY_NAME_PREFIX$slot"
}

/** Журнал и признак "идёт операция" для экрана Hassle. Живёт вне Compose, чтобы переживать смену вкладок. */
object HassleState {
    val log = mutableStateListOf<String>()
    var busy by mutableStateOf(false)

    fun add(line: String) {
        log.add(line)
        while (log.size > 80) log.removeAt(0)
    }

    fun clear() {
        log.clear()
    }
}

class HassleController(
    context: Context,
    private val repository: IDisplayRepository,
) {
    private val context: Context = context.applicationContext
    private val displayManager = this.context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private fun findDisplay(slot: Int): Display? =
        displayManager.displays.firstOrNull { it.name == HassleConfig.displayName(slot) }

    private fun findLegacyDisplay(slot: Int): Display? =
        displayManager.displays.firstOrNull { it.name == HassleConfig.legacyDisplayName(slot) }

    @Suppress("DEPRECATION")
    private fun realSize(display: Display): Point {
        val p = Point()
        display.getRealSize(p)
        return p
    }

    private fun overlayPermissionOk(log: (String) -> Unit): Boolean {
        if (Settings.canDrawOverlays(context)) return true
        log("Нет разрешения «Поверх других окон». Открываю настройки: включите его и нажмите кнопку ещё раз.")
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            log("Не удалось открыть настройки: ${e.message}")
        }
        return false
    }

    /**
     * Создаёт (или переиспользует) дисплеи для выбранных Hassle, показывает для каждого плавающее окно
     * и запускает игру на этом дисплее с паузой между запусками.
     */
    suspend fun start(
        slots: List<Int>,
        width: Int,
        height: Int,
        dpi: Int,
        delaySec: Int,
        log: (String) -> Unit,
    ) {
        if (slots.isEmpty()) {
            log("Не выбрано ни одного окна.")
            return
        }
        val packages = slots.joinToString(" ") { HassleConfig.PACKAGES[it - 1] }

        log("[1/3] Настраиваю фон для игр и разрешения...")
        repository.runShell(HassleConfig.SETUP_GLOBAL)
            .onFailure { log("  Общие настройки не применились: ${it.message}") }
        repository.runShell(HassleConfig.setupApps(packages))
            .onFailure { log("  Настройка приложений не применилась: ${it.message}") }
        // Выдаём себе право показывать окна поверх всех приложений (на MIUI ещё и "всплывающие окна в фоне").
        repository.runShell(
            "appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow; " +
                "appops set ${context.packageName} 10021 allow",
        )
        if (!overlayPermissionOk(log)) return

        log("[2/3] Создаю дисплеи и окна...")
        if (HassleConfig.ownFocusSupported) {
            log("  Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}): у каждого дисплея будет свой фокус")
        } else {
            log("  Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}): общий фокус недоступен (нужен Android 14+), фокус будет только у последнего нажатого окна")
        }
        val prepared = ArrayList<Triple<Int, Int, String>>() // slot, displayId, package
        for ((index, slot) in slots.withIndex()) {
            val pkg = HassleConfig.PACKAGES[slot - 1]
            if (context.packageManager.getLaunchIntentForPackage(pkg) == null) {
                log("  Hassle $slot: приложение $pkg не найдено, пропускаю")
                continue
            }
            var displayId: Int
            var w = width
            var h = height
            // Дисплей, созданный старой версией (без OWN_FOCUS), убираем: игра на нём запустится заново на новом
            if (HassleConfig.ownFocusSupported) {
                findLegacyDisplay(slot)?.let { old ->
                    log("  Hassle $slot: старый дисплей #${old.displayId} без своего фокуса, пересоздаю")
                    repository.runShell("am force-stop $pkg")
                    repository.releaseDisplay(old.displayId)
                        .onFailure { log("    не удалился: ${it.message}") }
                }
            }
            val existing = findDisplay(slot)
            if (existing != null) {
                displayId = existing.displayId
                val size = realSize(existing)
                if (size.x > 0 && size.y > 0) {
                    w = size.x
                    h = size.y
                }
                log("  Hassle $slot: использую уже созданный дисплей #$displayId")
            } else {
                val created = repository.createDisplay(HassleConfig.displayName(slot), width, height, dpi, HassleConfig.FOCUS_FLAGS)
                val id = created.getOrNull()
                if (id == null) {
                    log("  Hassle $slot: не удалось создать дисплей: ${created.exceptionOrNull()?.message}")
                    continue
                }
                displayId = id
                log("  Hassle $slot: дисплей #$displayId ${width}x$height")
            }
            OverlayService.show(context, displayId, "Hassle $slot", w, h, index)
            prepared.add(Triple(slot, displayId, pkg))
        }

        log("[3/3] Запускаю игры (пауза $delaySec с между запусками)...")
        for ((i, item) in prepared.withIndex()) {
            val (slot, displayId, pkg) = item
            if (i > 0) delay(delaySec * 1000L)
            val r = repository.launchApp(pkg, displayId)
            if (r.isSuccess) {
                log("  Hassle $slot запущен (код ${r.getOrNull()})")
            } else {
                log("  Hassle $slot: ошибка запуска: ${r.exceptionOrNull()?.message}")
            }
        }
        log("Готово. Сразу после запуска окно может быть чёрным 20-30 секунд, это нормально.")
        if (HassleConfig.ownFocusSupported && prepared.isNotEmpty()) {
            delay(3000)
            logFocus(log)
        }
    }

    /**
     * Диагностика фокуса: показывает, какое окно в фокусе на каждом дисплее.
     * При работающем OWN_FOCUS у каждого дисплея Hassle должна быть своя строка mCurrentFocus с игрой.
     */
    suspend fun logFocus(log: (String) -> Unit) {
        repository.runShell("dumpsys window displays | grep -E 'mDisplayId=|mCurrentFocus=|mFocusedApp='")
            .onSuccess { out ->
                log("Фокус по дисплеям:")
                out.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(40).forEach { log("  $it") }
            }
            .onFailure { log("Не удалось получить фокус: ${it.message}") }
    }

    /** Показать окна для уже существующих дисплеев Hassle (например, после перезапуска приложения). */
    fun showExisting(log: (String) -> Unit) {
        if (!overlayPermissionOk(log)) return
        var shown = 0
        for (slot in 1..HassleConfig.PACKAGES.size) {
            val display = findDisplay(slot) ?: findLegacyDisplay(slot) ?: continue
            val size = realSize(display)
            if (size.x <= 0 || size.y <= 0) continue
            OverlayService.show(context, display.displayId, "Hassle $slot", size.x, size.y, shown)
            shown++
        }
        log(if (shown == 0) "Дисплеев Hassle нет. Сначала нажмите «Запустить»." else "Показано окон: $shown")
    }

    fun hideWindows() {
        OverlayService.hideAll(context)
    }

    /** Закрыть окна, остановить все игры и удалить дисплеи Hassle. */
    suspend fun stopAll(log: (String) -> Unit) {
        OverlayService.hideAll(context)
        log("Останавливаю игры...")
        repository.runShell("for p in ${HassleConfig.PACKAGES.joinToString(" ")}; do am force-stop \$p; done")
            .onFailure { log("  Не удалось остановить игры: ${it.message}") }
        for (slot in 1..HassleConfig.PACKAGES.size) {
            for (display in listOfNotNull(findDisplay(slot), findLegacyDisplay(slot)).distinctBy { it.displayId }) {
                repository.releaseDisplay(display.displayId)
                    .onFailure { log("  Дисплей Hassle $slot не удалился: ${it.message}") }
            }
        }
        log("Всё остановлено.")
    }
}

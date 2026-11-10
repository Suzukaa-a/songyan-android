package app.songyan.ime.rime

import app.songyan.ime.keyboard.engine.EngineOptionDescriptor
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The user's choice for each schema switch, one small file per installed scheme / input method /
 * switch. Separate files keep a write from the settings app and one from the keyboard process from
 * overwriting each other's switches, and each write is atomic so a reader never sees half a file.
 *
 * Blocking disk access: callers run it off the main thread (the engine dispatcher or an IO one).
 */
class EngineOptionPreferences(
    private val directory: File,
) {
    /** The saved value, or null when the user never chose (or the file is unreadable as a choice). */
    fun read(option: EngineOptionDescriptor): Boolean? {
        val file = fileOf(option)
        if (!file.isFile) return null
        val text = file.readText(Charsets.UTF_8).trim()
        if (option.choiceGroup.isNotEmpty()) return text.takeIf { it in option.choiceGroup }?.let { it == option.name }
        return when (text) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    /**
     * Whether a value was ever saved for [option]'s record, readable or not. A choice this build's
     * switch cannot read back (an older switch's boolean, a state a later version added) is still
     * the user's and must not be replaced by a default; [read] keeps answering what the engine can
     * apply, so callers deciding whether to write a first default use this instead.
     */
    fun hasStoredValue(option: EngineOptionDescriptor): Boolean = fileOf(option).isFile

    @Throws(IOException::class)
    fun write(
        option: EngineOptionDescriptor,
        value: Boolean,
    ) {
        if (option.choiceGroup.isNotEmpty() && !value) throw IOException("choose a group member instead of clearing it")
        val file = fileOf(option)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            temp.writeText(if (option.choiceGroup.isEmpty()) value.toString() else option.name, Charsets.UTF_8)
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temp.delete()
        }
    }

    private fun fileOf(option: EngineOptionDescriptor): File =
        File(
            File(File(directory, hex(option.resourceID)), hex(option.schemaID)),
            hex(option.choiceGroup.firstOrNull() ?: option.name) + ".txt",
        )

    // Ids are arbitrary scheme-author text; hex keeps them out of the path syntax.
    private fun hex(text: String): String = text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
}

package app.songyan.ime.ime

import app.songyan.ime.keyboard.kana12.Kana12FlickDirection
import app.songyan.ime.keyboard.kana12.Kana12KeyOutput
import app.songyan.ime.keyboard.kana12.Kana12Layout
import app.songyan.ime.layout.LetterSwipeTracker
import kotlin.math.abs
import kotlin.math.max

/**
 * The direction a twelve-key press resolved to: a tap ([Center]) or one of the four flicks.
 *
 * The values name where the finger went, not the kana that comes out: [Kana12Row.face] turns a
 * direction into the row's face, and a row may leave a direction empty (わ行 down).
 */
enum class Kana12Direction {
    Center,
    Left,
    Up,
    Right,
    Down,
}

/** This surface's direction in the input model's own vocabulary; the two name the same five cells. */
internal fun Kana12Direction.coreDirection(): Kana12FlickDirection =
    when (this) {
        Kana12Direction.Center -> Kana12FlickDirection.Center
        Kana12Direction.Left -> Kana12FlickDirection.Left
        Kana12Direction.Up -> Kana12FlickDirection.Up
        Kana12Direction.Right -> Kana12FlickDirection.Right
        Kana12Direction.Down -> Kana12FlickDirection.Down
    }

/**
 * One row of the twelve-key kana grid.
 *
 * [id] is what [Kana12Action.Kana] reports, so the caller's input model owns the whole
 * kana-to-romaji table. [spokenName] is what TalkBack reads, the input language's row name (か行).
 * The faces are input-language data too, not interface text: they stay kana whatever language the
 * app interface uses. [keycap] is what the key shows and defaults to the tap face; the punctuation
 * key is the exception, showing its whole mark set.
 */
data class Kana12Row(
    val id: String,
    val spokenName: String,
    /** What a tap types. */
    val center: String,
    /** What the keycap shows; the punctuation key shows every mark it holds. */
    val keycap: String = center,
    val left: String? = null,
    val up: String? = null,
    val right: String? = null,
    val down: String? = null,
) {
    /** The face [direction] types, or null where the reference keyboard leaves it unavailable. */
    fun face(direction: Kana12Direction): String? =
        when (direction) {
            Kana12Direction.Center -> center
            Kana12Direction.Left -> left
            Kana12Direction.Up -> up
            Kana12Direction.Right -> right
            Kana12Direction.Down -> down
        }
}

/**
 * The eleven kana rows of the twelve-key keyboard.
 *
 * Every face comes from the input model's own frozen table ([Kana12Layout]): this surface never
 * repeats the kana-to-romaji data, it only adds what a keycap and TalkBack need on top of it. Every
 * row types its centre face on a tap and its vowel series on a flick (tap あ段, left い段, up う段,
 * right え段, down お段); わ行 leaves the down flick unavailable.
 */
object Kana12Rows {
    /** Builds a row from [Kana12Layout]'s table; only the spoken name and the cap are surface data. */
    private fun row(
        id: String,
        spokenName: String,
        keycap: String? = null,
    ): Kana12Row {
        fun face(direction: Kana12FlickDirection): String? =
            when (val output = Kana12Layout.output(id, direction)) {
                is Kana12KeyOutput.Kana -> output.kana
                is Kana12KeyOutput.Literal -> output.literal
                null -> null
            }
        val center = face(Kana12FlickDirection.Center).orEmpty()
        return Kana12Row(
            id = id,
            spokenName = spokenName,
            center = center,
            keycap = keycap ?: center,
            left = face(Kana12FlickDirection.Left),
            up = face(Kana12FlickDirection.Up),
            right = face(Kana12FlickDirection.Right),
            down = face(Kana12FlickDirection.Down),
        )
    }

    val a = row("a", "あ行")

    val ka = row("ka", "か行")

    val sa = row("sa", "さ行")

    val ta = row("ta", "た行")

    val na = row("na", "な行")

    val ha = row("ha", "は行")

    val ma = row("ma", "ま行")

    /** The や行 borrows the two round brackets for its empty vowels. */
    val ya = row("ya", "や行")

    val ra = row("ra", "ら行")

    /** わ行 has no down face on the reference keyboard; a release there types nothing. */
    val wa = row("wa", "わ行")

    val punctuation = row("punctuation", "句読点", keycap = "、。?!")

    /** Every kana row, in the grid's reading order. */
    val all: List<Kana12Row> = listOf(a, ka, sa, ta, na, ha, ma, ya, ra, wa, punctuation)

    /**
     * The centre 4x3 grid as the reference keyboard draws it: three kana keys per line, and the
     * fourth line starting with the modify key, which is not a kana row (null).
     */
    val grid: List<List<Kana12Row?>> =
        listOf(
            listOf(a, ka, sa),
            listOf(ta, na, ha),
            listOf(ma, ya, ra),
            listOf(null, wa, punctuation),
        )

    /** The row with [rowId], or null for a key that names no kana row of this input model. */
    fun rowFor(rowId: String): Kana12Row? = all.firstOrNull { it.id == rowId }

    /**
     * The face [rowId] types in [direction], or null where the input model has no glyph there. A
     * press is committed only when this is non-null, so an unavailable flick types nothing.
     */
    fun glyph(
        rowId: String,
        direction: Kana12Direction,
    ): String? = rowFor(rowId)?.face(direction)
}

/**
 * Flick resolution and the cross preview, in dp.
 *
 * Distances are dp because the threshold is a finger-size question: 18 dp is the distance the
 * two-letter swipe keys already lock at (iOS uses 18 pt), so a finger roll during a tap never
 * becomes a flick. Nothing here is Compose- or Android-specific.
 */
object Kana12Flick {
    /** Finger travel, in dp, past which a press is no longer a tap; the merged keys' own lock. */
    const val THRESHOLD_DP: Double = LetterSwipeTracker.LOCK_DISTANCE

    /** Side of one cell of the cross preview, in dp. */
    const val PREVIEW_CELL_DP: Double = 36.0

    /** Space between two preview cells, in dp. */
    const val PREVIEW_GAP_DP: Double = 4.0

    /**
     * The direction of a press that has moved [dx], [dy] dp from touch-down, in screen axes
     * (positive y is down). A move shorter than [thresholdDp] is a tap, and the threshold itself
     * already starts a flick. A move shorter on neither axis (an exact diagonal) resolves to the
     * horizontal direction, so one press never has two owners.
     */
    fun direction(
        dx: Double,
        dy: Double,
        thresholdDp: Double = THRESHOLD_DP,
    ): Kana12Direction {
        if (!dx.isFinite() || !dy.isFinite()) return Kana12Direction.Center
        val horizontal = abs(dx)
        val vertical = abs(dy)
        if (max(horizontal, vertical) < thresholdDp) return Kana12Direction.Center
        if (horizontal >= vertical) {
            return if (dx < 0) Kana12Direction.Left else Kana12Direction.Right
        }
        return if (dy < 0) Kana12Direction.Up else Kana12Direction.Down
    }

    /**
     * The five preview cells for a press whose pivot is [pivotX], [pivotY] dp: the centre cell
     * sits on the pivot and one cell per direction sits one cell and one gap away. The whole cross
     * moves as one to stay inside [surface], so the cells never change their positions relative to
     * each other, which is what lets a finger read the figure.
     */
    fun previewBounds(
        pivotX: Double,
        pivotY: Double,
        surface: Kana12Rect,
        cellDp: Double = PREVIEW_CELL_DP,
        gapDp: Double = PREVIEW_GAP_DP,
    ): Map<Kana12Direction, Kana12Rect> {
        val cell = max(1.0, cellDp)
        val gap = max(0.0, gapDp)
        val step = cell + gap

        fun cellAt(
            centerX: Double,
            centerY: Double,
        ): Kana12Rect =
            Kana12Rect(
                centerX - cell / 2,
                centerY - cell / 2,
                centerX + cell / 2,
                centerY + cell / 2,
            )

        val unshifted =
            mapOf(
                Kana12Direction.Center to cellAt(pivotX, pivotY),
                Kana12Direction.Left to cellAt(pivotX - step, pivotY),
                Kana12Direction.Up to cellAt(pivotX, pivotY - step),
                Kana12Direction.Right to cellAt(pivotX + step, pivotY),
                Kana12Direction.Down to cellAt(pivotX, pivotY + step),
            )
        val left = unshifted.values.minOf { it.left }
        val right = unshifted.values.maxOf { it.right }
        val top = unshifted.values.minOf { it.top }
        val bottom = unshifted.values.maxOf { it.bottom }
        val shiftX =
            when {
                left < surface.left -> surface.left - left
                right > surface.right -> surface.right - right
                else -> 0.0
            }
        val shiftY =
            when {
                top < surface.top -> surface.top - top
                bottom > surface.bottom -> surface.bottom - bottom
                else -> 0.0
            }
        return unshifted.mapValues { (_, rect) -> rect.translate(shiftX, shiftY) }
    }
}

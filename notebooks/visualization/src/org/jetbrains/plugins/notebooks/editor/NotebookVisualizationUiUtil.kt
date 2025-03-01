package org.jetbrains.plugins.notebooks.editor

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.util.TextRange
import com.intellij.ui.JBColor
import com.intellij.ui.JreHiDpiUtil
import com.intellij.ui.paint.LinePainter2D
import com.intellij.ui.paint.PaintUtil
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.scale.ScaleContext
import com.intellij.util.SmartList
import com.intellij.util.containers.ContainerUtil
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import javax.swing.JComponent
import kotlin.math.max
import kotlin.math.min
import kotlin.reflect.KProperty

infix fun IntRange.hasIntersectionWith(other: IntRange): Boolean =
  !(first > other.last || last < other.first)

inline fun <T, G : Graphics> G.use(handler: (g: G) -> T): T =
  try {
    handler(this)
  }
  finally {
    dispose()
  }

inline fun <T> trimLists(left: List<T>, right: List<T>, comparator: (T, T) -> Boolean): Pair<List<T>, List<T>> {
  val minSize = min(left.size, right.size)

  var trimLeft = 0
  while (trimLeft < minSize && comparator(left[trimLeft], right[trimLeft])) {
    ++trimLeft
  }

  var trimRight = 0
  while (trimRight < minSize - trimLeft && comparator(left[left.size - trimRight - 1], right[right.size - trimRight - 1])) {
    ++trimRight
  }

  return left.run { subList(trimLeft, size - trimRight) } to right.run { subList(trimLeft, size - trimRight) }
}

inline fun paintNotebookCellBackgroundGutter(
  editor: EditorImpl,
  g: Graphics,
  r: Rectangle,
  interval: NotebookCellLines.Interval,
  top: Int,
  height: Int,
  crossinline actionBetweenBackgroundAndStripe: () -> Unit = {}
) {
  val appearance = editor.notebookAppearance
  val stripe = appearance.getCellStripeColor(editor, interval)
  val stripeHover = appearance.getCellStripeHoverColor(editor, interval)
  val borderWidth = appearance.getLeftBorderWidth()
  val isDiff = editor.editorKind == EditorKind.DIFF
  if (!isDiff){
    g.color = appearance.getCodeCellBackground(editor.colorsScheme)
    g.fillRect(r.width - borderWidth, top, borderWidth, height)
    actionBetweenBackgroundAndStripe()
  }
  else {
    val key = EditorColors.TEARLINE_COLOR
    val color: Color? = editor.colorsScheme.getColor(key)
    g.color = color ?: JBColor.black

    val sw: Double = if (JreHiDpiUtil.isJreHiDPIEnabled() || JBUIScale.scale(1.toFloat()) * editor.scale < 2) 1.toDouble() else 2.toDouble()
    val ctx: ScaleContext = ScaleContext.create(editor.component)
    val strokeWidth = PaintUtil.alignToInt(sw, ctx,
      if (PaintUtil.devValue(1.0, ctx) > 2) PaintUtil.RoundingMode.FLOOR else PaintUtil.RoundingMode.ROUND, null)

    val tearLineX = (r.width - 1).toDouble()
    LinePainter2D.paint(g as Graphics2D, tearLineX, (r.y - 1000).toDouble(), tearLineX, (r.y + r.height + 1000).toDouble(),
      LinePainter2D.StrokeType.CENTERED, strokeWidth)
    return
  }
  if (stripe != null) {
    appearance.paintCellStripe(g, r, stripe, top, height)
  }
  if (stripeHover != null) {
    g.color = stripeHover
    g.fillRect(r.width - appearance.getLeftBorderWidth(), top, appearance.getCellLeftLineHoverWidth(), height)
  }
}

fun NotebookEditorAppearance.paintCellStripe(
  g: Graphics,
  r: Rectangle,
  stripe: Color,
  top: Int,
  height: Int,
) {
  g.color = stripe
  g.fillRect(r.width - getLeftBorderWidth(), top, getCellLeftLineWidth(), height)
}

/**
 * Creates a document listener that will be automatically unregistered when the editor is disposed.
 */
fun Editor.addEditorDocumentListener(listener: DocumentListener) {
  require(this is EditorImpl)
  if (!isDisposed) {
    document.addDocumentListener(listener, disposable)
  }
}

fun Document.getText(interval: NotebookCellLines.Interval): String =
  getText(TextRange(
    getLineStartOffset(interval.lines.first),
    getLineEndOffset(interval.lines.last)
  ))

fun Editor.getCell(line: Int): NotebookCellLines.Interval =
  NotebookCellLines.get(this).intervalsIterator(line).next()

fun Editor.getCells(lines: IntRange): List<NotebookCellLines.Interval> =
  NotebookCellLines.get(this).getCells(lines).toList()

fun Editor.getCellByOrdinal(ordinal: Int): NotebookCellLines.Interval =
  NotebookCellLines.get(this).getIterator(ordinal).next()

fun NotebookCellLines.getCells(lines: IntRange): Sequence<NotebookCellLines.Interval> =
  intervalsIterator(lines.first).asSequence().takeWhile { it.lines.first <= lines.last }

fun groupNeighborCells(cells: List<NotebookCellLines.Interval>): List<List<NotebookCellLines.Interval>> {
  val groups = SmartList<SmartList<NotebookCellLines.Interval>>()
  for (cell in cells) {
    if (groups.lastOrNull()?.last()?.let { it.ordinal + 1 } != cell.ordinal) {
      groups.add(SmartList())
    }
    groups.last().add(cell)
  }
  return groups
}

/** Both lists should be sorted by the [IntRange.first]. */
fun MutableList<IntRange>.mergeAndJoinIntersections(other: List<IntRange>) {
  val merged = ContainerUtil.mergeSortedLists(this, other, Comparator { o1, o2 -> o1.first - o2.first }, false)
  clear()
  for (current in merged) {
    val previous = removeLastOrNull()
    when {
      previous == null -> add(current)
      previous.last + 1 >= current.first -> add(previous.first..max(previous.last, current.last))
      else -> {
        add(previous)
        add(current)
      }
    }
  }
}

fun isLineVisible(editor: EditorImpl, line: Int): Boolean {
  val lineY = editor.logicalPositionToXY(LogicalPosition(line, 0)).y
  val viewArea = editor.scrollingModel.visibleAreaOnScrollingFinished
  return viewArea.y <= lineY && lineY <= viewArea.y + viewArea.height
}

class SwingClientProperty<T, R: T?>(name: String) {
  class Name(private val name: String) {
    override fun toString(): String = "Name($name)"
  }

  private val name = Name(name)

  operator fun getValue(thisRef: JComponent, property: KProperty<*>): R =
    @Suppress("UNCHECKED_CAST") (thisRef.getClientProperty(name) as R)

  operator fun setValue(thisRef: JComponent, property: KProperty<*>, value: R) {
    thisRef.putClientProperty(name, value)
  }
}
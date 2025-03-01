// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ui.dsl.gridLayout

import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JComponent
import kotlin.math.max
import kotlin.math.min

internal class JBGridImpl : JBGrid {

  override var resizableColumns = emptySet<Int>()
  override var resizableRows = emptySet<Int>()

  override var columnsGaps = emptyList<ColumnGaps>()
  override var rowsGaps = emptyList<RowGaps>()

  val visible: Boolean
    get() = cells.any { it.visible }

  private val layoutData = JBLayoutData()
  private val cells = mutableListOf<JBCell>()

  fun register(component: JComponent, constraints: JBConstraints) {
    if (!isEmpty(constraints)) {
      throw UiDslException("Some cells are occupied already: $constraints")
    }

    cells.add(JBComponentCell(constraints, component))
  }

  fun registerSubGrid(constraints: JBConstraints): JBGrid {
    if (!isEmpty(constraints)) {
      throw UiDslException("Some cells are occupied already: $constraints")
    }

    val result = JBGridImpl()
    cells.add(JBGridCell(constraints, result))
    return result
  }

  fun unregister(component: JComponent): Boolean {
    val iterator = cells.iterator()
    for (cell in iterator) {
      when (cell) {
        is JBComponentCell -> {
          if (cell.component == component) {
            iterator.remove()
            return true
          }
        }
        is JBGridCell -> {
          if (cell.content.unregister(component)) {
            return true
          }
        }
      }
    }
    return false
  }

  fun getPreferredSize(): Dimension {
    calculateLayoutData(-1, -1)
    return Dimension(layoutData.preferredWidth, layoutData.preferredHeight)
  }

  /**
   * Layouts components
   */
  fun layout(rect: Rectangle) {
    layoutData.visibleCellsData.forEach { layoutCellData ->
      val cell = layoutCellData.cell
      val constraints = cell.constraints
      val paddedX = rect.x + constraints.gaps.left + layoutCellData.columnGaps.left + layoutData.columnsCoord[constraints.x]
      val paddedY = rect.y + constraints.gaps.top + layoutCellData.rowGaps.top + layoutData.rowsCoord[constraints.y]
      val paddedWidth = layoutData.getPaddedWidth(layoutCellData)
      val fullPaddedWidth = layoutData.getFullPaddedWidth(layoutCellData)
      val paddedHeight = layoutData.getFullPaddedHeight(layoutCellData)

      when (cell) {
        is JBComponentCell -> {
          layoutComponent(cell.component, layoutCellData, paddedX, paddedY, paddedWidth, fullPaddedWidth, paddedHeight)
        }
        is JBGridCell -> {
          // todo constraints are not used
          cell.content.layout(Rectangle(paddedX, paddedY, fullPaddedWidth, paddedHeight))
        }
      }
    }
  }

  /**
   * Calculates [layoutData]
   *
   * @param width if negative - calculates layout for preferred size, otherwise uses [width]
   * @param height if negative - calculates layout for preferred size, otherwise uses [height]
   */
  fun calculateLayoutData(width: Int, height: Int) {
    calculateLayoutDataStep1()
    calculateLayoutDataStep2(width)
    calculateLayoutDataStep3()
    calculateLayoutDataStep4(height)
  }

  /**
   * Step 1 of [layoutData] calculations
   */
  fun calculateLayoutDataStep1() {
    layoutData.columnsSizeCalculator.reset()
    val visibleCellsData = mutableListOf<LayoutCellData>()

    for (cell in cells) {
      var preferredSize: Dimension

      when (cell) {
        is JBComponentCell -> {
          val component = cell.component
          if (!component.isVisible) {
            continue
          }
          preferredSize = component.preferredSize
        }

        is JBGridCell -> {
          val grid = cell.content
          if (!grid.visible) {
            continue
          }
          grid.calculateLayoutDataStep1()
          preferredSize = Dimension(grid.layoutData.preferredWidth, 0)
        }
      }

      val layoutCellData: LayoutCellData
      with(cell.constraints) {
        layoutCellData = LayoutCellData(cell = cell,
          preferredSize = preferredSize,
          columnGaps = ColumnGaps(
            left = columnsGaps.getOrNull(x)?.left ?: 0,
            right = columnsGaps.getOrNull(x + width - 1)?.right ?: 0),
          rowGaps = RowGaps(
            top = rowsGaps.getOrNull(y)?.top ?: 0,
            bottom = rowsGaps.getOrNull(y + height - 1)?.bottom ?: 0)
        )
      }

      visibleCellsData.add(layoutCellData)
      layoutData.columnsSizeCalculator.addConstraint(cell.constraints.x, cell.constraints.width, layoutCellData.cellPaddedWidth)
    }

    layoutData.visibleCellsData = visibleCellsData
    layoutData.preferredWidth = layoutData.columnsSizeCalculator.calculatePreferredSize()
  }

  /**
   * Step 2 of [layoutData] calculations
   *
   * @param width see [calculateLayoutData]
   */
  fun calculateLayoutDataStep2(width: Int) {
    val calcWidth = if (width < 0) layoutData.preferredWidth else width
    layoutData.columnsCoord = layoutData.columnsSizeCalculator.calculateCoords(calcWidth, resizableColumns)

    for (layoutCellData in layoutData.visibleCellsData) {
      val cell = layoutCellData.cell
      if (cell is JBGridCell) {
        cell.content.calculateLayoutDataStep2(layoutData.getFullPaddedWidth(layoutCellData))
      }
    }
  }

  /**
   * Step 3 of [layoutData] calculations
   */
  fun calculateLayoutDataStep3() {
    layoutData.rowsSizeCalculator.reset()
    initBaselineData()

    for (layoutCellData in layoutData.visibleCellsData) {
      when (val cell = layoutCellData.cell) {
        is JBComponentCell -> {
          val rowBaselineData = layoutCellData.rowBaselineData
          if (rowBaselineData == null) {
            continue
          }

          val constraints = layoutCellData.cell.constraints
          val componentWidth = layoutData.getPaddedWidth(layoutCellData) + layoutCellData.cell.constraints.visualPaddings.width
          val baseline: Int
          if (componentWidth >= 0) {
            baseline = cell.component.getBaseline(componentWidth, layoutCellData.preferredSize.height)
            // getBaseline changes preferredSize, at least for JLabel
            layoutCellData.preferredSize.height = cell.component.preferredSize.height
          }
          else {
            baseline = -1
          }

          if (baseline < 0) {
            layoutCellData.rowBaselineData = null
          }
          else {
            layoutCellData.baseline = baseline
            with(rowBaselineData) {
              maxAboveBaseline = max(maxAboveBaseline,
                baseline + layoutCellData.rowGaps.top + constraints.gaps.top - constraints.visualPaddings.top)
              maxBelowBaseline = max(maxBelowBaseline,
                layoutCellData.preferredSize.height - baseline + layoutCellData.rowGaps.bottom + constraints.gaps.bottom - constraints.visualPaddings.bottom)
            }
          }
        }

        is JBGridCell -> {
          val grid = cell.content
          grid.calculateLayoutDataStep3()
          layoutCellData.preferredSize.height = grid.layoutData.preferredHeight
          // todo use subgrid baseline
          layoutCellData.rowBaselineData = null
        }
      }
    }

    for (layoutCellData in layoutData.visibleCellsData) {
      val constraints = layoutCellData.cell.constraints
      layoutData.rowsSizeCalculator.addConstraint(constraints.y, constraints.height, layoutCellData.cellPaddedHeight)
    }

    layoutData.preferredHeight = layoutData.rowsSizeCalculator.calculatePreferredSize()
  }

  /**
   * Step 4 of [layoutData] calculations
   *
   * @param height see [calculateLayoutData]
   */
  fun calculateLayoutDataStep4(height: Int) {
    val calcHeight = if (height < 0) layoutData.preferredHeight else height
    layoutData.rowsCoord = layoutData.rowsSizeCalculator.calculateCoords(calcHeight, resizableRows)

    for (layoutCellData in layoutData.visibleCellsData) {
      val cell = layoutCellData.cell
      if (cell is JBGridCell) {
        cell.content.calculateLayoutDataStep4(layoutData.getFullPaddedHeight(layoutCellData))
      }
    }
  }

  /**
   * Assigns [LayoutCellData.rowBaselineData]
   */
  private fun initBaselineData() {
    for (layoutCellData in layoutData.visibleCellsData) {
      layoutCellData.baseline = null
      layoutCellData.rowBaselineData = null
    }

    layoutData.visibleCellsData
      .filter { it.cell.constraints.verticalAlign != VerticalAlign.FILL && it.cell.constraints.height == 1 }
      .groupBy { it.cell.constraints.y }
      .forEach { cellsByRow ->
        cellsByRow.value.groupBy { it.cell.constraints.verticalAlign }.forEach { cellsByAlign ->
          val rowBaselineData = RowBaselineData()
          for (layoutCellData in cellsByAlign.value) {
            layoutCellData.rowBaselineData = rowBaselineData
          }
        }
      }
  }

  /**
   * Layouts [component] in such way that its padded bounds (size of component  minus visualPaddings) equal provided rect
   */
  private fun layoutComponent(component: JComponent,
                              layoutCellData: LayoutCellData,
                              paddedX: Int,
                              paddedY: Int,
                              paddedWidth: Int,
                              fullPaddedWidth: Int,
                              paddedHeight: Int) {
    val constraints = layoutCellData.cell.constraints
    val visualPaddings = constraints.visualPaddings
    val resultPaddedHeight = if (constraints.verticalAlign == VerticalAlign.FILL)
      paddedHeight
    else
      min(paddedHeight, layoutCellData.preferredSize.height - visualPaddings.height)
    val resultPaddedX = paddedX +
                        when (constraints.horizontalAlign) {
                          HorizontalAlign.LEFT -> 0
                          HorizontalAlign.CENTER -> (fullPaddedWidth - paddedWidth) / 2
                          HorizontalAlign.RIGHT -> fullPaddedWidth - paddedWidth
                          HorizontalAlign.FILL -> 0
                        }
    val resultPaddedY = paddedY +
                        when (constraints.verticalAlign) {
                          VerticalAlign.TOP -> 0
                          VerticalAlign.CENTER -> (paddedHeight - resultPaddedHeight) / 2
                          VerticalAlign.BOTTOM -> paddedHeight - resultPaddedHeight
                          VerticalAlign.FILL -> 0
                        }

    component.setBounds(
      resultPaddedX - visualPaddings.left, resultPaddedY - visualPaddings.top,
      paddedWidth + visualPaddings.width, resultPaddedHeight + visualPaddings.height
    )
  }

  private fun isEmpty(constraints: JBConstraints): Boolean {
    cells.forEach { cell ->
      with(cell.constraints) {
        if (constraints.x + constraints.width > x &&
            x + width > constraints.x &&
            constraints.y + constraints.height > y &&
            y + height > constraints.y
        ) {
          return false
        }
      }
    }
    return true
  }
}

/**
 * Data that collected before layout/preferred size calculations
 */
private class JBLayoutData {

  //
  // Step 1
  //

  var visibleCellsData = emptyList<LayoutCellData>()
  val columnsSizeCalculator = JBColumnsSizeCalculator()
  var preferredWidth = 0

  //
  // Step 2
  //
  var columnsCoord = emptyArray<Int>()

  //
  // Step 3
  //
  val rowsSizeCalculator = JBColumnsSizeCalculator()
  var preferredHeight = 0

  //
  // Step 4
  //
  var rowsCoord = emptyArray<Int>()

  fun getPaddedWidth(layoutCellData: LayoutCellData): Int {
    val fullPaddedWidth = getFullPaddedWidth(layoutCellData)
    return if (layoutCellData.cell.constraints.horizontalAlign == HorizontalAlign.FILL)
      fullPaddedWidth
    else
      min(fullPaddedWidth, layoutCellData.preferredSize.width - layoutCellData.cell.constraints.visualPaddings.width)
  }

  fun getFullPaddedWidth(layoutCellData: LayoutCellData): Int {
    val constraints = layoutCellData.cell.constraints
    return columnsCoord[constraints.x + constraints.width] - columnsCoord[constraints.x] - layoutCellData.gapWidth
  }

  fun getFullPaddedHeight(layoutCellData: LayoutCellData): Int {
    val constraints = layoutCellData.cell.constraints
    return rowsCoord[constraints.y + constraints.height] - rowsCoord[constraints.y] - layoutCellData.gapHeight
  }
}

/**
 * For sub-grids height of [preferredSize] calculated on late steps of [JBGridImpl.calculateLayoutData]
 */
private data class LayoutCellData(val cell: JBCell, val preferredSize: Dimension,
                                  val columnGaps: ColumnGaps, val rowGaps: RowGaps) {
  /**
   * Calculated on late steps of [JBGridImpl.calculateLayoutData]
   */
  var baseline: Int? = null

  /**
   * Calculated on late steps of [JBGridImpl.calculateLayoutData]. null for cells without baseline,  height > 1 or vertical align FILL
   */
  var rowBaselineData: RowBaselineData? = null

  val gapWidth: Int
    get() = cell.constraints.gaps.width + columnGaps.width

  val gapHeight: Int
    get() = cell.constraints.gaps.height + rowGaps.height

  /**
   * Cell width including gaps and excluding visualPaddings
   */
  val cellPaddedWidth: Int
    get() = preferredSize.width + gapWidth - cell.constraints.visualPaddings.width

  /**
   * Cell height including gaps and excluding visualPaddings
   */
  val cellPaddedHeight: Int
    get() {
      val baselineData = rowBaselineData
      if (baselineData == null) {
        return preferredSize.height + gapHeight - cell.constraints.visualPaddings.height
      }

      return baselineData.maxAboveBaseline + baselineData.maxBelowBaseline
    }
}

/**
 * Max sizes for a row which include gaps and exclude paddings
 */
private data class RowBaselineData(var maxAboveBaseline: Int = 0, var maxBelowBaseline: Int = 0)

private sealed class JBCell(val constraints: JBConstraints) {
  abstract val visible: Boolean
}

private class JBComponentCell(constraints: JBConstraints, val component: JComponent) : JBCell(constraints) {
  override val visible: Boolean
    get() = component.isVisible
}

private class JBGridCell(constraints: JBConstraints, val content: JBGridImpl) : JBCell(constraints) {
  override val visible: Boolean
    get() = content.visible
}

// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.intellij.plugins.markdown.ui.floating

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.hint.HintManagerImpl
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiEditorUtil
import com.intellij.psi.util.parents
import com.intellij.ui.LightweightHint
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.util.hasType
import org.jetbrains.annotations.ApiStatus
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import kotlin.properties.Delegates

@ApiStatus.Internal
class FloatingToolbar(val editor: Editor, private val actionGroupId: String) : Disposable {
  companion object {
    private const val verticalGap = 2
  }

  private val mouseListener = MouseListener()
  private val keyboardListener = KeyboardListener()
  private val mouseMotionListener = MouseMotionListener()

  private var hint: LightweightHint? = null
  private var buttonSize: Int by Delegates.notNull()
  private var lastSelection: String? = null

  init {
    registerListeners()
  }

  fun isShown() = hint != null

  fun hideIfShown() {
    hint?.hide()
  }

  fun showIfHidden() {
    if (hint != null || !canBeShownAtCurrentSelection()) {
      return
    }
    val leftGroup = ActionManager.getInstance().getAction(actionGroupId) as ActionGroup
    val toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.EDITOR_TOOLBAR, leftGroup, true)
    toolbar.setTargetComponent(editor.contentComponent)
    toolbar.setReservePlaceAutoPopupIcon(false)
    buttonSize = toolbar.maxButtonHeight

    val newHint = LightweightHint(toolbar.component)
    newHint.setForceShowAsPopup(true)

    showOrUpdateLocation(newHint)
    newHint.addHintListener { this.hint = null }
    this.hint = newHint
  }

  fun updateLocationIfShown() {
    showOrUpdateLocation(hint ?: return)
  }

  override fun dispose() {
    unregisterListeners()
  }

  private fun showOrUpdateLocation(hint: LightweightHint) {
    HintManagerImpl.getInstanceImpl().showEditorHint(
      hint,
      editor,
      getHintPosition(hint),
      HintManager.HIDE_BY_ESCAPE or HintManager.UPDATE_BY_SCROLLING,
      0,
      true
    )
  }

  private fun registerListeners() {
    editor.addEditorMouseListener(mouseListener)
    editor.addEditorMouseMotionListener(mouseMotionListener)
    editor.contentComponent.addKeyListener(keyboardListener)
  }

  private fun unregisterListeners() {
    editor.removeEditorMouseListener(mouseListener)
    editor.removeEditorMouseMotionListener(mouseMotionListener)
    editor.contentComponent.removeKeyListener(keyboardListener)
  }

  private fun canBeShownAtCurrentSelection(): Boolean {
    val file = PsiEditorUtil.getPsiFile(editor)
    PsiDocumentManager.getInstance(file.project).commitDocument(editor.document)
    val selectionModel = editor.selectionModel
    val elementAtStart = file.findElementAt(selectionModel.selectionStart)
    val elementAtEnd = file.findElementAt(selectionModel.selectionEnd)
    return elementAtStart?.let(::hasFenceParent) == false && elementAtEnd?.let(::hasFenceParent) == false
  }

  private fun hasFenceParent(element: PsiElement): Boolean {
    return element.parents(withSelf = true).any { it.hasType(MarkdownElementTypes.CODE_FENCE) }
  }

  private fun getHintPosition(hint: LightweightHint): Point {
    val hintPos = HintManagerImpl.getInstanceImpl().getHintPosition(hint, editor, HintManager.DEFAULT)
    // because of `hint.setForceShowAsPopup(true)`, HintManager.ABOVE does not place the hint above
    // the hint remains on the line, so we need to move it up ourselves
    val dy = -(hint.component.preferredSize.height + verticalGap)
    val dx = buttonSize * -2
    hintPos.translate(dx, dy)
    return hintPos
  }

  private fun updateOnProbablyChangedSelection(onSelectionChanged: (String) -> Unit) {
    val newSelection = editor.selectionModel.selectedText

    when (newSelection) {
      null -> hideIfShown()
      lastSelection -> Unit
      else -> onSelectionChanged(newSelection)
    }

    lastSelection = newSelection
  }

  private inner class MouseListener : EditorMouseListener {
    override fun mouseReleased(e: EditorMouseEvent) {
      updateOnProbablyChangedSelection {
        if (isShown()) {
          updateLocationIfShown()
        } else {
          showIfHidden()
        }
      }
    }
  }

  private inner class KeyboardListener : KeyAdapter() {
    override fun keyReleased(e: KeyEvent) {
      super.keyReleased(e)
      if (e.source != editor.contentComponent) {
        return
      }
      updateOnProbablyChangedSelection { selection ->
        if ('\n' in selection) {
          hideIfShown()
        } else if (isShown()) {
          updateLocationIfShown()
        } else {
          showIfHidden()
        }
      }
    }
  }

  private inner class MouseMotionListener : EditorMouseMotionListener {
    override fun mouseMoved(e: EditorMouseEvent) {
      val visualPosition = e.visualPosition
      val hoverSelected = editor.caretModel.allCarets.any {
        val beforeSelectionEnd = it.selectionEndPosition.after(visualPosition)
        val afterSelectionStart = visualPosition.after(it.selectionStartPosition)
        beforeSelectionEnd && afterSelectionStart
      }
      if (hoverSelected) {
        showIfHidden()
      }
    }
  }
}

// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.documentation;

import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsUtil;
import com.intellij.openapi.options.FontSize;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.psi.PsiElement;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ui.GraphicsUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.accessibility.ScreenReader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.*;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.util.Map;
import java.util.function.Supplier;

class DocumentationEditorPane extends JEditorPane {

  private final Map<KeyStroke, ActionListener> myKeyboardActions;
  private final Supplier<? extends @Nullable PsiElement> myElementSupplier;

  DocumentationEditorPane(
    @NotNull Map<KeyStroke, ActionListener> keyboardActions,
    @NotNull Supplier<? extends @Nullable PsiElement> elementSupplier
  ) {
    myKeyboardActions = keyboardActions;
    myElementSupplier = elementSupplier;
    enableEvents(AWTEvent.KEY_EVENT_MASK);
    setEditable(false);
    if (ScreenReader.isActive()) {
      // Note: Making the caret visible is merely for convenience
      getCaret().setVisible(true);
    }
    else {
      putClientProperty("caretWidth", 0); // do not reserve space for caret (making content one pixel narrower than component)
      UIUtil.doNotScrollToCaret(this);
    }
    setBackground(EditorColorsUtil.getGlobalOrDefaultColor(DocumentationComponent.COLOR_KEY));
    setEditorKit(new DocumentationHtmlEditorKit(this));
    setBorder(JBUI.Borders.empty());
  }

  @Override
  protected void processKeyEvent(KeyEvent e) {
    KeyStroke keyStroke = KeyStroke.getKeyStrokeForEvent(e);
    ActionListener listener = myKeyboardActions.get(keyStroke);
    if (listener != null) {
      listener.actionPerformed(new ActionEvent(this, 0, ""));
      e.consume();
      return;
    }
    super.processKeyEvent(e);
  }

  @Override
  protected void paintComponent(Graphics g) {
    GraphicsUtil.setupAntialiasing(g);
    super.paintComponent(g);
  }

  @Override
  public void setDocument(Document doc) {
    super.setDocument(doc);
    doc.putProperty("IgnoreCharsetDirective", Boolean.TRUE);
    if (doc instanceof StyledDocument) {
      doc.putProperty("imageCache", new DocumentationImageProvider(this, myElementSupplier));
    }
  }

  int getPreferredWidth() {
    int definitionPreferredWidth = definitionPreferredWidth();
    return definitionPreferredWidth < 0 ? getPreferredSize().width
                                        : Math.max(definitionPreferredWidth, getMinimumSize().width);
  }

  private int definitionPreferredWidth() {
    View definition = findDefinition(getUI().getRootView(this));
    if (definition == null) {
      return -1;
    }

    // Heuristics to calculate popup width based on the amount of the content.
    // The proportions are set for 4 chars/1px in range between 200 and 1000 chars.
    // 200 chars and less is 300px, 1000 chars and more is 500px.
    // These values were calculated based on experiments with varied content and manual resizing to comfortable width.
    int textLength = definition.getDocument().getLength();
    final int contentLengthPreferredSize;
    if (textLength < 200) {
      contentLengthPreferredSize = JBUIScale.scale(300);
    }
    else if (textLength > 200 && textLength < 1000) {
      contentLengthPreferredSize = JBUIScale.scale(300) + JBUIScale.scale(1) * (textLength - 200) * (500 - 300) / (1000 - 200);
    }
    else {
      contentLengthPreferredSize = JBUIScale.scale(500);
    }
    int defaultPreferredSize = (int)definition.getPreferredSpan(View.X_AXIS);
    return Math.max(contentLengthPreferredSize, defaultPreferredSize);
  }

  private static @Nullable View findDefinition(@NotNull View view) {
    if ("definition".equals(view.getElement().getAttributes().getAttribute(HTML.Attribute.CLASS))) {
      return view;
    }
    for (int i = 0; i < view.getViewCount(); i++) {
      View definition = findDefinition(view.getView(i));
      if (definition != null) {
        return definition;
      }
    }
    return null;
  }

  void applyFontProps(@NotNull FontSize size) {
    Document document = getDocument();
    if (!(document instanceof StyledDocument)) {
      return;
    }
    String fontName = Registry.is("documentation.component.editor.font")
                      ? EditorColorsManager.getInstance().getGlobalScheme().getEditorFontName()
                      : getFont().getFontName();

    // changing font will change the doc's CSS as myEditorPane has JEditorPane.HONOR_DISPLAY_PROPERTIES via UIUtil.getHTMLEditorKit
    setFont(UIUtil.getFontWithFallback(fontName, Font.PLAIN, JBUIScale.scale(size.getSize())));
  }

  private Object myHighlightedTag;

  private @Nullable HTMLDocument.Iterator getLink(int n) {
    if (n >= 0) {
      HTMLDocument document = (HTMLDocument)getDocument();
      int linkCount = 0;
      for (HTMLDocument.Iterator it = document.getIterator(HTML.Tag.A); it.isValid(); it.next()) {
        if (it.getAttributes().isDefined(HTML.Attribute.HREF) && linkCount++ == n) {
          return it;
        }
      }
    }
    return null;
  }

  void highlightLink(int n) {
    Highlighter highlighter = getHighlighter();
    HTMLDocument.Iterator link = getLink(n);
    if (link != null) {
      int startOffset = link.getStartOffset();
      int endOffset = link.getEndOffset();
      try {
        if (myHighlightedTag == null) {
          myHighlightedTag = highlighter.addHighlight(startOffset, endOffset, DocumentationLinkHighlightPainter.INSTANCE);
        }
        else {
          highlighter.changeHighlight(myHighlightedTag, startOffset, endOffset);
        }
        setCaretPosition(startOffset);
        if (!ScreenReader.isActive()) {
          // scrolling to target location explicitly, as we've disabled auto-scrolling to caret
          scrollRectToVisible(modelToView(startOffset));
        }
      }
      catch (BadLocationException e) {
        DocumentationManager.LOG.warn("Error highlighting link", e);
      }
    }
    else if (myHighlightedTag != null) {
      highlighter.removeHighlight(myHighlightedTag);
      myHighlightedTag = null;
    }
  }

  @Nullable String getLinkHref(int n) {
    HTMLDocument.Iterator link = getLink(n);
    return link != null
           ? (String)link.getAttributes().getAttribute(HTML.Attribute.HREF)
           : null;
  }

  int getLinkCount() {
    HTMLDocument document = (HTMLDocument)getDocument();
    int linkCount = 0;
    for (HTMLDocument.Iterator it = document.getIterator(HTML.Tag.A); it.isValid(); it.next()) {
      if (it.getAttributes().isDefined(HTML.Attribute.HREF)) linkCount++;
    }
    return linkCount;
  }
}

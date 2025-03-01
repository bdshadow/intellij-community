// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vcs.readOnlyHandler;

import com.intellij.ide.presentation.VirtualFilePresentation;
import com.intellij.navigation.TargetPresentation;
import com.intellij.navigation.TargetPresentationBuilder;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.list.TargetPopup;

import javax.swing.*;
import java.awt.*;

public class FileListRenderer implements ListCellRenderer<VirtualFile> {
  private final ListCellRenderer<VirtualFile> myPresentationRenderer;

  public FileListRenderer() {
    myPresentationRenderer = TargetPopup.createTargetPresentationRenderer((vf) -> {
      TargetPresentationBuilder builder = TargetPresentation.builder(vf.getPresentableName())
        .icon(VirtualFilePresentation.getIcon(vf))
        .presentableText(vf.getPresentableName());
      VirtualFile vfParent = vf.getParent();
      if (vfParent != null) builder = builder.locationText(vfParent.getPresentableUrl());
      return builder.presentation();
    });
  }

  @Override
  public Component getListCellRendererComponent(JList<? extends VirtualFile> list,
                                                VirtualFile value,
                                                int index,
                                                boolean isSelected,
                                                boolean cellHasFocus) {
    return myPresentationRenderer.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
  }
}
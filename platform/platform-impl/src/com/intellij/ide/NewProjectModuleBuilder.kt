// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.module.ModuleType
import com.intellij.ui.UIBundle
import javax.swing.Icon

class NewProjectModuleBuilder : NewWizardModuleBuilder() {
  override fun createStep(context: WizardContext) = NewProjectStep(context)

  override fun getModuleType(): ModuleType<*> = NewProjectModuleType.INSTANCE
  override fun getGroupName(): String = DEFAULT_GROUP
  override fun getNodeIcon(): Icon? = null

  override fun getPresentableName() = UIBundle.message("list.item.new.project")

  companion object {
    const val DEFAULT_GROUP = "Default"
    const val GENERATORS = "Generators"
  }
}

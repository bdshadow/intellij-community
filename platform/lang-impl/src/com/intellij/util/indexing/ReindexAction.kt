// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.util.indexing

import com.intellij.ide.actions.cache.CacheInconsistencyProblem
import com.intellij.ide.actions.cache.RecoveryAction
import com.intellij.lang.LangBundle
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.project.DumbUtilImpl
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.Nls

internal class ReindexAction : RecoveryAction {
  override val performanceRate: Int
    get() = 1000
  override val presentableName: @Nls(capitalization = Nls.Capitalization.Title) String
    get() = LangBundle.message("reindex.project.recovery.action.name")
  override val actionKey: String
    get() = "reindex"

  override fun performSync(project: Project): List<CacheInconsistencyProblem> {
    invokeAndWaitIfNeeded {
      val tumbler = FileBasedIndexTumbler()
      tumbler.turnOff()
      try {
        CorruptionMarker.requestInvalidation()
      }
      finally {
        tumbler.turnOn(reason = "Reindex recovery action")
      }
    }
    DumbUtilImpl.waitForSmartMode(project)

    return emptyList()
  }
}
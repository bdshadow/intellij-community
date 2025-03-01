// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.kotlin.idea.gradle.configuration

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.AbstractExternalEntityData
import com.intellij.openapi.externalSystem.model.project.AbstractNamedData
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.serialization.PropertyMapping
import org.jetbrains.kotlin.gradle.ArgsInfo
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.Serializable

interface ImplementedModulesAware : Serializable {
    var implementedModuleNames: List<String>
}

class KotlinGradleProjectData : AbstractExternalEntityData(GradleConstants.SYSTEM_ID), ImplementedModulesAware {
    var isResolved: Boolean = false
    var kotlinTarget: String? = null
    var hasKotlinPlugin: Boolean = false
    var coroutines: String? = null
    var isHmpp: Boolean = false
    var platformPluginId: String? = null
    lateinit var kotlinNativeHome: String
    override var implementedModuleNames: List<String> = emptyList()

    @Transient
    val dependenciesCache: MutableMap<DataNode<ProjectData>, Collection<DataNode<out ModuleData>>> = mutableMapOf()
    val pureKotlinSourceFolders: MutableCollection<String> = hashSetOf()

    companion object {
        val KEY = Key.create(KotlinGradleProjectData::class.java, ProjectKeys.MODULE.processingWeight + 1)
    }
}

internal val DataNode<GradleSourceSetData>.kotlinGradleSourceSetDataOrFail: KotlinGradleSourceSetData
    get() = kotlinGradleSourceSetDataOrNull ?: error("Failed to determine KotlinGradleSourceSetData for $this")

internal val DataNode<GradleSourceSetData>.kotlinGradleSourceSetDataOrNull: KotlinGradleSourceSetData?
    get() = ExternalSystemApiUtil.getChildren(this, KotlinGradleSourceSetData.KEY).singleOrNull()?.data

class KotlinGradleSourceSetData @PropertyMapping("externalName") constructor(externalName: String) :
    AbstractNamedData(GradleConstants.SYSTEM_ID, externalName), ImplementedModulesAware {
    val sourceSetName: String
        get() = externalName.substringAfterLast(":")

    lateinit var compilerArguments: ArgsInfo
    lateinit var additionalVisibleSourceSets: Set<String>
    override var implementedModuleNames: List<String> = emptyList()

    companion object {
        val KEY = Key.create(KotlinGradleSourceSetData::class.java, KotlinGradleProjectData.KEY.processingWeight + 1)
    }
}


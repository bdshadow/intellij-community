// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.gradle.configuration

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider
import com.intellij.openapi.externalSystem.service.project.manage.AbstractProjectDataService
import com.intellij.openapi.externalSystem.service.project.manage.SourceFolderManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ExportableOrderEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.KotlinModuleKind
import org.jetbrains.kotlin.config.SourceKotlinRootType
import org.jetbrains.kotlin.config.TestSourceKotlinRootType
import org.jetbrains.kotlin.gradle.KotlinCompilation
import org.jetbrains.kotlin.gradle.KotlinModule
import org.jetbrains.kotlin.gradle.KotlinPlatform
import org.jetbrains.kotlin.gradle.KotlinSourceSet
import org.jetbrains.kotlin.idea.facet.*
import org.jetbrains.kotlin.idea.gradle.KotlinGradleFacadeImpl
import org.jetbrains.kotlin.idea.platform.IdePlatformKindTooling
import org.jetbrains.kotlin.idea.roots.migrateNonJvmSourceFolders
import org.jetbrains.kotlin.idea.roots.pathAsUrl
import org.jetbrains.kotlin.platform.IdePlatformKind
import org.jetbrains.kotlin.platform.SimplePlatform
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.impl.JvmIdePlatformKind
import org.jetbrains.kotlin.platform.impl.NativeIdePlatformKind
import org.jetbrains.kotlin.platform.js.JsPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.platform.konan.NativePlatform
import org.jetbrains.kotlin.platform.konan.NativePlatforms
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData
import org.jetbrains.plugins.gradle.util.GradleConstants

class KotlinSourceSetDataService : AbstractProjectDataService<GradleSourceSetData, Void>() {
    override fun getTargetDataKey() = GradleSourceSetData.KEY

    private fun getProjectPlatforms(toImport: MutableCollection<out DataNode<GradleSourceSetData>>): List<KotlinPlatform> {
        val platforms = HashSet<KotlinPlatform>()

        for (nodeToImport in toImport) {
            nodeToImport.kotlinSourceSetData?.sourceSetInfo?.also {
                platforms += it.actualPlatforms.platforms
            }

            if (nodeToImport.parent?.children?.any { it.key.dataType.contains("Android") } == true) {
                platforms += KotlinPlatform.ANDROID
            }
        }

        return platforms.toList()
    }

    override fun postProcess(
        toImport: MutableCollection<out DataNode<GradleSourceSetData>>,
        projectData: ProjectData?,
        project: Project,
        modelsProvider: IdeModifiableModelsProvider
    ) {
        val projectPlatforms = getProjectPlatforms(toImport)

        for (nodeToImport in toImport) {
            val mainModuleData = ExternalSystemApiUtil.findParent(
                nodeToImport,
                ProjectKeys.MODULE
            ) ?: continue
            val sourceSetData = nodeToImport.data
            val kotlinSourceSet = nodeToImport.kotlinSourceSetData?.sourceSetInfo ?: continue
            val ideModule = modelsProvider.findIdeModule(sourceSetData) ?: continue
            val platform = kotlinSourceSet.actualPlatforms
            val rootModel = modelsProvider.getModifiableRootModel(ideModule)

            if (platform.platforms.any { it != KotlinPlatform.JVM && it != KotlinPlatform.ANDROID }) {
                migrateNonJvmSourceFolders(rootModel)
                populateNonJvmSourceRootTypes(nodeToImport, ideModule)
            }

            configureFacet(sourceSetData, kotlinSourceSet, mainModuleData, ideModule, modelsProvider, projectPlatforms)?.let { facet ->
                GradleProjectImportHandler.getInstances(project).forEach { it.importBySourceSet(facet, nodeToImport) }
            }

            if (kotlinSourceSet.isTestModule) {
                assignTestScope(rootModel)
            }
        }
    }

    private fun assignTestScope(rootModel: ModifiableRootModel) {
        rootModel
            .orderEntries
            .asSequence()
            .filterIsInstance<ExportableOrderEntry>()
            .filter { it.scope == DependencyScope.COMPILE }
            .forEach { it.scope = DependencyScope.TEST }
    }

    companion object {
        private val KotlinModule.kind
            get() = when (this) {
                is KotlinCompilation -> KotlinModuleKind.COMPILATION_AND_SOURCE_SET_HOLDER
                is KotlinSourceSet -> KotlinModuleKind.SOURCE_SET_HOLDER
                else -> KotlinModuleKind.DEFAULT
            }

        private fun SimplePlatform.isRelevantFor(projectPlatforms: List<KotlinPlatform>): Boolean {
            val jvmPlatforms = listOf(KotlinPlatform.ANDROID, KotlinPlatform.JVM, KotlinPlatform.COMMON)
            return when (this) {
                is JvmPlatform -> projectPlatforms.intersect(jvmPlatforms).isNotEmpty()
                is JsPlatform -> KotlinPlatform.JS in projectPlatforms
                is NativePlatform -> KotlinPlatform.NATIVE in projectPlatforms
                else -> true
            }
        }

        private fun IdePlatformKind<*>.toSimplePlatforms(
            moduleData: ModuleData,
            isHmppModule: Boolean,
            projectPlatforms: List<KotlinPlatform>
        ): Collection<SimplePlatform> {
            if (this is JvmIdePlatformKind) {
                val jvmTarget = JvmTarget.fromString(moduleData.targetCompatibility ?: "") ?: JvmTarget.DEFAULT
                return JvmPlatforms.jvmPlatformByTargetVersion(jvmTarget)
            }

            if (this is NativeIdePlatformKind) {
                return NativePlatforms.nativePlatformByTargetNames(moduleData.konanTargets)
            }

            return if (isHmppModule) {
                this.defaultPlatform.filter { it.isRelevantFor(projectPlatforms) }
            } else {
                this.defaultPlatform
            }
        }

        fun configureFacet(
            moduleData: ModuleData,
            kotlinSourceSet: KotlinSourceSetInfo,
            mainModuleNode: DataNode<ModuleData>,
            ideModule: Module,
            modelsProvider: IdeModifiableModelsProvider
        ) = configureFacet(
            moduleData,
            kotlinSourceSet,
            mainModuleNode,
            ideModule,
            modelsProvider,
            enumValues<KotlinPlatform>().toList()
        )

        fun configureFacet(
            moduleData: ModuleData,
            kotlinSourceSet: KotlinSourceSetInfo,
            mainModuleNode: DataNode<ModuleData>,
            ideModule: Module,
            modelsProvider: IdeModifiableModelsProvider,
            projectPlatforms: List<KotlinPlatform>
        ): KotlinFacet? {

            val compilerVersion = KotlinGradleFacadeImpl.findKotlinPluginVersion(mainModuleNode)
                // ?: return null TODO: Fix in CLion or our plugin KT-27623

            val platformKinds = kotlinSourceSet.actualPlatforms.platforms //TODO(auskov): fix calculation of jvm target
                .map { IdePlatformKindTooling.getTooling(it).kind }
                .flatMap { it.toSimplePlatforms(moduleData, mainModuleNode.kotlinGradleProjectDataOrFail.isHmpp, projectPlatforms) }
                .distinct()
                .toSet()

            val platform = TargetPlatform(platformKinds)

            val compilerArguments = kotlinSourceSet.compilerArguments
            // Used ID is the same as used in org/jetbrains/kotlin/idea/configuration/KotlinGradleSourceSetDataService.kt:280
            // because this DataService was separated from KotlinGradleSourceSetDataService for MPP projects only
            val id = if (compilerArguments?.multiPlatform == true) GradleConstants.SYSTEM_ID.id else null
            val kotlinFacet = ideModule.getOrCreateFacet(modelsProvider, false, id)
            val kotlinGradleProjectData = mainModuleNode.kotlinGradleProjectDataOrFail
            kotlinFacet.configureFacet(
                compilerVersion = compilerVersion,
                platform = platform,
                modelsProvider = modelsProvider,
                hmppEnabled = kotlinGradleProjectData.isHmpp,
                pureKotlinSourceFolders = kotlinGradleProjectData.pureKotlinSourceFolders.toList(),
                dependsOnList = kotlinSourceSet.dependsOn,
                additionalVisibleModuleNames = kotlinSourceSet.additionalVisible
            )

            val defaultCompilerArguments = kotlinSourceSet.defaultCompilerArguments
            if (compilerArguments != null) {
                applyCompilerArgumentsToFacet(
                    compilerArguments,
                    defaultCompilerArguments,
                    kotlinFacet,
                    modelsProvider
                )
            }

            adjustClasspath(kotlinFacet, kotlinSourceSet.dependencyClasspath)

            kotlinFacet.noVersionAutoAdvance()

            with(kotlinFacet.configuration.settings) {
                kind = kotlinSourceSet.kotlinModule.kind

                isTestModule = kotlinSourceSet.isTestModule
                externalSystemRunTasks = ArrayList(kotlinSourceSet.externalSystemRunTasks)

                externalProjectId = kotlinSourceSet.gradleModuleId

                sourceSetNames = kotlinSourceSet.sourceSetIdsByName.values.mapNotNull { sourceSetId ->
                    val node = mainModuleNode.findChildModuleById(sourceSetId) ?: return@mapNotNull null
                    val data = node.data as? ModuleData ?: return@mapNotNull null
                    modelsProvider.findIdeModule(data)?.name
                }

                if (kotlinSourceSet.isTestModule) {
                    testOutputPath = (kotlinSourceSet.compilerArguments as? K2JSCompilerArguments)?.outputFile
                    productionOutputPath = null
                } else {
                    productionOutputPath = (kotlinSourceSet.compilerArguments as? K2JSCompilerArguments)?.outputFile
                    testOutputPath = null
                }

                this.pureKotlinSourceFolders = kotlinGradleProjectData.pureKotlinSourceFolders.toList()
            }

            return kotlinFacet
        }
    }
}

private const val KOTLIN_NATIVE_TARGETS_PROPERTY = "konanTargets"

var ModuleData.konanTargets: Set<String>
    get() {
        val value = getProperty(KOTLIN_NATIVE_TARGETS_PROPERTY) ?: return emptySet()
        return if (value.isNotEmpty()) value.split(',').toSet() else emptySet()
    }
    set(value) = setProperty(KOTLIN_NATIVE_TARGETS_PROPERTY, value.takeIf { it.isNotEmpty() }?.joinToString(","))

private fun populateNonJvmSourceRootTypes(sourceSetNode: DataNode<GradleSourceSetData>, module: Module) {
    val sourceFolderManager = SourceFolderManager.getInstance(module.project)
    val contentRootDataNodes = ExternalSystemApiUtil.findAll(sourceSetNode, ProjectKeys.CONTENT_ROOT)
    val contentRootDataList = contentRootDataNodes.mapNotNull { it.data }
    if (contentRootDataList.isEmpty()) return

    val externalToKotlinSourceTypes = mapOf(
        ExternalSystemSourceType.SOURCE to SourceKotlinRootType,
        ExternalSystemSourceType.TEST to TestSourceKotlinRootType
    )
    externalToKotlinSourceTypes.forEach { (externalType, kotlinType) ->
        val sourcesRoots = contentRootDataList.flatMap { it.getPaths(externalType) }
        sourcesRoots.forEach {
            if (!FileUtil.exists(it.path)) {
                sourceFolderManager.addSourceFolder(module, it.pathAsUrl, kotlinType)
            }
        }
    }
}
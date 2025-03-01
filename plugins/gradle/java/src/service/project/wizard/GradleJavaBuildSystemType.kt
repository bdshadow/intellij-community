// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.gradle.service.project.wizard

import com.intellij.ide.NewModuleStep
import com.intellij.ide.projectWizard.generators.JavaBuildSystemType
import com.intellij.ide.projectWizard.generators.JavaNewProjectWizard
import com.intellij.ide.util.projectWizard.ModuleBuilder
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardStepSettings
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.model.project.ProjectId
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManagerImpl
import com.intellij.openapi.externalSystem.util.ExternalSystemBundle
import com.intellij.openapi.externalSystem.util.ui.DataView
import com.intellij.openapi.module.Module
import com.intellij.openapi.observable.properties.GraphPropertyImpl.Companion.graphProperty
import com.intellij.openapi.observable.properties.map
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SortedComboBoxModel
import com.intellij.ui.layout.*
import com.intellij.util.io.systemIndependentPath
import com.intellij.util.lang.JavaVersion
import icons.GradleIcons
import org.gradle.util.GradleVersion
import org.jetbrains.plugins.gradle.service.project.wizard.AbstractGradleModuleBuilder.getBuildScriptData
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.jetbrains.plugins.gradle.util.suggestGradleVersion
import java.util.function.Function
import javax.swing.Icon
import javax.swing.JList
import javax.swing.ListCellRenderer


class GradleJavaBuildSystemType : JavaBuildSystemType {
  override val name = "Gradle"

  override fun createStep(context: WizardContext) = Step(context)

  class Step(private val context: WizardContext) : NewProjectWizardStep<Settings> {
    override val settings = Settings(context)

    override fun setupUI(builder: RowBuilder) {
      with(builder) {
        if (!context.isCreatingNewProject) {
          row(ExternalSystemBundle.message("external.system.mavenized.structure.wizard.parent.label")) {
            val presentationName = Function<DataView<ProjectData>, String> { it.presentationName }
            val parentComboBoxModel = SortedComboBoxModel(Comparator.comparing(presentationName, String.CASE_INSENSITIVE_ORDER))
            parentComboBoxModel.add(Settings.EMPTY_VIEW)
            parentComboBoxModel.addAll(settings.parents)
            comboBox(parentComboBoxModel, settings.parentProperty, renderer = getParentRenderer())
          }.largeGapAfter()
        }
        hideableRow(ExternalSystemBundle.message("external.system.mavenized.structure.wizard.artifact.coordinates.title")) {
          row(ExternalSystemBundle.message("external.system.mavenized.structure.wizard.group.id.label")) {
            textField(settings.groupIdProperty)
          }
          row(ExternalSystemBundle.message("external.system.mavenized.structure.wizard.artifact.id.label")) {
            textField(settings.artifactIdProperty)
          }
          row(ExternalSystemBundle.message("external.system.mavenized.structure.wizard.version.label")) {
            textField(settings.versionProperty)
          }
        }.largeGapAfter()
      }
    }

    private fun getParentRenderer(): ListCellRenderer<DataView<ProjectData>?> {
      return object : SimpleListCellRenderer<DataView<ProjectData>?>() {
        override fun customize(list: JList<out DataView<ProjectData>?>,
                               value: DataView<ProjectData>?,
                               index: Int,
                               selected: Boolean,
                               hasFocus: Boolean) {
          val view = value ?: Settings.EMPTY_VIEW
          text = view.presentationName
          icon = DataView.getIcon(view)
        }
      }
    }

    override fun setupProject(project: Project) {
      val builder = InternalGradleModuleBuilder().apply {
        isCreatingNewProject = true
        moduleJdk = JavaNewProjectWizard.Settings.getSdk(context)

        parentProject = settings.parentData
        projectId = ProjectId(settings.groupId, settings.artifactId, settings.version)
        isInheritGroupId = settings.parentData?.group == settings.groupId
        isInheritVersion = settings.parentData?.version == settings.version

        isUseKotlinDsl = false

        gradleVersion = suggestGradleVersion(context) ?: GradleVersion.current()
      }

      builder.addModuleConfigurationUpdater(object : ModuleBuilder.ModuleConfigurationUpdater() {
        override fun update(module: Module, rootModel: ModifiableRootModel) {
          getBuildScriptData(module)
            ?.withJavaPlugin()
            ?.withJUnit()
        }
      })

      ExternalProjectsManagerImpl.setupCreatedProject(project)
      builder.commit(project)
    }

    private fun suggestGradleVersion(context: WizardContext): GradleVersion? {
      val jdk = JavaNewProjectWizard.Settings.getSdk(context) ?: return null
      val versionString = jdk.versionString ?: return null
      val javaVersion = JavaVersion.tryParse(versionString) ?: return null
      return suggestGradleVersion(javaVersion)
    }
  }

  class Settings(private val context: WizardContext) : NewProjectWizardStepSettings<Settings>(KEY, context) {
    val parentProperty = propertyGraph.graphProperty(::suggestParentByPath)
    val groupIdProperty = propertyGraph.graphProperty(::suggestGroupIdByParent)
    val artifactIdProperty = propertyGraph.graphProperty(::suggestArtifactIdByName)
    val versionProperty = propertyGraph.graphProperty(::suggestVersionByParent)

    var parent by parentProperty
    var groupId by groupIdProperty.map { it.trim() }
    var artifactId by artifactIdProperty.map { it.trim() }
    var version by versionProperty.map { it.trim() }

    val parents by lazy { parentsData.map(::GradleDataView) }
    val parentsData by lazy { findAllParents() }
    var parentData: ProjectData?
      get() = DataView.getData(parent)
      set(value) {
        parent = if (value == null) EMPTY_VIEW else GradleDataView(value)
      }

    private fun findAllParents(): List<ProjectData> {
      val project = context.project ?: return emptyList()
      return ProjectDataManager.getInstance()
        .getExternalProjectsData(project, GradleConstants.SYSTEM_ID)
        .mapNotNull { it.externalProjectStructure }
        .map { it.data }
    }

    private fun suggestParentByPath(): DataView<ProjectData> {
      val path = NewModuleStep.Settings.getPath(context)
      return parents.find { FileUtil.isAncestor(it.location, path.systemIndependentPath, true) } ?: EMPTY_VIEW
    }

    private fun suggestGroupIdByParent(): String {
      return parent.groupId
    }

    private fun suggestArtifactIdByName(): String {
      return NewModuleStep.Settings.getName(context)
    }

    private fun suggestVersionByParent(): String {
      return parent.version
    }

    private fun suggestNameByArtifactId(): String {
      return artifactId
    }

    private fun suggestLocationByParent(): String {
      return if (parent.isPresent) parent.location else context.projectFileDirectory
    }

    init {
      val nameProperty = NewModuleStep.Settings.getNameProperty(context)
      val pathProperty = NewModuleStep.Settings.getPathProperty(context)
      nameProperty.dependsOn(artifactIdProperty, ::suggestNameByArtifactId)
      parentProperty.dependsOn(pathProperty, ::suggestParentByPath)
      pathProperty.dependsOn(parentProperty, ::suggestLocationByParent)
      groupIdProperty.dependsOn(parentProperty, ::suggestGroupIdByParent)
      artifactIdProperty.dependsOn(nameProperty, ::suggestArtifactIdByName)
      versionProperty.dependsOn(parentProperty, ::suggestVersionByParent)
    }

    class GradleDataView(override val data: ProjectData) : DataView<ProjectData>() {
      override val location: String = data.linkedExternalProjectPath
      override val icon: Icon = GradleIcons.GradleFile
      override val presentationName: String = data.externalName
      override val groupId: String = data.group ?: ""
      override val version: String = data.version ?: ""
    }

    companion object {
      val KEY = Key.create<Settings>(Settings::class.java.name)

      val EMPTY_VIEW = object : DataView<Nothing>() {
        override val data: Nothing get() = throw UnsupportedOperationException()
        override val location: String = ""
        override val icon: Nothing get() = throw UnsupportedOperationException()
        override val presentationName: String = "<None>"
        override val groupId: String = "org.example"
        override val version: String = "1.0-SNAPSHOT"

        override val isPresent: Boolean = false
      }
    }
  }
}

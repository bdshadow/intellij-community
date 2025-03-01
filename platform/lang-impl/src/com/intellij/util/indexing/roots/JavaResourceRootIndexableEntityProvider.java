// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.util.indexing.roots;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.pointers.VirtualFilePointer;
import com.intellij.workspaceModel.storage.WorkspaceEntityStorage;
import com.intellij.workspaceModel.storage.bridgeEntities.JavaResourceRootEntity;
import com.intellij.workspaceModel.storage.bridgeEntities.ModuleEntity;
import com.intellij.workspaceModel.storage.bridgeEntities.SourceRootEntity;
import kotlin.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;

import static com.intellij.util.indexing.roots.JavaSourceRootIndexableEntityProvider.collectIteratorsOnAddedEntityWithDataExtractor;
import static com.intellij.util.indexing.roots.JavaSourceRootIndexableEntityProvider.collectIteratorsOnReplacedEntityWithDataExtractor;

class JavaResourceRootIndexableEntityProvider implements IndexableEntityProvider<JavaResourceRootEntity> {
  @Override
  public @NotNull Class<JavaResourceRootEntity> getEntityClass() {
    return JavaResourceRootEntity.class;
  }

  @Override
  public @NotNull Collection<? extends IndexableFilesIterator> getExistingEntityForModuleIterator(@NotNull JavaResourceRootEntity entity,
                                                                                                  @NotNull ModuleEntity moduleEntity,
                                                                                                  @NotNull WorkspaceEntityStorage entityStorage,
                                                                                                  @NotNull Project project) {
    if (moduleEntity.equals(entity.getSourceRoot().getContentRoot().getModule())) {
      return getExistingEntityIterator(entity, entityStorage, project);
    }
    return Collections.emptyList();
  }

  @Override
  public @NotNull Collection<? extends IndexableFilesIterator> getAddedEntityIterator(@NotNull JavaResourceRootEntity entity,
                                                                                      @NotNull WorkspaceEntityStorage storage,
                                                                                      @NotNull Project project) {
    return collectIteratorsOnAddedEntityWithDataExtractor(entity, JavaResourceRootIndexableEntityProvider::getDataToIndex, project);
  }

  @Override
  public @NotNull Collection<? extends IndexableFilesIterator> getReplacedEntityIterator(@NotNull JavaResourceRootEntity oldEntity,
                                                                                         @NotNull JavaResourceRootEntity newEntity,
                                                                                         @NotNull WorkspaceEntityStorage storage,
                                                                                         @NotNull Project project) {
    return collectIteratorsOnReplacedEntityWithDataExtractor(oldEntity, newEntity, JavaResourceRootIndexableEntityProvider::getDataToIndex,
                                                             project);
  }


  @Nullable
  private static Pair<VirtualFile, ModuleEntity> getDataToIndex(@NotNull JavaResourceRootEntity entity) {
    SourceRootEntity sourceRootEntity = entity.getSourceRoot();
    VirtualFilePointer url = (VirtualFilePointer)sourceRootEntity.getUrl();
    if (url.isValid()) {
      return new Pair<>(url.getFile(), sourceRootEntity.getContentRoot().getModule());
    }
    return null;
  }
}

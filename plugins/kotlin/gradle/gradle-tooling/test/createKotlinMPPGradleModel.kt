// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.kotlin.gradle

internal fun createKotlinMPPGradleModel(
    dependencyMap: Map<KotlinDependencyId, KotlinDependency> = emptyMap(),
    sourceSets: Set<KotlinSourceSet> = emptySet(),
    targets: Iterable<KotlinTarget> = emptyList(),
    extraFeatures: ExtraFeatures = createExtraFeatures(),
    kotlinNativeHome: String = ""

): KotlinMPPGradleModelImpl {
    return KotlinMPPGradleModelImpl(
        dependencyMap = dependencyMap,
        sourceSetsByName = sourceSets.associateBy { it.name },
        targets = targets.toList(),
        extraFeatures = extraFeatures,
        kotlinNativeHome = kotlinNativeHome
    )
}

internal fun createExtraFeatures(
    coroutinesState: String? = null,
    isHmppEnabled: Boolean = false,
    isNativeDependencyPropagationEnabled: Boolean = false
): ExtraFeaturesImpl {
    return ExtraFeaturesImpl(
        coroutinesState = coroutinesState,
        isHMPPEnabled = isHmppEnabled,
        isNativeDependencyPropagationEnabled = isNativeDependencyPropagationEnabled
    )
}

internal fun createKotlinSourceSet(
    name: String,
    declaredDependsOnSourceSets: Set<String> = emptySet(),
    allDependsOnSourceSets: Set<String> = declaredDependsOnSourceSets,
    platforms: Set<KotlinPlatform> = emptySet(),
): KotlinSourceSetImpl = KotlinSourceSetImpl(
    name = name,
    languageSettings = KotlinLanguageSettingsImpl(
        languageVersion = null,
        apiVersion = null,
        isProgressiveMode = false,
        enabledLanguageFeatures = emptySet(),
        optInAnnotationsInUse = emptySet(),
        compilerPluginArguments = emptyArray(),
        compilerPluginClasspath = emptySet(),
        freeCompilerArgs = emptyArray()
    ),
    sourceDirs = emptySet(),
    resourceDirs = emptySet(),
    regularDependencies = emptyArray(),
    intransitiveDependencies = emptyArray(),
    declaredDependsOnSourceSets = declaredDependsOnSourceSets,
    allDependsOnSourceSets = allDependsOnSourceSets,
    additionalVisibleSourceSets = emptySet(),
    defaultActualPlatforms = KotlinPlatformContainerImpl().apply { pushPlatforms(platforms) },
)

internal fun createKotlinCompilation(
    name: String = "main",
    defaultSourceSets: Set<KotlinSourceSet> = emptySet(),
    allSourceSets: Set<KotlinSourceSet> = emptySet(),
    dependencies: Iterable<KotlinDependencyId> = emptyList(),
    output: KotlinCompilationOutput = createKotlinCompilationOutput(),
    arguments: KotlinCompilationArguments = createKotlinCompilationArguments(),
    dependencyClasspath: Iterable<String> = emptyList(),
    kotlinTaskProperties: KotlinTaskProperties = createKotlinTaskProperties(),
    nativeExtensions: KotlinNativeCompilationExtensions? = null

): KotlinCompilationImpl {
    return KotlinCompilationImpl(
        name = name,
        declaredSourceSets = defaultSourceSets,
        allSourceSets = allSourceSets,
        dependencies = dependencies.toList().toTypedArray(),
        output = output,
        arguments = arguments,
        dependencyClasspath = dependencyClasspath.toList().toTypedArray(),
        kotlinTaskProperties = kotlinTaskProperties,
        nativeExtensions = nativeExtensions
    )
}

internal fun createKotlinCompilationOutput(): KotlinCompilationOutputImpl {
    return KotlinCompilationOutputImpl(
        classesDirs = emptySet(),
        effectiveClassesDir = null,
        resourcesDir = null
    )
}

internal fun createKotlinCompilationArguments(): KotlinCompilationArgumentsImpl {
    return KotlinCompilationArgumentsImpl(
        defaultArguments = emptyArray(),
        currentArguments = emptyArray()
    )
}

internal fun createKotlinTaskProperties(): KotlinTaskPropertiesImpl {
    return KotlinTaskPropertiesImpl(
        null, null, null, null
    )
}

internal fun createKotlinTarget(
    name: String,
    platform: KotlinPlatform = KotlinPlatform.COMMON,
    compilations: Iterable<KotlinCompilation> = emptyList()
): KotlinTargetImpl {
    return KotlinTargetImpl(
        name = name,
        presetName = null,
        disambiguationClassifier = null,
        platform = platform,
        compilations = compilations.toList(),
        testRunTasks = emptyList(),
        nativeMainRunTasks = emptyList(),
        jar = null,
        konanArtifacts = emptyList()
    )
}

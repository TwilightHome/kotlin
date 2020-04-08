/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script.configuration

import com.intellij.ProjectTopics
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElementFinder
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.EditorNotifications
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.idea.core.script.*
import org.jetbrains.kotlin.idea.core.script.ScriptConfigurationManager.Companion.toVfsRoots
import org.jetbrains.kotlin.idea.core.script.SpecialScriptConfigurationManagerProvider.Companion.SPECIAL_SCRIPT_CONFIGURATION_MANAGER_PROVIDER
import org.jetbrains.kotlin.idea.core.script.configuration.cache.*
import org.jetbrains.kotlin.idea.core.script.configuration.listener.ScriptConfigurationUpdater
import org.jetbrains.kotlin.idea.core.script.configuration.loader.ScriptConfigurationLoader
import org.jetbrains.kotlin.idea.core.script.configuration.loader.ScriptConfigurationLoadingContext
import org.jetbrains.kotlin.idea.core.script.configuration.loader.ScriptOutsiderFileConfigurationLoader
import org.jetbrains.kotlin.idea.core.script.configuration.utils.ScriptClassRootsCache
import org.jetbrains.kotlin.idea.core.script.configuration.utils.ScriptClassRootsIndexer
import org.jetbrains.kotlin.idea.core.script.configuration.utils.getKtFile
import org.jetbrains.kotlin.idea.core.script.settings.KotlinScriptingSettings
import org.jetbrains.kotlin.idea.core.util.EDT
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.findScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.isNonScript
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationWrapper
import org.jetbrains.kotlin.scripting.resolve.ScriptReportSink
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Abstract [ScriptConfigurationManager] implementation based on [cache] and [reloadOutOfDateConfiguration].
 * Among this two methods concrete implementation should provide script changes listening (by calling [updater] on some event).
 *
 * Basically all requests routed to [cache]. If there is no entry in [cache] or it is considered out-of-date,
 * then [reloadOutOfDateConfiguration] will be called, which, in turn, should call [setAppliedConfiguration]
 * immediately or in some future  (e.g. after user will click "apply context" or/and configuration will
 * be calculated by some background thread).
 *
 * [classpathRoots] will be calculated lazily based on [cache]d configurations.
 * Every change in [cache] will invalidate [classpathRoots] cache.
 * Some internal state changes in [cache] may also invalidate [classpathRoots] by calling [clearClassRootsCaches]
 * (for example, when cache loaded from FS to memory)
 */
abstract class AbstractScriptConfigurationManager(
    protected val project: Project
) : ScriptConfigurationManager {
    protected val rootsIndexer = ScriptClassRootsIndexer(project)

    private val outsiderLoader = ScriptOutsiderFileConfigurationLoader(project)
    private val fileAttributeCache = ScriptConfigurationFileAttributeCache(project)

    private val defaultLoaders = listOf(outsiderLoader, fileAttributeCache)

    protected val cache: ScriptConfigurationCache = object : ScriptConfigurationMemoryCache(project) {
        override fun setLoaded(file: VirtualFile, configurationSnapshot: ScriptConfigurationSnapshot) {
            super.setLoaded(file, configurationSnapshot)
            fileAttributeCache.save(file, configurationSnapshot)
        }
    }

    /**
     * Will be called on [cache] miss or when [file] is changed.
     * Implementation should initiate loading of [file]'s script configuration and call [setAppliedConfiguration]
     * immediately or in some future
     * (e.g. after user will click "apply context" or/and configuration will be calculated by some background thread).
     *
     * @param isFirstLoad may be set explicitly for optimization reasons (to avoid expensive fs cache access)
     * @param loadEvenWillNotBeApplied may should be set to false only on requests from particular editor, when
     * user can see potential notification and accept new configuration. In other cases this should be `false` since
     * loaded configuration will be just leaved in hidden user notification and cannot be used in any way.
     * @param forceSync should be used in tests only
     * @param isPostponedLoad is used to postspone loading: show a notification for out of date script and start loading when user request
     */
    protected abstract fun reloadOutOfDateConfiguration(
        file: KtFile,
        virtualFile: VirtualFile,
        definition: ScriptDefinition,
        forceSync: Boolean, /* test only */
        postponeLoading: Boolean
    )

    protected abstract val loadingContext: ScriptConfigurationLoadingContext

    private fun reloadOutOfDateConfiguration(
        file: KtFile,
        isFirstLoad: Boolean = getAppliedConfiguration(file.originalFile.virtualFile) == null,
        loadEvenWillNotBeApplied: Boolean = false,
        forceSync: Boolean = false,
        isPostponedLoad: Boolean = false
    ) {
        val virtualFile = file.originalFile.virtualFile ?: return

        val autoReloadEnabled = KotlinScriptingSettings.getInstance(project).isAutoReloadEnabled
        val shouldLoad = isFirstLoad || loadEvenWillNotBeApplied || autoReloadEnabled
        if (!shouldLoad) return

        val postponeLoading = isPostponedLoad && !autoReloadEnabled && !isFirstLoad

        if (!ScriptDefinitionsManager.getInstance(project).isReady()) return
        val scriptDefinition = file.findScriptDefinition() ?: return

        val syncLoader = defaultLoaders.firstOrNull { it.loadDependencies(isFirstLoad, file, scriptDefinition, loadingContext) }
        if (syncLoader == null) {
            val specialManager = SPECIAL_SCRIPT_CONFIGURATION_MANAGER_PROVIDER.getPoint(project).extensionList.firstNotNullResult {
                it.getSpecialScriptConfigurationManager(file)
            }
            if (specialManager != null) {
                specialManager.reloadOutOfDateConfiguration(file, isFirstLoad, loadEvenWillNotBeApplied, forceSync, isPostponedLoad)
            } else {
                reloadOutOfDateConfiguration(file, virtualFile, scriptDefinition, forceSync, postponeLoading)
            }
        }
    }

    /**
     * Will be called on user action
     * Load configuration even it is already cached or inputs are up-to-date
     *
     * @param loader is used to load configuration. Other loaders aren't taken into account.
     */
    protected abstract fun forceReloadConfiguration(
        file: KtFile,
        loader: ScriptConfigurationLoader
    )

    @Deprecated("Use getScriptClasspath(KtFile) instead")
    override fun getScriptClasspath(file: VirtualFile): List<VirtualFile> {
        val ktFile = project.getKtFile(file) ?: return emptyList()
        return getScriptClasspath(ktFile)
    }

    override fun getScriptClasspath(file: KtFile): List<VirtualFile> =
        toVfsRoots(getConfiguration(file)?.dependenciesClassPath.orEmpty())

    fun getCachedConfigurationState(file: VirtualFile?): ScriptConfigurationState? {
        if (file == null) return null
        return cache[file]
    }

    fun getAppliedConfiguration(file: VirtualFile?): ScriptConfigurationSnapshot? =
        getCachedConfigurationState(file)?.applied

    override fun hasConfiguration(file: KtFile): Boolean {
        return getAppliedConfiguration(file.originalFile.virtualFile) != null
    }

    override fun getConfiguration(file: KtFile): ScriptCompilationConfigurationWrapper? {
        return getConfiguration(file.originalFile.virtualFile, file)
    }

    fun getConfiguration(
        virtualFile: VirtualFile,
        preloadedKtFile: KtFile? = null
    ): ScriptCompilationConfigurationWrapper? {
        val cached = getAppliedConfiguration(virtualFile)
        if (cached != null) return cached.configuration

        val ktFile = project.getKtFile(virtualFile, preloadedKtFile) ?: return null
        rootsIndexer.transaction {
            reloadOutOfDateConfiguration(ktFile, isFirstLoad = true)
        }

        return getAppliedConfiguration(virtualFile)?.configuration
    }

    override fun forceReloadConfiguration(
        file: VirtualFile,
        loader: ScriptConfigurationLoader
    ): ScriptCompilationConfigurationWrapper? {
        val ktFile = project.getKtFile(file, null) ?: return null

        rootsIndexer.transaction {
            forceReloadConfiguration(ktFile, loader)
        }

        return getAppliedConfiguration(file)?.configuration
    }

    override val updater: ScriptConfigurationUpdater = object : ScriptConfigurationUpdater {
        override fun ensureUpToDatedConfigurationSuggested(file: KtFile) {
            reloadIfOutOfDate(listOf(file), loadEvenWillNotBeApplied = true, isPostponedLoad = false)
        }

        override fun ensureConfigurationUpToDate(files: List<KtFile>): Boolean {
            return reloadIfOutOfDate(files, loadEvenWillNotBeApplied = false, isPostponedLoad = false)
        }

        override fun postponeConfigurationReload(scope: ScriptConfigurationCacheScope) {
            cache.markOutOfDate(scope)
        }

        override fun suggestToUpdateConfigurationIfOutOfDate(file: KtFile) {
            reloadIfOutOfDate(listOf(file), loadEvenWillNotBeApplied = true, isPostponedLoad = true)
        }
    }

    private fun reloadIfOutOfDate(files: List<KtFile>, loadEvenWillNotBeApplied: Boolean, isPostponedLoad: Boolean): Boolean {
        if (!ScriptDefinitionsManager.getInstance(project).isReady()) return false

        var upToDate = true
        rootsIndexer.transaction {
            files.forEach { file ->
                val virtualFile = file.originalFile.virtualFile
                if (virtualFile != null) {
                    val state = cache[virtualFile]
                    if (state == null || !state.isUpToDate(project, virtualFile, file)) {
                        upToDate = false
                        reloadOutOfDateConfiguration(
                            file,
                            isFirstLoad = state == null,
                            loadEvenWillNotBeApplied = loadEvenWillNotBeApplied,
                            isPostponedLoad = isPostponedLoad
                        )
                    }
                }
            }
        }

        return upToDate
    }

    @TestOnly
    internal fun updateScriptDependenciesSynchronously(file: PsiFile) {
        file.findScriptDefinition() ?: return

        file as? KtFile ?: error("PsiFile $file should be a KtFile, otherwise script dependencies cannot be loaded")

        val virtualFile = file.virtualFile
        if (cache[virtualFile]?.isUpToDate(project, virtualFile, file) == true) return

        rootsIndexer.transaction {
            reloadOutOfDateConfiguration(file, forceSync = true, loadEvenWillNotBeApplied = true)
        }
    }

    protected open fun setAppliedConfiguration(
        file: VirtualFile,
        newConfigurationSnapshot: ScriptConfigurationSnapshot?
    ) {
        rootsIndexer.checkInTransaction()
        val newConfiguration = newConfigurationSnapshot?.configuration
        debug(file) { "configuration changed = $newConfiguration" }

        if (newConfiguration != null) {
            if (hasNotCachedRoots(newConfiguration)) {
                rootsIndexer.markNewRoot(file, newConfiguration)
            }

            cache.setApplied(file, newConfigurationSnapshot)

            clearClassRootsCaches()
        }

        updateHighlighting(listOf(file))
    }

    protected fun setLoadedConfiguration(
        file: VirtualFile,
        configurationSnapshot: ScriptConfigurationSnapshot
    ) {
        cache.setLoaded(file, configurationSnapshot)
    }

    protected fun saveReports(
        file: VirtualFile,
        newReports: List<ScriptDiagnostic>
    ) {
        val oldReports = IdeScriptReportSink.getReports(file)
        if (oldReports != newReports) {
            debug(file) { "new script reports = $newReports" }

            ServiceManager.getService(project, ScriptReportSink::class.java).attachReports(file, newReports)

            GlobalScope.launch(EDT(project)) {
                if (project.isDisposed) return@launch

                val ktFile = PsiManager.getInstance(project).findFile(file)
                if (ktFile != null) {
                    DaemonCodeAnalyzer.getInstance(project).restart(ktFile)
                }
                EditorNotifications.getInstance(project).updateAllNotifications()
            }
        }
    }

    private fun hasNotCachedRoots(configuration: ScriptCompilationConfigurationWrapper): Boolean {
        return classpathRoots.hasNotCachedRoots(configuration)
    }

    init {
        val connection = project.messageBus.connect(project)
        connection.subscribe(ProjectTopics.PROJECT_ROOTS, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) {
                clearClassRootsCaches()
            }
        })
    }

    /**
     * Clear configuration caches
     * Start re-highlighting for opened scripts
     */
    override fun clearConfigurationCachesAndRehighlight() {
        ScriptDependenciesModificationTracker.getInstance(project).incModificationCount()

        // todo: cache.clear()
        clearClassRootsCaches()

        if (project.isOpen) {
            val openedScripts = FileEditorManager.getInstance(project).openFiles.filterNot { it.isNonScript() }
            updateHighlighting(openedScripts)
        }
    }

    @TestOnly
    internal fun clearCaches() {
        cache.clear()
    }

    private fun updateHighlighting(files: List<VirtualFile>) {
        if (files.isEmpty()) return

        GlobalScope.launch(EDT(project)) {
            if (project.isDisposed) return@launch

            val openFiles = FileEditorManager.getInstance(project).openFiles
            val openScripts = files.filter { it.isValid && openFiles.contains(it) }

            openScripts.forEach {
                PsiManager.getInstance(project).findFile(it)?.let { psiFile ->
                    DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
                }
            }
        }
    }

    ///////////////////
    // ScriptRootsCache

    private val classpathRootsLock = ReentrantLock()

    @Volatile
    private var _classpathRoots: ScriptClassRootsCache? = null
    private val classpathRoots: ScriptClassRootsCache
        get() {
            val value1 = _classpathRoots
            if (value1 != null) return value1

            classpathRootsLock.withLock {
                val value2 = _classpathRoots
                if (value2 != null) return value2

                val value3 = ScriptClassRootsCache(project, cache.allApplied())
                _classpathRoots = value3
                return value3
            }
        }

    private fun clearClassRootsCaches() {
        debug { "class roots caches cleared" }

        classpathRootsLock.withLock {
            _classpathRoots = null
        }

        val kotlinScriptDependenciesClassFinder =
            Extensions.getArea(project).getExtensionPoint(PsiElementFinder.EP_NAME).extensions
                .filterIsInstance<KotlinScriptDependenciesClassFinder>()
                .single()

        kotlinScriptDependenciesClassFinder.clearCache()

        ScriptDependenciesModificationTracker.getInstance(project).incModificationCount()
    }

    /**
     * Returns script classpath roots
     * Loads script configuration if classpath roots don't contain [file] yet
     */
    private fun getActualClasspathRoots(file: VirtualFile): ScriptClassRootsCache {
        if (classpathRoots.contains(file)) {
            return classpathRoots
        }

        getConfiguration(file)
        return classpathRoots
    }

    override fun getScriptSdk(file: VirtualFile): Sdk? = getActualClasspathRoots(file).getScriptSdk(file)

    override fun getFirstScriptsSdk(): Sdk? = classpathRoots.firstScriptSdk

    override fun getScriptDependenciesClassFilesScope(file: VirtualFile): GlobalSearchScope =
        getActualClasspathRoots(file).getScriptDependenciesClassFilesScope(file)

    override fun getAllScriptsDependenciesClassFilesScope(): GlobalSearchScope = classpathRoots.allDependenciesClassFilesScope

    override fun getAllScriptDependenciesSourcesScope(): GlobalSearchScope = classpathRoots.allDependenciesSourcesScope

    override fun getAllScriptsDependenciesClassFiles(): List<VirtualFile> = classpathRoots.allDependenciesClassFiles

    override fun getAllScriptDependenciesSources(): List<VirtualFile> = classpathRoots.allDependenciesSources
}
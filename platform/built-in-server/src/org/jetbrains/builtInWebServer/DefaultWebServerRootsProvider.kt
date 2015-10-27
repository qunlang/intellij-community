/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.builtInWebServer

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.JavadocOrderRootType
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.impl.DirectoryIndex
import com.intellij.openapi.roots.impl.ModuleLibraryOrderEntryImpl
import com.intellij.openapi.roots.libraries.LibraryTable
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.PlatformUtils

private class DefaultWebServerRootsProvider : WebServerRootsProvider() {
  override fun resolve(path: String, project: Project): PathInfo? {
    var effectivePath = path
    if (PlatformUtils.isIntelliJ()) {
      val index = effectivePath.indexOf('/')
      if (index > 0 && !effectivePath.regionMatches(0, project.name, 0, index, !SystemInfo.isFileSystemCaseSensitive)) {
        val moduleName = effectivePath.substring(0, index)
        val module = runReadAction { ModuleManager.getInstance(project).findModuleByName(moduleName) }
        if (module != null && !module.isDisposed) {
          effectivePath = effectivePath.substring(index + 1)
          val resolver = WebServerPathToFileManager.getInstance(project).getResolver(effectivePath)
          val moduleRootManager = ModuleRootManager.getInstance(module)
          val result = RootProvider.values.computeOrNull { findByRelativePath(effectivePath, it.getRoots(moduleRootManager), resolver, moduleName) }
            ?: findInModuleLibraries(effectivePath, module, resolver)
          if (result != null) {
            return result
          }
        }
      }
    }

    val resolver = WebServerPathToFileManager.getInstance(project).getResolver(effectivePath)
    return RootProvider.values.computeOrNull { rootProvider ->
      runReadAction { ModuleManager.getInstance(project).modules }
        .computeOrNull { module ->
          if (!module.isDisposed) {
            val result = findByRelativePath(path, rootProvider.getRoots(ModuleRootManager.getInstance(module)), resolver, null)
            if (result != null) {
              result.moduleName = getModuleNameQualifier(project, module)
              return result
            }
          }
          null
        }
    }
      ?: findInLibraries(project, effectivePath, resolver)
  }

  override fun getPathInfo(file: VirtualFile, project: Project): PathInfo? {
    runReadAction {
      val directoryIndex = DirectoryIndex.getInstance(project)
      val info = directoryIndex.getInfoForFile(file)
      // we serve excluded files
      if (!info.isExcluded && !info.isInProject) {
        // javadoc jars is "not under project", but actually is, so, let's check project library table
        return if (file.fileSystem == JarFileSystem.getInstance()) getInfoForDocJar(file, project) else null
      }

      var root = info.sourceRoot
      val isLibrary: Boolean
      if (root == null) {
        root = info.contentRoot
        if (root == null) {
          root = info.libraryClassRoot
          isLibrary = true

          assert(root != null) { file.presentableUrl }
        }
        else {
          isLibrary = false
        }
      }
      else {
        isLibrary = info.isInLibrarySource
      }

      var module = info.module
      if (isLibrary && module == null) {
        for (entry in directoryIndex.getOrderEntries(info)) {
          if (entry is ModuleLibraryOrderEntryImpl) {
            module = entry.ownerModule
            break
          }
        }
      }

      return PathInfo(null, file, root!!, getModuleNameQualifier(project, module), isLibrary)
    }
  }
}

private enum class RootProvider {
  SOURCE {
    override fun getRoots(rootManager: ModuleRootManager) = rootManager.sourceRoots
  },
  CONTENT {
    override fun getRoots(rootManager: ModuleRootManager) = rootManager.contentRoots
  },
  EXCLUDED {
    override fun getRoots(rootManager: ModuleRootManager) = rootManager.excludeRoots
  };

  abstract fun getRoots(rootManager: ModuleRootManager): Array<VirtualFile>
}

private val ORDER_ROOT_TYPES by lazy {
  val javaDocRootType = getJavadocOrderRootType()
  if (javaDocRootType == null)
    arrayOf(OrderRootType.DOCUMENTATION, OrderRootType.SOURCES, OrderRootType.CLASSES)
  else
    arrayOf(javaDocRootType, OrderRootType.DOCUMENTATION, OrderRootType.SOURCES, OrderRootType.CLASSES)
}

private fun getJavadocOrderRootType(): OrderRootType? {
  try {
    return JavadocOrderRootType.getInstance()
  }
  catch (e: Throwable) {
    return null
  }
}

private fun findInModuleLibraries(path: String, module: Module, resolver: FileResolver): PathInfo? {
  val index = path.indexOf('/')
  if (index <= 0) {
    return null
  }

  val libraryFileName = path.substring(0, index)
  val relativePath = path.substring(index + 1)
  findInModuleLevelLibraries(module, ORDER_ROOT_TYPES) { root, module ->
    if (StringUtil.equalsIgnoreCase(root.nameSequence, libraryFileName)) resolver.resolve(relativePath, root, isLibrary = true) else null
  }
  return null
}

private fun findInLibraries(project: Project, path: String, resolver: FileResolver): PathInfo? {
  val index = path.indexOf('/')
  if (index < 0) {
    return null
  }

  val libraryFileName = path.substring(0, index)
  val relativePath = path.substring(index + 1)
  return findInLibrariesAndSdk(project, ORDER_ROOT_TYPES) { root, module ->
    if (StringUtil.equalsIgnoreCase(root.nameSequence, libraryFileName)) resolver.resolve(relativePath, root, isLibrary = true) else null
  }
}

private fun getInfoForDocJar(file: VirtualFile, project: Project): PathInfo? {
  val javaDocRootType = getJavadocOrderRootType() ?: return null
  return findInLibrariesAndSdk(project, arrayOf(javaDocRootType)) { root, module ->
    if (VfsUtilCore.isAncestor(root, file, false)) PathInfo(null, file, root, getModuleNameQualifier(project, module), true) else null
  }
}

private fun getModuleNameQualifier(project: Project, module: Module?): String? {
  if (module != null && PlatformUtils.isIntelliJ() && !(module.name.equals(project.name, ignoreCase = true) || compareNameAndProjectBasePath(module.name, project))) {
    return module.name
  }
  return null
}

private fun findByRelativePath(path: String, roots: Array<VirtualFile>, resolver: FileResolver, moduleName: String?) = roots.computeOrNull { resolver.resolve(path, it, moduleName) }

private fun findInLibrariesAndSdk(project: Project, rootTypes: Array<OrderRootType>, fileProcessor: (root: VirtualFile, module: Module?) -> PathInfo?): PathInfo? {
  fun find(table: LibraryTable) = table.libraryIterator.computeOrNull { library -> rootTypes.computeOrNull { library.getFiles(it).computeOrNull { fileProcessor(it, null) } } }

  runReadAction {
    return ModuleManager.getInstance(project).modules.computeOrNull { module -> if (module.isDisposed) null else findInModuleLevelLibraries(module, rootTypes, fileProcessor) }
      ?: find(LibraryTablesRegistrar.getInstance().getLibraryTable(project))
      ?: ProjectJdkTable.getInstance().allJdks.computeOrNull { sdk -> rootTypes.computeOrNull { sdk.rootProvider.getFiles(it).computeOrNull { fileProcessor(it, null) } } }
      ?: find(LibraryTablesRegistrar.getInstance().libraryTable)
  }
}

private fun findInModuleLevelLibraries(module: Module, rootTypes: Array<OrderRootType>, fileProcessor: (root: VirtualFile, module: Module?) -> PathInfo?): PathInfo? {
  return ModuleRootManager.getInstance(module).orderEntries.computeOrNull { entry ->
    if (entry is LibraryOrderEntry && entry.isModuleLevel) {
      rootTypes.computeOrNull { entry.getFiles(it).computeOrNull { fileProcessor(it, module) } }
    }
    else {
      null
    }
  }
}

private inline fun <T, R> Iterator<T>.computeOrNull(processor: (T) -> R): R? {
  for (file in this) {
    val result = processor(file)
    if (result != null) {
      return result
    }
  }
  return null
}

private inline fun <T, R> Array<T>.computeOrNull(processor: (T) -> R): R? {
  for (file in this) {
    val result = processor(file)
    if (result != null) {
      return result
    }
  }
  return null
}
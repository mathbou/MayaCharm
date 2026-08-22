package settings

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PluginPathManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.text.VersionComparatorUtil
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream

private const val pluginId = "fr.mathbou.mayarecharm"

object PythonStubsManager {
    private val versionCache = ConcurrentHashMap<String, List<String>>()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    val supportedLibraries: List<String> = listOf("maya-stubs", "types-maya-strict")

    fun getVersions(library: String, forceRefresh: Boolean = false): List<String> {
        if (!forceRefresh) {
            versionCache[library]?.let { return it }
        }

        val json = fetchJson("https://pypi.org/pypi/$library/json")
        val root = JsonParser.parseString(json).asJsonObject
        val releases = root.getAsJsonObject("releases")

        val versions = releases.entrySet()
            .asSequence()
            .filter { (_, value) -> value.isJsonArray && value.asJsonArray.size() > 0 }
            .map { it.key }
            .sortedWith { a, b -> VersionComparatorUtil.compare(b, a) }
            .toList()

        versionCache[library] = versions
        return versions
    }

    fun latestKnownVersion(library: String): String? = versionCache[library]?.firstOrNull()

    fun isDownloaded(library: String, version: String): Boolean = Files.isDirectory(installPath(library, version))

    fun installPath(library: String, version: String): Path = stubsRoot().resolve("$library-$version")

    @Throws(IOException::class)
    fun deleteDownloaded(library: String, version: String) {
        val path = installPath(library, version)
        if (Files.exists(path)) {
            FileUtil.delete(path.toFile())
        }
    }

    @Throws(IOException::class, IllegalStateException::class)
    fun ensureDownloaded(library: String, version: String): Path {
        val destination = installPath(library, version)
        if (Files.isDirectory(destination)) return destination

        val artifactUrl = resolveWheelArtifactUrl(library, version)
        val archiveFile = Files.createTempFile("mayarecharm-$library-$version-", ".whl")
        val extractRoot = Files.createTempDirectory("mayarecharm-$library-$version-")

        try {
            downloadTo(artifactUrl, archiveFile)
            extractZip(archiveFile, extractRoot)

            extractRoot.createDirectories()
            destination.parent.createDirectories()
            if (Files.exists(destination)) {
                FileUtil.delete(destination.toFile())
            }
            copyDirectory(extractRoot, destination)
            return destination
        } finally {
            Files.deleteIfExists(archiveFile)
            if (Files.exists(extractRoot)) {
                runCatching { FileUtil.delete(extractRoot.toFile()) }
            }
        }
    }

    @Throws(IllegalStateException::class)
    fun applyToSdk(sdk: Sdk, selectedLibrary: String?, selectedVersion: String?) {
        val selectedPath = if (selectedLibrary != null && selectedVersion != null) {
            installPath(selectedLibrary, selectedVersion)
        } else {
            null
        }

        val rootPrefix = FileUtil.toSystemIndependentName(stubsRoot().toString())
        ApplicationManager.getApplication().runWriteAction {
            val modificator = sdk.sdkModificator
            val managedRootTypes = listOf(OrderRootType.CLASSES, OrderRootType.SOURCES)
            for (rootType in managedRootTypes) {
                val roots = sdk.rootProvider.getFiles(rootType)
                for (root in roots) {
                    if (FileUtil.isAncestor(rootPrefix, root.path, false)) {
                        modificator.removeRoot(root, rootType)
                    }
                }
            }

            if (selectedPath != null) {
                val vFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(selectedPath)
                    ?: throw IllegalStateException("Unable to resolve stubs path: $selectedPath")
                modificator.addRoot(vFile, OrderRootType.CLASSES)
                modificator.addRoot(vFile, OrderRootType.SOURCES)
            }
            modificator.commitChanges()
        }
    }

    private fun stubsRoot(): Path {
        val pluginPath = PluginPathManager.getPluginDistPath(PythonStubsManager::class.java, "")
            ?: throw IllegalStateException("Plugin path not found for $pluginId")
        val path = pluginPath.resolve("stubs")
        path.createDirectories()
        return path
    }

    @Throws(IOException::class)
    private fun fetchJson(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IOException("HTTP ${response.statusCode()} for $url")
        }
        return response.body()
    }

    @Throws(IOException::class, IllegalStateException::class)
    private fun resolveWheelArtifactUrl(library: String, version: String): String {
        val json = fetchJson("https://pypi.org/pypi/$library/$version/json")
        val root = JsonParser.parseString(json).asJsonObject
        val urls = root.getAsJsonArray("urls")

        val wheelCandidates = urls
            .asSequence()
            .mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val filename = obj.get("filename")?.asString ?: return@mapNotNull null
                val packagetype = obj.get("packagetype")?.asString ?: return@mapNotNull null
                val url = obj.get("url")?.asString ?: return@mapNotNull null
                Triple(filename, packagetype, url)
            }
            .filter { (_, packagetype, _) -> packagetype == "bdist_wheel" }
            .toList()

        val preferred = wheelCandidates.firstOrNull { (filename, _, _) ->
            filename.contains("py3-none-any")
        } ?: wheelCandidates.firstOrNull()

        return preferred?.third
            ?: throw IllegalStateException("No wheel artifact found for $library $version")
    }

    @Throws(IOException::class)
    private fun downloadTo(url: String, destination: Path) {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(90))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            throw IOException("HTTP ${response.statusCode()} while downloading $url")
        }
        response.body().use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    @Throws(IOException::class)
    private fun extractZip(archive: Path, destination: Path) {
        destination.createDirectories()
        ZipInputStream(Files.newInputStream(archive)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outPath = destination.resolve(entry.name).normalize()
                if (!outPath.startsWith(destination)) {
                    throw IOException("Archive contains invalid path: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outPath.createDirectories()
                } else {
                    outPath.parent?.createDirectories()
                    Files.copy(zip, outPath, StandardCopyOption.REPLACE_EXISTING)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    @Throws(IOException::class)
    private fun copyDirectory(source: Path, destination: Path) {
        if (Files.exists(destination)) {
            FileUtil.delete(destination.toFile())
        }
        Files.createDirectories(destination)

        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val targetDir = destination.resolve(source.relativize(dir).toString())
                Files.createDirectories(targetDir)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val targetFile = destination.resolve(source.relativize(file).toString())
                targetFile.parent?.let { Files.createDirectories(it) }
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING)
                return FileVisitResult.CONTINUE
            }
        })
    }
}

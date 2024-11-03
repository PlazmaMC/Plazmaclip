package plazmaclip

import io.sigpipe.jbsdiff.Patch.patch as merge
import java.io.BufferedReader
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringWriter
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.FileSystems.newFileSystem
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.lang.Boolean.getBoolean
import kotlin.concurrent.thread
import kotlin.io.path.*
import kotlin.system.exitProcess
import java.lang.System.err
import java.lang.System.getProperty
import java.lang.System.arraycopy
import java.net.MalformedURLException
import java.net.URI
import java.nio.channels.Channels.newChannel
import java.nio.channels.FileChannel.open
import java.security.MessageDigest.getInstance

private var first = false
private val digest = getInstance("SHA-256") // TODO: Use SHA-512 instead, needs to edit paperweight

fun main(vararg args: String) {

    val repo = Path(getProperty("bundlerRepoDir", ""))
    val patchOnly = getBoolean("paperclip.patchonly")
    val mainClass = tryOrThrow("Failed to retrieve main class") {
        getProperty("bundlerMainClass") ?: readResource("/META-INF/main-class")!!
    }

    if (Path(".").absolutePathString().contains("!")) {
        err.println("Plazmaclip will not run in a directory containing '!'. Please rename the affected folder.")
        exitProcess(1)
    }

    val patches = findPatches()
    val remotes = findRemotes()
    if (patches.isNotEmpty() && remotes == null) throw IllegalArgumentException("patches list found without a corresponding original-url")

    val orig = tryOrThrow("Failed to download original file") { remotes?.download(repo) }
    if (orig == null && patches.isNotEmpty()) throw IllegalArgumentException("Patch found without target")

    val entry = mutableMapOf<String, MutableMap<String, URL>>()
    tryOrThrow("Failed to extract jar files") { entry.extract(patches, orig, repo) }

    if (patches.isNotEmpty()) tryOrThrow("Failed to apply patches") {
        newFileSystem(orig).use { for (p in patches) p?.merge(entry, it.getPath("/"), repo) }
    }

    if (patchOnly) exitProcess(0)

    val versions = entry["versions"]!!.values
    val libraries = entry["libraries"]!!.values

    val entries = arrayOfNulls<URL>(versions.size + libraries.size)
    arraycopy(versions.toTypedArray(), 0, entries, 0, versions.size)
    arraycopy(libraries.toTypedArray(), 0, entries, versions.size, libraries.size)

    val loader = URLClassLoader(entries, Patch::class.java.classLoader)
    thread(name = "ServerMain", contextClassLoader = loader) {
        try {
            val main = Class.forName(mainClass, true, loader)
            val handle = MethodHandles.lookup()
                .findStatic(main, "main", MethodType.methodType(Void.TYPE, Array<String>::class.java))
                .asFixedArity()

            handle.invoke(args)
        } catch (t: Throwable) {
            throw t
        }
    }

}

private inline fun <T> tryOrThrow(msg: String, block: () -> T): T {
    try {
        return block()
    } catch (e: Exception) {
        err.println(msg)
        e.printStackTrace()
        exitProcess(1)
    }
}

private val Char.hex: Int
    get() {
        val i = Character.digit(this, 16)
        if (i < 0) throw IllegalArgumentException("Invalid hex char: $this")
        return i
    }

private val String.hex: ByteArray
    get() {
        if (this.length % 2 != 0) throw IllegalArgumentException("Length of hex $this must be divisible by two")

        try {
            val bytes = ByteArray(this.length / 2)
            for (i in bytes.indices) bytes[i] = ((this[i * 2].hex shl 4) or (this[i * 2 + 1].hex and 0xF)).toByte()
            return bytes
        } catch (e: Exception) {
            throw IllegalArgumentException("Cannot convert non-hex string: $this")
        }
    }

private fun resourceStream(path: String): InputStream? = Patch::class.java.getResourceAsStream(path)
private fun ByteArray.validate(hash: ByteArray) = hash.contentEquals(digest.digest(this))
private fun Path.validate(hash: ByteArray) = this.exists() && this.readBytes().validate(hash)
private fun Path.open() = open(this, CREATE, WRITE, TRUNCATE_EXISTING)

private fun readResource(path: String): String? = with(StringWriter()) {
    (resourceStream(if (path.startsWith("/")) path else "/$path") ?: return null).use {
        InputStreamReader(it).transferTo(this)
    }
    return this.toString()
}

private fun findEntries(name: String): Array<FileEntry?>? = tryOrThrow("Failed to read file: $name") {
    (resourceStream("/META-INF/$name") ?: return@tryOrThrow null).use {
        return@tryOrThrow BufferedReader(InputStreamReader(it)).parse {
            val parts = this.split("\t")
            check(parts.size == 3) { "Malformed library entry: $this" }

            return@parse FileEntry(parts[0].hex, parts[1], parts[2])
        }
    }
}

private inline fun <reified T> BufferedReader.parse(parser: String.() -> T): Array<T?> {
    var result = arrayOfNulls<T>(8)

    var i = 0
    for (l in this.lines()) {
        val data = l.parser() ?: continue

        if (i == result.size) result = result.copyOf(i * 2)
        result[i++] = data
    }

    return if (i != result.size) result.copyOf(i) else result
}

private fun MutableMap<String, MutableMap<String, URL>>.extract(
    patches: Array<Patch?>,
    orig: Path?,
    repo: Path
) = (if (orig == null) null else newFileSystem(orig)).use {
    val root = it?.getPath("/")

    val versions = mutableMapOf<String, URL>()
    val libraries = mutableMapOf<String, URL>()

    this.putIfAbsent("versions", versions)
    this.putIfAbsent("libraries", libraries)

    findEntries("versions.list")?.extract(versions, patches, root, repo, "versions")
    findEntries("libraries.list")?.extract(libraries, patches, root, repo, "libraries")
}

private fun findPatches(): Array<Patch?> {
    val stream = resourceStream("/META-INF/patches.list") ?: return emptyArray()
    return tryOrThrow("Failed to read patches.list") {
        BufferedReader(InputStreamReader(stream)).parse {
            if (this.isBlank() || this.startsWith("#")) return@parse null

            val parts = this.split("\t")
            check(parts.size == 7) { "Invalid patch-data line: $this" }

            return@parse Patch(parts)
        }
    }
}

private fun findRemotes(): RemoteEntry? {
    val l = tryOrThrow("Failed to read download-context file") {
        readResource("/META-INF/download-context")
    } ?: return null

    val parts = l.split("\t")
    check(parts.size == 3) { "Invalid download-context line: $l" }

    try {
        return RemoteEntry(parts[0].hex, URI.create(parts[1]).toURL(), parts[2])
    } catch (e: MalformedURLException) {
        throw IllegalStateException("Unable to parse URL in download-context", e)
    }
}

private fun Array<FileEntry?>.extract(
    urls: MutableMap<String, URL>,
    patches: Array<Patch?>,
    root: Path?,
    repo: Path,
    target: String
) = ("/META-INF/$target" to repo.resolve(target)).let {
    for (e in this) e?.extract(urls, patches, target, root, it.first, it.second)
}

@JvmRecord
private data class RemoteEntry(
    val hash: ByteArray,
    val url: URL,
    val name: String
) {

    fun download(outPath: Path): Path {

        val outFile = outPath.resolve("cache").resolve(this.name)
        if (outFile.validate(this.hash)) return outFile

        if (!outFile.parent.isDirectory()) outFile.parent.createDirectories()
        outFile.deleteIfExists()

        println("Downloading $name")
        tryOrThrow("Failed to download $name") {
            val src = newChannel(this.url.openStream())
            outFile.open().transferFrom(src, 0, Long.MAX_VALUE)
        }

        return outFile

    }

}

@JvmRecord
private data class FileEntry(
    val hash: ByteArray,
    val id: String,
    val path: String
) {

    fun extract(
        url: MutableMap<String, URL>,
        patches: Array<Patch?>,
        target: String,
        root: Path?,
        base: String,
        outPath: Path
    ) {

        for (p in patches) {
            if (p?.path == target && p.pathOutput == this.path) return
        }

        val outFile = outPath.resolve(this.path)
        if (outFile.exists() && outFile.validate(this.hash)) {
            url[this.path] = outFile.toUri().toURL()
            return
        }

        val srcPath = (if (base.endsWith("/")) base else "$base/") + this.path
        val stream = resourceStream(srcPath) ?: with(root) {
            if (this == null) throw IllegalStateException("$path not found in our jar, and no original provided")

            val orig = this.resolve(srcPath)
            if (orig.notExists()) throw IllegalStateException("Could not find $path in our jar or original jar file")

            return@with orig.inputStream()
        }

        if (!outFile.parent.isDirectory()) outFile.parent.createDirectories()
        outFile.deleteIfExists()

        stream.use {
            val input = newChannel(it)
            val output = outFile.open()

            output.transferFrom(input, 0, Long.MAX_VALUE)
        }

        if (!outFile.validate(this.hash)) throw IllegalStateException("Hash validation failed for extract file $outFile")

        url[this.path] = outFile.toUri().toURL()

    }

}

@JvmRecord
private data class Patch(
    val path: String,
    val hashOrig: ByteArray,
    val hashPatch: ByteArray,
    val hashOutput: ByteArray,
    val pathOrig: String,
    val pathPatch: String,
    val pathOutput: String
) {

    constructor(parts: List<String>) : this(parts[0], parts[1].hex, parts[2].hex, parts[3].hex, parts[4], parts[5], parts[6])

    fun merge(urls: Map<String, MutableMap<String, URL>>, orig: Path, repo: Path) {

        val src = orig.resolve("META-INF").resolve(this.path).resolve(this.pathOrig)
        val out = repo.resolve(this.path).resolve(this.pathOutput)

        if (out.validate(this.hashOutput)) {
            urls[this.path]?.set(this.pathOrig, out.toUri().toURL())
            return
        }

        if (!first) {
            println("Applying patches")
            first = true
        }

        check(src.exists()) { "Input file not found: $src" }
        check(src.validate(this.hashOrig)) { "Hash validation of input file failed for $src" }

        val patchFile = "/META-INF/${if (this.path.endsWith("/")) this.path else "$path/"}$pathPatch"
        val stream = resourceStream(patchFile) ?: throw IllegalStateException("Could not find patch file: $patchFile")

        val patch = stream.readBytes()
        check(patch.validate(this.hashPatch)) { "Hash validation of patch file failed for $patchFile" }

        val bytes = src.readBytes()
        tryOrThrow("Failed to patch $src") {
            if (!out.parent.isDirectory()) out.parent.createDirectories()
            BufferedOutputStream(out.outputStream(CREATE, WRITE, TRUNCATE_EXISTING)).use { merge(bytes, patch, it) }
        }

        check(out.validate(this.hashOutput)) { "Patch not applied correctly for $pathOutput" }

        urls[this.path]?.set(this.pathOrig, out.toUri().toURL())

    }

}

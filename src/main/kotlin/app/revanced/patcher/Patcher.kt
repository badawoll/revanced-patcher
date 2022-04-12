package app.revanced.patcher

import app.revanced.patcher.cache.Cache
import app.revanced.patcher.cache.findIndexed
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.PatchResultSuccess
import app.revanced.patcher.signature.MethodSignature
import app.revanced.patcher.signature.resolver.SignatureResolver
import app.revanced.patcher.util.ListBackedSet
import lanchon.multidexlib2.BasicDexFileNamer
import lanchon.multidexlib2.DexIO
import lanchon.multidexlib2.MultiDexIO
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.writer.io.MemoryDataStore
import java.io.File

val NAMER = BasicDexFileNamer()

/**
 * ReVanced Patcher.
 * @param input The input file (an apk or any other multi dex container).
 * @param signatures A list of method signatures for the patches.
 */
class Patcher(
    input: File,
    private val signatures: Iterable<MethodSignature>,
) {
    private val cache: Cache
    private val patches = mutableSetOf<Patch>()
    private val opcodes: Opcodes
    private var sigsResolved = false

    init {
        val dexFile = MultiDexIO.readDexFile(true, input, NAMER, null, null)
        opcodes = dexFile.opcodes
        cache = Cache(dexFile.classes.toMutableList())
    }

    /**
     * Add additional dex file container to the patcher.
     * @param files The dex file containers to add to the patcher.
     * @param allowedOverwrites A list of class types that are allowed to be overwritten.
     * @param throwOnDuplicates If this is set to true, the patcher will throw an exception if a duplicate class has been found.
     */
    fun addFiles(
        files: Iterable<File>,
        allowedOverwrites: Iterable<String> = emptyList(),
        throwOnDuplicates: Boolean = false
    ) {
        for (file in files) {
            val dexFile = MultiDexIO.readDexFile(true, file, NAMER, null, null)
            for (classDef in dexFile.classes) {
                val e = cache.classes.findIndexed { it.type == classDef.type }
                if (e != null) {
                    if (throwOnDuplicates) {
                        throw Exception("Class ${classDef.type} has already been added to the patcher.")
                    }
                    val (_, idx) = e
                    if (allowedOverwrites.contains(classDef.type)) {
                        cache.classes[idx] = classDef
                    }
                    continue
                }
                cache.classes.add(classDef)
            }
        }
    }

    /**
     * Save the patched dex file.
     */
    fun save(): Map<String, MemoryDataStore> {
        val newDexFile = object : DexFile {
            override fun getClasses(): Set<ClassDef> {
                cache.methodMap.values.forEach {
                    if (it.definingClassProxy.proxyUsed) {
                        cache.classes[it.definingClassProxy.originalIndex] = it.definingClassProxy.mutatedClass
                    }
                }
                cache.classProxy.filter { it.proxyUsed }.forEach { proxy ->
                    cache.classes[proxy.originalIndex] = proxy.mutatedClass
                }
                return ListBackedSet(cache.classes)
            }

            override fun getOpcodes(): Opcodes {
                return this@Patcher.opcodes
            }
        }

        val output = mutableMapOf<String, MemoryDataStore>()
        MultiDexIO.writeDexFile(
            true, -1, // core count
            output, NAMER, newDexFile,
            DexIO.DEFAULT_MAX_DEX_POOL_SIZE,
            null
        )
        return output
    }

    /**
     * Add a patch to the patcher.
     * @param patches The patches to add.
     */
    fun addPatches(patches: Iterable<Patch>) {
        this.patches.addAll(patches)
    }

    /**
     * Apply patches loaded into the patcher.
     * @param stopOnError If true, the patches will stop on the first error.
     * @return A map of results. If the patch was successfully applied,
     * PatchResultSuccess will always be returned in the wrapping Result object.
     * If the patch failed to apply, an Exception will always be returned in the wrapping Result object.
     */
    fun applyPatches(
        stopOnError: Boolean = false,
        callback: (String) -> Unit = {}
    ): Map<String, Result<PatchResultSuccess>> {
        if (!sigsResolved) {
            SignatureResolver(cache.classes, signatures).resolve(cache.methodMap)
            sigsResolved = true
        }
        return buildMap {
            for (patch in patches) {
                callback(patch.patchName)
                val result: Result<PatchResultSuccess> = try {
                    val pr = patch.execute(cache)
                    if (pr.isSuccess()) {
                        Result.success(pr.success()!!)
                    } else {
                        Result.failure(Exception(pr.error()?.errorMessage() ?: "Unknown error"))
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
                this[patch.patchName] = result
                if (result.isFailure && stopOnError) break
            }
        }
    }
}

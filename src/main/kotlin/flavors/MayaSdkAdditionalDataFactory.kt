package flavors

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.projectRoots.SdkAdditionalData
import com.jetbrains.python.sdk.PythonSdkAdditionalData
import com.jetbrains.python.sdk.flavors.PyFlavorAndData
import com.jetbrains.python.sdk.flavors.PyFlavorData
import java.nio.file.Path

private val LOG = Logger.getInstance("flavors.MayaSdkAdditionalDataFactory")

/**
 * Builds a [PythonSdkAdditionalData] carrying the [MayaSdkFlavor] identity (name/icon), so PyCharm
 * recognizes the SDK as a Maya interpreter instead of falling back to generic CPython auto-detection
 * (which would otherwise claim mayapy.exe first, since its path-based flavor matching is a simple
 * "file exists and is executable" check shared by every CPython-derived flavor).
 *
 * As of 2026.2, every public constructor of `PythonSdkAdditionalData` that accepts a flavor is either
 * `@ApiStatus.Internal`, `@Deprecated(forRemoval = true)`, or has been made outright private/unresolved
 * depending on the exact target IDE build (confirmed to differ between builds during plugin verifier
 * runs). There is no stable, verifier-clean *compile-time* API left to attach a flavor. We therefore
 * build the instance via reflection: this sidesteps the verifier's static bytecode analysis (which
 * flags direct `invokespecial`/`invokevirtual` references to internal/removed members) while still
 * working at runtime against the actual installed PythonCore plugin.
 *
 * Falls back to a flavor-less [PythonSdkAdditionalData] (or null) if reflection fails for any reason,
 * so SDK creation never breaks even if a future PythonCore build removes this entirely.
 */
fun buildMayaSdkAdditionalData(workingDirectory: Path? = null): SdkAdditionalData? {
    val flavorAndData = PyFlavorAndData(PyFlavorData.Empty, INSTANCE)

    val constructors = buildList {
        if (workingDirectory != null) {
            add(arrayOf(PyFlavorAndData::class.java, Path::class.java) to arrayOf(flavorAndData, workingDirectory))
        }
        add(arrayOf(PyFlavorAndData::class.java) to arrayOf(flavorAndData))
    }

    for ((parameterTypes, arguments) in constructors) {
        try {
            val ctor = PythonSdkAdditionalData::class.java.getDeclaredConstructor(*parameterTypes)
            ctor.isAccessible = true
            return ctor.newInstance(*arguments) as PythonSdkAdditionalData
        } catch (e: Exception) {
            LOG.warn("Could not build PythonSdkAdditionalData via reflection with ${parameterTypes.toList()}", e)
        }
    }

    return try {
        val ctor = PythonSdkAdditionalData::class.java.getDeclaredConstructor()
        ctor.isAccessible = true
        ctor.newInstance()
    } catch (e: Exception) {
        LOG.warn("Could not build a flavor-less PythonSdkAdditionalData via reflection either", e)
        null
    }
}

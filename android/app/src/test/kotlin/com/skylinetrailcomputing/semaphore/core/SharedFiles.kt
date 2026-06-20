package com.skylinetrailcomputing.semaphore.core

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Resolves the repo's `shared/` directory and loads the JSON contract files.
 *
 * Per the #14 decision, the tests read the `shared/` JSON files directly rather
 * than copying or symlinking them, so both platforms parse the exact bytes the
 * Python generator wrote. We anchor on the JVM working directory and walk up
 * until we find the directory containing `shared/test_vectors.json`, which is
 * robust to whether Gradle runs tests from the module dir or the repo root.
 */
object SharedFiles {
    val gson: Gson = Gson()

    val directory: File by lazy {
        val start = System.getProperty("user.dir") ?: error("user.dir is not set")
        var dir: File? = File(start).absoluteFile
        while (dir != null) {
            if (File(dir, "shared/test_vectors.json").exists()) return@lazy File(dir, "shared")
            dir = dir.parentFile
        }
        error("Could not locate repo root (shared/test_vectors.json) from $start")
    }

    inline fun <reified T> load(fileName: String): T =
        gson.fromJson(File(directory, fileName).readText(), object : TypeToken<T>() {}.type)
}

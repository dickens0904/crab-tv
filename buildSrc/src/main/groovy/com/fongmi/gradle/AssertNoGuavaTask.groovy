package com.fongmi.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

import java.util.zip.ZipFile

/**
 * Fails the build when a packaged APK still ships an UNOBFUSCATED
 * com/google/common type descriptor.
 *
 * Why this has to exist
 * ---------------------
 * Dreambox puts /system/framework/libsetting.jar on BOOTCLASSPATH, which carries an
 * ancient Guava. The platform loads boot classpath entries parent-first, so if the app
 * ships any type still NAMED com/google/common/**, the old system copy wins and the app
 * blows up with NoSuchMethodError (e.g. ImmutableList.buildOrThrow) at first use.
 *
 * Guava cannot simply be excluded from the build: 33 sources in this repo import
 * com.google.common.* (HttpHeaders, ImmutableList, Futures, MoreExecutors, ByteStreams,
 * CacheBuilder, ...), so removing the artifact breaks javac outright. The library stays.
 * What keeps the app safe today is that R8 renames every surviving Guava class to a short
 * name, so no com/google/common descriptor ever reaches the dex.
 *
 * That protection is real but implicit -- a single `-keep class com.google.common.** { *; }`
 * added anywhere would silently restore the original package name and re-open the crash,
 * with nothing in the build to notice. This task is that missing tripwire: it parses the
 * dex string pool of the real artifact and fails loudly instead of shipping.
 */
abstract class AssertNoGuavaTask extends DefaultTask {

    @InputFile
    abstract RegularFileProperty getApkFile()

    @TaskAction
    void run() {
        File apk = apkFile.get().asFile
        logger.lifecycle("[assertNoGuava] scanning ${apk.name}")

        List<String> descriptors = []
        int dexCount = 0
        int stringCount = 0

        new ZipFile(apk).withCloseable { zip ->
            List<String> dexEntries = zip.entries().collect {
                String n = it.name
                (n.startsWith('classes') && n.endsWith('.dex')) ? n : null
            }.findAll { it != null }.sort()

            dexEntries.each { String entry ->
                dexCount++
                byte[] data = zip.getInputStream(zip.getEntry(entry)).bytes
                List<String> strings = AssertNoGuavaTask.readDexStrings(data)
                stringCount += strings.size()
                strings.each { String s ->
                    if (s.contains('com/google/common')) descriptors.add("${entry}: ${s}")
                }
            }
        }

        logger.lifecycle("[assertNoGuava]   dex files      : ${dexCount}")
        logger.lifecycle("[assertNoGuava]   dex strings    : ${stringCount}")
        logger.lifecycle("[assertNoGuava]   com/google/common descriptors : ${descriptors.size()}")

        if (!descriptors.isEmpty()) {
            descriptors.take(25).each { logger.error("[assertNoGuava]     ${it}") }
            if (descriptors.size() > 25) {
                logger.error("[assertNoGuava]     ... and ${descriptors.size() - 25} more")
            }
            throw new GradleException(
                    "Guava collision guard tripped: the APK still ships ${descriptors.size()} " +
                            "unobfuscated com/google/common type descriptor(s).\n" +
                            "Dreambox loads /system/framework/libsetting.jar (ancient Guava) from " +
                            "BOOTCLASSPATH parent-first, so these names would resolve to the system " +
                            "copy and crash with NoSuchMethodError at runtime.\n" +
                            "Guava cannot be removed from the classpath (33 sources import it). " +
                            "Make sure no rule keeps com.google.common under its original name.")
        }

        logger.lifecycle("[assertNoGuava] PASS - no com/google/common type descriptor in ${apk.name}")
    }

    /** Every MUTF-8 entry in the dex string_ids table. */
    private static List<String> readDexStrings(byte[] d) {
        if (d.length < 0x70 || d[0] != 0x64 || d[1] != 0x65 || d[2] != 0x78) {
            throw new GradleException("not a dex file (magic=${new String(d, 0, Math.min(3, d.length))})")
        }
        int count = readInt(d, 0x38)
        int table = readInt(d, 0x3c)
        List<String> out = new ArrayList<>(count)
        for (int i = 0; i < count; i++) {
            int p = readInt(d, table + 4 * i)
            // skip the ULEB128 utf16 length
            while ((d[p] & 0x80) != 0) p++
            p++
            int end = p
            while (d[end] != 0) end++
            out.add(new String(d, p, end - p, 'UTF-8'))
        }
        return out
    }

    private static int readInt(byte[] d, int off) {
        return (d[off] & 0xff) | ((d[off + 1] & 0xff) << 8) |
                ((d[off + 2] & 0xff) << 16) | ((d[off + 3] & 0xff) << 24)
    }
}
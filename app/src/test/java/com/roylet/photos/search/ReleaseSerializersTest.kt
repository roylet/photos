package com.roylet.photos.search

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The release build (R8) must keep the search DTOs and their generated serializers under their
 * own names, or decoding a search response fails only in release. Reads the R8 mapping that
 * `assembleRelease` writes; skipped when there's no release build yet.
 */
class ReleaseSerializersTest {
    private val mapping = listOf("build/outputs/mapping/release/mapping.txt", "app/build/outputs/mapping/release/mapping.txt")
        .map(::File).firstOrNull { it.isFile }

    @Test fun `search DTO serializers survive minification`() {
        assumeTrue("no release build", mapping != null)
        val renames = HashMap<String, String>()
        mapping!!.useLines { lines ->
            lines.filter { !it.startsWith(" ") && !it.startsWith("#") && it.endsWith(":") && " -> " in it }
                .forEach { val (from, to) = it.removeSuffix(":").split(" -> "); renames[from] = to }
        }
        val dtos = listOf("SearchResponseDto", "SearchAssetsDto", "ExploreDto", "ExploreItemDto", "AssetDto", "ExifDto", "PersonDto", "SmartSearchBody", "MetadataSearchBody")
        for (name in dtos) {
            for (cls in listOf("com.roylet.photos.immich.$name", "com.roylet.photos.immich.$name\$\$serializer")) {
                val kept = renames[cls]
                assertTrue("$cls was removed by R8", kept != null)
                assertTrue("$cls was renamed to $kept", kept == cls)
            }
        }
    }
}

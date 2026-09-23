package dev.brahmkshatriya.echo.link

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class Opener : Activity() {

    // ⚠⚠ READ FROM OUR OWN MANIFEST, NOT HARDCODED - IT WAS "deezerApp" AND THE EXTENSION'S
    // ID IS "deezer", SO EVERY URI THIS ACTIVITY EMITTED NAMED AN EXTENSION THAT DOES NOT EXIST.
    // The two have NEVER agreed: `git log -S` shows both values arriving together in the
    // submodule-flattening commit, and extId has never been "deezerApp" in this repo - so this is
    // inherited from upstream rather than a rename that missed a string.
    // ⚠⚠ THE META-DATA IS THE SAME SINGLE SOURCE THE HOST READS, WHICH IS WHY THIS CANNOT
    // DRIFT AGAIN. `<meta-data android:name="id" android:value="${id}">` is filled from
    // gradle.properties' extId via manifestPlaceholders, and ExtensionParser.parseManifest builds
    // an extension's Metadata.id from `metaData.getString("id")` - this exact key. Hardcoding any
    // string here re-creates a second copy of a value that already has one authority; a
    // buildConfigField would too. Pair with the reader, do not restate.
    private val extensionId by lazy {
        runCatching {
            packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                .metaData?.getString("id")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data

        if (uri != null) {
            Thread {
                val resolvedUri = resolveRedirects(uri.toString())
                runOnUiThread {
                    if (resolvedUri != null) {
                        processUri(Uri.parse(resolvedUri))
                    } else {
                        finishAndRemoveTask()
                    }
                }
            }.start()
        }
    }

    private fun resolveRedirects(urlString: String): String? {
        var url = urlString
        var redirectUrl: String?

        try {
            while (true) {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    redirectUrl = connection.getHeaderField("Location")
                    if (redirectUrl != null) {
                        url = if (Uri.parse(redirectUrl).isRelative) {
                            URL(URL(url), redirectUrl).toString()
                        } else {
                            redirectUrl
                        }
                    } else {
                        break
                    }
                } else {
                    redirectUrl = url
                    break
                }
                connection.disconnect()
            }
        } catch (e: IOException) {
            e.printStackTrace()
            return null
        }

        return redirectUrl
    }

    private fun processUri(uri: Uri) {
        val type: String
        val segment: Int
        if (!uri.pathSegments[0].contains("album") ||
            !uri.pathSegments[0].contains("artist") ||
            !uri.pathSegments[0].contains("playlist") ||
            !uri.pathSegments[0].contains("track")
        ) {
            type = uri.pathSegments[1]
            segment = 2
        } else {
            type = uri.pathSegments[0]
            segment = 1
        }

        val path = when (type) {
            "artist" -> {
                val artistId = uri.pathSegments[segment] ?: return
                "artist/$artistId"
            }

            "playlist" -> {
                val playlistId = uri.pathSegments[segment] ?: return
                "playlist/$playlistId"
            }

            "album" -> {
                val albumId = uri.pathSegments[segment] ?: return
                "album/$albumId"
            }

            "track" -> {
                val trackId = uri.pathSegments[segment] ?: return
                "track/$trackId"
            }

            else -> return
        }

        // Nothing useful can be built without it - the host would open a media page for an
        // extension it cannot find, which resolves to a spinner that never completes (see
        // MediaViewModel: extensionFlow is `list.find { it.id == extensionId }`, and the init
        // collector early-returns on null, leaving itemResultFlow null and isRefreshing true).
        val id = extensionId ?: return
        val uriString = "echo://music/$id/$path"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uriString)))
        finishAndRemoveTask()
    }
}
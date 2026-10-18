package com.floatbrowser.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.OutputStream

/** Salva arquivos na pasta Download do celular (sem permissão especial). */
object Downloader {
    private fun toast(c: Context, t: String) =
        Handler(Looper.getMainLooper()).post { Toast.makeText(c, t, Toast.LENGTH_LONG).show() }

    private fun fileName(r: WebResponse): String {
        val cd = r.headers.entries.firstOrNull { it.key.equals("content-disposition", true) }?.value
        val fromCd = cd?.let { Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        val n = fromCd ?: Uri.parse(r.uri).lastPathSegment ?: "arquivo"
        return n.replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    private fun write(c: Context, name: String, mime: String?, body: (OutputStream) -> Unit) {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                if (mime != null) put(MediaStore.Downloads.MIME_TYPE, mime)
            }
            val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
            c.contentResolver.openOutputStream(uri)!!.use(body)
        } else {
            val dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
            File(dir, name).outputStream().use(body)
        }
    }

    fun save(c: Context, r: WebResponse) {
        val body = r.body
        if (body == null) { toast(c, "Download sem conteúdo"); return }
        val name = fileName(r)
        val mime = r.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value?.substringBefore(";")
        Thread {
            try {
                write(c, name, mime) { o -> body.use { it.copyTo(o) } }
                toast(c, "Baixado em Downloads: $name")
            } catch (e: Exception) { toast(c, "Falha no download") }
        }.start()
    }

    fun saveBytes(c: Context, name: String, bytes: ByteArray, mime: String?) {
        Thread {
            try {
                write(c, name, mime) { it.write(bytes) }
                toast(c, "Salvo em Downloads: $name")
            } catch (e: Exception) { toast(c, "Falha ao salvar o arquivo") }
        }.start()
    }
}

package com.floatbrowser.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Tela transparente que abre o seletor de arquivos do Android (upload nas janelas). */
class PickerActivity : Activity() {
    companion object {
        private var cb: ((Uri?) -> Unit)? = null
        fun pick(c: Context, done: (Uri?) -> Unit) {
            cb = done
            c.startActivity(Intent(c, PickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 1)
    }
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        cb?.invoke(if (res == RESULT_OK) data?.data else null)
        cb = null
        finish()
    }
}

package com.floatbrowser.app

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.*
import org.mozilla.geckoview.StorageController

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CrashLog.handler)
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        ProxyBridge.init(this)

        status = TextView(this).apply { textSize = 15f; text = "Pronto" }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 90, 40, 40)
        }
        fun btn(t: String, a: () -> Unit) = col.addView(Button(this).apply {
            text = t; setOnClickListener { a() }
        })

        btn("1) Permitir janelas flutuantes") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("2) Nova janela flutuante") {
            if (!Settings.canDrawOverlays(this)) status.text = "Faça o passo 1 primeiro"
            else startForegroundService(Intent(this, FloatService::class.java).setAction(FloatService.ACTION_NEW))
        }
        btn("Minimizar janelas (continuam ativas)") {
            startForegroundService(Intent(this, FloatService::class.java).setAction(FloatService.ACTION_MIN))
        }
        btn("Mostrar janelas") {
            startForegroundService(Intent(this, FloatService::class.java).setAction(FloatService.ACTION_SHOW))
        }
        val awake = Button(this)
        fun awakeText() { awake.text = "Manter ativo com tela desligada: " + if (Prefs.keepAwake) "SIM" else "NÃO" }
        awakeText()
        awake.setOnClickListener {
            Prefs.keepAwake = !Prefs.keepAwake; awakeText(); FloatService.instance?.updateLocks()
        }
        col.addView(awake)
        val dataBtn = Button(this)
        fun dataText() {
            dataBtn.text = "Uso de dados: " + listOf("NORMAL", "ECONÔMICO", "AUTOMÁTICO")[Prefs.dataMode]
        }
        dataText()
        dataBtn.setOnClickListener {
            val items = arrayOf(
                "Normal: testes a cada 45 s, lista a cada 10 min",
                "Econômico: testes a cada 3 min, lista a cada 30 min e só se faltar proxy",
                "Automático: econômico no 4G/rede limitada, normal no Wi-Fi")
            AlertDialog.Builder(this).setTitle("Uso de dados")
                .setSingleChoiceItems(items, Prefs.dataMode) { dlg, i -> Prefs.dataMode = i; dataText(); dlg.dismiss() }
                .show()
        }
        col.addView(dataBtn)
        val vpnBtn = Button(this)
        fun vpnText() {
            vpnBtn.text = "Ignorar VPN (rede direta do operador): " + if (Prefs.bypassVpn) "SIM" else "NÃO"
        }
        vpnText()
        vpnBtn.setOnClickListener {
            Prefs.bypassVpn = !Prefs.bypassVpn; vpnText(); NetBind.apply(this)
            status.text = if (Prefs.bypassVpn) "Só este app ignora o VPN agora" else "Este app volta a usar o VPN"
        }
        col.addView(vpnBtn)
        val toolsBtn = Button(this)
        fun toolsText() {
            toolsBtn.text = "Ferramentas 🛠 nas páginas (seletor, gravador, CSV): " + if (Prefs.tools) "SIM" else "NÃO"
        }
        toolsText()
        toolsBtn.setOnClickListener {
            Prefs.tools = !Prefs.tools; toolsText(); ProxyBridge.setTools(Prefs.tools)
            status.text = "Vale nas próximas páginas carregadas"
        }
        col.addView(toolsBtn)
        btn("Atualizar proxys agora") { refreshProxies(true) }
        btn("Proxys manuais") {
            editDialog("Um por linha: ip:porta ou usuario:senha@ip:porta", Prefs.manualText) {
                Prefs.manualText = it; AppState.reloadManual()
                status.text = "${AppState.pool.manual.size} proxy(s) manual(is)"
            }
        }
        btn("Fontes de listas de proxy (URLs)") {
            editDialog("Uma URL por linha (arquivo .txt com ip:porta)", Prefs.sourcesText) { Prefs.sourcesText = it }
        }
        btn("Limpar cache") {
            GeckoHolder.get(this).storageController.clearData(StorageController.ClearFlags.ALL_CACHES)
            status.text = "Cache limpo"
        }
        btn("Limpar cookies e dados de sites") {
            GeckoHolder.get(this).storageController.clearData(
                StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.SITE_DATA)
            status.text = "Cookies e dados limpos"
        }
        btn("Importar script (.js) ou ZIP de scripts") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 10)
        }
        btn("Gerenciar scripts") { manageScripts() }
        btn("Não otimizar bateria (segundo plano)") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        btn("Diagnóstico: por que o app fechou?") { showDiag() }
        btn("Fechar todas as janelas") {
            startForegroundService(Intent(this, FloatService::class.java).setAction(FloatService.ACTION_STOP))
        }
        col.addView(status)
        setContentView(ScrollView(this).apply { addView(col) })

        refreshProxies() // ao abrir o app, já busca e testa proxys novos
    }

    private fun refreshProxies(force: Boolean = false) {
        scope.launch { AppState.pool.refresh(force) { status.text = it } }
    }

    private fun editDialog(title: String, initial: String, onOk: (String) -> Unit) {
        val et = EditText(this).apply {
            setText(initial); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5; gravity = android.view.Gravity.TOP
        }
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("Salvar") { _, _ -> onOk(et.text.toString()) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun manageScripts() {
        val names = ScriptStore.list()
        if (names.isEmpty()) { status.text = "Nenhum script importado"; return }
        val shown = names.map { (if (ScriptStore.enabled(it)) "✅ " else "⛔ ") + it }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Scripts").setItems(shown) { _, i ->
            val n = names[i]
            AlertDialog.Builder(this).setTitle(n).setItems(arrayOf("Ativar / desativar", "Remover")) { _, a ->
                if (a == 0) ScriptStore.toggle(n) else ScriptStore.remove(n)
                ProxyBridge.pushScripts()
                status.text = "Scripts atualizados (valem nas próximas páginas)"
            }.show()
        }.show()
    }

    private fun showDiag() {
        val sb = StringBuilder()
        if (Build.VERSION.SDK_INT >= 30) {
            val list = getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(packageName, 0, 5)
            if (list.isEmpty()) sb.append("Nenhum encerramento registrado.\n\n")
            val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
            list.forEach {
                sb.append(fmt.format(Date(it.timestamp))).append(" · ")
                    .append(CrashLog.reasonName(it.reason)).append("\n")
                if (!it.description.isNullOrEmpty()) sb.append(it.description).append("\n")
                sb.append("\n")
            }
        } else sb.append("Histórico indisponível (precisa de Android 11 ou superior).\n\n")
        val crash = CrashLog.read()
        sb.append(if (crash.isEmpty()) "Nenhum erro interno registrado." else "Último erro interno:\n$crash")
        val text = sb.toString()
        AlertDialog.Builder(this).setTitle("Diagnóstico").setMessage(text)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copiar") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("diagnostico", text))
                status.text = "Diagnóstico copiado"
            }.show()
    }

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r, c, d)
        if (r == 10 && c == RESULT_OK) d?.data?.let { uri ->
            val name = contentResolver.query(uri, null, null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(cur.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
            } ?: "script.js"
            val n = runCatching { contentResolver.openInputStream(uri)?.use { ScriptStore.importStream(name, it) } ?: 0 }.getOrDefault(0)
            ProxyBridge.pushScripts()
            status.text = "$n script(s) importado(s)"
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

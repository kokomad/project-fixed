package com.elephant.action3d

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FixerActivity : Activity() {

    private companion object {
        const val REQ_PICK = 101
        const val MAX_FILE_BYTES = 250_000
        const val MAX_TOTAL_BYTES = 700_000
        val TEXT_EXT = setOf(
            "kt", "kts", "java", "xml", "gradle", "properties", "json", "md", "txt",
            "yml", "yaml", "html", "js", "css", "ts", "py", "cs", "lua", "gd", "c",
            "cpp", "h", "toml", "cfg", "glsl", "pro", "sh"
        )
        val SKIP_DIRS = setOf("build", ".git", ".gradle", "node_modules", ".idea", "Pods", ".dart_tool")
        const val SYSTEM_PROMPT =
            "You are a senior Android and game developer. You receive the source files of a project. " +
            "Find real problems (compile errors, crashes, logic bugs, memory leaks, undisposed resources, " +
            "null-safety and threading issues, build configuration problems) and fix them. " +
            "If the user asked for improvements or new features, implement them too. " +
            "Rules: return ONLY files you changed or created, each with its FULL final content " +
            "(never placeholders such as '...' or 'rest unchanged'). Keep package names, architecture and " +
            "behavior unless a change is required. Do not touch files that have no problem. " +
            "Write the report in Arabic: list every fix with file name and reason, then list remaining risks. " +
            "If nothing needs changing, return an empty files array and explain in the report."
        const val SCHEMA =
            """{"type":"OBJECT","properties":{"report":{"type":"STRING"},"files":{"type":"ARRAY","items":{"type":"OBJECT","properties":{"path":{"type":"STRING"},"content":{"type":"STRING"}},"required":["path","content"]}}},"required":["report","files"]}"""
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var notesInput: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pickBtn: Button
    private lateinit var shareBtn: Button
    private var shareUri: Uri? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun lp(top: Int = 10) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun label(text: String, size: Float = 16f) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.WHITE)
    }

    private fun input(hint: String, value: String = "") = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("fixer", MODE_PRIVATE)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(28))
        }
        col.addView(label("Action 3D", 26f).apply { gravity = Gravity.CENTER })

        col.addView(button("🎮  تشغيل اللعبة") {
            startActivity(Intent(this, AndroidLauncher::class.java))
        }, lp(20))

        col.addView(label("🛠  مصلح وتطوير الألعاب والتطبيقات", 20f), lp(30))
        col.addView(label("ارفع مشروعك (ملف zip أو ملف كود واحد) وسيقوم الذكاء الاصطناعي بإصلاح الأخطاء وتطويره، ثم تحصل على ملف مضغوط جاهز.", 14f), lp(6))

        keyInput = input("مفتاح Gemini API", prefs.getString("key", "") ?: "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        col.addView(keyInput, lp(14))

        modelInput = input("اسم النموذج", prefs.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash")
        col.addView(modelInput, lp(4))

        notesInput = input("ماذا تريد تطويره؟ (اختياري)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        col.addView(notesInput, lp(4))

        pickBtn = button("📂  رفع لعبة أو تطبيق للإصلاح") { pickFile() }
        col.addView(pickBtn, lp(14))

        progress = ProgressBar(this).apply { visibility = View.GONE }
        col.addView(progress, lp(10))

        status = label("", 14f)
        status.setTextIsSelectable(true)
        col.addView(status, lp(10))

        shareBtn = button("📤  مشاركة الملف الناتج") { shareResult() }.apply { visibility = View.GONE }
        col.addView(shareBtn, lp(10))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF121212.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(col)
        })
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            data?.data?.let { startFix(it) }
        }
    }

    private fun startFix(uri: Uri) {
        val key = keyInput.text.toString().trim()
        val model = modelInput.text.toString().trim().ifEmpty { "gemini-2.5-flash" }
        if (key.isEmpty()) {
            status.text = "أدخل مفتاح Gemini API أولاً."
            return
        }
        prefs.edit().putString("key", key).putString("model", model).apply()
        val notes = notesInput.text.toString().trim()

        pickBtn.isEnabled = false
        shareBtn.visibility = View.GONE
        progress.visibility = View.VISIBLE
        status.text = "جاري قراءة الملف..."

        Thread {
            try {
                process(uri, key, model, notes)
            } catch (e: Exception) {
                ui { finish(false, "فشلت العملية: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun finish(ok: Boolean, message: String) {
        progress.visibility = View.GONE
        pickBtn.isEnabled = true
        status.text = message
        if (!ok) shareBtn.visibility = View.GONE
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx) ?: "project"
        }
        return "project"
    }

    private fun isZip(uri: Uri): Boolean =
        contentResolver.openInputStream(uri)?.use {
            val b = ByteArray(2)
            it.read(b) == 2 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte()
        } ?: false

    private fun isSafePath(entryName: String): Boolean {
        val normalized = entryName.replace('\\', '/')
        return !normalized.contains("../") && !normalized.startsWith("/")
    }

    private fun isSourceName(name: String): Boolean {
        val lower = name.lowercase()
        val segs = name.split('/')
        if (segs.dropLast(1).any { it in SKIP_DIRS }) return false
        if (lower.endsWith(".min.js") || lower.endsWith("package-lock.json")) return false
        val ext = lower.substringAfterLast('.', "")
        return ext in TEXT_EXT
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun process(uri: Uri, key: String, model: String, notes: String) {
        val name = displayName(uri)
        val zip = isZip(uri)
        val sources = LinkedHashMap<String, String>()
        var total = 0
        var skipped = 0

        if (zip) {
            ZipInputStream(BufferedInputStream(contentResolver.openInputStream(uri)!!)).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    if (!e.isDirectory && isSafePath(e.name) && isSourceName(e.name)) {
                        val bytes = readLimited(zin, MAX_FILE_BYTES)
                        if (bytes != null && total + bytes.size <= MAX_TOTAL_BYTES) {
                            sources[e.name] = String(bytes, Charsets.UTF_8)
                            total += bytes.size
                        } else skipped++
                    }
                    e = zin.nextEntry
                }
            }
        } else {
            val bytes = contentResolver.openInputStream(uri)!!.use { readLimited(it, MAX_FILE_BYTES) }
                ?: throw IOException("الملف كبير جداً")
            sources[name] = String(bytes, Charsets.UTF_8)
        }

        if (sources.isEmpty()) throw IOException("لم أجد ملفات كود قابلة للفحص داخل الملف المرفوع.")

        ui { status.text = "تم قراءة ${sources.size} ملف كود. جاري التحليل والإصلاح بالذكاء الاصطناعي (قد يستغرق دقائق)..." }

        val sb = StringBuilder()
        if (notes.isNotEmpty()) sb.append("User request (Arabic): ").append(notes).append("\n\n")
        sb.append("PROJECT FILES:\n")
        for ((p, c) in sources) sb.append("=== FILE: ").append(p).append(" ===\n").append(c).append("\n")

        val resp = callGemini(key, model, sb.toString())
        val out = parseResponse(resp)

        val report = out.optString("report", "")
        val changes = LinkedHashMap<String, String>()
        val arr = out.optJSONArray("files") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            val path = f.optString("path").replace("\\", "/").trimStart('/')
            if (path.isBlank() || !isSafePath(path)) continue
            changes[path] = f.optString("content")
        }

        if (changes.isEmpty()) {
            ui { finish(true, "لم يجد الذكاء الاصطناعي ما يحتاج تعديلاً.\n\n$report") }
            return
        }

        ui { status.text = "جاري بناء الملف المضغوط..." }
        val base = name.substringBeforeLast('.')
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outName = "${base}_fixed_$stamp.zip"

        var sharable: Uri? = null
        val savedWhere: String
        val os: java.io.OutputStream
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, outName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val outUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("تعذر إنشاء الملف في التنزيلات")
            os = contentResolver.openOutputStream(outUri)!!
            sharable = outUri
            savedWhere = "مجلد التنزيلات (Downloads)"
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            val f = File(dir, outName)
            os = f.outputStream()
            savedWhere = f.absolutePath
        }

        val written = HashSet<String>()
        ZipOutputStream(BufferedOutputStream(os)).use { zout ->
            if (zip) {
                ZipInputStream(BufferedInputStream(contentResolver.openInputStream(uri)!!)).use { zin ->
                    var e = zin.nextEntry
                    while (e != null) {
                        val n = e.name
                        if (isSafePath(n) && written.add(n)) {
                            zout.putNextEntry(ZipEntry(n))
                            if (!e.isDirectory) {
                                val repl = changes[n]
                                if (repl != null) zout.write(repl.toByteArray(Charsets.UTF_8))
                                else zin.copyTo(zout)
                            }
                            zout.closeEntry()
                        }
                        e = zin.nextEntry
                    }
                }
            } else if (!changes.containsKey(name)) {
                written.add(name)
                zout.putNextEntry(ZipEntry(name))
                contentResolver.openInputStream(uri)!!.use { it.copyTo(zout) }
                zout.closeEntry()
            }
            for ((p, c) in changes) {
                if (isSafePath(p) && written.add(p)) {
                    zout.putNextEntry(ZipEntry(p))
                    zout.write(c.toByteArray(Charsets.UTF_8))
                    zout.closeEntry()
                }
            }
            var reportName = "FIX_REPORT.md"
            while (!written.add(reportName)) reportName = "_$reportName"
            zout.putNextEntry(ZipEntry(reportName))
            zout.write(("# تقرير الإصلاح\n\n$report\n").toByteArray(Charsets.UTF_8))
            zout.closeEntry()
        }

        val warn = if (skipped > 0) "\n\n⚠️ تم تخطي $skipped ملف لأنها كبيرة جداً (بقيت كما هي)." else ""
        ui {
            shareUri = sharable
            shareBtn.visibility = if (sharable != null) View.VISIBLE else View.GONE
            finish(true, "✅ تم! عُدّل/أُضيف ${changes.size} ملف.\nالملف: $outName\nتم الحفظ في: $savedWhere$warn\n\n$report")
        }
    }

    private fun callGemini(key: String, model: String, user: String): String {
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .put("contents", JSONArray().put(
                JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", user)))
            ))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.2)
                .put("maxOutputTokens", 65536)
                .put("responseMimeType", "application/json")
                .put("responseSchema", JSONObject(SCHEMA)))

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 30_000
        c.readTimeout = 600_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = try {
                JSONObject(text).getJSONObject("error").getString("message")
            } catch (_: Exception) { text.take(300) }
            throw IOException("خطأ من الخادم ($code): $msg")
        }
        return text
    }

    private fun parseResponse(resp: String): JSONObject {
        val cand = JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)
            ?: throw IOException("لم يرجع النموذج أي رد")
        if (cand.optString("finishReason") == "MAX_TOKENS") {
            throw IOException("الرد طويل جداً وانقطع. جرّب مشروعاً أصغر أو اكتب طلباً أكثر تحديداً.")
        }
        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IOException("رد فارغ من النموذج")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return try {
            JSONObject(sb.toString())
        } catch (e: Exception) {
            throw IOException("تعذر قراءة رد النموذج، أعد المحاولة")
        }
    }

    private fun shareResult() {
        val u = shareUri ?: return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, u)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "مشاركة الملف"))
    }
}

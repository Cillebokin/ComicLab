package com.example.comiclab

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.File


class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var etPath: EditText
    private lateinit var btnBack: ImageButton

    private val fileItems = mutableListOf<FileItem>()

    private val permissions = mutableListOf<String>()

    private var useSaf = false
    // =========================
    // File（Android 12-）
    // =========================
    private var currentPath: String = CommonData.mainHomePath
    // =========================
    // SAF（Android 13+）
    // =========================
    private var currentDocDir: DocumentFile? = null
    private val docStack = ArrayDeque<DocumentFile>()
    private val prefs by lazy {
        getSharedPreferences("saf_prefs", MODE_PRIVATE)
    }

    //==============================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //==========================================================================================

        listView = findViewById(R.id.listFiles)
        etPath = findViewById(R.id.etPath)
        btnBack = findViewById(R.id.btnBack)

        // 入口逻辑
        if (Build.VERSION.SDK_INT >= 33) {
            tryRestoreSaf()
        } else {
            loadFilesByFile()
        }

        //==========================================================================================

        btnBack.setOnClickListener {
            goParent()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val item = fileItems[position]

            if (item.isParent) {
                goParent()
                return@setOnItemClickListener
            }

            // ========== File ==========
            item.file?.let { file ->
                if (file.isDirectory) {
                    currentPath = file.absolutePath
                    loadFilesByFile()
                } else {
                    Toast.makeText(this, "文件：${file.name}", Toast.LENGTH_SHORT).show()
                }
                return@setOnItemClickListener
            }

            // ========== SAF ==========
            item.docFile?.let { doc ->
                if (doc.isDirectory) {
                    currentDocDir?.let { docStack.addLast(it) }
                    openSafDir(doc)
                } else {
                    Toast.makeText(this, "文件：${doc.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    //==============================================================================================

    private fun goParent() {
        if (!useSaf) {
            if (currentPath != CommonData.mainHomePath) {
                currentPath = File(currentPath).parent ?: currentPath
                loadFilesByFile()
            }
            return
        }

        if (docStack.isNotEmpty()) {
            val parent = docStack.removeLast()
            openSafDir(parent)
        }
    }

    private fun updatePath() {
        etPath.setText(currentPath)
    }

    //==============================================================================================

    private fun loadFilesByFile() {
        useSaf = false

        Thread {
            val dir = File(currentPath)
            val files = dir.listFiles()?.filter { !it.name.startsWith(".") }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()

            val items = mutableListOf<FileItem>()
            if (currentPath != CommonData.mainHomePath) {
                items.add(FileItem(isParent = true))
            }
            files.forEach { items.add(FileItem(file = it)) }

            // 回到主线程更新 UI
            runOnUiThread {
                fileItems.clear()
                fileItems.addAll(items)

                // Adapter 不建议每次 new 一个
                if (listView.adapter == null) {
                    listView.adapter = FileListAdapter(this, fileItems)
                } else {
                    (listView.adapter as FileListAdapter).notifyDataSetChanged()
                }

                etPath.setText(currentPath)
            }
        }.start()
    }

    //==============================================================================================

    private fun tryRestoreSaf() {
        val uriStr = prefs.getString("tree_uri", null)
        if (uriStr != null) {
            try {
                val uri = Uri.parse(uriStr)
                val doc = DocumentFile.fromTreeUri(this, uri)
                if (doc != null && doc.isDirectory) {
                    useSaf = true
                    docStack.clear()
                    openSafDir(doc)
                    return
                }
            } catch (_: Exception) {
            }
        }
        openSafRoot()
    }

    private fun openSafRoot() {
        openTreeLauncher.launch(null)
    }

    private val openTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                Toast.makeText(this, "未选择目录", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            prefs.edit()
                .putString("tree_uri", uri.toString())
                .apply()

            val root = DocumentFile.fromTreeUri(this, uri)
            if (root == null || !root.isDirectory) {
                Toast.makeText(this, "无效目录", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            useSaf = true
            docStack.clear()
            openSafDir(root)
        }

    private fun openSafDir(dir: DocumentFile) {
        useSaf = true
        currentDocDir = dir

        Thread {
            val docs = dir.listFiles()?.filter { it.name?.startsWith(".") == false }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() }))
                ?: emptyList()

            val items = mutableListOf<FileItem>()
            if (docStack.isNotEmpty()) {
                items.add(FileItem(isParent = true))
            }
            docs.forEach { items.add(FileItem(docFile = it)) }

            runOnUiThread {
                fileItems.clear()
                fileItems.addAll(items)

                if (listView.adapter == null) {
                    listView.adapter = FileListAdapter(this, fileItems)
                } else {
                    (listView.adapter as FileListAdapter).notifyDataSetChanged()
                }

                etPath.setText(dir.name ?: "SAF")
            }
        }.start()
    }
}

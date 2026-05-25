package com.nx111.llama

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.EngineBackend
import com.arm.aichat.InferenceEngine
import com.arm.aichat.InferenceOptions
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var settingsBtn: MaterialButton
    private lateinit var newChatBtn: MaterialButton
    private lateinit var installScreen: View
    private lateinit var useScreen: View
    private lateinit var modelStatusTv: TextView
    private lateinit var conversationModeTv: TextView
    private lateinit var addModelTabBtn: MaterialButton
    private lateinit var modelListTabBtn: MaterialButton
    private lateinit var addModelSection: View
    private lateinit var installedModelSection: View
    private lateinit var modelSourceSpinner: Spinner
    private lateinit var huggingFaceSection: View
    private lateinit var localModelSection: View
    private lateinit var openAiModelSection: View
    private lateinit var hfSearchEt: EditText
    private lateinit var installedModelsRv: RecyclerView
    private lateinit var hotModelsBtn: MaterialButton
    private lateinit var latestModelsBtn: MaterialButton
    private lateinit var searchModelBtn: MaterialButton
    private lateinit var hfPrevPageBtn: MaterialButton
    private lateinit var hfNextPageBtn: MaterialButton
    private lateinit var hfPageStatusTv: TextView
    private lateinit var importBtn: MaterialButton
    private lateinit var configureOpenAiModelBtn: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var hubModelsRv: RecyclerView
    private lateinit var messagesRv: RecyclerView
    private lateinit var conversationPlusBtn: MaterialButton
    private lateinit var userInputEt: EditText
    private lateinit var userActionFab: FloatingActionButton

    private lateinit var modelRepository: ModelRepository
    private val openAiClient = OpenAiCompatibleClient()
    private var appSettings = AppSettings()
    private var engine: InferenceEngine? = null
    private var generationJob: Job? = null
    private var isBusy = true
    private var isModelReady = false
    private var currentModelLabel: String? = null
    private var currentAgentMode = AgentMode.CHAT
    private var currentSessionId = UUID.randomUUID().toString()
    private var didLoadHubModels = false
    private var hubPage = 1
    private var hubSort = HuggingFaceSort.HOT
    private var hubQuery: String? = null
    private var hubHasNextPage = false
    private var pendingPublicStorageSelection = false
    private var assistantRenderJob: Job? = null
    private var shouldAutoScrollAssistant = false

    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages) { position ->
        showMessageActions(position)
    }
    private val installedModelAdapter = InstalledModelAdapter(
        onClick = { model -> loadInstalledModel(model) },
        onLongClick = { model -> showInstalledModelActions(model) }
    )
    private val hubModelAdapter = HuggingFaceModelAdapter { model -> showQuantizationPicker(model) }

    private val pickModel = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importModel(uri)
    }

    private val pickAttachment = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) attachFileToInput(uri)
    }

    private val publicStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (pendingPublicStorageSelection && hasPublicStoragePermission()) {
            openPublicModelDirectoryPicker()
        } else {
            pendingPublicStorageSelection = false
            Toast.makeText(this, "需要外部存储读写权限", Toast.LENGTH_SHORT).show()
        }
    }

    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (pendingPublicStorageSelection && hasPublicStoragePermission()) {
            openPublicModelDirectoryPicker()
        } else {
            pendingPublicStorageSelection = false
            Toast.makeText(this, "需要所有文件访问权限", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickPublicModelDir = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        pendingPublicStorageSelection = false
        if (uri != null) usePublicModelDirectory(uri)
    }

    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) backupState(uri)
    }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) restoreState(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback {
            when {
                generationJob?.isActive == true -> stopGeneration()
                installScreen.visibility == View.VISIBLE -> showScreen(Screen.USE)
                else -> finish()
            }
        }

        modelRepository = ModelRepository(applicationContext)
        appSettings = loadAppSettings()
        if (appSettings.modelProvider == ModelProvider.OPENAI_COMPATIBLE && appSettings.openAiModel.isNotBlank()) {
            currentModelLabel = "OpenAI兼容: ${appSettings.openAiModel}"
        } else if (appSettings.localModelPath.isNotBlank()) {
            currentModelLabel = File(appSettings.localModelPath).nameWithoutExtension
        }
        currentAgentMode = appSettings.agentMode
        bindViews()
        setupLists()
        migrateLegacyMessages()
        startEmptySession(appSettings.agentMode, persistPrevious = false)
        refreshInstalledModels()
        wireActions()
        showScreen(Screen.USE)
        setBusy(true, "引擎初始化中...")

        lifecycleScope.launch {
            val createdEngine = withContext(Dispatchers.Default) {
                AiChat.getInferenceEngine(applicationContext)
            }
            engine = createdEngine
            createdEngine.state.collect { state -> renderEngineState(state) }
        }
    }

    private fun bindViews() {
        settingsBtn = findViewById(R.id.settings_button)
        newChatBtn = findViewById(R.id.new_chat_button)
        installScreen = findViewById(R.id.install_screen)
        useScreen = findViewById(R.id.use_screen)
        modelStatusTv = findViewById(R.id.model_status)
        conversationModeTv = findViewById(R.id.conversation_mode)
        addModelTabBtn = findViewById(R.id.add_model_tab)
        modelListTabBtn = findViewById(R.id.model_list_tab)
        addModelSection = findViewById(R.id.add_model_section)
        installedModelSection = findViewById(R.id.installed_model_section)
        modelSourceSpinner = findViewById(R.id.model_source_spinner)
        huggingFaceSection = findViewById(R.id.hugging_face_section)
        localModelSection = findViewById(R.id.local_model_section)
        openAiModelSection = findViewById(R.id.openai_model_section)
        hfSearchEt = findViewById(R.id.hf_search)
        installedModelsRv = findViewById(R.id.installed_models)
        hotModelsBtn = findViewById(R.id.load_hot_models)
        latestModelsBtn = findViewById(R.id.load_latest_models)
        searchModelBtn = findViewById(R.id.search_model)
        hfPrevPageBtn = findViewById(R.id.hf_prev_page)
        hfNextPageBtn = findViewById(R.id.hf_next_page)
        hfPageStatusTv = findViewById(R.id.hf_page_status)
        importBtn = findViewById(R.id.import_model)
        configureOpenAiModelBtn = findViewById(R.id.configure_openai_model)
        progressBar = findViewById(R.id.download_progress)
        hubModelsRv = findViewById(R.id.hf_models)
        messagesRv = findViewById(R.id.messages)
        conversationPlusBtn = findViewById(R.id.conversation_plus)
        userInputEt = findViewById(R.id.user_input)
        userActionFab = findViewById(R.id.fab)
    }

    private fun setupLists() {
        installedModelsRv.layoutManager = LinearLayoutManager(this)
        installedModelsRv.adapter = installedModelAdapter

        hubModelsRv.layoutManager = LinearLayoutManager(this)
        hubModelsRv.adapter = hubModelAdapter

        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.itemAnimator = null
        messagesRv.adapter = messageAdapter

        modelSourceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            AddModelSource.values().map { it.label }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun wireActions() {
        settingsBtn.setOnClickListener { showAppMenu() }
        newChatBtn.setOnClickListener { showConversationMenu(newChatBtn) }
        modelStatusTv.setOnClickListener { showCurrentModelPicker() }
        conversationPlusBtn.setOnClickListener { showAttachmentMenu() }
        addModelTabBtn.setOnClickListener { showInstallPanel(InstallPanel.ADD_MODEL) }
        modelListTabBtn.setOnClickListener { showInstallPanel(InstallPanel.MODEL_LIST) }
        hotModelsBtn.setOnClickListener { resetAndLoadHubModels(HuggingFaceSort.HOT, null) }
        latestModelsBtn.setOnClickListener { resetAndLoadHubModels(HuggingFaceSort.LATEST, null) }
        hfPrevPageBtn.setOnClickListener {
            if (hubPage > 1) {
                hubPage -= 1
                loadHubModels()
            }
        }
        hfNextPageBtn.setOnClickListener {
            if (hubHasNextPage) {
                hubPage += 1
                loadHubModels()
            }
        }
        configureOpenAiModelBtn.setOnClickListener { showOpenAiModelDialog() }
        searchModelBtn.setOnClickListener {
            val query = hfSearchEt.text.toString().trim()
            if (query.isBlank()) {
                Toast.makeText(this, "请输入搜索关键词", Toast.LENGTH_SHORT).show()
            } else {
                resetAndLoadHubModels(HuggingFaceSort.SEARCH, query)
            }
        }
        importBtn.setOnClickListener { pickModel.launch(arrayOf("*/*")) }
        userActionFab.setOnClickListener {
            if (generationJob?.isActive == true) {
                stopGeneration()
            } else {
                handleUserInput()
            }
        }
        modelSourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val source = AddModelSource.values()[position.coerceIn(0, AddModelSource.values().lastIndex)]
                updateAddModelSource(source)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun showScreen(screen: Screen) {
        val installing = screen == Screen.INSTALL
        installScreen.visibility = if (installing) View.VISIBLE else View.GONE
        useScreen.visibility = if (installing) View.GONE else View.VISIBLE
    }

    private fun showConversationMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_CHAT, 0, "新建聊天")
            menu.add(0, MENU_WRITING, 1, "新建写作")
            menu.add(0, MENU_TRANSLATION, 2, "新建翻译")
            menu.add(0, MENU_IMAGE_PROMPT, 3, "新建生图提示词")
            menu.add(0, MENU_ADD_MODEL, 4, "添加模型")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_CHAT -> startConversation(AgentMode.CHAT)
                    MENU_WRITING -> startConversation(AgentMode.WRITING)
                    MENU_TRANSLATION -> startConversation(AgentMode.TRANSLATION)
                    MENU_IMAGE_PROMPT -> startConversation(AgentMode.IMAGE_PROMPT)
                    MENU_ADD_MODEL -> showInstallScreen()
                }
                true
            }
            show()
        }
    }

    private fun showAttachmentMenu() {
        PopupMenu(this, conversationPlusBtn).apply {
            menu.add(0, MENU_ATTACH_TEXT, 0, "添加文本文件")
            menu.add(0, MENU_ATTACH_IMAGE, 1, "添加图片")
            menu.add(0, MENU_ATTACH_FILE, 2, "添加文件")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_ATTACH_TEXT -> pickAttachment.launch(arrayOf("text/*"))
                    MENU_ATTACH_IMAGE -> pickAttachment.launch(arrayOf("image/*"))
                    MENU_ATTACH_FILE -> pickAttachment.launch(arrayOf("*/*"))
                }
                true
            }
            show()
        }
    }

    private fun showAppMenu() {
        saveMessages()
        val sessionItems = loadSessions()
        val menuWidth = minOf(resources.displayMetrics.widthPixels - 48.dp(), 320.dp())
        val sessionRowCount = sessionItems.size.coerceAtLeast(1)
        val menuHeight = minOf(48.dp() + 1 + sessionRowCount * 48.dp(), 420.dp())
        lateinit var popup: PopupWindow
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = appMenuBackground()
            addView(appMenuRow(getString(R.string.settings), enabled = true).apply {
                setOnClickListener {
                    popup.dismiss()
                    showSettingsDialog()
                }
            })
            addView(appMenuDivider())
            if (sessionItems.isEmpty()) {
                addView(appMenuRow(getString(R.string.no_session_history), enabled = false))
            } else {
                sessionItems.forEach { session ->
                    addView(appMenuRow(session.title, enabled = true).apply {
                        setOnClickListener {
                            popup.dismiss()
                            loadSession(session)
                        }
                        setOnLongClickListener {
                            popup.dismiss()
                            confirmDeleteSession(session)
                            true
                        }
                    })
                }
            }
        }
        val scrollView = ScrollView(this).apply {
            isFillViewport = false
            addView(content)
        }
        popup = PopupWindow(scrollView, menuWidth, menuHeight, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 8.dp().toFloat()
            }
        }
        popup.showAsDropDown(settingsBtn, 0, 4.dp())
    }

    private fun confirmDeleteSession(session: ConversationSession) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除会话")
            .setMessage("确定删除“${session.title}”？")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("删除") { _, _ -> deleteSession(session) }
            .show()
    }

    private fun deleteSession(session: ConversationSession) {
        writeSessions(loadSessions().filterNot { it.id == session.id })
        if (session.id == currentSessionId) {
            startEmptySession(currentAgentMode, persistPrevious = false)
        }
        Toast.makeText(this, "会话已删除", Toast.LENGTH_SHORT).show()
    }

    private fun appMenuRow(text: String, enabled: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            isEnabled = enabled
            maxLines = 1
            minHeight = 48.dp()
            setPadding(20.dp(), 0, 20.dp(), 0)
            setTextColor(themeTextColor(if (enabled) android.R.attr.textColorPrimary else android.R.attr.textColorSecondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }

    private fun appMenuDivider(): View =
        View(this).apply {
            setBackgroundColor(themeTextColor(android.R.attr.textColorSecondary))
            alpha = 0.22f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }

    private fun appMenuBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12.dp().toFloat()
            setColor(themeColor(com.google.android.material.R.attr.colorSurface))
            val borderColor = themeTextColor(android.R.attr.textColorSecondary)
            setStroke(
                1.dp(),
                Color.argb(
                    96,
                    Color.red(borderColor),
                    Color.green(borderColor),
                    Color.blue(borderColor)
                )
            )
        }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun themeTextColor(attr: Int): Int {
        val typedArray = theme.obtainStyledAttributes(intArrayOf(attr))
        return try {
            typedArray.getColor(0, themeColor(android.R.attr.textColorPrimary))
        } finally {
            typedArray.recycle()
        }
    }

    private fun startConversation(mode: AgentMode) {
        startEmptySession(mode, persistPrevious = true)
    }

    private fun startEmptySession(mode: AgentMode, persistPrevious: Boolean) {
        if (persistPrevious) saveMessages()
        currentSessionId = UUID.randomUUID().toString()
        currentAgentMode = mode
        appSettings = appSettings.copy(agentMode = mode)
        messages.clear()
        lastAssistantMsg.clear()
        messageAdapter.notifyDataSetChanged()
        updateConversationMode()
        showScreen(Screen.USE)
        saveMessages()
        saveAppSettings()
        refreshControls()
    }

    private fun updateConversationMode() {
        conversationModeTv.text = currentAgentMode.label
    }

    private fun showMessageActions(position: Int) {
        if (position !in messages.indices) return
        val message = messages[position]
        MaterialAlertDialogBuilder(this)
            .setItems(arrayOf("复制", "修改")) { _, index ->
                when (index) {
                    0 -> copyMessage(message)
                    1 -> showEditMessageDialog(position)
                }
            }
            .show()
    }

    private fun copyMessage(message: Message) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("message", message.content))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun showEditMessageDialog(position: Int) {
        if (generationJob?.isActive == true) {
            Toast.makeText(this, "请先中断生成", Toast.LENGTH_SHORT).show()
            return
        }
        if (position !in messages.indices) return
        val editText = EditText(this).apply {
            setText(messages[position].content)
            setSelection(text?.length ?: 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 8
            setPadding(16.dp(), 8.dp(), 16.dp(), 8.dp())
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("修改发言")
            .setView(editText)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("保存") { _, _ ->
                val updatedContent = editText.text?.toString().orEmpty()
                if (updatedContent.isBlank()) {
                    Toast.makeText(this, "内容不能为空", Toast.LENGTH_SHORT).show()
                } else {
                    applyEditedMessage(position, updatedContent)
                }
            }
            .show()
    }

    private fun applyEditedMessage(position: Int, updatedContent: String) {
        if (position !in messages.indices) return
        val original = messages[position]
        messages[position] = original.copy(content = updatedContent)
        messageAdapter.notifyItemChanged(position, MESSAGE_CONTENT_PAYLOAD)

        val removeStart = position + 1
        val removeCount = messages.size - removeStart
        if (removeCount > 0) {
            messages.subList(removeStart, messages.size).clear()
            messageAdapter.notifyItemRangeRemoved(removeStart, removeCount)
        }
        lastAssistantMsg.clear()
        saveMessages()
        if (original.isUser) {
            generateReplyForUserAt(position)
        }
    }

    private fun attachFileToInput(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val name = attachmentName(uri)
                val mimeType = contentResolver.getType(uri).orEmpty()
                val attachmentText = if (isTextAttachment(name, mimeType)) {
                    val body = withContext(Dispatchers.IO) { readTextAttachment(uri) }
                    val truncated = body.length >= MAX_ATTACHMENT_TEXT_CHARS
                    buildString {
                        append("[附件: ")
                        append(name)
                        append("]\n")
                        append(body)
                        if (truncated) append("\n[附件内容已截断]")
                        append("\n[/附件]")
                    }
                } else {
                    "[附件: $name${mimeType.takeIf { it.isNotBlank() }?.let { ", 类型: $it" }.orEmpty()}]"
                }
                appendToInput(attachmentText)
                name
            }.onSuccess { name ->
                Toast.makeText(this@MainActivity, "已添加附件: $name", Toast.LENGTH_SHORT).show()
            }.onFailure { showError(it) }
        }
    }

    private fun appendToInput(text: String) {
        val current = userInputEt.text?.toString().orEmpty()
        val separator = if (current.isBlank()) "" else "\n\n"
        userInputEt.setText(current + separator + text)
        userInputEt.setSelection(userInputEt.text?.length ?: 0)
    }

    private fun attachmentName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "附件"
    }

    private fun isTextAttachment(name: String, mimeType: String): Boolean {
        if (mimeType.startsWith("text/")) return true
        val lowerName = name.lowercase()
        return TEXT_ATTACHMENT_EXTENSIONS.any { lowerName.endsWith(it) }
    }

    private fun readTextAttachment(uri: Uri): String =
        contentResolver.openInputStream(uri)?.use { input ->
            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                val builder = StringBuilder()
                while (builder.length < MAX_ATTACHMENT_TEXT_CHARS) {
                    val read = reader.read(buffer, 0, minOf(buffer.size, MAX_ATTACHMENT_TEXT_CHARS - builder.length))
                    if (read == -1) break
                    builder.append(buffer, 0, read)
                }
                builder.toString()
            }
        } ?: error("无法读取附件")

    private fun showInstallScreen() {
        showScreen(Screen.INSTALL)
        showInstallPanel(InstallPanel.ADD_MODEL)
        refreshInstalledModels()
        modelSourceSpinner.setSelection(AddModelSource.HUGGING_FACE.ordinal)
        updateAddModelSource(AddModelSource.HUGGING_FACE)
        loadHubModelsOnce()
    }

    private fun showInstallPanel(panel: InstallPanel) {
        val showAddModel = panel == InstallPanel.ADD_MODEL
        addModelSection.visibility = if (showAddModel) View.VISIBLE else View.GONE
        installedModelSection.visibility = if (showAddModel) View.GONE else View.VISIBLE
        addModelTabBtn.isChecked = showAddModel
        modelListTabBtn.isChecked = !showAddModel
        if (!showAddModel) refreshInstalledModels()
    }

    private fun updateAddModelSource(source: AddModelSource) {
        huggingFaceSection.visibility = if (source == AddModelSource.HUGGING_FACE) View.VISIBLE else View.GONE
        localModelSection.visibility = if (source == AddModelSource.LOCAL_GGUF) View.VISIBLE else View.GONE
        openAiModelSection.visibility = if (source == AddModelSource.OPENAI_COMPATIBLE) View.VISIBLE else View.GONE
        if (source == AddModelSource.HUGGING_FACE) loadHubModelsOnce()
    }

    private fun refreshInstalledModels() {
        lifecycleScope.launch {
            val models = modelRepository.listInstalledModels(installedModelDirs())
            installedModelAdapter.submitList(models)
        }
    }

    private fun loadInstalledModel(model: InstalledModel) {
        if (isBusy) return
        lifecycleScope.launch {
            runCatching {
                setBusy(true, "正在加载已安装模型...")
                loadModel(model.file, persist = true)
            }.onFailure { showError(it) }
            refreshControls()
        }
    }

    private fun showCurrentModelPicker() {
        if (isBusy) return
        lifecycleScope.launch {
            runCatching {
                val models = modelRepository.listInstalledModels(installedModelDirs())
                if (models.isEmpty()) {
                    Toast.makeText(this@MainActivity, "没有已安装模型", Toast.LENGTH_SHORT).show()
                } else {
                    showModelPicker(models, "选择模型")
                }
            }.onFailure { showError(it) }
        }
    }

    private fun showInstalledModelActions(model: InstalledModel) {
        if (isBusy) return
        MaterialAlertDialogBuilder(this)
            .setTitle(model.name)
            .setItems(arrayOf("卸载", "删除", "迁移到当前模型目录")) { _, index ->
                when (index) {
                    0 -> unloadInstalledModel(model)
                    1 -> confirmDeleteInstalledModel(model)
                    2 -> migrateInstalledModel(model)
                }
            }
            .show()
    }

    private fun unloadInstalledModel(model: InstalledModel) {
        if (!isCurrentLocalModel(model.file)) {
            Toast.makeText(this, "该模型当前未加载", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            runCatching {
                engine?.cleanUp()
                isModelReady = false
                currentModelLabel = null
                appSettings = appSettings.copy(localModelPath = "")
                saveAppSettings()
            }.onSuccess {
                modelStatusTv.text = statusLine(engine)
                Toast.makeText(this@MainActivity, "模型已卸载", Toast.LENGTH_SHORT).show()
                refreshControls()
            }.onFailure { showError(it) }
        }
    }

    private fun confirmDeleteInstalledModel(model: InstalledModel) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除模型")
            .setMessage("确定删除 ${model.file.name}？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> deleteInstalledModel(model) }
            .show()
    }

    private fun deleteInstalledModel(model: InstalledModel) {
        lifecycleScope.launch {
            runCatching {
                setBusy(true, "正在删除模型...")
                if (isCurrentLocalModel(model.file)) {
                    engine?.cleanUp()
                    isModelReady = false
                    currentModelLabel = null
                    appSettings = appSettings.copy(localModelPath = "")
                }
                modelRepository.deleteInstalledModel(model.file)
                saveAppSettings()
                refreshInstalledModels()
            }.onSuccess {
                setBusy(false, statusLine(engine))
                Toast.makeText(this@MainActivity, "模型已删除", Toast.LENGTH_SHORT).show()
            }.onFailure { showError(it) }
        }
    }

    private fun migrateInstalledModel(model: InstalledModel) {
        lifecycleScope.launch {
            runCatching {
                setBusy(true, "正在迁移模型...")
                val target = modelRepository.migrateInstalledModel(model.file, modelStorageDir())
                if (isCurrentLocalModel(model.file)) {
                    appSettings = appSettings.copy(localModelPath = target.absolutePath)
                    saveAppSettings()
                }
                refreshInstalledModels()
                target
            }.onSuccess {
                setBusy(false, statusLine(engine))
                Toast.makeText(this@MainActivity, "模型已迁移", Toast.LENGTH_SHORT).show()
            }.onFailure { showError(it) }
        }
    }

    private fun isCurrentLocalModel(file: File): Boolean =
        appSettings.modelProvider == ModelProvider.LOCAL &&
            appSettings.localModelPath.isNotBlank() &&
            File(appSettings.localModelPath).safeCanonicalPath() == file.safeCanonicalPath()

    private fun loadHubModelsOnce() {
        if (didLoadHubModels || isBusy || engine == null) return
        didLoadHubModels = true
        resetAndLoadHubModels(HuggingFaceSort.HOT, null)
    }

    private fun loadLocalModelForChat(onReady: () -> Unit) {
        lifecycleScope.launch {
            runCatching {
                val savedFile = savedLocalModelFile()
                val savedModel = savedFile?.takeIf { it.isFile && it.canRead() }
                if (savedModel != null) {
                    setBusy(true, "正在加载模型...")
                    loadModel(savedModel, persist = true)
                    return@runCatching true
                }

                if (savedFile != null) {
                    currentModelLabel = null
                    appSettings = appSettings.copy(localModelPath = "")
                    saveAppSettings()
                    modelStatusTv.text = statusLine(engine)
                }

                val installedModels = modelRepository.listInstalledModels(installedModelDirs())
                when (installedModels.size) {
                    0 -> {
                        Toast.makeText(this@MainActivity, "请先添加模型", Toast.LENGTH_SHORT).show()
                        false
                    }
                    1 -> {
                        setBusy(true, "正在加载模型...")
                        loadModel(installedModels.first().file, persist = true)
                        true
                    }
                    else -> {
                        showModelPicker(installedModels, "选择模型") {
                            onReady()
                        }
                        false
                    }
                }
            }.onSuccess { loaded ->
                if (loaded) onReady()
            }.onFailure { showError(it) }
            refreshControls()
        }
    }

    private fun showModelPicker(models: List<InstalledModel>, title: String, onLoaded: (() -> Unit)? = null) {
        val items = models.map { model ->
            "${model.name}  ${model.sizeLabel}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(items) { _, index ->
                lifecycleScope.launch {
                    runCatching {
                        setBusy(true, "正在加载选择的模型...")
                        loadModel(models[index].file, persist = true)
                    }.onSuccess {
                        onLoaded?.invoke()
                    }.onFailure { showError(it) }
                    refreshControls()
                }
            }
            .show()
    }

    private fun savedLocalModelFile(): File? =
        appSettings.localModelPath
            .takeIf { it.isNotBlank() }
            ?.let { File(it) }

    private fun modelStorageDir(): File =
        modelRepository.modelsDir(appSettings.modelStoragePath)

    private fun installedModelDirs(): List<File> =
        buildList {
            add(modelStorageDir())
            add(modelRepository.defaultModelsDir)
            modelRepository.externalModelsDir?.let { add(it) }
        }.distinctBy { it.safeCanonicalPath() }

    private fun resetAndLoadHubModels(sort: HuggingFaceSort, query: String?) {
        hubSort = sort
        hubQuery = query
        hubPage = 1
        loadHubModels()
    }

    private fun loadHubModels() {
        lifecycleScope.launch {
            runCatching {
                setBusy(true, if (hubQuery == null) "正在加载 Hugging Face 模型..." else "正在搜索 Hugging Face...")
                val models = modelRepository.listHuggingFaceModels(
                    query = hubQuery,
                    sort = hubSort,
                    baseUrl = appSettings.huggingFaceBaseUrl,
                    token = appSettings.huggingFaceToken.ifBlank { null },
                    limit = HUGGING_FACE_PAGE_SIZE + 1,
                    page = hubPage
                )
                hubHasNextPage = models.size > HUGGING_FACE_PAGE_SIZE
                val pageModels = models.take(HUGGING_FACE_PAGE_SIZE)
                hubModelAdapter.submitList(pageModels)
                updateHubPager()
                setBusy(false, if (pageModels.isEmpty()) "没有找到可直接下载的 GGUF 模型" else statusLine(engine))
            }.onFailure {
                didLoadHubModels = false
                showError(it)
            }
            refreshControls()
        }
    }

    private fun updateHubPager() {
        hfPageStatusTv.text = "第 $hubPage 页"
        hfPrevPageBtn.isEnabled = !isBusy && hubPage > 1
        hfNextPageBtn.isEnabled = !isBusy && hubHasNextPage
    }

    private fun importModel(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                setBusy(true, "正在导入本地 GGUF...")
                val file = modelRepository.importModel(uri, targetDir = modelStorageDir())
                loadModel(file, persist = true)
                refreshInstalledModels()
            }.onFailure { showError(it) }
            refreshControls()
        }
    }

    private fun showQuantizationPicker(model: HuggingFaceModel) {
        if (isBusy) return
        val items = model.files.map { file ->
            "${file.quantization}  ${file.sizeLabel}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(model.displayName)
            .setItems(items) { _, index -> downloadModel(model, model.files[index]) }
            .show()
    }

    private fun downloadModel(model: HuggingFaceModel, selectedFile: HuggingFaceFile) {
        if (isBusy) return
        lifecycleScope.launch {
            runCatching {
                if (model.gated && appSettings.huggingFaceToken.isBlank()) {
                    error("该模型需要 Hugging Face 令牌")
                }
                setBusy(true, "正在下载 ${model.displayName} ${selectedFile.quantization}...")
                progressBar.progress = 0
                progressBar.visibility = View.VISIBLE
                val file = modelRepository.downloadFromHuggingFace(
                    repo = model.repoId,
                    filename = selectedFile.filename,
                    baseUrl = appSettings.huggingFaceBaseUrl,
                    targetDir = modelStorageDir(),
                    token = appSettings.huggingFaceToken.ifBlank { null }
                ) { progress ->
                    withContext(Dispatchers.Main) {
                        progressBar.progress = progress
                        modelStatusTv.text = "下载中... $progress%\n${selectedFile.filename}"
                    }
                }
                loadModel(file, persist = true)
                refreshInstalledModels()
            }.onFailure { showError(it) }
            progressBar.visibility = View.GONE
            refreshControls()
        }
    }

    private suspend fun loadModel(modelFile: File, persist: Boolean) {
        val activeEngine = engine ?: error("Engine is not ready")
        if (isModelReady) {
            activeEngine.cleanUp()
            isModelReady = false
        }

        setBusy(true, "正在读取 GGUF 元数据...")
        val metadata = withContext(Dispatchers.IO) {
            FileInputStream(modelFile).use { GgufMetadataReader.create().readStructuredMetadata(it) }
        }
        currentModelLabel = metadata.toModelLabel(modelFile)
        modelStatusTv.text = "模型: $currentModelLabel\n正在加载..."

        val options = InferenceOptions(
            backend = appSettings.backend.backend,
            contextSize = appSettings.contextSize,
            threads = appSettings.threads
        )
        activeEngine.loadModel(modelFile.path, options)
        if (persist) {
            appSettings = appSettings.copy(
                modelProvider = ModelProvider.LOCAL,
                localModelPath = modelFile.absolutePath
            )
        }
        isModelReady = true
        isBusy = false
        saveAppSettings()
        modelStatusTv.text = statusLine(activeEngine)
        showScreen(Screen.USE)
        refreshControls()
    }

    private fun handleUserInput() {
        val userMsg = userInputEt.text.toString().trim()
        if (userMsg.isBlank()) {
            Toast.makeText(this, "请输入内容", Toast.LENGTH_SHORT).show()
            return
        }
        if (appSettings.modelProvider == ModelProvider.LOCAL && !isModelReady) {
            loadLocalModelForChat { submitUserMessage(userMsg) }
            return
        }
        if (!activeModelReady()) {
            Toast.makeText(this, "请先添加并加载模型", Toast.LENGTH_SHORT).show()
            return
        }

        submitUserMessage(userMsg)
    }

    private fun submitUserMessage(userMsg: String) {
        userInputEt.text = null
        val firstInsertedIndex = messages.size
        messages.add(Message(UUID.randomUUID().toString(), userMsg, true))
        messageAdapter.notifyItemInserted(firstInsertedIndex)
        messagesRv.scrollToPosition(messages.lastIndex)
        generateReplyForUserAt(firstInsertedIndex)
    }

    private fun generateReplyForUserAt(userIndex: Int) {
        if (userIndex !in messages.indices || !messages[userIndex].isUser) return
        if (appSettings.modelProvider == ModelProvider.LOCAL && !isModelReady) {
            loadLocalModelForChat { generateReplyForUserAt(userIndex) }
            return
        }
        if (!activeModelReady()) {
            Toast.makeText(this, "请先添加并加载模型", Toast.LENGTH_SHORT).show()
            return
        }

        setBusy(true, "生成中...")

        val userMsg = messages[userIndex].content
        val prompt = currentAgentMode.prompt(userMsg)
        val history = messages.take(userIndex)
        val assistantInsertedIndex = messages.size
        lastAssistantMsg.clear()
        messages.add(Message(UUID.randomUUID().toString(), "", false))
        shouldAutoScrollAssistant = true
        messageAdapter.notifyItemInserted(assistantInsertedIndex)
        messagesRv.scrollToPosition(messages.lastIndex)
        if (appSettings.modelProvider == ModelProvider.OPENAI_COMPATIBLE) {
            trackGenerationJob(lifecycleScope.launch {
                runCatching {
                    openAiClient.complete(
                        baseUrl = appSettings.openAiBaseUrl,
                        apiKey = appSettings.openAiApiKey.ifBlank { null },
                        model = appSettings.openAiModel,
                        history = history,
                        prompt = prompt,
                        maxTokens = appSettings.predictLength
                    )
                }.onSuccess { reply ->
                    appendAssistantToken(reply)
                    finishGeneration()
                }.onFailure { error ->
                    handleGenerationFailure(error)
                }
                refreshControls()
            })
        } else {
            val activeEngine = engine ?: return
            trackGenerationJob(lifecycleScope.launch(Dispatchers.Default) {
                runCatching {
                    activeEngine.sendUserPrompt(prompt, appSettings.predictLength)
                        .onCompletion {
                            withContext(Dispatchers.Main) {
                                finishGeneration()
                                refreshControls()
                            }
                        }
                        .collect { token ->
                            withContext(Dispatchers.Main) { appendAssistantToken(token) }
                        }
                }.onFailure { error ->
                    withContext(Dispatchers.Main) {
                        handleGenerationFailure(error)
                        refreshControls()
                    }
                }
            })
        }
    }

    private fun trackGenerationJob(job: Job) {
        generationJob = job
        refreshControls()
        job.invokeOnCompletion {
            lifecycleScope.launch { refreshControls() }
        }
    }

    private fun finishGeneration() {
        flushAssistantMessage()
        removeEmptyTrailingAssistant()
        saveMessages()
        isBusy = false
        modelStatusTv.text = statusLine(engine)
    }

    private fun removeEmptyTrailingAssistant() {
        val index = messages.lastIndex
        if (index >= 0 && !messages[index].isUser && messages[index].content.isBlank()) {
            messages.removeAt(index)
            messageAdapter.notifyItemRemoved(index)
        }
    }

    private fun handleGenerationFailure(error: Throwable) {
        finishGeneration()
        if (error !is CancellationException) {
            showError(error)
        }
    }

    private fun stopGeneration() {
        val activeJob = generationJob ?: return
        if (!activeJob.isActive) return
        engine?.cancelGeneration()
        activeJob.cancel()
        finishGeneration()
        refreshControls()
    }

    private fun activeModelReady(): Boolean =
        when (appSettings.modelProvider) {
            ModelProvider.LOCAL -> isModelReady
            ModelProvider.OPENAI_COMPATIBLE -> appSettings.openAiBaseUrl.isNotBlank() &&
                appSettings.openAiModel.isNotBlank()
        }

    private fun showOpenAiModelDialog() {
        val view = formLayout()
        val baseUrlEt = addField(
            view,
            "接口地址",
            appSettings.openAiBaseUrl,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        val apiKeyEt = addField(
            view,
            "API Key，可选",
            appSettings.openAiApiKey,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        val modelEt = addField(
            view,
            "模型名",
            appSettings.openAiModel,
            InputType.TYPE_CLASS_TEXT
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("OpenAI 兼容模型")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val model = modelEt.text.toString().trim()
                if (model.isBlank()) {
                    Toast.makeText(this, "模型名不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                appSettings = appSettings.copy(
                    modelProvider = ModelProvider.OPENAI_COMPATIBLE,
                    openAiBaseUrl = baseUrlEt.text.toString().trim().ifBlank { DEFAULT_OPENAI_BASE_URL },
                    openAiApiKey = apiKeyEt.text.toString().trim(),
                    openAiModel = model
                )
                currentModelLabel = "OpenAI兼容: $model"
                saveAppSettings()
                modelStatusTv.text = statusLine(engine)
                showScreen(Screen.USE)
                refreshControls()
            }
            .show()
    }

    private fun appendAssistantToken(token: String) {
        if (messages.isEmpty() || messages.last().isUser) return
        shouldAutoScrollAssistant = shouldAutoScrollAssistant || isMessagesAtBottom()
        val updated = messages.removeAt(messages.lastIndex).copy(
            content = lastAssistantMsg.append(token).toString()
        )
        messages.add(updated)
        scheduleAssistantMessageRender()
    }

    private fun scheduleAssistantMessageRender() {
        if (assistantRenderJob?.isActive == true) return
        assistantRenderJob = lifecycleScope.launch {
            delay(ASSISTANT_RENDER_INTERVAL_MS)
            assistantRenderJob = null
            renderAssistantMessage()
        }
    }

    private fun flushAssistantMessage() {
        assistantRenderJob?.cancel()
        assistantRenderJob = null
        renderAssistantMessage()
    }

    private fun renderAssistantMessage() {
        if (messages.isEmpty() || messages.last().isUser) return
        val shouldScroll = shouldAutoScrollAssistant || isMessagesAtBottom()
        shouldAutoScrollAssistant = false
        messageAdapter.notifyItemChanged(messages.lastIndex, MESSAGE_CONTENT_PAYLOAD)
        if (shouldScroll) {
            messagesRv.post { messagesRv.scrollToPosition(messages.lastIndex) }
        }
    }

    private fun isMessagesAtBottom(): Boolean =
        !messagesRv.canScrollVertically(1)

    private fun showSettingsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("设置")
            .setItems(arrayOf("模型参数", "模型存储目录", "HuggingFace 设置", "备份与恢复")) { _, index ->
                when (index) {
                    0 -> showModelParamsDialog()
                    1 -> showModelStorageDialog()
                    2 -> showHuggingFaceSettingsDialog()
                    3 -> showBackupRestoreDialog()
                }
            }
            .show()
    }

    private fun showModelStorageDialog() {
        val options = buildList {
            add(ModelStorageOption("内部存储\n${modelRepository.defaultModelsDir.absolutePath}", modelRepository.defaultModelsDir.absolutePath))
            modelRepository.externalModelsDir?.let { dir ->
                add(ModelStorageOption("外部应用目录\n${dir.absolutePath}", dir.absolutePath))
            }
            val currentPath = modelStorageDir().absolutePath
            val isKnownAppPath = any { option -> option.path == currentPath }
            val publicLabel = if (isKnownAppPath) "外部公共目录\n选择目录" else "外部公共目录\n$currentPath"
            add(ModelStorageOption(publicLabel, null, usesPublicPicker = true))
        }
        val currentPath = modelStorageDir().absolutePath
        val selectedIndex = options.indexOfFirst { option ->
            option.path == currentPath || (option.usesPublicPicker && options.none { it.path == currentPath })
        }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("模型存储目录")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), selectedIndex) { dialog, index ->
                val option = options[index]
                dialog.dismiss()
                if (option.usesPublicPicker) {
                    requestPublicModelDirectorySelection()
                } else {
                    changeModelStorage(option.path.orEmpty())
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun requestPublicModelDirectorySelection() {
        pendingPublicStorageSelection = true
        if (hasPublicStoragePermission()) {
            openPublicModelDirectoryPicker()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appSettingsIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            try {
                allFilesAccessLauncher.launch(appSettingsIntent)
            } catch (_: ActivityNotFoundException) {
                allFilesAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            publicStoragePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun hasPublicStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun openPublicModelDirectoryPicker() {
        pickPublicModelDir.launch(null)
    }

    private fun usePublicModelDirectory(uri: Uri) {
        rememberPublicDirectoryAccess(uri)
        val dir = publicDirectoryFromTreeUri(uri)
        if (dir == null) {
            Toast.makeText(this, "无法解析所选公共目录路径", Toast.LENGTH_LONG).show()
            return
        }
        changeModelStorage(dir.absolutePath)
    }

    private fun rememberPublicDirectoryAccess(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun publicDirectoryFromTreeUri(uri: Uri): File? {
        if (!DocumentsContract.isTreeUri(uri)) return null
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val parts = documentId.split(":", limit = 2)
        val volume = parts.getOrNull(0).orEmpty()
        val relativePath = parts.getOrNull(1).orEmpty()
        val root = when {
            volume.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory()
            volume.equals("home", ignoreCase = true) -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            volume.isNotBlank() -> File("/storage/$volume")
            else -> return null
        }
        return if (relativePath.isBlank()) root else File(root, relativePath)
    }

    private fun changeModelStorage(selectedPath: String) {
        val oldDir = modelStorageDir()
        val newDir = modelRepository.modelsDir(selectedPath)
        if (oldDir.absolutePath == newDir.absolutePath) {
            Toast.makeText(this, "模型存储目录未变化", Toast.LENGTH_SHORT).show()
            return
        }
        val oldLocalPath = appSettings.localModelPath

        lifecycleScope.launch {
            runCatching {
                setBusy(true, "正在迁移已安装模型...")
                val migratedModels = modelRepository.migrateInstalledModels(oldDir, newDir)
                appSettings = appSettings.copy(
                    modelStoragePath = newDir.absolutePath,
                    localModelPath = updatedLocalModelPath(oldLocalPath, migratedModels)
                )
                saveAppSettings()
                refreshInstalledModels()
                migratedModels.size
            }.onSuccess { movedCount ->
                setBusy(false, statusLine(engine))
                Toast.makeText(this@MainActivity, "模型存储目录已切换，迁移 $movedCount 个模型", Toast.LENGTH_SHORT).show()
            }.onFailure { showError(it) }
        }
    }

    private fun updatedLocalModelPath(oldLocalPath: String, migratedModels: Map<String, File>): String {
        if (oldLocalPath.isBlank()) return ""
        val oldLocalFile = File(oldLocalPath)
        return migratedModels[oldLocalFile.absolutePath]?.absolutePath
            ?: oldLocalPath.takeIf { oldLocalFile.isFile }
            ?: ""
    }

    private fun showModelParamsDialog() {
        val view = formLayout()
        val backendSpinner = Spinner(this)
        backendSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            EngineChoice.values().map { it.label }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        backendSpinner.setSelection(EngineChoice.values().indexOf(appSettings.backend).coerceAtLeast(0))
        addLabel(view, "后端")
        view.addView(backendSpinner)

        val contextEt = addField(view, "上下文长度", appSettings.contextSize.toString(), InputType.TYPE_CLASS_NUMBER)
        val threadsEt = addField(view, "线程数，0 为自动", appSettings.threads.toString(), InputType.TYPE_CLASS_NUMBER)
        val predictEt = addField(view, "最大输出 Token", appSettings.predictLength.toString(), InputType.TYPE_CLASS_NUMBER)

        MaterialAlertDialogBuilder(this)
            .setTitle("模型参数")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                appSettings = appSettings.copy(
                    backend = EngineChoice.values()[backendSpinner.selectedItemPosition.coerceIn(0, EngineChoice.values().lastIndex)],
                    contextSize = (contextEt.text.toString().toIntOrNull() ?: DEFAULT_CONTEXT_SIZE).coerceAtLeast(512),
                    threads = (threadsEt.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0),
                    predictLength = (predictEt.text.toString().toIntOrNull() ?: DEFAULT_PREDICT_LENGTH).coerceAtLeast(1)
                )
                saveAppSettings()
                modelStatusTv.text = statusLine(engine)
            }
            .show()
    }

    private fun showHuggingFaceSettingsDialog() {
        val view = formLayout()
        val mirrorEt = addField(
            view,
            "镜像地址",
            appSettings.huggingFaceBaseUrl,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        val tokenEt = addField(
            view,
            "访问令牌",
            appSettings.huggingFaceToken,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("HuggingFace 设置")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                appSettings = appSettings.copy(
                    huggingFaceBaseUrl = mirrorEt.text.toString().trim().ifBlank { DEFAULT_HUGGING_FACE_BASE_URL },
                    huggingFaceToken = tokenEt.text.toString().trim()
                )
                didLoadHubModels = false
                hubModelAdapter.submitList(emptyList())
                saveAppSettings()
            }
            .show()
    }

    private fun showBackupRestoreDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("备份与恢复")
            .setItems(arrayOf("备份到文件", "从文件恢复")) { _, index ->
                if (index == 0) {
                    backupLauncher.launch("myllama-backup.json")
                } else {
                    restoreLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                }
            }
            .show()
    }

    private fun formLayout(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 8.dp(), 24.dp(), 0)
        }

    private fun addLabel(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            setPadding(0, 12.dp(), 0, 4.dp())
        })
    }

    private fun addField(parent: LinearLayout, label: String, value: String, inputType: Int): EditText {
        addLabel(parent, label)
        return EditText(this).apply {
            setText(value)
            this.inputType = inputType
            setSingleLine(true)
            parent.addView(this)
        }
    }

    private fun migrateLegacyMessages() {
        val raw = prefs().getString(KEY_MESSAGES, null) ?: return
        val legacyMessages = runCatching { messagesFromJson(JSONArray(raw)) }.getOrDefault(emptyList())
        if (legacyMessages.isNotEmpty()) {
            val sessions = loadSessions()
            sessions.add(
                0,
                ConversationSession(
                    id = UUID.randomUUID().toString(),
                    title = buildSessionTitle(legacyMessages, appSettings.agentMode),
                    updatedAt = System.currentTimeMillis(),
                    mode = appSettings.agentMode,
                    messages = legacyMessages
                )
            )
            writeSessions(sessions)
        }
        prefs().edit().remove(KEY_MESSAGES).apply()
    }

    private fun loadSession(session: ConversationSession) {
        saveMessages()
        currentSessionId = session.id
        currentAgentMode = session.mode
        appSettings = appSettings.copy(agentMode = session.mode)
        messages.clear()
        messages.addAll(session.messages)
        lastAssistantMsg.clear()
        messageAdapter.notifyDataSetChanged()
        if (messages.isNotEmpty()) messagesRv.scrollToPosition(messages.lastIndex)
        updateConversationMode()
        showScreen(Screen.USE)
        saveAppSettings()
        refreshControls()
    }

    private fun saveMessages() {
        if (messages.isEmpty()) return
        val sessions = loadSessions()
            .filterNot { it.id == currentSessionId }
            .toMutableList()
        sessions.add(
            0,
            ConversationSession(
                id = currentSessionId,
                title = buildSessionTitle(messages, currentAgentMode),
                updatedAt = System.currentTimeMillis(),
                mode = currentAgentMode,
                messages = messages.toList()
            )
        )
        writeSessions(sessions)
    }

    private fun loadSessions(): MutableList<ConversationSession> =
        runCatching {
            val raw = prefs().getString(KEY_SESSIONS, "[]") ?: "[]"
            sessionsFromJson(JSONArray(raw))
        }.getOrDefault(mutableListOf())

    private fun writeSessions(sessions: List<ConversationSession>) {
        prefs().edit()
            .putString(KEY_SESSIONS, sessionsToJson(sessions).toString())
            .apply()
    }

    private fun sessionsFromJson(array: JSONArray): MutableList<ConversationSession> {
        val sessions = mutableListOf<ConversationSession>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val modeName = item.optString("mode", AgentMode.CHAT.name)
            val mode = AgentMode.values().firstOrNull { it.name == modeName } ?: AgentMode.CHAT
            val sessionMessages = messagesFromJson(item.optJSONArray("messages"))
            if (sessionMessages.isEmpty()) continue
            sessions.add(
                ConversationSession(
                    id = item.optString("id", UUID.randomUUID().toString()),
                    title = item.optString("title").ifBlank { buildSessionTitle(sessionMessages, mode) },
                    updatedAt = item.optLong("updatedAt", 0L),
                    mode = mode,
                    messages = sessionMessages
                )
            )
        }
        return sessions.sortedByDescending { it.updatedAt }.toMutableList()
    }

    private fun sessionsToJson(sessions: List<ConversationSession>): JSONArray {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(
                JSONObject()
                    .put("id", session.id)
                    .put("title", session.title)
                    .put("updatedAt", session.updatedAt)
                    .put("mode", session.mode.name)
                    .put("messages", messagesToJson(session.messages))
            )
        }
        return array
    }

    private fun messagesFromJson(array: JSONArray?): List<Message> {
        val result = mutableListOf<Message>()
        if (array == null) return result
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            result.add(
                Message(
                    id = item.optString("id", UUID.randomUUID().toString()),
                    content = item.optString("content"),
                    isUser = item.optBoolean("isUser")
                )
            )
        }
        return result
    }

    private fun messagesToJson(source: List<Message> = messages): JSONArray {
        val array = JSONArray()
        source.forEach { message ->
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put("content", message.content)
                    .put("isUser", message.isUser)
            )
        }
        return array
    }

    private fun buildSessionTitle(source: List<Message>, mode: AgentMode): String {
        val firstUserMessage = source.firstOrNull { it.isUser }?.content.orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()
        val title = firstUserMessage.ifBlank { mode.label }
        return if (title.length > 30) "${title.take(30)}..." else title
    }

    private fun loadAppSettings(): AppSettings {
        val prefs = prefs()
        val backendName = prefs.getString(KEY_BACKEND, EngineChoice.CPU.name)
        val modeName = prefs.getString(KEY_AGENT_MODE, AgentMode.CHAT.name)
        val providerName = prefs.getString(KEY_MODEL_PROVIDER, ModelProvider.LOCAL.name)
        return AppSettings(
            modelProvider = ModelProvider.values().firstOrNull { it.name == providerName } ?: ModelProvider.LOCAL,
            modelStoragePath = prefs.getString(KEY_MODEL_STORAGE_PATH, "").orEmpty(),
            huggingFaceBaseUrl = prefs.getString(KEY_HF_BASE_URL, DEFAULT_HUGGING_FACE_BASE_URL)
                ?: DEFAULT_HUGGING_FACE_BASE_URL,
            huggingFaceToken = prefs.getString(KEY_HF_TOKEN, "").orEmpty(),
            localModelPath = prefs.getString(KEY_LOCAL_MODEL_PATH, "").orEmpty(),
            openAiBaseUrl = prefs.getString(KEY_OPENAI_BASE_URL, DEFAULT_OPENAI_BASE_URL)
                ?: DEFAULT_OPENAI_BASE_URL,
            openAiApiKey = prefs.getString(KEY_OPENAI_API_KEY, "").orEmpty(),
            openAiModel = prefs.getString(KEY_OPENAI_MODEL, "").orEmpty(),
            backend = EngineChoice.values().firstOrNull { it.name == backendName } ?: EngineChoice.CPU,
            contextSize = prefs.getInt(KEY_CONTEXT_SIZE, DEFAULT_CONTEXT_SIZE).coerceAtLeast(512),
            threads = prefs.getInt(KEY_THREADS, 0).coerceAtLeast(0),
            predictLength = prefs.getInt(KEY_PREDICT_LENGTH, DEFAULT_PREDICT_LENGTH).coerceAtLeast(1),
            agentMode = AgentMode.values().firstOrNull { it.name == modeName } ?: AgentMode.CHAT
        )
    }

    private fun saveAppSettings() {
        prefs().edit()
            .putString(KEY_MODEL_PROVIDER, appSettings.modelProvider.name)
            .putString(KEY_MODEL_STORAGE_PATH, appSettings.modelStoragePath)
            .putString(KEY_HF_BASE_URL, appSettings.huggingFaceBaseUrl)
            .putString(KEY_HF_TOKEN, appSettings.huggingFaceToken)
            .putString(KEY_LOCAL_MODEL_PATH, appSettings.localModelPath)
            .putString(KEY_OPENAI_BASE_URL, appSettings.openAiBaseUrl)
            .putString(KEY_OPENAI_API_KEY, appSettings.openAiApiKey)
            .putString(KEY_OPENAI_MODEL, appSettings.openAiModel)
            .putString(KEY_BACKEND, appSettings.backend.name)
            .putInt(KEY_CONTEXT_SIZE, appSettings.contextSize)
            .putInt(KEY_THREADS, appSettings.threads)
            .putInt(KEY_PREDICT_LENGTH, appSettings.predictLength)
            .putString(KEY_AGENT_MODE, appSettings.agentMode.name)
            .apply()
    }

    private fun backupState(uri: Uri) {
        saveMessages()
        saveAppSettings()
        lifecycleScope.launch {
            runCatching {
                val json = JSONObject()
                    .put("version", 1)
                    .put("settings", appSettings.toJson())
                    .put("sessions", sessionsToJson(loadSessions()))
                    .put("messages", messagesToJson())
                    .toString(2)
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        OutputStreamWriter(output, Charsets.UTF_8).use { writer -> writer.write(json) }
                    } ?: error("Cannot open backup target")
                }
            }.onSuccess {
                Toast.makeText(this@MainActivity, "备份完成", Toast.LENGTH_SHORT).show()
            }.onFailure { showError(it) }
        }
    }

    private fun restoreState(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val body = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        InputStreamReader(input, Charsets.UTF_8).use { reader -> reader.readText() }
                    } ?: error("Cannot open backup file")
                }
                val json = JSONObject(body)
                appSettings = AppSettings.fromJson(json.optJSONObject("settings") ?: JSONObject())
                currentAgentMode = appSettings.agentMode
                currentModelLabel =
                    when {
                        appSettings.modelProvider == ModelProvider.OPENAI_COMPATIBLE && appSettings.openAiModel.isNotBlank() ->
                            "OpenAI兼容: ${appSettings.openAiModel}"
                        appSettings.localModelPath.isNotBlank() ->
                            File(appSettings.localModelPath).nameWithoutExtension
                        else -> currentModelLabel
                    }

                val restoredSessions = json.optJSONArray("sessions")
                if (restoredSessions != null) {
                    writeSessions(sessionsFromJson(restoredSessions))
                } else {
                    val restoredMessages = messagesFromJson(json.optJSONArray("messages"))
                    if (restoredMessages.isNotEmpty()) {
                        writeSessions(
                            listOf(
                                ConversationSession(
                                    id = UUID.randomUUID().toString(),
                                    title = buildSessionTitle(restoredMessages, currentAgentMode),
                                    updatedAt = System.currentTimeMillis(),
                                    mode = currentAgentMode,
                                    messages = restoredMessages
                                )
                            )
                        )
                    } else {
                        writeSessions(emptyList())
                    }
                }

                saveAppSettings()
                currentSessionId = UUID.randomUUID().toString()
                messages.clear()
                lastAssistantMsg.clear()
                didLoadHubModels = false
                hubModelAdapter.submitList(emptyList())
            }.onSuccess {
                startEmptySession(appSettings.agentMode, persistPrevious = false)
                messageAdapter.notifyDataSetChanged()
                refreshInstalledModels()
                showScreen(Screen.USE)
                modelStatusTv.text = statusLine(engine)
                Toast.makeText(this@MainActivity, "恢复完成", Toast.LENGTH_SHORT).show()
                refreshControls()
            }.onFailure { showError(it) }
        }
    }

    private fun renderEngineState(state: InferenceEngine.State) {
        when (state) {
            is InferenceEngine.State.Initialized -> {
                isModelReady = false
                setBusy(false, statusLine(engine))
                if (installScreen.visibility == View.VISIBLE) loadHubModelsOnce()
            }
            is InferenceEngine.State.ModelReady -> {
                isModelReady = true
                setBusy(false, statusLine(engine))
                if (installScreen.visibility == View.VISIBLE) loadHubModelsOnce()
            }
            is InferenceEngine.State.Error -> {
                isModelReady = false
                showError(state.exception)
            }
            is InferenceEngine.State.Generating,
            is InferenceEngine.State.LoadingModel,
            is InferenceEngine.State.ProcessingUserPrompt,
            is InferenceEngine.State.ProcessingSystemPrompt,
            is InferenceEngine.State.Benchmarking,
            is InferenceEngine.State.UnloadingModel,
            is InferenceEngine.State.Initializing,
            is InferenceEngine.State.Uninitialized -> {
                isBusy = true
                refreshControls()
            }
        }
    }

    private fun setBusy(busy: Boolean, status: String) {
        isBusy = busy
        modelStatusTv.text = status
        refreshControls()
    }

    private fun refreshControls() {
        val engineReady = engine != null
        val isGenerating = generationJob?.isActive == true
        val canInstall = engineReady && !isBusy
        settingsBtn.isEnabled = !isBusy
        newChatBtn.isEnabled = !isBusy
        modelStatusTv.isEnabled = !isBusy
        conversationPlusBtn.isEnabled = !isBusy
        addModelTabBtn.isEnabled = canInstall
        modelListTabBtn.isEnabled = canInstall
        modelSourceSpinner.isEnabled = canInstall
        hotModelsBtn.isEnabled = canInstall
        latestModelsBtn.isEnabled = canInstall
        configureOpenAiModelBtn.isEnabled = canInstall
        searchModelBtn.isEnabled = canInstall
        hfPrevPageBtn.isEnabled = canInstall && hubPage > 1
        hfNextPageBtn.isEnabled = canInstall && hubHasNextPage
        importBtn.isEnabled = canInstall
        hfSearchEt.isEnabled = canInstall
        hubModelsRv.isEnabled = canInstall
        installedModelsRv.isEnabled = canInstall
        userInputEt.isEnabled = canInstall
        userActionFab.isEnabled = canInstall || isGenerating
        userActionFab.setImageResource(if (isGenerating) R.drawable.outline_stop_24 else R.drawable.outline_send_24)
        userActionFab.contentDescription = getString(if (isGenerating) R.string.stop_generation else R.string.send_message)
    }

    private fun statusLine(_activeEngine: InferenceEngine?): String {
        if (appSettings.modelProvider == ModelProvider.OPENAI_COMPATIBLE) {
            val model = appSettings.openAiModel.ifBlank { "未配置" }
            return "模型: OpenAI兼容 / $model"
        }
        val model = currentModelLabel ?: "未加载模型"
        return "模型: $model"
    }

    private fun showError(error: Throwable) {
        isBusy = false
        modelStatusTv.text = "错误: ${error.message ?: error.javaClass.simpleName}"
        Toast.makeText(this, modelStatusTv.text, Toast.LENGTH_LONG).show()
    }

    override fun onStop() {
        if (generationJob?.isActive == true) engine?.cancelGeneration()
        generationJob?.cancel()
        saveMessages()
        saveAppSettings()
        super.onStop()
    }

    override fun onDestroy() {
        engine?.destroy()
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    private fun File.safeCanonicalPath(): String =
        runCatching { canonicalPath }.getOrDefault(absolutePath)

    private enum class Screen {
        INSTALL,
        USE
    }

    private enum class InstallPanel {
        ADD_MODEL,
        MODEL_LIST
    }

    private enum class ModelProvider {
        LOCAL,
        OPENAI_COMPATIBLE
    }

    private data class ModelStorageOption(
        val label: String,
        val path: String?,
        val usesPublicPicker: Boolean = false
    )

    private enum class AddModelSource(val label: String) {
        HUGGING_FACE("Hugging Face"),
        LOCAL_GGUF("本地 GGUF"),
        OPENAI_COMPATIBLE("OpenAI 兼容")
    }

    private data class ConversationSession(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val mode: AgentMode,
        val messages: List<Message>
    )

    private enum class EngineChoice(val label: String, val backend: EngineBackend) {
        CPU("CPU", EngineBackend.CPU),
        GPU("GPU", EngineBackend.GPU)
    }

    private enum class AgentMode(val label: String) {
        CHAT("聊天") {
            override fun prompt(input: String) = input
        },
        WRITING("写作") {
            override fun prompt(input: String) =
                "你是写作助手。请根据用户要求直接产出可用文本，语言保持自然、简洁。\n\n用户要求:\n$input"
        },
        TRANSLATION("翻译") {
            override fun prompt(input: String) =
                "你是翻译助手。请自动识别源语言，并翻译成用户要求或上下文最合适的目标语言，只输出译文和必要注释。\n\n待翻译内容:\n$input"
        },
        IMAGE_PROMPT("生图提示词") {
            override fun prompt(input: String) =
                "你不能直接生成位图。请把用户描述改写成可用于 Stable Diffusion、Flux 或类似扩散模型的生图提示词，输出正向提示词、负向提示词和推荐参数。\n\n用户描述:\n$input"
        };

        abstract fun prompt(input: String): String
    }

    private data class AppSettings(
        val modelProvider: ModelProvider = ModelProvider.LOCAL,
        val modelStoragePath: String = "",
        val huggingFaceBaseUrl: String = DEFAULT_HUGGING_FACE_BASE_URL,
        val huggingFaceToken: String = "",
        val localModelPath: String = "",
        val openAiBaseUrl: String = DEFAULT_OPENAI_BASE_URL,
        val openAiApiKey: String = "",
        val openAiModel: String = "",
        val backend: EngineChoice = EngineChoice.CPU,
        val contextSize: Int = DEFAULT_CONTEXT_SIZE,
        val threads: Int = 0,
        val predictLength: Int = DEFAULT_PREDICT_LENGTH,
        val agentMode: AgentMode = AgentMode.CHAT
    ) {
        fun toJson() = JSONObject()
            .put(KEY_MODEL_PROVIDER, modelProvider.name)
            .put(KEY_MODEL_STORAGE_PATH, modelStoragePath)
            .put(KEY_HF_BASE_URL, huggingFaceBaseUrl)
            .put(KEY_HF_TOKEN, huggingFaceToken)
            .put(KEY_LOCAL_MODEL_PATH, localModelPath)
            .put(KEY_OPENAI_BASE_URL, openAiBaseUrl)
            .put(KEY_OPENAI_API_KEY, openAiApiKey)
            .put(KEY_OPENAI_MODEL, openAiModel)
            .put(KEY_BACKEND, backend.name)
            .put(KEY_CONTEXT_SIZE, contextSize)
            .put(KEY_THREADS, threads)
            .put(KEY_PREDICT_LENGTH, predictLength)
            .put(KEY_AGENT_MODE, agentMode.name)

        companion object {
            fun fromJson(json: JSONObject): AppSettings {
                val backendName = json.optString(KEY_BACKEND, EngineChoice.CPU.name)
                val modeName = json.optString(KEY_AGENT_MODE, AgentMode.CHAT.name)
                val providerName = json.optString(KEY_MODEL_PROVIDER, ModelProvider.LOCAL.name)
                return AppSettings(
                    modelProvider = ModelProvider.values().firstOrNull { it.name == providerName } ?: ModelProvider.LOCAL,
                    modelStoragePath = json.optString(KEY_MODEL_STORAGE_PATH),
                    huggingFaceBaseUrl = json.optString(KEY_HF_BASE_URL, DEFAULT_HUGGING_FACE_BASE_URL)
                        .ifBlank { DEFAULT_HUGGING_FACE_BASE_URL },
                    huggingFaceToken = json.optString(KEY_HF_TOKEN),
                    localModelPath = json.optString(KEY_LOCAL_MODEL_PATH),
                    openAiBaseUrl = json.optString(KEY_OPENAI_BASE_URL, DEFAULT_OPENAI_BASE_URL)
                        .ifBlank { DEFAULT_OPENAI_BASE_URL },
                    openAiApiKey = json.optString(KEY_OPENAI_API_KEY),
                    openAiModel = json.optString(KEY_OPENAI_MODEL),
                    backend = EngineChoice.values().firstOrNull { it.name == backendName } ?: EngineChoice.CPU,
                    contextSize = json.optInt(KEY_CONTEXT_SIZE, DEFAULT_CONTEXT_SIZE).coerceAtLeast(512),
                    threads = json.optInt(KEY_THREADS, 0).coerceAtLeast(0),
                    predictLength = json.optInt(KEY_PREDICT_LENGTH, DEFAULT_PREDICT_LENGTH).coerceAtLeast(1),
                    agentMode = AgentMode.values().firstOrNull { it.name == modeName } ?: AgentMode.CHAT
                )
            }
        }
    }

    private companion object {
        private const val DEFAULT_CONTEXT_SIZE = 4096
        private const val DEFAULT_PREDICT_LENGTH = 1024
        private const val HUGGING_FACE_PAGE_SIZE = 8
        private const val ASSISTANT_RENDER_INTERVAL_MS = 60L
        private const val MESSAGE_CONTENT_PAYLOAD = "message_content"
        private const val DEFAULT_HUGGING_FACE_BASE_URL = "https://huggingface.co"
        private const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
        private const val PREFS_NAME = "myllama_state"
        private const val KEY_MESSAGES = "messages"
        private const val KEY_SESSIONS = "sessions"
        private const val KEY_MODEL_PROVIDER = "modelProvider"
        private const val KEY_MODEL_STORAGE_PATH = "modelStoragePath"
        private const val KEY_HF_BASE_URL = "huggingFaceBaseUrl"
        private const val KEY_HF_TOKEN = "huggingFaceToken"
        private const val KEY_LOCAL_MODEL_PATH = "localModelPath"
        private const val KEY_OPENAI_BASE_URL = "openAiBaseUrl"
        private const val KEY_OPENAI_API_KEY = "openAiApiKey"
        private const val KEY_OPENAI_MODEL = "openAiModel"
        private const val KEY_BACKEND = "backend"
        private const val KEY_CONTEXT_SIZE = "contextSize"
        private const val KEY_THREADS = "threads"
        private const val KEY_PREDICT_LENGTH = "predictLength"
        private const val KEY_AGENT_MODE = "agentMode"
        private const val MENU_CHAT = 1
        private const val MENU_WRITING = 2
        private const val MENU_TRANSLATION = 3
        private const val MENU_IMAGE_PROMPT = 4
        private const val MENU_ADD_MODEL = 5
        private const val MENU_ATTACH_TEXT = 20
        private const val MENU_ATTACH_IMAGE = 21
        private const val MENU_ATTACH_FILE = 22
        private const val MAX_ATTACHMENT_TEXT_CHARS = 16000
        private val TEXT_ATTACHMENT_EXTENSIONS = setOf(
            ".txt",
            ".md",
            ".json",
            ".csv",
            ".xml",
            ".html",
            ".kt",
            ".java",
            ".py",
            ".js",
            ".ts",
            ".css",
            ".c",
            ".cpp",
            ".h",
            ".log",
            ".yaml",
            ".yml"
        )
    }
}

fun GgufMetadata.toModelLabel(file: File): String {
    val name = basic.name ?: file.nameWithoutExtension
    val size = basic.sizeLabel?.let { " $it" }.orEmpty()
    val architecture = architecture?.architecture?.let { " / $it" }.orEmpty()
    val fileSize = file.length().takeIf { it > 0 }?.let {
        " / %.2f GB".format(it.toDouble() / 1024.0 / 1024.0 / 1024.0)
    }.orEmpty()
    return "$name$size$architecture$fileSize"
}

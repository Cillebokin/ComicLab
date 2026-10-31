package com.example.comiclab

import com.example.comiclab.core.settings.ComicLabConfigurationStore
import com.example.comiclab.core.settings.ComicLabConfigurationJson
import com.example.comiclab.core.nas.NasShareConnectionTester
import com.example.comiclab.core.nas.NasShareProfile
import com.example.comiclab.core.nas.NasShareStore
import com.example.comiclab.core.update.GitHubReleaseApi
import com.example.comiclab.core.diagnostics.CrashLogManager
import com.example.comiclab.core.store.PrefsGateway
import com.example.comiclab.ui.common.createRounded
import com.example.comiclab.ui.common.SystemBars
import com.example.comiclab.ui.settings.SettingsSection
import com.example.comiclab.ui.settings.SettingsSectionAdapter

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Paint
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

internal const val COMICLAB_RELEASES_URL =
    "https://github.com/Cillebokin/ComicLab/releases"

class SettingsActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var btnContents: ImageButton
    private lateinit var tvCurrentSettingsSectionTitle: TextView
    private lateinit var settingsContentsScrim: View
    private lateinit var settingsSectionFlipper: ViewFlipper
    private var settingsContentsPopupWindow: PopupWindow? = null
    private var settingsSections: List<SettingsSection> = emptyList()
    private var currentSettingsSectionIndex = 0
    private var layoutDebugTools: View? = null
    private var btnExportCrashLog: Button? = null
    private var btnRunReaderStressTest: Button? = null
    private lateinit var btnExportConfiguration: Button
    private lateinit var btnImportConfiguration: Button
    private lateinit var btnCheckUpdates: Button
    private lateinit var tvAppLink: TextView
    private lateinit var tvLanguageValue: TextView
    private lateinit var switchDarkMode: SwitchCompat
    private lateinit var radioReadingDirection: RadioGroup
    private lateinit var switchDoublePageCoverSingle: SwitchCompat
    private lateinit var inputStartMarkerErrorTags: EditText
    private lateinit var switchVolumeKeyPageTurn: SwitchCompat
    private lateinit var switchAutoHideSystemBars: SwitchCompat
    private lateinit var switchCustomReaderBrightness: SwitchCompat
    private lateinit var sliderSettingsReaderBrightness: SeekBar
    private lateinit var tvSettingsBrightnessValue: TextView
    private lateinit var switchNasEnabled: SwitchCompat
    private lateinit var nasSettingsEmptyState: View
    private lateinit var nasShareListContainer: LinearLayout
    private lateinit var tvNasShareConfigError: TextView
    private lateinit var btnNasAddFirstShare: Button
    private lateinit var btnNasAddShare: Button
    private val debugToolExecutor = Executors.newSingleThreadExecutor()
    private val configurationExecutor = Executors.newSingleThreadExecutor()
    private val releaseCheckExecutor = Executors.newSingleThreadExecutor()
    private val nasConnectionExecutor = Executors.newSingleThreadExecutor()
    private val configurationImportListener = object : ConfigurationImportCoordinator.Listener {
        override fun onImportRunningChanged(running: Boolean) {
            this@SettingsActivity.onImportRunningChanged(running)
        }

        override fun onImportCompleted(completion: ConfigurationImportCompletion) {
            this@SettingsActivity.onImportCompleted(completion)
        }
    }
    private val exportConfigurationLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let(::exportConfiguration)
    }
    private val importConfigurationLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(::importConfiguration)
    }
    private var isUpdatingBrightnessControls = false
    private var configurationImportRunning = false
    private var configurationExportRunning = false
    @Volatile
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialSectionIndex = savedInstanceState?.getInt(STATE_SETTINGS_SECTION_INDEX) ?: 0
        val requestedSection = if (savedInstanceState == null) {
            intent.getStringExtra(EXTRA_SETTINGS_SECTION)
        } else {
            null
        }
        renderSettingsContent(initialSectionIndex, requestedSection)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (settingsContentsPopupWindow != null) {
                    dismissSettingsContentsPanel()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        ProcessConfigurationImportCoordinator.addListener(configurationImportListener)
    }

    override fun onStop() {
        ProcessConfigurationImportCoordinator.removeListener(configurationImportListener)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SETTINGS_SECTION_INDEX, currentSettingsSectionIndex)
        super.onSaveInstanceState(outState)
    }

    private fun renderSettingsContent(
        initialSectionIndex: Int,
        requestedSection: String? = null
    ) {
        setContentView(R.layout.activity_settings)
        SystemBars.fitContentBelowSystemBars(
            this,
            findViewById<View>(R.id.main),
            findViewById<View>(R.id.statusBarBackground),
            statusBarColorResId = R.color.comiclab_file_picker_background,
            lightStatusBars = true
        )

        btnBack = findViewById(R.id.btnBack)
        btnContents = findViewById(R.id.btnContents)
        tvCurrentSettingsSectionTitle = findViewById(R.id.tvCurrentSettingsSectionTitle)
        settingsContentsScrim = findViewById(R.id.settingsContentsScrim)
        settingsSectionFlipper = findViewById(R.id.settingsSectionFlipper)
        bindSettingsNavigation(initialSectionIndex, requestedSection)

        layoutDebugTools = findViewById(R.id.layoutDebugTools)
        btnExportCrashLog = findViewById(R.id.btnExportCrashLog)
        btnRunReaderStressTest = findViewById(R.id.btnRunReaderStressTest)
        btnExportConfiguration = findViewById(R.id.btnExportConfiguration)
        btnImportConfiguration = findViewById(R.id.btnImportConfiguration)
        btnCheckUpdates = findViewById(R.id.btnCheckUpdates)
        tvAppLink = findViewById(R.id.tvAppLink)
        tvLanguageValue = findViewById(R.id.tvLanguageValue)
        switchDarkMode = findViewById(R.id.switchDarkMode)
        radioReadingDirection = findViewById(R.id.radioReadingDirection)
        switchDoublePageCoverSingle = findViewById(R.id.switchDoublePageCoverSingle)
        inputStartMarkerErrorTags = findViewById(R.id.inputStartMarkerErrorTags)
        switchVolumeKeyPageTurn = findViewById(R.id.switchVolumeKeyPageTurn)
        switchAutoHideSystemBars = findViewById(R.id.switchAutoHideSystemBars)
        switchCustomReaderBrightness = findViewById(R.id.switchCustomReaderBrightness)
        sliderSettingsReaderBrightness = findViewById(R.id.sliderSettingsReaderBrightness)
        tvSettingsBrightnessValue = findViewById(R.id.tvSettingsBrightnessValue)

        btnBack.setOnClickListener {
            if (settingsContentsPopupWindow != null) {
                dismissSettingsContentsPanel()
            } else {
                finish()
            }
        }

        bindLanguageSetting()
        bindNasShareSettings()
        bindDarkModeSetting()
        bindReadingDirection()
        bindEnhancementScale()
        bindDoublePageCoverSingle()
        bindStartMarkerErrorTags()
        bindVolumeKeyPageTurn()
        bindAutoHideSystemBars()
        bindCustomReaderBrightness()
        bindDebugTools()
        bindConfigurationManagement()
        bindProjectReleaseLink()
        bindUpdateCheck()
        configurationImportRunning = ProcessConfigurationImportCoordinator.isRunning()
        updateConfigurationButtons()
    }

    private fun bindSettingsNavigation(
        initialSectionIndex: Int,
        requestedSection: String?
    ) {
        settingsSections = createSettingsSections()
        settingsSections.forEach { section ->
            settingsSectionFlipper.addView(
                layoutInflater.inflate(section.layoutResId, settingsSectionFlipper, false)
            )
        }
        val requestedIndex = when (requestedSection) {
            SETTINGS_SECTION_NAS -> settingsSections.indexOfFirst {
                it.layoutResId == R.layout.settings_section_nas
            }
            else -> -1
        }
        showSettingsSection(if (requestedIndex >= 0) requestedIndex else initialSectionIndex)

        btnContents.setOnClickListener {
            if (settingsContentsPopupWindow == null) {
                showSettingsContentsPanel()
            } else {
                dismissSettingsContentsPanel()
            }
        }
        settingsContentsScrim.setOnClickListener {
            dismissSettingsContentsPanel()
        }
    }

    private fun createSettingsSections(): List<SettingsSection> = buildList {
        add(SettingsSection(R.string.settings_section_appearance, R.layout.settings_section_appearance))
        add(SettingsSection(R.string.file_browser, R.layout.settings_section_file_browser))
        add(SettingsSection(R.string.settings_section_reading, R.layout.settings_section_reading))
        add(SettingsSection(R.string.settings_section_configuration, R.layout.settings_section_configuration))
        add(SettingsSection(R.string.source_nas, R.layout.settings_section_nas))
        add(SettingsSection(R.string.about, R.layout.settings_section_about))
        if (isDebuggableBuild()) {
            add(SettingsSection(R.string.settings_section_diagnostics, R.layout.settings_section_diagnostics))
        }
    }

    private fun bindNasShareSettings() {
        val sectionIndex = settingsSections.indexOfFirst {
            it.layoutResId == R.layout.settings_section_nas
        }
        if (sectionIndex < 0) return

        val sectionView = settingsSectionFlipper.getChildAt(sectionIndex)
        switchNasEnabled = sectionView.findViewById(R.id.switchNasEnabled)
        switchNasEnabled.isChecked = NasShareStore.isNasEnabled(this)
        switchNasEnabled.setOnCheckedChangeListener { _, isChecked ->
            NasShareStore.setNasEnabled(this, isChecked)
        }
        nasSettingsEmptyState = sectionView.findViewById(R.id.nasSettingsEmptyState)
        nasShareListContainer = sectionView.findViewById(R.id.nasShareListContainer)
        tvNasShareConfigError = sectionView.findViewById(R.id.tvNasShareConfigError)
        btnNasAddFirstShare = sectionView.findViewById(R.id.btnNasAddFirstShare)
        btnNasAddShare = sectionView.findViewById(R.id.btnNasAddShare)
        btnNasAddFirstShare.setOnClickListener { showNasShareEditor(existing = null) }
        btnNasAddShare.setOnClickListener { showNasShareEditor(existing = null) }
        renderNasShareSettings()
    }

    private fun renderNasShareSettings() {
        val profiles = try {
            NasShareStore.load(this)
        } catch (_: Exception) {
            tvNasShareConfigError.setText(R.string.nas_share_config_read_failed)
            tvNasShareConfigError.visibility = View.VISIBLE
            nasSettingsEmptyState.visibility = View.GONE
            nasShareListContainer.visibility = View.GONE
            btnNasAddShare.visibility = View.GONE
            return
        }

        tvNasShareConfigError.visibility = View.GONE
        nasShareListContainer.removeAllViews()
        val hasProfiles = profiles.isNotEmpty()
        nasSettingsEmptyState.visibility = if (hasProfiles) View.GONE else View.VISIBLE
        nasShareListContainer.visibility = if (hasProfiles) View.VISIBLE else View.GONE
        btnNasAddShare.visibility = if (hasProfiles) View.VISIBLE else View.GONE

        profiles.forEach { profile ->
            val item = layoutInflater.inflate(
                R.layout.item_nas_share,
                nasShareListContainer,
                false
            )
            item.findViewById<TextView>(R.id.tvNasShareName).text = profile.name
            val status = item.findViewById<TextView>(R.id.tvNasShareTestStatus)
            val testButton = item.findViewById<TextView>(R.id.btnNasShareTest)
            testButton.setOnClickListener {
                testSavedNasShare(profile, status, testButton)
            }
            item.findViewById<TextView>(R.id.btnNasShareEdit).setOnClickListener {
                showNasShareEditor(profile)
            }
            item.findViewById<TextView>(R.id.btnNasShareDelete).setOnClickListener {
                confirmDeleteNasShare(profile)
            }
            nasShareListContainer.addView(item)
        }
    }

    private data class NasShareFormValues(
        val profile: NasShareProfile,
        val password: String
    )

    private fun showNasShareEditor(existing: NasShareProfile?) {
        val content = layoutInflater.inflate(R.layout.dialog_nas_share, null)
        val hostInput = content.findViewById<EditText>(R.id.inputNasHost)
        val shareInput = content.findViewById<EditText>(R.id.inputNasShareFolder)
        val usernameInput = content.findViewById<EditText>(R.id.inputNasUsername)
        val passwordInput = content.findViewById<EditText>(R.id.inputNasPassword)
        passwordInput.isSaveEnabled = false
        val rememberPassword = content.findViewById<CheckBox>(R.id.checkRememberNasPassword)
        val status = content.findViewById<TextView>(R.id.tvNasFormStatus)

        existing?.let { profile ->
            hostInput.setText(profile.host)
            shareInput.setText(profile.shareName)
            usernameInput.setText(profile.username)
            rememberPassword.isChecked = profile.rememberPassword
            if (profile.rememberPassword && profile.encryptedPassword != null) {
                passwordInput.hint = getString(R.string.nas_password_keep_hint)
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.nas_add_share_title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.nas_test_connection, null)
            .setPositiveButton(R.string.nas_save, null)
            .createRounded()

        dialog.show()
        val testButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        testButton.setOnClickListener {
            val values = readNasShareForm(
                existing,
                hostInput,
                shareInput,
                usernameInput,
                passwordInput,
                status
            ) ?: return@setOnClickListener
            val useRememberedPassword = values.password.isEmpty() &&
                rememberPassword.isChecked &&
                existing?.rememberPassword == true &&
                existing.encryptedPassword != null
            if (values.profile.username.isNotEmpty() &&
                values.password.isEmpty() &&
                !useRememberedPassword
            ) {
                status.setText(R.string.nas_test_password_required)
                return@setOnClickListener
            }
            startNasConnectionTest(
                values.profile,
                values.password,
                useRememberedPassword,
                status,
                testButton,
                saveButton,
                dialog
            )
        }
        saveButton.setOnClickListener {
            val values = readNasShareForm(
                existing,
                hostInput,
                shareInput,
                usernameInput,
                passwordInput,
                status
            ) ?: return@setOnClickListener
            try {
                NasShareStore.save(
                    this,
                    values.profile,
                    values.password,
                    rememberPassword.isChecked
                )
                dialog.dismiss()
                renderNasShareSettings()
                showMessage(getString(R.string.nas_share_saved))
            } catch (_: Exception) {
                status.setText(R.string.nas_share_save_failed)
            }
        }
    }

    private fun readNasShareForm(
        existing: NasShareProfile?,
        hostInput: EditText,
        shareInput: EditText,
        usernameInput: EditText,
        passwordInput: EditText,
        status: TextView
    ): NasShareFormValues? {
        status.text = ""
        val host = hostInput.text.toString().trim()
        val shareName = shareInput.text.toString().trim()
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString()

        if (host.isEmpty()) {
            status.setText(R.string.nas_host_required)
            return null
        }
        if (host.contains('/') || host.contains('\\') || host.startsWith("smb:", ignoreCase = true)) {
            status.setText(R.string.nas_host_invalid)
            return null
        }
        if (shareName.isEmpty()) {
            status.setText(R.string.nas_share_required)
            return null
        }
        if (shareName.contains('/') || shareName.contains('\\')) {
            status.setText(R.string.nas_share_invalid)
            return null
        }
        if (username.isEmpty() && password.isNotEmpty()) {
            status.setText(R.string.nas_credentials_invalid)
            return null
        }

        val profile = NasShareProfile(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = "$host/$shareName",
            host = host,
            shareName = shareName,
            username = username,
            rememberPassword = existing?.rememberPassword ?: false,
            encryptedPassword = existing?.encryptedPassword
        )
        return NasShareFormValues(profile, password)
    }

    private fun testSavedNasShare(
        profile: NasShareProfile,
        status: TextView,
        button: View
    ) {
        if (profile.username.isNotEmpty() &&
            (!profile.rememberPassword || profile.encryptedPassword == null)
        ) {
            status.setText(R.string.nas_test_password_required)
            return
        }
        startNasConnectionTest(
            profile,
            enteredPassword = "",
            useRememberedPassword = profile.username.isNotEmpty(),
            status = status,
            actionButton = button,
            saveButton = null,
            dialog = null
        )
    }

    private fun startNasConnectionTest(
        profile: NasShareProfile,
        enteredPassword: String,
        useRememberedPassword: Boolean,
        status: TextView,
        actionButton: View,
        saveButton: Button?,
        dialog: AlertDialog?
    ) {
        status.setText(R.string.nas_test_in_progress)
        actionButton.isEnabled = false
        saveButton?.isEnabled = false
        nasConnectionExecutor.execute {
            val result = try {
                val password = if (useRememberedPassword) {
                    try {
                        NasShareStore.rememberedPassword(profile)
                            ?: throw NasPasswordUnavailableException()
                    } catch (exception: NasPasswordUnavailableException) {
                        throw exception
                    } catch (_: Exception) {
                        throw NasPasswordUnavailableException()
                    }
                } else {
                    enteredPassword
                }
                if (profile.username.isNotEmpty() && password.isEmpty()) {
                    throw NasPasswordUnavailableException()
                }
                NasShareConnectionTester.test(profile, password)
                R.string.nas_test_success
            } catch (_: NasPasswordUnavailableException) {
                R.string.nas_password_unavailable
            } catch (_: Exception) {
                R.string.nas_test_failure
            }

            runOnUiThread {
                if (destroyed || (dialog != null && !dialog.isShowing)) {
                    return@runOnUiThread
                }
                status.setText(result)
                actionButton.isEnabled = true
                saveButton?.isEnabled = true
            }
        }
    }

    private fun confirmDeleteNasShare(profile: NasShareProfile) {
        AlertDialog.Builder(this)
            .setTitle(R.string.nas_share_delete_title)
            .setMessage(getString(R.string.nas_share_delete_message, profile.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                try {
                    NasShareStore.delete(this, profile.id)
                    renderNasShareSettings()
                    showMessage(getString(R.string.nas_share_deleted))
                } catch (_: Exception) {
                    showMessage(getString(R.string.nas_share_delete_failed))
                }
            }
            .show()
    }

    private class NasPasswordUnavailableException : Exception()

    private fun showSettingsSection(index: Int) {
        if (settingsSections.isEmpty()) {
            return
        }

        currentSettingsSectionIndex = index.coerceIn(settingsSections.indices)
        settingsSectionFlipper.displayedChild = currentSettingsSectionIndex
        tvCurrentSettingsSectionTitle.setText(
            settingsSections[currentSettingsSectionIndex].titleResId
        )
        (settingsSectionFlipper.getChildAt(currentSettingsSectionIndex) as? ScrollView)
            ?.scrollTo(0, 0)
    }

    private fun showSettingsContentsPanel() {
        if (settingsContentsPopupWindow != null) {
            return
        }

        val rootView = findViewById<View>(R.id.main)
        val screenWidth = rootView.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val panelWidth = (screenWidth * 0.75f).roundToInt()
            .coerceAtLeast(dpToPx(280))
            .coerceAtMost(screenWidth)
        val content = layoutInflater.inflate(R.layout.panel_settings_contents, null)
        val listSettingsSections = content.findViewById<RecyclerView>(R.id.listSettingsSections)
        listSettingsSections.layoutManager = LinearLayoutManager(this)
        listSettingsSections.adapter = SettingsSectionAdapter(settingsSections) { index ->
            dismissSettingsContentsPanel()
            showSettingsSection(index)
        }

        showSettingsContentsScrim()
        val popupWindow = PopupWindow(
            content,
            panelWidth,
            ViewGroup.LayoutParams.MATCH_PARENT,
            true
        )
        popupWindow.isOutsideTouchable = true
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popupWindow.animationStyle = R.style.Animation_ComicLab_HelpContentsPanel
        popupWindow.elevation = dpToPx(8).toFloat()
        popupWindow.setOnDismissListener {
            if (settingsContentsPopupWindow === popupWindow) {
                settingsContentsPopupWindow = null
                hideSettingsContentsScrim()
                updateContentsButtonDescription(false)
            }
        }

        settingsContentsPopupWindow = popupWindow
        updateContentsButtonDescription(true)
        popupWindow.showAtLocation(rootView, Gravity.START or Gravity.TOP, 0, 0)
    }

    private fun dismissSettingsContentsPanel() {
        val popupWindow = settingsContentsPopupWindow ?: return
        settingsContentsPopupWindow = null
        popupWindow.dismiss()
        hideSettingsContentsScrim()
        updateContentsButtonDescription(false)
    }

    private fun showSettingsContentsScrim() {
        settingsContentsScrim.animate().cancel()
        settingsContentsScrim.alpha = 0f
        settingsContentsScrim.visibility = View.VISIBLE
        settingsContentsScrim.animate()
            .alpha(1f)
            .setDuration(180L)
            .start()
    }

    private fun hideSettingsContentsScrim() {
        if (settingsContentsScrim.visibility != View.VISIBLE) {
            return
        }

        settingsContentsScrim.animate().cancel()
        settingsContentsScrim.animate()
            .alpha(0f)
            .setDuration(150L)
            .withEndAction {
                settingsContentsScrim.visibility = View.GONE
                settingsContentsScrim.alpha = 1f
            }
            .start()
    }

    private fun updateContentsButtonDescription(expanded: Boolean) {
        btnContents.contentDescription = getString(
            if (expanded) {
                R.string.settings_contents_collapse
            } else {
                R.string.settings_contents_expand
            }
        )
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
    }

    override fun onDestroy() {
        settingsContentsPopupWindow?.dismiss()
        settingsContentsPopupWindow = null
        destroyed = true
        debugToolExecutor.shutdownNow()
        configurationExecutor.shutdownNow()
        releaseCheckExecutor.shutdownNow()
        nasConnectionExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun onImportRunningChanged(running: Boolean) {
        if (!destroyed) {
            configurationImportRunning = running
            updateConfigurationButtons()
        }
    }

    private fun onImportCompleted(completion: ConfigurationImportCompletion) {
        if (destroyed) {
            return
        }
        updateConfigurationButtons()
        when (completion) {
            is ConfigurationImportCompletion.Success -> {
                refreshSettingsControlsAfterImport()
                showMessage(
                    getString(
                        R.string.configuration_imported,
                        completion.summary.restoredEntries + completion.appliedSettings,
                        completion.summary.skippedEntries,
                        completion.summary.preservedReadingProgressEntries
                    )
                )
            }
            is ConfigurationImportCompletion.Failure -> {
                showMessage(getString(R.string.configuration_import_failed, completion.message))
            }
        }
    }

    private fun bindEnhancementScale() {
        val group = findViewById<RadioGroup>(R.id.radioEnhancementScale)
        group.setOnCheckedChangeListener(null)
        group.check(when (AppSettings.getEnhancementScale(this)) {
            3 -> R.id.radioEnhancement3
            4 -> R.id.radioEnhancement4
            else -> R.id.radioEnhancement2
        })
        group.setOnCheckedChangeListener { _, id ->
            AppSettings.setEnhancementScale(this, when (id) {
                R.id.radioEnhancement3 -> 3
                R.id.radioEnhancement4 -> 4
                else -> 2
            })
        }
    }

    private fun bindReadingDirection() {
        val checkedId = when (AppSettings.getReadingDirection(this)) {
            AppSettings.READING_DIRECTION_RIGHT_TO_LEFT -> R.id.radioRightToLeft
            AppSettings.READING_DIRECTION_LEFT_TO_RIGHT -> R.id.radioLeftToRight
            else -> R.id.radioTopToBottom
        }

        radioReadingDirection.check(checkedId)
        radioReadingDirection.setOnCheckedChangeListener { _, checkedRadioId ->
            val direction = when (checkedRadioId) {
                R.id.radioRightToLeft -> AppSettings.READING_DIRECTION_RIGHT_TO_LEFT
                R.id.radioLeftToRight -> AppSettings.READING_DIRECTION_LEFT_TO_RIGHT
                else -> AppSettings.READING_DIRECTION_TOP_TO_BOTTOM
            }
            AppSettings.setReadingDirection(this, direction)
        }
    }

    private fun bindVolumeKeyPageTurn() {
        switchVolumeKeyPageTurn.isChecked = AppSettings.isVolumeKeyPageTurnEnabled(this)
        switchVolumeKeyPageTurn.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setVolumeKeyPageTurnEnabled(this, isChecked)
        }
    }

    private fun bindLanguageSetting() {
        val languageCard = findViewById<View>(R.id.cardLanguage)
        updateLanguageValue(languageCard)
        languageCard.setOnClickListener {
            val options = arrayOf(
                getString(R.string.language_simplified_chinese),
                getString(R.string.language_english)
            )
            AlertDialog.Builder(this)
                .setTitle(R.string.language)
                .setSingleChoiceItems(options, currentLanguageIndex()) { dialog, selectedIndex ->
                    dialog.dismiss()
                    val selectedTag = if (selectedIndex == ENGLISH_LANGUAGE_INDEX) {
                        ENGLISH_LANGUAGE_TAG
                    } else {
                        SIMPLIFIED_CHINESE_LANGUAGE_TAG
                    }
                    if (AppCompatDelegate.getApplicationLocales().toLanguageTags()
                            .equals(selectedTag, ignoreCase = true)
                    ) {
                        return@setSingleChoiceItems
                    }
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(selectedTag)
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun bindDarkModeSetting() {
        switchDarkMode.isChecked = AppSettings.isDarkModeEnabled(this)
        switchDarkMode.setOnCheckedChangeListener { _, enabled ->
            AppSettings.setDarkModeEnabled(this, enabled)
            window.decorView.post {
                renderSettingsContent(currentSettingsSectionIndex)
            }
        }
    }

    private fun updateLanguageValue(languageCard: View) {
        val currentLanguageString = if (currentLanguageIndex() == ENGLISH_LANGUAGE_INDEX) {
            R.string.language_current_english
        } else {
            R.string.language_current_chinese
        }
        tvLanguageValue.setText(currentLanguageString)
        languageCard.contentDescription = getString(
            R.string.language_setting_accessibility_description,
            getString(currentLanguageString)
        )
    }

    private fun currentLanguageIndex(): Int {
        val applicationLocale = AppCompatDelegate.getApplicationLocales().get(0)
        val language = (applicationLocale ?: resources.configuration.locales.get(0)).language
            .lowercase(Locale.ROOT)
        return if (language == ENGLISH_LANGUAGE_TAG) ENGLISH_LANGUAGE_INDEX else CHINESE_LANGUAGE_INDEX
    }

    private fun bindDoublePageCoverSingle() {
        switchDoublePageCoverSingle.isChecked =
            AppSettings.isDoublePageCoverSingleEnabled(this)
        switchDoublePageCoverSingle.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setDoublePageCoverSingleEnabled(this, isChecked)
        }
    }

    private fun bindStartMarkerErrorTags() {
        inputStartMarkerErrorTags.setText(AppSettings.getStartMarkerErrorTags(this))
        inputStartMarkerErrorTags.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                AppSettings.setStartMarkerErrorTags(this@SettingsActivity, s?.toString().orEmpty())
            }
        })
    }

    private fun bindAutoHideSystemBars() {
        switchAutoHideSystemBars.isChecked = AppSettings.isAutoHideSystemBarsEnabled(this)
        switchAutoHideSystemBars.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setAutoHideSystemBarsEnabled(this, isChecked)
        }
    }

    private fun bindCustomReaderBrightness() {
        sliderSettingsReaderBrightness.min = AppSettings.MIN_READER_BRIGHTNESS
        sliderSettingsReaderBrightness.max = AppSettings.MAX_READER_BRIGHTNESS
        updateBrightnessControls()

        switchCustomReaderBrightness.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingBrightnessControls) {
                return@setOnCheckedChangeListener
            }

            AppSettings.setCustomReaderBrightnessEnabled(this, isChecked)
            updateBrightnessControls()
        }

        sliderSettingsReaderBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingBrightnessControls) {
                    return
                }

                val brightness = normalizeBrightness(progress)
                AppSettings.setCustomReaderBrightness(this@SettingsActivity, brightness)
                updateBrightnessValueText(brightness)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val brightness = normalizeBrightness(seekBar.progress)
                AppSettings.setCustomReaderBrightness(this@SettingsActivity, brightness)
                updateBrightnessValueText(brightness)
            }
        })
    }

    private fun updateBrightnessControls() {
        isUpdatingBrightnessControls = true
        val isCustomBrightnessEnabled = AppSettings.isCustomReaderBrightnessEnabled(this)
        val brightness = AppSettings.getCustomReaderBrightness(this)

        switchCustomReaderBrightness.isChecked = isCustomBrightnessEnabled
        sliderSettingsReaderBrightness.progress = brightness
        sliderSettingsReaderBrightness.isEnabled = isCustomBrightnessEnabled
        sliderSettingsReaderBrightness.isClickable = isCustomBrightnessEnabled
        sliderSettingsReaderBrightness.isFocusable = isCustomBrightnessEnabled
        sliderSettingsReaderBrightness.alpha = if (isCustomBrightnessEnabled) {
            ENABLED_BRIGHTNESS_SLIDER_ALPHA
        } else {
            DISABLED_BRIGHTNESS_SLIDER_ALPHA
        }
        updateBrightnessValueText(brightness)
        isUpdatingBrightnessControls = false
    }

    private fun updateBrightnessValueText(brightness: Int) {
        val percent = (normalizeBrightness(brightness) * 100f / AppSettings.MAX_READER_BRIGHTNESS)
            .toInt()
            .coerceIn(0, 100)
        tvSettingsBrightnessValue.text = getString(R.string.brightness_percent, percent)
    }

    private fun normalizeBrightness(brightness: Int): Int {
        return brightness.coerceIn(
            AppSettings.MIN_READER_BRIGHTNESS,
            AppSettings.MAX_READER_BRIGHTNESS
        )
    }

    private fun bindDebugTools() {
        val showDebugTools = isDebuggableBuild()
        if (!showDebugTools) {
            return
        }

        layoutDebugTools?.visibility = View.VISIBLE
        btnExportCrashLog?.setOnClickListener {
            exportCrashLog()
        }
        btnRunReaderStressTest?.setOnClickListener {
            runReaderStressTest()
        }
    }

    private fun bindProjectReleaseLink() {
        tvAppLink.text = COMICLAB_RELEASES_URL
        tvAppLink.setTextColor(getColor(R.color.comiclab_accent))
        tvAppLink.paintFlags = tvAppLink.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        tvAppLink.setOnClickListener {
            openProjectReleasePage()
        }
    }

    private fun bindUpdateCheck() {
        btnCheckUpdates.setOnClickListener {
            checkForUpdates()
        }
    }

    private fun checkForUpdates() {
        if (destroyed || !btnCheckUpdates.isEnabled) {
            return
        }

        btnCheckUpdates.isEnabled = false
        btnCheckUpdates.setText(R.string.checking_updates)
        val localVersion = BuildConfig.VERSION_NAME
        releaseCheckExecutor.execute {
            val result = runCatching {
                GitHubReleaseApi.fetchLatestReleaseTag()
            }
            runOnUiThread {
                if (destroyed) {
                    return@runOnUiThread
                }
                btnCheckUpdates.isEnabled = true
                btnCheckUpdates.setText(R.string.check_updates)
                result
                    .onSuccess { remoteVersion ->
                        showUpdateVersionInfo(localVersion, remoteVersion)
                    }
                    .onFailure {
                        showMessage(getString(R.string.update_check_failed))
                    }
            }
        }
    }

    private fun showUpdateVersionInfo(
        localVersion: String,
        remoteVersion: String
    ) {
        val message = getString(
            R.string.update_version_info,
            displayVersion(localVersion),
            displayVersion(remoteVersion)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.update_check_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_view_release) { _, _ ->
                openProjectReleasePage()
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun displayVersion(rawVersion: String): String {
        val withoutSuffix = rawVersion.trim().substringBefore('_')
        return if (withoutSuffix.startsWith("V", ignoreCase = true)) {
            withoutSuffix.drop(1)
        } else {
            withoutSuffix
        }
    }

    private fun openProjectReleasePage() {
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    COMICLAB_RELEASES_URL.toUri()
                )
            )
        } catch (_: ActivityNotFoundException) {
            showMessage(getString(R.string.app_link_open_failed))
        }
    }

    private fun bindConfigurationManagement() {
        btnExportConfiguration.setOnClickListener {
            exportConfigurationLauncher.launch("comiclab_config.json")
        }
        btnImportConfiguration.setOnClickListener {
            importConfigurationLauncher.launch(
                arrayOf("application/json", "text/plain", "*/*")
            )
        }
    }

    private fun exportConfiguration(uri: Uri) {
        configurationExportRunning = true
        updateConfigurationButtons()
        val appContext = applicationContext
        configurationExecutor.execute {
            try {
                val json = ComicLabConfigurationJson.encode(
                    ComicLabConfigurationStore.export(appContext)
                )
                val outputStream = appContext.contentResolver.openOutputStream(uri)
                    ?: error(appContext.getString(R.string.configuration_open_output_failed))
                outputStream.use { stream ->
                    stream.writer(Charsets.UTF_8).use { writer ->
                        writer.write(json)
                    }
                }
                runOnUiThread {
                    if (destroyed) {
                        return@runOnUiThread
                    }
                    configurationExportRunning = false
                    updateConfigurationButtons()
                    showMessage(getString(R.string.configuration_exported))
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (destroyed) {
                        return@runOnUiThread
                    }
                    configurationExportRunning = false
                    updateConfigurationButtons()
                    showMessage(
                        getString(
                            R.string.configuration_export_failed,
                            error.message.orEmpty().ifBlank { error::class.java.simpleName }
                        )
                    )
                }
            }
        }
    }

    private fun importConfiguration(uri: Uri) {
        if (ProcessConfigurationImportCoordinator.start(applicationContext, uri)) {
            configurationImportRunning = true
            updateConfigurationButtons()
        }
    }

    private fun updateConfigurationButtons() {
        val enabled = !configurationImportRunning && !configurationExportRunning
        btnExportConfiguration.isEnabled = enabled
        btnImportConfiguration.isEnabled = enabled
    }

    private fun refreshSettingsControlsAfterImport() {
        switchDarkMode.isChecked = AppSettings.isDarkModeEnabled(this)
        bindReadingDirection()
        bindEnhancementScale()
        switchDoublePageCoverSingle.isChecked = AppSettings.isDoublePageCoverSingleEnabled(this)
        inputStartMarkerErrorTags.setText(AppSettings.getStartMarkerErrorTags(this))
        switchVolumeKeyPageTurn.isChecked = AppSettings.isVolumeKeyPageTurnEnabled(this)
        switchAutoHideSystemBars.isChecked = AppSettings.isAutoHideSystemBarsEnabled(this)
        updateBrightnessControls()
    }

    private fun exportCrashLog() {
        val exportFile = CrashLogManager.createLatestCrashLogExport(this)
        if (exportFile == null) {
            showMessage(getString(R.string.crash_log_empty))
            return
        }

        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            exportFile
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_log_share_title))
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.crash_log_share_title)))
        } catch (_: ActivityNotFoundException) {
            showMessage(getString(R.string.message_no_app_for_file))
        }
    }

    private fun runReaderStressTest() {
        if (!Environment.isExternalStorageManager()) {
            showMessage(getString(R.string.storage_permission_required))
            return
        }

        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.run_reader_stress_test)
            .setMessage(R.string.debug_stress_scanning)
            .setCancelable(false)
            .createRounded()
        progressDialog.show()

        val scanRoot = savedBrowserDirectoryForStress()
        debugToolExecutor.execute {
            val targetFile = findLargestSupportedComic(scanRoot)
            runOnUiThread {
                if (destroyed) {
                    return@runOnUiThread
                }

                progressDialog.dismiss()
                if (targetFile == null) {
                    showMessage(getString(R.string.debug_stress_no_comic_found))
                    return@runOnUiThread
                }

                launchReaderStressTest(targetFile)
            }
        }
    }

    private fun launchReaderStressTest(file: File) {
        val intent = Intent().setClassName(this, "$packageName.ReaderTestActivity").apply {
            putExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH, file.absolutePath)
            putExtra(MangaReaderActivity.EXTRA_START_FROM_BEGINNING, true)
            putExtra(MangaReaderActivity.EXTRA_DEBUG_READER_STRESS, true)
            putExtra(MangaReaderActivity.EXTRA_DEBUG_READER_STRESS_ITERATIONS, DEBUG_STRESS_ITERATIONS)
            putExtra(MangaReaderActivity.EXTRA_DEBUG_READER_STRESS_DELAY_MS, DEBUG_STRESS_DELAY_MS)
            putExtra(MangaReaderActivity.EXTRA_DEBUG_READER_STRESS_TRIM_EVERY, DEBUG_STRESS_TRIM_EVERY)
        }
        showMessage(getString(R.string.debug_stress_starting, file.name))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showMessage(getString(R.string.debug_stress_reader_unavailable))
        }
    }

    private fun savedBrowserDirectoryForStress(): File {
        val savedPath = PrefsGateway.open(this, BROWSER_PREFS_NAME)
            .getString(KEY_BROWSER_CURRENT_PATH, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isDirectory }
        return savedPath ?: Environment.getExternalStorageDirectory()
    }

    private fun findLargestSupportedComic(root: File): File? {
        val startedAt = SystemClock.elapsedRealtime()
        val pending = ArrayDeque<File>().apply {
            add(root)
        }
        var visitedFileCount = 0
        var bestFile: File? = null

        while (pending.isNotEmpty() &&
            visitedFileCount < MAX_DEBUG_STRESS_SCAN_FILES &&
            SystemClock.elapsedRealtime() - startedAt < MAX_DEBUG_STRESS_SCAN_MS
        ) {
            val file = pending.removeLast()
            if (file.name.startsWith(".")) {
                continue
            }

            if (file.isDirectory) {
                val children = runCatching { file.listFiles() }.getOrNull().orEmpty()
                children.forEach { child ->
                    pending.add(child)
                }
                continue
            }

            visitedFileCount++
            if (ComicArchive.isSupportedArchive(file) &&
                (bestFile == null || file.length() > bestFile.length())
            ) {
                bestFile = file
            }
        }

        return bestFile
    }

    private fun isDebuggableBuild(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_SETTINGS_SECTION = "settings_section"
        const val SETTINGS_SECTION_NAS = "nas"
        private const val STATE_SETTINGS_SECTION_INDEX = "settings_section_index"
        private const val CHINESE_LANGUAGE_INDEX = 0
        private const val ENGLISH_LANGUAGE_INDEX = 1
        private const val SIMPLIFIED_CHINESE_LANGUAGE_TAG = "zh-CN"
        private const val ENGLISH_LANGUAGE_TAG = "en"
        private const val ENABLED_BRIGHTNESS_SLIDER_ALPHA = 1f
        private const val DISABLED_BRIGHTNESS_SLIDER_ALPHA = 0.72f
        private const val BROWSER_PREFS_NAME = "saf_prefs"
        private const val KEY_BROWSER_CURRENT_PATH = "current_path"
        private const val MAX_DEBUG_STRESS_SCAN_FILES = 5_000
        private const val MAX_DEBUG_STRESS_SCAN_MS = 12_000L
        private const val DEBUG_STRESS_ITERATIONS = 720
        private const val DEBUG_STRESS_DELAY_MS = 24L
        private const val DEBUG_STRESS_TRIM_EVERY = 18
    }
}

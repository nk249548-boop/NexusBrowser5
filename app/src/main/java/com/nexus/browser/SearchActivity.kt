package com.nexus.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts

class SearchActivity : AppCompatActivity() {

    private lateinit var searchEditText: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var micButton: ImageButton
    private lateinit var settingsButton: ImageButton

    // Quick links
    private lateinit var googleQuickLink: LinearLayout
    private lateinit var youtubeQuickLink: LinearLayout
    private lateinit var facebookQuickLink: LinearLayout
    private lateinit var whatsappQuickLink: LinearLayout
    private lateinit var moreQuickLink: LinearLayout

    // Menu items
    private lateinit var bookmarksMenu: LinearLayout
    private lateinit var historyMenu: LinearLayout
    private lateinit var downloadMenu: LinearLayout
    private lateinit var settingsMenu: LinearLayout
    private lateinit var refreshMenu: LinearLayout
    private lateinit var nightModeMenu: LinearLayout
    private lateinit var incognitoMenu: LinearLayout
    private lateinit var addTabMenu: LinearLayout
    private lateinit var desktopSiteMenu: LinearLayout
    private lateinit var findInPageMenu: LinearLayout
    private lateinit var translateMenu: LinearLayout
    private lateinit var savePageMenu: LinearLayout
    private lateinit var adBlockMenu: LinearLayout
    private lateinit var dataSaverMenu: LinearLayout
    private lateinit var screenshotMenu: LinearLayout
    private lateinit var exitMenu: LinearLayout

    // Voice search launcher
    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (results != null && results.isNotEmpty()) {
                val voiceQuery = results[0]
                searchEditText.setText(voiceQuery)
                performSearch()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        initializeViews()
        setupListeners()
    }

    private fun initializeViews() {
        // Search components
        searchEditText = findViewById(R.id.searchEditText)
        btnBack = findViewById(R.id.btnBack)
        micButton = findViewById(R.id.micButton)
        settingsButton = findViewById(R.id.settingsButton)

        // Quick links
        googleQuickLink = findViewById(R.id.googleQuickLink)
        youtubeQuickLink = findViewById(R.id.youtubeQuickLink)
        facebookQuickLink = findViewById(R.id.facebookQuickLink)
        whatsappQuickLink = findViewById(R.id.whatsappQuickLink)
        moreQuickLink = findViewById(R.id.moreQuickLink)

        // Menu items
        bookmarksMenu = findViewById(R.id.bookmarksMenu)
        historyMenu = findViewById(R.id.historyMenu)
        downloadMenu = findViewById(R.id.downloadMenu)
        settingsMenu = findViewById(R.id.settingsMenu)
        refreshMenu = findViewById(R.id.refreshMenu)
        nightModeMenu = findViewById(R.id.nightModeMenu)
        incognitoMenu = findViewById(R.id.incognitoMenu)
        addTabMenu = findViewById(R.id.addTabMenu)
        desktopSiteMenu = findViewById(R.id.desktopSiteMenu)
        findInPageMenu = findViewById(R.id.findInPageMenu)
        translateMenu = findViewById(R.id.translateMenu)
        savePageMenu = findViewById(R.id.savePageMenu)
        adBlockMenu = findViewById(R.id.adBlockMenu)
        dataSaverMenu = findViewById(R.id.dataSaverMenu)
        screenshotMenu = findViewById(R.id.screenshotMenu)
        exitMenu = findViewById(R.id.exitMenu)
    }

    private fun setupListeners() {
        // Back button
        btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Search action
        searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                return@setOnEditorActionListener true
            }
            false
        }

        // Microphone button
        micButton.setOnClickListener {
            performVoiceSearch()
        }

        // Settings button
        settingsButton.setOnClickListener {
            openSettings()
        }

        // Quick links
        googleQuickLink.setOnClickListener {
            openUrl("https://www.google.com")
        }
        youtubeQuickLink.setOnClickListener {
            openUrl("https://www.youtube.com")
        }
        facebookQuickLink.setOnClickListener {
            openUrl("https://www.facebook.com")
        }
        whatsappQuickLink.setOnClickListener {
            openUrl("https://www.whatsapp.com")
        }
        moreQuickLink.setOnClickListener {
            showMoreApps()
        }

        // Menu items
        bookmarksMenu.setOnClickListener {
            showBookmarks()
        }
        historyMenu.setOnClickListener {
            showHistory()
        }
        downloadMenu.setOnClickListener {
            showDownloads()
        }
        settingsMenu.setOnClickListener {
            openSettings()
        }
        refreshMenu.setOnClickListener {
            refreshPage()
        }
        nightModeMenu.setOnClickListener {
            toggleNightMode()
        }
        incognitoMenu.setOnClickListener {
            openIncognito()
        }
        addTabMenu.setOnClickListener {
            addNewTab()
        }
        desktopSiteMenu.setOnClickListener {
            toggleDesktopSite()
        }
        findInPageMenu.setOnClickListener {
            findInPage()
        }
        translateMenu.setOnClickListener {
            translatePage()
        }
        savePageMenu.setOnClickListener {
            savePage()
        }
        adBlockMenu.setOnClickListener {
            toggleAdBlock()
        }
        dataSaverMenu.setOnClickListener {
            toggleDataSaver()
        }
        screenshotMenu.setOnClickListener {
            takeScreenshot()
        }
        exitMenu.setOnClickListener {
            finish()
        }
    }

    private fun performSearch() {
        val query = searchEditText.text.toString().trim()
        if (query.isNotEmpty()) {
            hideKeyboard()
            val searchUrl = if (query.startsWith("http://") || query.startsWith("https://")) {
                query
            } else {
                "https://www.google.com/search?q=${Uri.encode(query)}"
            }
            openUrl(searchUrl)
        }
    }

    private fun performVoiceSearch() {
        try {
            // Check if device supports speech recognition
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to search...")
            }
            
            voiceSearchLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Speech Recognition not available", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(this, MainActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        intent.data = Uri.parse(url)
        startActivity(intent)
        finish()
    }

    private fun openSettings() {
        // Send signal to MainActivity to open settings
        val intent = Intent(this, MainActivity::class.java)
        intent.action = "OPEN_SETTINGS"
        intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        startActivity(intent)
        finish()
    }

    private fun toggleNightMode() {
        // Route through MainActivity so BrowserViewModel (DataStore-backed) handles it
        val intent = Intent(this, MainActivity::class.java)
        intent.action = "TOGGLE_NIGHT_MODE"
        intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        startActivity(intent)
        finish()
    }

    private fun openIncognito() {
        val intent = Intent(this, MainActivity::class.java)
        intent.action = "OPEN_INCOGNITO"
        startActivity(intent)
        finish()
    }

    private fun showMoreApps() {
        // Could show a dialog with more app options or web services
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
    }

    // ─── Menu item implementations ───────────────────────────────────

    private fun showBookmarks() {
        // Open MainActivity with bookmarks dialog
        val intent = Intent(this, MainActivity::class.java)
        intent.action = "SHOW_BOOKMARKS"
        startActivity(intent)
        finish()
    }

    private fun showHistory() {
        // Open MainActivity with history dialog
        val intent = Intent(this, MainActivity::class.java)
        intent.action = "SHOW_HISTORY"
        startActivity(intent)
        finish()
    }

    private fun showDownloads() {
        // Open Downloads via MainActivity
        val intent = Intent(this, MainActivity::class.java).apply { action = "OPEN_DOWNLOADS_SETTINGS" }
        startActivity(intent)
        finish()
    }

    private fun refreshPage() {
        // Send refresh command back to MainActivity
        val intent = Intent(this, MainActivity::class.java)
        intent.action = "REFRESH_PAGE"
        startActivity(intent)
        finish()
    }

    private fun addNewTab() {
        val intent = Intent(this, MainActivity::class.java)
        intent.action = "ADD_NEW_TAB"
        startActivity(intent)
        finish()
    }

    private fun toggleDesktopSite() {
        val intent = Intent(this, MainActivity::class.java)
        intent.action = "TOGGLE_DESKTOP"
        startActivity(intent)
        finish()
    }

    private fun findInPage() {
        val intent = Intent(this, MainActivity::class.java)
        intent.action = "FIND_IN_PAGE"
        startActivity(intent)
        finish()
    }

    private fun translatePage() {
        val intent = Intent(this, MainActivity::class.java)
        intent.action = "TRANSLATE_PAGE"
        startActivity(intent)
        finish()
    }

    private fun savePage() {
        val intent = Intent(this, MainActivity::class.java)
        intent.action = "SAVE_PAGE"
        startActivity(intent)
        finish()
    }

    private fun toggleAdBlock() {
        val intent = Intent(this, MainActivity::class.java)
        intent.action = "TOGGLE_ADBLOCK"
        startActivity(intent)
        finish()
    }

    private fun toggleDataSaver() {
        val intent = Intent(this, MainActivity::class.java)
        intent.action = "TOGGLE_DATA_SAVER"
        startActivity(intent)
        finish()
    }

    private fun takeScreenshot() {
        val intent = Intent(this, MainActivity::class.java)
        intent.action = "TAKE_SCREENSHOT"
        startActivity(intent)
        finish()
    }

}

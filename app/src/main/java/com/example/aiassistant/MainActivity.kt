package com.example.aiassistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.Locale
import java.util.UUID

val CanvasDark = Color(0xFF08090A)
val Surface1 = Color(0xFF121316)
val Surface2 = Color(0xFF1A1C20)
val HairlineBorder = Color(0x18FFFFFF)
val AccentIndigo = Color(0xFF5E6AD2)
val TextPrimary = Color(0xFFF4F5F8)
val TextMuted = Color(0x99F4F5F8)

enum class AIPersonality(val title: String, val defaultPitch: Float, val defaultSpeed: Float) {
    AGENT_TEAM("AGENT TEAM", 1.0f, 1.0f),
    SOCIAL_AUTOPILOT("SOCIAL AUTO", 1.0f, 1.05f),
    DEEP_RESEARCH("RESEARCH", 0.95f, 1.05f),
    PLAN_CODE("PLAN/CODE", 0.96f, 1.0f),
    GIRLFRIEND("GF MODE", 1.28f, 0.95f),
    BOYFRIEND("BF MODE", 0.80f, 1.0f)
}

// Defined AssistantState to resolve Unresolved Reference errors
enum class AssistantState { IDLE, LISTENING, ROUTING, THINKING, REFLECTING, SPEAKING }

data class ChatMessage(val sender: String, val text: String, val badge: String)
data class PendingHumanApproval(val title: String, val description: String, val onConfirm: () -> Unit)

class OfflineActionExecutor(private val context: Context) {

    @SuppressLint("Range")
    private fun lookupContactNumber(nameQuery: String): String? {
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$nameQuery%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    return it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun shareToSocialMedia(postContent: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, postContent)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val chooser = Intent.createChooser(sendIntent, "Publish Automated Post via").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(chooser)
    }

    fun checkAndExecute(
        command: String,
        onRequireHumanApproval: (PendingHumanApproval) -> Unit
    ): String? {
        val cmd = command.lowercase(Locale.getDefault())
        return try {
            when {
                cmd.contains("morning briefing") || cmd.contains("good morning") -> {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    "Good Morning! Phone battery $level% hai. Aapki Multi-Agent team ready hai."
                }
                cmd.contains("battery") || cmd.contains("charge") -> {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    "Aapke phone ki battery abhi $level percent hai."
                }
                cmd.contains("open camera") || cmd.contains("camera kholo") -> {
                    context.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    "Camera open kar raha hoon."
                }
                cmd.startsWith("call ") || cmd.startsWith("dial ") -> {
                    val target = cmd.replace("call ", "").replace("dial ", "").trim()
                    val resolvedNumber = lookupContactNumber(target) ?: target
                    onRequireHumanApproval(
                        PendingHumanApproval(
                            title = "CONFIRM PHONE CALL",
                            description = "Call '$target' ($resolvedNumber)?",
                            onConfirm = {
                                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$resolvedNumber")).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                })
                            }
                        )
                    )
                    "$target ($resolvedNumber) ko call lagane ke liye Approve dabayein."
                }
                else -> null
            }
        } catch (e: Exception) {
            "Offline action ke liye permission zaroori hai."
        }
    }
}

class AssistantViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AssistantState.IDLE)
    val uiState: StateFlow<AssistantState> = _uiState

    private val _personality = MutableStateFlow(AIPersonality.AGENT_TEAM)
    val personality: StateFlow<AIPersonality> = _personality

    private val _selectedEngine = MutableStateFlow("AUTO_FREE")
    val selectedEngine: StateFlow<String> = _selectedEngine

    private val _selectedStyle = MutableStateFlow("CONCISE")
    val selectedStyle: StateFlow<String> = _selectedStyle

    private val _aiResponse = MutableStateFlow("SAY 'HEY MANNU' OR TAP THE ORB")
    val aiResponse: StateFlow<String> = _aiResponse

    private val _agentTrace = MutableStateFlow("ROUTER READY • ARCHITECTURE ACTIVE")
    val agentTrace: StateFlow<String> = _agentTrace

    private val _chatHistory = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatHistory: StateFlow<List<ChatMessage>> = _chatHistory

    private val _freeCredits = MutableStateFlow(5)
    val freeCredits: StateFlow<Int> = _freeCredits

    private val _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro

    private val _wakeWordEnabled = MutableStateFlow(false)
    val wakeWordEnabled: StateFlow<Boolean> = _wakeWordEnabled

    private val _customPitch = MutableStateFlow(1.0f)
    val customPitch: StateFlow<Float> = _customPitch

    private val _customSpeed = MutableStateFlow(1.0f)
    val customSpeed: StateFlow<Float> = _customSpeed

    private val _pendingApproval = MutableStateFlow<PendingHumanApproval?>(null)
    val pendingApproval: StateFlow<PendingHumanApproval?> = _pendingApproval

    private var userUuid: String = UUID.randomUUID().toString()

    fun initPersistence(context: Context) {
        val prefs = context.getSharedPreferences("mannu_master_state", Context.MODE_PRIVATE)
        userUuid = prefs.getString("user:uuid", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("user:uuid", it).apply()
        }
        _freeCredits.value = prefs.getInt("user:credits", 5)
        _isPro.value = prefs.getBoolean("user:is_pro", false)
        prefs.getString("session:last_reply", null)?.let { _aiResponse.value = it }
    }

    private fun savePersistence(context: Context, lastReply: String) {
        context.getSharedPreferences("mannu_master_state", Context.MODE_PRIVATE).edit()
            .putInt("user:credits", _freeCredits.value)
            .putBoolean("user:is_pro", _isPro.value)
            .putString("session:last_reply", lastReply)
            .apply()
    }

    fun clearAllUserData(context: Context) {
        context.getSharedPreferences("mannu_master_state", Context.MODE_PRIVATE).edit().clear().apply()
        _chatHistory.value = emptyList()
        _aiResponse.value = "ALL USER DATA & MEMORY DELETED."
        _agentTrace.value = "MEMORY WIPED"
    }

    fun compactSessionMemory() {
        val count = _chatHistory.value.size
        if (count > 2) {
            val lastTwo = _chatHistory.value.takeLast(2)
            _chatHistory.value = listOf(
                ChatMessage("SYSTEM (/compact)", "Compacted $count messages.", "SAVER")
            ) + lastTwo
            _agentTrace.value = "SESSION COMPACTED"
        }
    }

    fun switchPersonality(mode: AIPersonality) {
        _personality.value = mode
        _customPitch.value = mode.defaultPitch
        _customSpeed.value = mode.defaultSpeed
        _agentTrace.value = "MODE -> ${mode.title}"
    }

    fun setEngine(engine: String) { _selectedEngine.value = engine }
    fun setOutputStyle(style: String) { _selectedStyle.value = style }
    fun toggleWakeWord() { _wakeWordEnabled.value = !_wakeWordEnabled.value }
    fun updateVoiceSettings(pitch: Float, speed: Float) {
        _customPitch.value = pitch
        _customSpeed.value = speed
    }
    fun dismissApproval() { _pendingApproval.value = null }

    fun rewardWatchAdCredits(context: Context) {
        _freeCredits.value += 3
        val msg = "REWARD UNLOCKED: +3 Free Credits added!"
        _aiResponse.value = msg
        savePersistence(context, msg)
    }

    fun activatePro(context: Context) {
        _isPro.value = true
        _freeCredits.value = 99999
        val msg = "PRO UNLOCKED"
        _aiResponse.value = msg
        savePersistence(context, msg)
    }

    fun setListening() {
        _uiState.value = AssistantState.LISTENING
        _agentTrace.value = "LISTENING..."
    }

    fun processInput(
        context: Context,
        rawInput: String,
        offlineExecutor: OfflineActionExecutor,
        onSpeak: (String, Float, Float) -> Unit
    ) {
        val cleanInput = rawInput.trim()
        if (cleanInput.isBlank()) return

        viewModelScope.launch {
            _uiState.value = AssistantState.ROUTING
            delay(100)

            val offlineReply = offlineExecutor.checkAndExecute(cleanInput) { approval ->
                _pendingApproval.value = approval
            }
            if (offlineReply != null) {
                _aiResponse.value = offlineReply
                _agentTrace.value = "OFFLINE EXECUTOR"
                savePersistence(context, offlineReply)
                _uiState.value = AssistantState.SPEAKING
                onSpeak(offlineReply, _customPitch.value, _customSpeed.value)
                return@launch
            }

            if (!_isPro.value && _freeCredits.value <= 0) {
                _aiResponse.value = "Free Credits khatam! Upgrade to Pro."
                _uiState.value = AssistantState.IDLE
                return@launch
            }

            _uiState.value = AssistantState.THINKING
            delay(300)

            val finalReply = "Agent team processed: '$cleanInput'."
            appendHistory(cleanInput, finalReply, _personality.value.title)
            savePersistence(context, finalReply)
            _aiResponse.value = finalReply
            _uiState.value = AssistantState.SPEAKING
            onSpeak(finalReply, _customPitch.value, _customSpeed.value)
        }
    }

    private fun appendHistory(userMsg: String, aiMsg: String, badge: String) {
        val list = _chatHistory.value.toMutableList()
        list.add(ChatMessage("YOU", userMsg, "INPUT"))
        list.add(ChatMessage("MANNU AI", aiMsg, badge))
        _chatHistory.value = list
    }

    fun setIdle() { _uiState.value = AssistantState.IDLE }
}

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var offlineExecutor: OfflineActionExecutor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileAds.initialize(this) {}
        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        offlineExecutor = OfflineActionExecutor(this)

        setContent {
            val viewModel: AssistantViewModel = viewModel()
            LaunchedEffect(Unit) { viewModel.initPersistence(this@MainActivity) }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { _ -> }

            MannuZeroGapScreen(
                viewModel = viewModel,
                offlineExecutor = offlineExecutor,
                onOrbClick = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.CAMERA,
                            Manifest.permission.CALL_PHONE,
                            Manifest.permission.READ_CONTACTS
                        )
                    )
                },
                onSendText = { text ->
                    viewModel.processInput(this, text, offlineExecutor) { reply, pitch, speed ->
                        speakOut(reply, pitch, speed) {
                            viewModel.setIdle()
                        }
                    }
                },
                onRewardAdClick = { viewModel.rewardWatchAdCredits(this) },
                onUpgradeClick = { viewModel.activatePro(this) }
            )
        }
    }

    private fun speakOut(text: String, pitch: Float, speed: Float, onDone: () -> Unit) {
        tts.setPitch(pitch)
        tts.setSpeechRate(speed)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "MANNU_UTTERANCE")
        onDone()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("hi", "IN")
        }
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}

@Composable
fun MannuZeroGapScreen(
    viewModel: AssistantViewModel,
    offlineExecutor: OfflineActionExecutor,
    onOrbClick: () -> Unit,
    onSendText: (String) -> Unit,
    onRewardAdClick: () -> Unit,
    onUpgradeClick: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val personality by viewModel.personality.collectAsState()
    val responseText by viewModel.aiResponse.collectAsState()
    val agentTrace by viewModel.agentTrace.collectAsState()
    val history by viewModel.chatHistory.collectAsState()
    val credits by viewModel.freeCredits.collectAsState()
    val isPro by viewModel.isPro.collectAsState()
    val pendingApproval by viewModel.pendingApproval.collectAsState()

    var textInput by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "orb")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = if (state == AssistantState.IDLE) 1.0f else 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    if (pendingApproval != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissApproval() },
            containerColor = Surface2,
            title = { Text(pendingApproval!!.title, color = AccentIndigo, fontSize = 13.sp) },
            text = { Text(pendingApproval!!.description, color = TextPrimary, fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingApproval!!.onConfirm()
                        viewModel.dismissApproval()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo)
                ) {
                    Text("APPROVE", color = TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissApproval() }) {
                    Text("CANCEL", color = TextMuted)
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(CanvasDark)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("M A N N U", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier.background(Surface1, RoundedCornerShape(6.dp))
                            .border(1.dp, HairlineBorder, RoundedCornerShape(6.dp))
                            .clickable { showHistory = !showHistory; showSettings = false }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text("LOG", color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                    Box(
                        modifier = Modifier.background(Surface1, RoundedCornerShape(6.dp))
                            .border(1.dp, HairlineBorder, RoundedCornerShape(6.dp))
                            .clickable { showSettings = !showSettings; showHistory = false }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text("TUNE", color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                    Box(
                        modifier = Modifier.background(if (isPro) Surface2 else AccentIndigo, RoundedCornerShape(6.dp))
                            .clickable { onUpgradeClick() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(if (isPro) "PRO" else "⚡$credits | PRO", color = TextPrimary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .background(Surface1, RoundedCornerShape(8.dp))
                    .border(1.dp, HairlineBorder, RoundedCornerShape(8.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AIPersonality.entries.forEach { mode ->
                    val selected = (mode == personality)
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .background(if (selected) Surface2 else Color.Transparent)
                            .clickable { viewModel.switchPersonality(mode) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(mode.title, color = if (selected) TextPrimary else TextMuted, fontSize = 9.sp)
                    }
                }
            }

            when {
                showSettings -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 10.dp)
                            .background(Surface1, RoundedCornerShape(8.dp))
                            .border(1.dp, HairlineBorder, RoundedCornerShape(8.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("SETTINGS & COMPLIANCE", color = AccentIndigo, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            text = "DELETE MY DATA (GDPR)",
                            color = Color(0xFFEF4444),
                            fontSize = 10.sp,
                            modifier = Modifier.clickable { viewModel.clearAllUserData(context) }.padding(6.dp)
                        )
                    }
                }
                showHistory -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().weight(1f).background(Surface1, RoundedCornerShape(8.dp)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(history) { msg ->
                            Column(modifier = Modifier.fillMaxWidth().background(Surface2, RoundedCornerShape(6.dp)).padding(10.dp)) {
                                Text(msg.sender, color = AccentIndigo, fontSize = 10.sp)
                                Text(msg.text, color = TextPrimary, fontSize = 13.sp)
                            }
                        }
                    }
                }
                else -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(200.dp).clip(CircleShape).clickable { onOrbClick() }
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawCircle(
                                    brush = Brush.radialGradient(colors = listOf(AccentIndigo.copy(alpha = 0.75f), Color.Transparent)),
                                    radius = (size.minDimension / 2.0f) * pulseScale
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(agentTrace, color = AccentIndigo, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(responseText, color = TextPrimary, fontSize = 14.sp, textAlign = TextAlign.Center)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(8.dp)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Ask agents or type command...", color = TextMuted, fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    singleLine = true
                )
                Button(
                    onClick = {
                        if (textInput.isNotBlank()) {
                            onSendText(textInput)
                            textInput = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo)
                ) {
                    Text("RUN", color = TextPrimary, fontSize = 10.sp)
                }
            }
        }
    }
}

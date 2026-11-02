package com.example.campuseventsocietyhub

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.campuseventsocietyhub.data.*
import com.example.campuseventsocietyhub.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object EventNotificationHelper {
    private const val CHANNEL_ID = "campus_event_reminders"

    fun showNotification(context: Context, title: String, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Campus Event Reminders", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Reminders and updates for campus events" }
            notificationManager.createNotificationChannel(channel)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), notification)
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var repository: CampusRepository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        repository = CampusRepository(CampusDatabase.get(this), this)
        lifecycleScope.launch { repository.seedIfNeeded() }
        setContent {
            CampusEventSocietyHubTheme {
                Surface(Modifier.fillMaxSize(), color = LightBackground) {
                    var showSplash by rememberSaveable { mutableStateOf(true) }
                    LaunchedEffect(Unit) {
                        delay(2200)
                        showSplash = false
                    }
                    if (showSplash) {
                        SplashScreen()
                    } else {
                        CampusApp(repository)
                    }
                }
            }
        }
    }
}

private enum class AppPage {
    LOGIN, REGISTER, HOME, EVENTS, PROFILE, APPLICATION, ADMIN,
    CREATE_EVENT, REGISTERED, MANAGE_EVENTS, EDIT_EVENT, EVENT_DETAILS, EVENT_ATTENDEES
}

@Composable
private fun CampusApp(repo: CampusRepository) {
    val context = LocalContext.current
    var loggedInUserId by rememberSaveable { mutableStateOf<Long?>(null) }
    val activeUserFlow = remember(loggedInUserId) {
        loggedInUserId?.let { repo.userFlow(it) } ?: kotlinx.coroutines.flow.flowOf(null)
    }
    val activeUserFromDb by activeUserFlow.collectAsStateWithLifecycle(initialValue = null)
    val user = activeUserFromDb

    var page by remember { mutableStateOf(AppPage.LOGIN) }
    var previousPageForDetails by remember { mutableStateOf(AppPage.HOME) }
    var message by remember { mutableStateOf("") }
    var messageIsError by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    var showReminderSettings by remember { mutableStateOf(false) }
    var showAppSettings by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var selectedEventToEdit by remember { mutableStateOf<EventEntity?>(null) }
    var selectedEventAttendees by remember { mutableStateOf<EventEntity?>(null) }
    var selectedEventForDetails by remember { mutableStateOf<EventEntity?>(null) }
    var eventRemindersEnabled by remember { mutableStateOf(true) }
    var newEventNotificationsEnabled by remember { mutableStateOf(true) }
    var registrationNotificationsEnabled by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {}
    )
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val events by repo.events(search, category).collectAsStateWithLifecycle(initialValue = emptyList())
    val users by repo.users().collectAsStateWithLifecycle(initialValue = emptyList())
    val pending by repo.pendingApplications().collectAsStateWithLifecycle(initialValue = emptyList())
    val allApplications by repo.allApplications().collectAsStateWithLifecycle(initialValue = emptyList())
    val registrationList by (user?.let { repo.registrations(it.id) }
        ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsStateWithLifecycle(initialValue = emptyList())
    val organiserEvents by (user?.let { repo.eventsByOrganiser(it.id) }
        ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsStateWithLifecycle(initialValue = emptyList())
    val attendeesList by (selectedEventAttendees?.let { repo.eventAttendees(it.id) }
        ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsStateWithLifecycle(initialValue = emptyList())

    fun notify(text: String, error: Boolean = false) {
        message = text
        messageIsError = error
    }

    LaunchedEffect(user?.active) {
        if (user != null && !user.active) {
            notify("Your account has been disabled by an administrator.", true)
            repo.signOut()
            loggedInUserId = null
            page = AppPage.LOGIN
        }
    }

    val isRootPage = page == AppPage.HOME || page == AppPage.LOGIN
    BackHandler(enabled = !isRootPage) {
        when (page) {
            AppPage.REGISTER -> page = AppPage.LOGIN
            AppPage.APPLICATION, AppPage.ADMIN, AppPage.CREATE_EVENT, AppPage.REGISTERED, AppPage.MANAGE_EVENTS -> page = AppPage.PROFILE
            AppPage.EVENT_DETAILS -> page = previousPageForDetails
            AppPage.EVENT_ATTENDEES, AppPage.EDIT_EVENT -> page = AppPage.MANAGE_EVENTS
            AppPage.EVENTS, AppPage.PROFILE -> page = AppPage.HOME
            else -> Unit
        }
    }

    if (user == null) {
        when (page) {
            AppPage.REGISTER -> RegisterScreen(
                onBack = { page = AppPage.LOGIN },
                onRegister = { name, email, password ->
                    scope.launch {
                        val result = repo.registerStudent(name, email, password)
                        result.onSuccess { loggedInUserId = it.id; page = AppPage.HOME; notify("Account created successfully.") }
                            .onFailure { notify(it.message ?: "Registration failed.", true) }
                    }
                }
            )
            else -> LoginScreen(
                onRegister = { page = AppPage.REGISTER },
                onLogin = { email, password ->
                    scope.launch {
                        repo.login(email, password).onSuccess {
                            loggedInUserId = it.id
                            page = AppPage.HOME
                            notify("Welcome back, ${it.name.substringBefore(" ")}.")
                        }.onFailure { notify(it.message ?: "Login failed.", true) }
                    }
                },
                onForgotPassword = { email ->
                    scope.launch {
                        repo.resetPassword(email).onSuccess {
                            notify("Password reset email sent to $email. Please check your email inbox or spam folder.")
                        }.onFailure { notify(it.message ?: "Could not send reset email.", true) }
                    }
                }
            )
        }
        MessageDialog(message, messageIsError) { message = "" }
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = White) {
                listOf(
                    Triple(AppPage.HOME, Icons.Default.Home, "Home"),
                    Triple(AppPage.EVENTS, Icons.Default.Event, "Events"),
                    Triple(AppPage.PROFILE, Icons.Default.Person, "Profile")
                ).forEach { (target, icon, label) ->
                    NavigationBarItem(
                        selected = page == target || (target == AppPage.PROFILE &&
                            page in listOf(AppPage.APPLICATION, AppPage.ADMIN, AppPage.CREATE_EVENT, AppPage.REGISTERED, AppPage.MANAGE_EVENTS, AppPage.EDIT_EVENT, AppPage.EVENT_ATTENDEES)),
                        onClick = { page = target },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NavyBlue,
                            selectedTextColor = Gold,
                            indicatorColor = PaleBlue
                        )
                    )
                }
            }
        },
        containerColor = LightBackground
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (page) {
                AppPage.HOME -> HomeScreen(
                    events = events, category = category, onCategory = { category = it },
                    onEvents = { page = AppPage.EVENTS },
                    onSelectEvent = {
                        selectedEventForDetails = it
                        previousPageForDetails = AppPage.HOME
                        page = AppPage.EVENT_DETAILS
                    }
                )
                AppPage.EVENTS -> EventsScreen(
                    events = events, user = user, registrations = registrationList.map { it.eventId }.toSet(),
                    search = search, onSearch = { search = it }, category = category, onCategory = { category = it },
                    onSelectEvent = {
                        selectedEventForDetails = it
                        previousPageForDetails = AppPage.EVENTS
                        page = AppPage.EVENT_DETAILS
                    },
                    onRegister = { event ->
                        scope.launch {
                            repo.registerForEvent(event.id, user).onSuccess {
                                notify("You are registered for ${event.title}.")
                                EventNotificationHelper.showNotification(
                                    context, "Event Reminder Set 🔔",
                                    "Reminder saved for ${event.title} on ${event.date} at ${event.time} (${event.location})."
                                )
                            }.onFailure { notify(it.message ?: "Could not register.", true) }
                        }
                    },
                    onCancel = { event ->
                        scope.launch { repo.cancelRegistration(event.id, user.id); notify("Registration cancelled.") }
                    }
                )
                AppPage.PROFILE -> ProfileScreen(
                    user = user, onApplication = { page = AppPage.APPLICATION },
                    onAdmin = { page = AppPage.ADMIN }, onCreateEvent = { page = AppPage.CREATE_EVENT },
                    onManageEvents = { page = AppPage.MANAGE_EVENTS },
                    onRegistered = { page = AppPage.REGISTERED },
                    onReminders = { showReminderSettings = true },
                    onSettings = { showAppSettings = true },
                    onLogout = { showLogoutConfirm = true }
                )
                AppPage.APPLICATION -> ApplicationScreen(
                    user = user,
                    previous = allApplications.firstOrNull { it.studentId == user.id },
                    onBack = { page = AppPage.PROFILE },
                    onSubmit = { organisation, reason ->
                        scope.launch {
                            repo.applyForOrganiser(user, organisation, reason)
                                .onSuccess { notify("Application submitted. The administrator must review it."); page = AppPage.PROFILE }
                                .onFailure { notify(it.message ?: "Could not submit application.", true) }
                        }
                    }
                )
                AppPage.ADMIN -> AdminScreen(
                    users = users, applications = pending, allApplications = allApplications,
                    onBack = { page = AppPage.PROFILE },
                    onReview = { application, approve ->
                        scope.launch {
                            runCatching { repo.reviewApplication(application, approve) }
                                .onSuccess { notify(if (approve) "Organiser approved." else "Application rejected.") }
                                .onFailure { notify(it.message ?: "Could not review application.", true) }
                        }
                    },
                    onToggleUser = { account ->
                        scope.launch {
                            runCatching { repo.setUserActive(account, !account.active) }
                                .onSuccess { notify("Account status updated.") }
                                .onFailure { notify(it.message ?: "Could not update account.", true) }
                        }
                    }
                )
                AppPage.CREATE_EVENT -> CreateEventScreen(
                    onBack = { page = AppPage.PROFILE },
                    onCreate = { title, category, date, time, location, organisation, description ->
                        scope.launch {
                            repo.createEvent(user, title, category, date, time, location, organisation, description)
                                .onSuccess {
                                    notify("Event created and published.")
                                    EventNotificationHelper.showNotification(
                                        context, "New Campus Event 📢",
                                        "$title has been published for $date at $time!"
                                    )
                                    page = AppPage.HOME
                                }
                                .onFailure { notify(it.message ?: "Could not create event.", true) }
                        }
                    }
                )
                AppPage.MANAGE_EVENTS -> ManageEventsScreen(
                    organiserEvents = organiserEvents,
                    onBack = { page = AppPage.PROFILE },
                    onEdit = { selectedEventToEdit = it; page = AppPage.EDIT_EVENT },
                    onViewAttendees = { selectedEventAttendees = it; page = AppPage.EVENT_ATTENDEES }
                )
                AppPage.EDIT_EVENT -> EditEventScreen(
                    event = selectedEventToEdit!!,
                    onBack = { page = AppPage.MANAGE_EVENTS },
                    onSave = { updated ->
                        scope.launch {
                            repo.updateEvent(user, updated)
                                .onSuccess { notify("Event updated successfully."); page = AppPage.MANAGE_EVENTS }
                                .onFailure { notify(it.message ?: "Could not update event.", true) }
                        }
                    }
                )
                AppPage.EVENT_DETAILS -> EventDetailsScreen(
                    event = selectedEventForDetails!!,
                    isRegistered = registrationList.any { it.eventId == selectedEventForDetails!!.id },
                    onBack = { page = previousPageForDetails },
                    onRegister = { target ->
                        scope.launch {
                            repo.registerForEvent(target.id, user).onSuccess {
                                notify("You are registered for ${target.title}.")
                                EventNotificationHelper.showNotification(
                                    context, "Event Reminder Set 🔔",
                                    "Reminder saved for ${target.title} on ${target.date} at ${target.time}."
                                )
                            }.onFailure { notify(it.message ?: "Could not register.", true) }
                        }
                    },
                    onCancel = { target ->
                        scope.launch { repo.cancelRegistration(target.id, user.id); notify("Registration cancelled.") }
                    }
                )
                AppPage.EVENT_ATTENDEES -> EventAttendeesScreen(
                    event = selectedEventAttendees!!,
                    attendees = attendeesList,
                    onBack = { page = AppPage.MANAGE_EVENTS }
                )
                AppPage.REGISTERED -> RegisteredScreen(
                    events = events.filter { event -> registrationList.any { it.eventId == event.id } },
                    onBack = { page = AppPage.PROFILE },
                    onSelectEvent = {
                        selectedEventForDetails = it
                        previousPageForDetails = AppPage.REGISTERED
                        page = AppPage.EVENT_DETAILS
                    },
                    onCancel = { event ->
                        scope.launch { repo.cancelRegistration(event.id, user.id); notify("Registration cancelled.") }
                    }
                )
                AppPage.LOGIN, AppPage.REGISTER -> Unit
            }
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Log out", color = NavyBlue, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to log out of Campus Event Hub?") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    repo.signOut()
                    loggedInUserId = null
                    page = AppPage.LOGIN
                    search = ""
                    category = "All"
                }) { Text("Log out", color = ErrorRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showLogoutConfirm = false }) { Text("Cancel") } }
        )
    }
    if (showReminderSettings) {
        AlertDialog(
            onDismissRequest = { showReminderSettings = false },
            title = { Text("Notifications", color = NavyBlue, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Choose which campus updates you want to receive.", color = MutedText)
                    Spacer(Modifier.height(8.dp))
                    SettingSwitch("Event reminders", "Remind me about registered events", eventRemindersEnabled) { eventRemindersEnabled = it }
                    SettingSwitch("New events", "Updates when events are published", newEventNotificationsEnabled) { newEventNotificationsEnabled = it }
                    SettingSwitch("Registration updates", "Confirmations and cancellations", registrationNotificationsEnabled) { registrationNotificationsEnabled = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showReminderSettings = false
                    EventNotificationHelper.showNotification(
                        context, "Notifications Saved 🔔",
                        "Campus Event Hub reminders are active on this device."
                    )
                    notify("Notification preferences saved.")
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showReminderSettings = false }) { Text("Cancel") } }
        )
    }
    if (showAppSettings) {
        AlertDialog(
            onDismissRequest = { showAppSettings = false },
            title = { Text("Settings", color = NavyBlue, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Campus Event Hub", fontWeight = FontWeight.SemiBold, color = NavyBlue)
                    Text("University of Malawi", color = MutedText)
                    Spacer(Modifier.height(8.dp))
                    Text("Version 1.0", color = MutedText)
                    Text("Room Database keeps a local cache. Firebase shares accounts, events and approvals when configured.", color = MutedText)
                    Spacer(Modifier.height(8.dp))
                    Text("Signed in as: ${user?.email.orEmpty()}", color = NavyBlue)
                }
            },
            confirmButton = { TextButton(onClick = { showAppSettings = false }) { Text("Done") } }
        )
    }
    MessageDialog(message, messageIsError) { message = "" }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = NavyBlue, fontWeight = FontWeight.SemiBold)
            Text(description, color = MutedText, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun BrandHeader(title: String, subtitle: String = "", onBack: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().background(NavyBlue, RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .padding(horizontal = 22.dp, vertical = 20.dp)
    ) {
        if (onBack != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = White) }
                Text(title, color = White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        } else {
            Text("UNIVERSITY OF MALAWI", color = Gold, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(title, color = White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(subtitle, color = White.copy(alpha = .85f), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun LoginScreen(onRegister: () -> Unit, onLogin: (String, String) -> Unit, onForgotPassword: (String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showForgotDialog by remember { mutableStateOf(false) }
    var forgotEmail by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(LightBackground)) {
        BrandHeader("Campus Event Hub", "Discover. Connect. Participate.")
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(20.dp))
            Text("Welcome back", style = MaterialTheme.typography.headlineSmall, color = NavyBlue, fontWeight = FontWeight.Bold)
            Text("Log in to explore campus activities.", color = MutedText)
            Spacer(Modifier.height(24.dp))
            AppTextField(email, { email = it }, "Email address", leading = { Icon(Icons.Default.Email, null) }, keyboardType = KeyboardType.Email)
            Spacer(Modifier.height(12.dp))
            AppTextField(password, { password = it }, "Password", keyboardType = KeyboardType.Password,
                visual = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = { IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } })
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                TextButton(onClick = { forgotEmail = email; showForgotDialog = true }) {
                    Text("Forgot password?", color = RoyalBlue, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onLogin(email, password) }, modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp)) { Text("Log in", fontWeight = FontWeight.Bold) }
            TextButton(onClick = onRegister) { Text("New to Campus Event Hub? Create an account") }
        }
    }

    if (showForgotDialog) {
        AlertDialog(
            onDismissRequest = { showForgotDialog = false },
            title = { Text("Reset Password", color = NavyBlue, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Enter your email address and we will send you instructions to reset your password.", color = MutedText)
                    Spacer(Modifier.height(12.dp))
                    AppTextField(forgotEmail, { forgotEmail = it }, "Email address", keyboardType = KeyboardType.Email)
                }
            },
            confirmButton = {
                Button(onClick = {
                    showForgotDialog = false
                    onForgotPassword(forgotEmail)
                }) { Text("Send reset link") }
            },
            dismissButton = {
                TextButton(onClick = { showForgotDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun RegisterScreen(onBack: () -> Unit, onRegister: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(LightBackground)) {
        BrandHeader("Create your account", "Student registration", onBack)
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            AppTextField(name, { name = it }, "Full name")
            Spacer(Modifier.height(10.dp))
            AppTextField(email, { email = it }, "Email address", keyboardType = KeyboardType.Email)
            Spacer(Modifier.height(10.dp))
            AppTextField(password, { password = it }, "Password (8+ characters)", keyboardType = KeyboardType.Password, visual = PasswordVisualTransformation())
            Spacer(Modifier.height(10.dp))
            AppTextField(confirm, { confirm = it }, "Confirm password", keyboardType = KeyboardType.Password, visual = PasswordVisualTransformation())
            Spacer(Modifier.height(10.dp))
            Text("All new accounts are registered as students. Organiser access requires administrator approval.",
                color = MutedText, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(18.dp))
            Button(onClick = {
                if (password != confirm) return@Button
                onRegister(name, email, password)
            }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) { Text("Create account") }
            if (password.isNotEmpty() && confirm.isNotEmpty() && password != confirm)
                Text("Passwords do not match.", color = ErrorRed)
        }
    }
}

@Composable
private fun HomeScreen(
    events: List<EventEntity>, category: String, onCategory: (String) -> Unit,
    onEvents: () -> Unit, onSelectEvent: (EventEntity) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Column(Modifier.fillMaxWidth().background(NavyBlue, RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)).padding(22.dp)) {
                Text("UNIVERSITY OF MALAWI", color = Gold, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("Campus Event Hub", color = White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Discover. Connect. Participate.", color = White.copy(alpha = .85f), style = MaterialTheme.typography.titleMedium)
            }
        }
        item {
            Column(Modifier.padding(top = 20.dp)) {
                Text("Explore Categories", modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge,
                    color = NavyBlue, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Academic", "Workshop", "Social", "Club", "Other").forEach { item ->
                        FilterChip(selected = category == item, onClick = { onCategory(item) }, label = { Text(item) })
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Upcoming Events", color = NavyBlue, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${events.size} events", color = MutedText)
            }
        }
        if (events.isEmpty()) item { EmptyState("No events found", "Try selecting another category.") }
        items(events.take(4), key = { it.id }) { event -> EventCard(event, actionLabel = "View details →", onAction = { onSelectEvent(event) }) }
        item {
            TextButton(onClick = onEvents, modifier = Modifier.fillMaxWidth()) { Text("View all events") }
        }
    }
}

@Composable
private fun EventsScreen(
    events: List<EventEntity>, user: UserEntity, registrations: Set<Long>, search: String, onSearch: (String) -> Unit,
    category: String, onCategory: (String) -> Unit, onSelectEvent: (EventEntity) -> Unit,
    onRegister: (EventEntity) -> Unit, onCancel: (EventEntity) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        BrandHeader("Campus Events", "Search and find activities")
        OutlinedTextField(search, onSearch, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            placeholder = { Text("Search events...") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("All", "Academic", "Workshop", "Social", "Club", "Other").forEach {
                FilterChip(selected = category == it, onClick = { onCategory(it) }, label = { Text(it) })
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            if (events.isEmpty()) item { EmptyState("No matching events", "Try changing your search.") }
            items(events, key = { it.id }) { event ->
                Column {
                    EventCard(event, actionLabel = if (registrations.contains(event.id)) "Cancel registration" else "Register for event",
                        onAction = {
                            if (registrations.contains(event.id)) onCancel(event) else onRegister(event)
                        },
                        onClickCard = { onSelectEvent(event) })
                }
            }
        }
    }
}

@Composable
private fun EventCard(event: EventEntity, actionLabel: String, onAction: () -> Unit, onClickCard: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp).clickable { onClickCard?.invoke() ?: onAction() }, shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = White), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(68.dp).background(PaleGold, RoundedCornerShape(16.dp)).padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                val day = event.date.substringAfterLast("-").toIntOrNull()?.toString() ?: "--"
                Text(day, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = NavyBlue)
                Text(event.date.take(7).substringAfter("-").let { month ->
                    when (month) { "01" -> "Jan"; "02" -> "Feb"; "03" -> "Mar"; "04" -> "Apr"; "05" -> "May"; "06" -> "Jun"; "07" -> "Jul"; "08" -> "Aug"; "09" -> "Sep"; "10" -> "Oct"; "11" -> "Nov"; "12" -> "Dec"; else -> month }
                }, color = NavyBlue)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(event.category.uppercase(), color = RoyalBlue, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(event.title, color = NavyBlue, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(event.location, color = MutedText)
                Text("${event.time} • ${event.organiserName}", color = MutedText, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text(actionLabel, color = RoyalBlue, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onAction).padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun EventDetailsScreen(
    event: EventEntity,
    isRegistered: Boolean,
    onBack: () -> Unit,
    onRegister: (EventEntity) -> Unit,
    onCancel: (EventEntity) -> Unit
) {
    Column(Modifier.fillMaxSize().background(LightBackground)) {
        BrandHeader("Event details", event.category.uppercase(), onBack)
        LazyColumn(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = White), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Surface(color = PaleGold, shape = RoundedCornerShape(12.dp)) {
                            Text(event.category.uppercase(), color = NavyBlue, style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(event.title, color = NavyBlue, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = White), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Event, null, tint = RoyalBlue, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Date & Time", color = MutedText, style = MaterialTheme.typography.bodySmall)
                                Text("${event.date} at ${event.time}", color = DarkText, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Divider(color = PaleBlue)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Place, null, tint = RoyalBlue, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Venue / Location", color = MutedText, style = MaterialTheme.typography.bodySmall)
                                Text(event.location, color = DarkText, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Divider(color = PaleBlue)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Group, null, tint = RoyalBlue, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Organiser", color = MutedText, style = MaterialTheme.typography.bodySmall)
                                Text(event.organiserName, color = DarkText, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = White), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("About this event", color = NavyBlue, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(event.description, color = DarkText, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        Surface(Modifier.fillMaxWidth(), color = White, shadowElevation = 8.dp) {
            Box(Modifier.padding(16.dp)) {
                Button(
                    onClick = { if (isRegistered) onCancel(event) else onRegister(event) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = if (isRegistered) ButtonDefaults.buttonColors(containerColor = ErrorRed)
                             else ButtonDefaults.buttonColors(containerColor = NavyBlue)
                ) {
                    Text(if (isRegistered) "Cancel registration" else "Register for event", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(
    user: UserEntity, onApplication: () -> Unit, onAdmin: () -> Unit, onCreateEvent: () -> Unit,
    onManageEvents: () -> Unit, onRegistered: () -> Unit, onReminders: () -> Unit, onSettings: () -> Unit, onLogout: () -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Column(Modifier.fillMaxWidth().background(NavyBlue, RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                .padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).background(Gold, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    Text(user.name.firstOrNull()?.uppercase() ?: "U", color = NavyBlue, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                Text(user.name, color = White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(user.email, color = White.copy(alpha = .85f))
                Spacer(Modifier.height(8.dp))
                Surface(color = Gold, shape = RoundedCornerShape(20.dp)) {
                    Text(user.role.lowercase().replaceFirstChar { it.uppercase() },
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp), color = NavyBlue, fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Text("Account", modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp), color = NavyBlue, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item { ProfileRow(Icons.Default.EventAvailable, "My registered events", "View or cancel your registrations", onRegistered) }
        if (user.role == Roles.STUDENT) item {
            ProfileRow(Icons.Default.AssignmentInd, "Apply to become an organiser", "Applications are reviewed by an administrator", onApplication)
        }
        if (user.role == Roles.ORGANISER || user.role == Roles.ADMIN) item {
            ProfileRow(Icons.Default.AddCircle, "Create event", "Publish a new campus event", onCreateEvent)
            ProfileRow(Icons.Default.Edit, "My created events", "Edit events or view registered attendees", onManageEvents)
        }
        if (user.role == Roles.ADMIN) item {
            ProfileRow(Icons.Default.AdminPanelSettings, "Admin dashboard", "Review applications and manage accounts", onAdmin)
        }
        item { ProfileRow(Icons.Default.NotificationsActive, "Notifications", "Manage event and registration alerts", onReminders) }
        item { ProfileRow(Icons.Default.Settings, "Settings", "App information and local data details", onSettings) }
        item { ProfileRow(Icons.Default.Logout, "Log out", "Return to the login screen", onLogout, danger = true) }
    }
}

@Composable
private fun ProfileRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit, danger: Boolean = false) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = White), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (danger) ErrorRed else RoyalBlue)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = if (danger) ErrorRed else NavyBlue, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MutedText, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MutedText)
        }
    }
}

@Composable
private fun ApplicationScreen(
    user: UserEntity, previous: OrganiserApplicationEntity?, onBack: () -> Unit,
    onSubmit: (String, String) -> Unit
) {
    var organisation by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        BrandHeader("Organiser application", "Request permission to manage events", onBack)
        if (previous != null) {
            Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = White)) {
                Column(Modifier.padding(16.dp)) {
                    val statusText = when(previous.status) {
                        ApplicationStatus.PENDING -> "Pending review"
                        ApplicationStatus.APPROVED -> "Approved"
                        ApplicationStatus.REJECTED -> "Previous application rejected"
                        else -> previous.status
                    }
                    Text("Application status: $statusText", color = NavyBlue, fontWeight = FontWeight.Bold)
                    Text(previous.organisationName, color = MutedText)
                    if (previous.reviewNote.isNotBlank())
                        Text("Admin note: ${previous.reviewNote}", color = MutedText)
                    if (previous.status == ApplicationStatus.PENDING)
                        Text("Please wait for the administrator to review your request.", color = MutedText)
                    if (previous.status == ApplicationStatus.APPROVED)
                        Text("Your account has organiser access.", color = SuccessGreen)
                    if (previous.status == ApplicationStatus.REJECTED)
                        Text("Your previous application was reviewed and rejected. You may submit a new application below.", color = ErrorRed)
                }
            }
            if (previous.status == ApplicationStatus.PENDING || previous.status == ApplicationStatus.APPROVED) return
        }
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("Tell us about your organisation", style = MaterialTheme.typography.titleLarge, color = NavyBlue, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            AppTextField(organisation, { organisation = it }, "Club or organisation name")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(reason, { reason = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Why do you want to become an organiser?") },
                minLines = 4, shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(18.dp))
            Button(onClick = { onSubmit(organisation, reason) }, modifier = Modifier.fillMaxWidth()) { Text("Submit application") }
        }
    }
}

@Composable
private fun AdminScreen(
    users: List<UserEntity>, applications: List<OrganiserApplicationEntity>,
    allApplications: List<OrganiserApplicationEntity>, onBack: () -> Unit,
    onReview: (OrganiserApplicationEntity, Boolean) -> Unit, onToggleUser: (UserEntity) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        BrandHeader("Admin dashboard", "Manage organiser requests and accounts", onBack)
        TabRow(selectedTabIndex = selectedTab, containerColor = White) {
            Tab(selectedTab == 0, { selectedTab = 0 }, text = { Text("Applications (${applications.size})") })
            Tab(selectedTab == 1, { selectedTab = 1 }, text = { Text("Users (${users.size})") })
        }
        if (selectedTab == 0) {
            LazyColumn(contentPadding = PaddingValues(12.dp)) {
                if (applications.isEmpty()) item { EmptyState("No pending applications", "New organiser requests will appear here.") }
                items(applications, key = { it.id }) { app ->
                    val applicant = users.firstOrNull { it.id == app.studentId }
                    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = White)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(app.organisationName, style = MaterialTheme.typography.titleMedium, color = NavyBlue, fontWeight = FontWeight.Bold)
                            Text(applicant?.name ?: "Student", color = RoyalBlue)
                            Text(applicant?.email ?: "", color = MutedText, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            Text(app.reason)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onReview(app, true) }, modifier = Modifier.weight(1f)) { Text("Approve") }
                                OutlinedButton(onClick = { onReview(app, false) }, modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed)) { Text("Reject") }
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(12.dp)) {
                items(users, key = { it.id }) { account ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp), colors = CardDefaults.cardColors(containerColor = White)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(account.name, color = NavyBlue, fontWeight = FontWeight.Bold)
                                Text(account.email, color = MutedText, style = MaterialTheme.typography.bodySmall)
                                Text("${account.role} • ${if (account.active) "Active" else "Disabled"}", color = RoyalBlue, style = MaterialTheme.typography.labelSmall)
                            }
                            if (account.role != Roles.ADMIN) {
                                TextButton(onClick = { onToggleUser(account) }) {
                                    Text(if (account.active) "Disable" else "Enable", color = if (account.active) ErrorRed else SuccessGreen)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateEventScreen(
    onBack: () -> Unit,
    onCreate: (String, String, String, String, String, String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Academic") }
    var date by remember { mutableStateOf("2026-11-10") }
    var time by remember { mutableStateOf("10:00") }
    var location by remember { mutableStateOf("") }
    var organisation by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        BrandHeader("Create event", "Publish a campus activity", onBack)
        LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { AppTextField(title, { title = it }, "Event title") }
            item {
                Text("Category", color = NavyBlue, fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("Academic", "Workshop", "Social", "Club", "Other").forEach {
                        FilterChip(selected = category == it, onClick = { category = it }, label = { Text(it) })
                    }
                }
            }
            item { AppTextField(date, { date = it }, "Date (YYYY-MM-DD)") }
            item { AppTextField(time, { time = it }, "Time (HH:MM)") }
            item { AppTextField(location, { location = it }, "Venue") }
            item { AppTextField(organisation, { organisation = it }, "Organiser / society name") }
            item {
                OutlinedTextField(description, { description = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Event description") }, minLines = 4, shape = RoundedCornerShape(12.dp))
            }
            item {
                Button(onClick = { onCreate(title, category, date, time, location, organisation, description) },
                    modifier = Modifier.fillMaxWidth()) { Text("Publish event") }
            }
        }
    }
}

@Composable
private fun ManageEventsScreen(
    organiserEvents: List<EventEntity>,
    onBack: () -> Unit,
    onEdit: (EventEntity) -> Unit,
    onViewAttendees: (EventEntity) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        BrandHeader("My created events", "Edit events or check registrations", onBack)
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            if (organiserEvents.isEmpty()) item { EmptyState("No events created", "Events you publish will appear here.") }
            items(organiserEvents, key = { it.id }) { event ->
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = White), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(event.title, style = MaterialTheme.typography.titleMedium, color = NavyBlue, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("${event.date} at ${event.time} • ${event.location}", color = MutedText, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onEdit(event) }, modifier = Modifier.weight(1f)) { Text("Edit Event") }
                            OutlinedButton(onClick = { onViewAttendees(event) }, modifier = Modifier.weight(1f)) { Text("Attendees") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditEventScreen(
    event: EventEntity,
    onBack: () -> Unit,
    onSave: (EventEntity) -> Unit
) {
    var title by remember { mutableStateOf(event.title) }
    var category by remember { mutableStateOf(event.category) }
    var date by remember { mutableStateOf(event.date) }
    var time by remember { mutableStateOf(event.time) }
    var location by remember { mutableStateOf(event.location) }
    var organisation by remember { mutableStateOf(event.organiserName) }
    var description by remember { mutableStateOf(event.description) }

    Column(Modifier.fillMaxSize()) {
        BrandHeader("Edit event", "Update event information", onBack)
        LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { AppTextField(title, { title = it }, "Event title") }
            item {
                Text("Category", color = NavyBlue, fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("Academic", "Workshop", "Social", "Club", "Other").forEach {
                        FilterChip(selected = category == it, onClick = { category = it }, label = { Text(it) })
                    }
                }
            }
            item { AppTextField(date, { date = it }, "Date (YYYY-MM-DD)") }
            item { AppTextField(time, { time = it }, "Time (HH:MM)") }
            item { AppTextField(location, { location = it }, "Venue") }
            item { AppTextField(organisation, { organisation = it }, "Organiser / society name") }
            item {
                OutlinedTextField(description, { description = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Event description") }, minLines = 4, shape = RoundedCornerShape(12.dp))
            }
            item {
                Button(onClick = {
                    onSave(event.copy(
                        title = title, category = category, date = date, time = time,
                        location = location, organiserName = organisation, description = description
                    ))
                }, modifier = Modifier.fillMaxWidth()) { Text("Save changes") }
            }
        }
    }
}

@Composable
private fun EventAttendeesScreen(
    event: EventEntity,
    attendees: List<UserEntity>,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(LightBackground)) {
        BrandHeader("Registered Attendees", event.title, onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = White), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.People, null, tint = RoyalBlue, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("${attendees.size} Students Registered", color = NavyBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("${event.date} at ${event.time} • ${event.location}", color = MutedText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (attendees.isEmpty()) {
                item { EmptyState("No registrations yet", "Registered students will appear in this list.") }
            } else {
                items(attendees, key = { it.id }) { student ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = White), shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).background(PaleBlue, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                                Text(student.name.firstOrNull()?.uppercase() ?: "S", color = NavyBlue, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(student.name, color = NavyBlue, fontWeight = FontWeight.Bold)
                                Text(student.email, color = MutedText, style = MaterialTheme.typography.bodySmall)
                            }
                            Surface(color = PaleGold, shape = RoundedCornerShape(12.dp)) {
                                Text("Student", color = NavyBlue, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegisteredScreen(
    events: List<EventEntity>, onBack: () -> Unit,
    onSelectEvent: (EventEntity) -> Unit, onCancel: (EventEntity) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        BrandHeader("My registered events", "Your event registrations", onBack)
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            if (events.isEmpty()) item { EmptyState("No registrations yet", "Register for an event from the Events page.") }
            items(events, key = { it.id }) { event ->
                EventCard(event, "Cancel registration", { onCancel(event) }, onClickCard = { onSelectEvent(event) })
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.EventBusy, null, tint = MutedText, modifier = Modifier.size(38.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, color = NavyBlue, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MutedText)
    }
}

@Composable
private fun AppTextField(
    value: String, onValueChange: (String) -> Unit, label: String,
    leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text, visual: VisualTransformation = VisualTransformation.None
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, leadingIcon = leading, trailingIcon = trailing,
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visual, shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun MessageDialog(message: String, error: Boolean, onDismiss: () -> Unit) {
    if (message.isNotBlank()) AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text(if (error) "Please check" else "Campus Event Hub") },
        text = { Text(message) }
    )
}

@Composable
private fun SplashScreen() {
    val darkBlue = Color(0xFF072448)
    val circleBlue = Color(0xFF041833)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(darkBlue),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 90.dp, y = (-90).dp)
                .size(280.dp)
                .background(circleBlue, shape = androidx.compose.foundation.shape.CircleShape)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-100).dp, y = 110.dp)
                .size(300.dp)
                .background(circleBlue, shape = androidx.compose.foundation.shape.CircleShape)
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Surface(
                color = Gold,
                shape = RoundedCornerShape(32.dp),
                modifier = Modifier.size(110.dp),
                shadowElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.School,
                        contentDescription = null,
                        tint = darkBlue,
                        modifier = Modifier.size(62.dp)
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
            Text(
                text = "UNIVERSITY OF MALAWI",
                color = Gold,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = androidx.compose.ui.unit.TextUnit(1.5f, androidx.compose.ui.unit.TextUnitType.Sp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Campus Event",
                color = White,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Hub",
                color = White,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Discover. Connect. Participate.",
                color = White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(7.dp)
                    .background(Gold, shape = RoundedCornerShape(50))
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(White.copy(alpha = 0.5f), shape = androidx.compose.foundation.shape.CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(White.copy(alpha = 0.5f), shape = androidx.compose.foundation.shape.CircleShape)
            )
        }
    }
}

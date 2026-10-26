package com.example.campuseventsocietyhub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.campuseventsocietyhub.data.*
import com.example.campuseventsocietyhub.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var repository: CampusRepository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = CampusRepository(CampusDatabase.get(this), this)
        lifecycleScope.launch { repository.seedIfNeeded() }
        setContent {
            CampusEventSocietyHubTheme {
                Surface(Modifier.fillMaxSize(), color = LightBackground) {
                    CampusApp(repository)
                }
            }
        }
    }
}

private enum class AppPage { LOGIN, REGISTER, HOME, EVENTS, PROFILE, APPLICATION, ADMIN, CREATE_EVENT, REGISTERED }

@Composable
private fun CampusApp(repo: CampusRepository) {
    var sessionUser by remember { mutableStateOf<UserEntity?>(null) }
    val activeUserFlow = remember(sessionUser?.id) {
        sessionUser?.let { repo.userFlow(it.id) } ?: kotlinx.coroutines.flow.flowOf(null)
    }
    val activeUserFromDb by activeUserFlow.collectAsStateWithLifecycle(initialValue = null)
    val user = activeUserFromDb ?: sessionUser

    var page by remember { mutableStateOf(AppPage.LOGIN) }
    var message by remember { mutableStateOf("") }
    var messageIsError by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    var showReminderSettings by remember { mutableStateOf(false) }
    var showAppSettings by remember { mutableStateOf(false) }
    var eventRemindersEnabled by remember { mutableStateOf(true) }
    var newEventNotificationsEnabled by remember { mutableStateOf(true) }
    var registrationNotificationsEnabled by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val events by repo.events(search, category).collectAsStateWithLifecycle(initialValue = emptyList())
    val users by repo.users().collectAsStateWithLifecycle(initialValue = emptyList())
    val pending by repo.pendingApplications().collectAsStateWithLifecycle(initialValue = emptyList())
    val allApplications by repo.allApplications().collectAsStateWithLifecycle(initialValue = emptyList())
    val registrationList by (user?.let { repo.registrations(it.id) }
        ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsStateWithLifecycle(initialValue = emptyList())

    fun notify(text: String, error: Boolean = false) {
        message = text
        messageIsError = error
    }

    LaunchedEffect(user?.active) {
        if (user != null && !user.active) {
            notify("Your account has been disabled by an administrator.", true)
            repo.signOut()
            sessionUser = null
            page = AppPage.LOGIN
        }
    }

    if (user == null) {
        when (page) {
            AppPage.REGISTER -> RegisterScreen(
                onBack = { page = AppPage.LOGIN },
                onRegister = { name, email, password ->
                    scope.launch {
                        val result = repo.registerStudent(name, email, password)
                        result.onSuccess { sessionUser = it; page = AppPage.HOME; notify("Account created successfully.") }
                            .onFailure { notify(it.message ?: "Registration failed.", true) }
                    }
                }
            )
            else -> LoginScreen(
                onRegister = { page = AppPage.REGISTER },
                onLogin = { email, password ->
                    scope.launch {
                        repo.login(email, password).onSuccess {
                            sessionUser = it
                            page = AppPage.HOME
                            notify("Welcome back, ${it.name.substringBefore(" ")}.")
                        }.onFailure { notify(it.message ?: "Login failed.", true) }
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
                            page in listOf(AppPage.APPLICATION, AppPage.ADMIN, AppPage.CREATE_EVENT, AppPage.REGISTERED)),
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
                    user = user!!, events = events, search = search, onSearch = { search = it },
                    category = category, onCategory = { category = it },
                    onEvents = { page = AppPage.EVENTS }, onDetails = { page = AppPage.EVENTS }
                )
                AppPage.EVENTS -> EventsScreen(
                    events = events, user = user!!, registrations = registrationList.map { it.eventId }.toSet(),
                    search = search, onSearch = { search = it }, category = category, onCategory = { category = it },
                    onRegister = { event ->
                        scope.launch {
                            repo.registerForEvent(event.id, user!!).onSuccess { notify("You are registered for ${event.title}.") }
                                .onFailure { notify(it.message ?: "Could not register.", true) }
                        }
                    },
                    onCancel = { event ->
                        scope.launch { repo.cancelRegistration(event.id, user!!.id); notify("Registration cancelled.") }
                    }
                )
                AppPage.PROFILE -> ProfileScreen(
                    user = user!!, onApplication = { page = AppPage.APPLICATION },
                    onAdmin = { page = AppPage.ADMIN }, onCreateEvent = { page = AppPage.CREATE_EVENT },
                    onRegistered = { page = AppPage.REGISTERED },
                    onReminders = { showReminderSettings = true },
                    onSettings = { showAppSettings = true },
                    onLogout = { repo.signOut(); sessionUser = null; page = AppPage.LOGIN; search = ""; category = "All" }
                )
                AppPage.APPLICATION -> ApplicationScreen(
                    user = user!!,
                    previous = allApplications.firstOrNull { it.studentId == user!!.id },
                    onBack = { page = AppPage.PROFILE },
                    onSubmit = { organisation, reason ->
                        scope.launch {
                            repo.applyForOrganiser(user!!, organisation, reason)
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
                            repo.createEvent(user!!, title, category, date, time, location, organisation, description)
                                .onSuccess { notify("Event created and published."); page = AppPage.HOME }
                                .onFailure { notify(it.message ?: "Could not create event.", true) }
                        }
                    }
                )
                AppPage.REGISTERED -> RegisteredScreen(
                    events = events.filter { event -> registrationList.any { it.eventId == event.id } },
                    onBack = { page = AppPage.PROFILE },
                    onCancel = { event ->
                        scope.launch { repo.cancelRegistration(event.id, user!!.id); notify("Registration cancelled.") }
                    }
                )
                AppPage.LOGIN, AppPage.REGISTER -> Unit
            }
        }
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
            confirmButton = { TextButton(onClick = { showReminderSettings = false; notify("Notification preferences saved on this device.") }) { Text("Save") } },
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
            Text(subtitle, color = White.copy(alpha = .82f), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun LoginScreen(onRegister: () -> Unit, onLogin: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
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
            Spacer(Modifier.height(18.dp))
            Button(onClick = { onLogin(email, password) }, modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp)) { Text("Log in", fontWeight = FontWeight.Bold) }
            TextButton(onClick = onRegister) { Text("New to Campus Event Hub? Create an account") }
        }
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
    user: UserEntity, events: List<EventEntity>, search: String, onSearch: (String) -> Unit,
    category: String, onCategory: (String) -> Unit, onEvents: () -> Unit, onDetails: () -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Column(Modifier.fillMaxWidth().background(NavyBlue, RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)).padding(22.dp)) {
                Text("UNIVERSITY OF MALAWI", color = Gold, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("Campus Event Hub", color = White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Discover. Connect. Participate.", color = White.copy(alpha = .85f), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(value = search, onValueChange = onSearch, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search campus events...") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true, shape = RoundedCornerShape(18.dp), colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = White, unfocusedContainerColor = White,
                        focusedBorderColor = Gold, unfocusedBorderColor = White))
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
        if (events.isEmpty()) item { EmptyState("No events found", "Try another search or category.") }
        items(events.take(4), key = { it.id }) { event -> EventCard(event, actionLabel = "View details →", onAction = onDetails) }
        item {
            TextButton(onClick = onEvents, modifier = Modifier.fillMaxWidth()) { Text("View all events") }
        }
    }
}

@Composable
private fun EventsScreen(
    events: List<EventEntity>, user: UserEntity, registrations: Set<Long>, search: String, onSearch: (String) -> Unit,
    category: String, onCategory: (String) -> Unit, onRegister: (EventEntity) -> Unit, onCancel: (EventEntity) -> Unit
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
                        })
                }
            }
        }
    }
}

@Composable
private fun EventCard(event: EventEntity, actionLabel: String, onAction: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), shape = RoundedCornerShape(22.dp),
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
private fun ProfileScreen(
    user: UserEntity, onApplication: () -> Unit, onAdmin: () -> Unit, onCreateEvent: () -> Unit,
    onRegistered: () -> Unit, onReminders: () -> Unit, onSettings: () -> Unit, onLogout: () -> Unit
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
        if (user.role == Roles.ORGANISER) item {
            ProfileRow(Icons.Default.AddCircle, "Create event", "Publish a new campus event", onCreateEvent)
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
private fun RegisteredScreen(events: List<EventEntity>, onBack: () -> Unit, onCancel: (EventEntity) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        BrandHeader("My registered events", "Your event registrations", onBack)
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            if (events.isEmpty()) item { EmptyState("No registrations yet", "Register for an event from the Events page.") }
            items(events, key = { it.id }) { event -> EventCard(event, "Cancel registration", { onCancel(event) }) }
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

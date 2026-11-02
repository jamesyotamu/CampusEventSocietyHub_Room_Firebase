package com.example.campuseventsocietyhub.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Database(
    entities = [
        UserEntity::class, EventEntity::class,
        OrganiserApplicationEntity::class, EventRegistrationEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class CampusDatabase : RoomDatabase() {
    abstract fun campusDao(): CampusDao

    companion object {
        @Volatile private var INSTANCE: CampusDatabase? = null
        fun get(context: Context): CampusDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext, CampusDatabase::class.java, "campus_event_hub.db"
            ).build().also { INSTANCE = it }
        }
    }
}

object Passwords {
    // Demonstration only: use a server-side password hash in a real application.
    fun hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
    fun matches(value: String, hash: String): Boolean = hash(value) == hash
}

class CampusRepository(private val db: CampusDatabase, context: Context? = null) {
    private val dao = db.campusDao()
    private val firebase = context?.let { FirebaseCampusService(it) }

    init { firebase?.startEventCacheSync(dao) }

    private fun todayDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

    suspend fun seedIfNeeded() = withContext(Dispatchers.IO) {
        dao.deletePassedEvents(todayDate())
        db.withTransaction {
            if (dao.userByEmail("admincampus@gmail.com") == null) {
                dao.insertUser(
                    UserEntity(
                        name = "Campus Administrator",
                        email = "admincampus@gmail.com",
                        passwordHash = Passwords.hash("Admin12345!"),
                        role = Roles.ADMIN
                    )
                )
            }
            if (dao.eventCount() == 0) {
                val samples = listOf(
                    EventEntity( title = "Technology Innovation Workshop", category = "Academic",
                        date = "2026-10-28", time = "10:00", location = "Computer Laboratory",
                        organiserName = "Computer Science Department",
                        description = "Explore practical technology ideas, innovation and problem-solving.",
                        organiserId = 0),
                    EventEntity(title = "Business and Entrepreneurship Talk", category = "Workshop",
                        date = "2026-11-02", time = "14:00", location = "Main Hall",
                        organiserName = "Business Students Society",
                        description = "Learn about starting and managing a small business.",
                        organiserId = 0),
                    EventEntity(title = "Campus Music Night", category = "Social",
                        date = "2026-11-06", time = "18:00", location = "Open Ground",
                        organiserName = "University Music Society",
                        description = "An evening of campus performances and music.",
                        organiserId = 0),
                    EventEntity(title = "Computer Science Club Meeting", category = "Club",
                        date = "2026-11-10", time = "12:30", location = "ICT Block",
                        organiserName = "Computer Science Club",
                        description = "Meet fellow students and discuss upcoming club activities.",
                        organiserId = 0)
                )
                samples.forEach { dao.insertEvent(it) }
            }
        }
    }

    suspend fun registerStudent(name: String, email: String, password: String): Result<UserEntity> {
        val cleanName = name.trim()
        val cleanEmail = email.trim().lowercase(Locale.ROOT)
        if (cleanName.length < 2) return Result.failure(IllegalArgumentException("Enter your full name."))
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches())
            return Result.failure(IllegalArgumentException("Enter a valid email address."))
        if (password.length < 8 || password.none(Char::isLetter) || password.none(Char::isDigit))
            return Result.failure(IllegalArgumentException("Use at least 8 characters with letters and numbers."))
        if (firebase?.configured == true) {
            try {
                return Result.success(firebase.registerStudent(cleanName, cleanEmail, password, dao))
            } catch (e: Exception) {
                val isNetworkError = e.javaClass.simpleName.contains("Network", ignoreCase = true) ||
                        (e.message?.contains("network", ignoreCase = true) == true) ||
                        (e.message?.contains("unreachable", ignoreCase = true) == true) ||
                        (e.message?.contains("timeout", ignoreCase = true) == true)
                if (isNetworkError) {
                    if (dao.userByEmail(cleanEmail) != null)
                        return Result.failure(IllegalArgumentException("An account with this email already exists."))
                    val role = if (cleanEmail == "admincampus@gmail.com") Roles.ADMIN else Roles.STUDENT
                    val id = dao.insertUser(UserEntity(name = cleanName, email = cleanEmail,
                        passwordHash = Passwords.hash(password), role = role))
                    return Result.success(dao.userById(id)!!)
                }
                return Result.failure(IllegalArgumentException(e.message ?: "Firebase registration failed."))
            }
        }
        if (dao.userByEmail(cleanEmail) != null)
            return Result.failure(IllegalArgumentException("An account with this email already exists."))
        return try {
            val role = if (cleanEmail == "admincampus@gmail.com") Roles.ADMIN else Roles.STUDENT
            val id = dao.insertUser(UserEntity(name = cleanName, email = cleanEmail,
                passwordHash = Passwords.hash(password), role = role))
            Result.success(dao.userById(id)!!)
        } catch (_: Exception) {
            Result.failure(IllegalArgumentException("Could not create the account. Please try again."))
        }
    }

    suspend fun login(email: String, password: String): Result<UserEntity> {
        val cleanEmail = email.trim().lowercase(Locale.ROOT)
        if (firebase?.configured == true) {
            try {
                return Result.success(firebase.login(cleanEmail, password, dao))
            } catch (e: Exception) {
                val isNetworkError = e.javaClass.simpleName.contains("Network", ignoreCase = true) ||
                        (e.message?.contains("network", ignoreCase = true) == true) ||
                        (e.message?.contains("unreachable", ignoreCase = true) == true) ||
                        (e.message?.contains("timeout", ignoreCase = true) == true)
                if (isNetworkError) {
                    val localUser = dao.userByEmail(cleanEmail)
                    if (localUser != null) {
                        if (!localUser.active) return Result.failure(IllegalArgumentException("This account is disabled."))
                        if (localUser.passwordHash.isBlank() || Passwords.matches(password, localUser.passwordHash)) {
                            return Result.success(localUser)
                        }
                    }
                }
                return Result.failure(IllegalArgumentException(e.message ?: "Firebase sign in failed."))
            }
        }
        val user = dao.userByEmail(cleanEmail)
            ?: return Result.failure(IllegalArgumentException("Email or password is incorrect."))
        if (!user.active) return Result.failure(IllegalArgumentException("This account is disabled."))
        if (!Passwords.matches(password, user.passwordHash))
            return Result.failure(IllegalArgumentException("Email or password is incorrect."))
        return Result.success(user)
    }

    suspend fun resetPassword(email: String): Result<Unit> {
        val cleanEmail = email.trim().lowercase(Locale.ROOT)
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches())
            return Result.failure(IllegalArgumentException("Enter a valid email address."))
        if (firebase?.configured == true) {
            return try {
                firebase.sendPasswordResetEmail(cleanEmail)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(IllegalArgumentException(e.message ?: "Could not send reset email."))
            }
        }
        val user = dao.userByEmail(cleanEmail)
            ?: return Result.failure(IllegalArgumentException("No account found with this email address."))
        return Result.success(Unit)
    }

    fun events(search: String = "", category: String = "All") = dao.searchEvents(search, category, todayDate())
    fun eventsByOrganiser(organiserId: Long) = dao.eventsByOrganiser(organiserId)
    fun eventAttendees(eventId: Long) = dao.eventAttendees(eventId)
    fun userFlow(id: Long): kotlinx.coroutines.flow.Flow<UserEntity?> = dao.userByIdFlow(id)
    fun users() = dao.users()
    fun pendingApplications() = dao.pendingApplications()
    fun allApplications() = dao.allApplications()
    fun pendingApplicationCount() = dao.pendingApplicationCount()
    fun registrations(studentId: Long) = dao.registrations(studentId)

    suspend fun applyForOrganiser(user: UserEntity, organisation: String, reason: String): Result<Unit> {
        if (user.role != Roles.STUDENT) return Result.failure(IllegalStateException("Only students can apply."))
        if (organisation.trim().length < 2 || reason.trim().length < 10)
            return Result.failure(IllegalArgumentException("Enter an organisation and a reason of at least 10 characters."))
        if (firebase?.configured == true) {
            return try {
                firebase.submitOrganiserApplication(user, organisation, reason, dao)
                Result.success(Unit)
            } catch (e: Exception) { Result.failure(IllegalStateException(e.message ?: "Could not submit application.")) }
        }
        val latest = dao.latestApplication(user.id)
        if (latest?.status == ApplicationStatus.PENDING)
            return Result.failure(IllegalStateException("You already have a pending application."))
        if (latest?.status == ApplicationStatus.APPROVED)
            return Result.failure(IllegalStateException("Your organiser application has already been approved."))
        dao.insertApplication(OrganiserApplicationEntity(
            studentId = user.id, organisationName = organisation.trim(), reason = reason.trim()
        ))
        return Result.success(Unit)
    }

    suspend fun reviewApplication(application: OrganiserApplicationEntity, approve: Boolean, note: String = "") {
        if (firebase?.configured == true) {
            firebase.reviewApplication(application, approve, note, dao)
            return
        }
        db.withTransaction {
            val current = dao.latestApplication(application.studentId)
            require(current?.id == application.id && current.status == ApplicationStatus.PENDING) {
                "This application has already been reviewed."
            }
            val student = dao.userById(application.studentId) ?: error("Applicant not found.")
            dao.updateApplication(application.copy(
                status = if (approve) ApplicationStatus.APPROVED else ApplicationStatus.REJECTED,
                reviewNote = note.trim()
            ))
            if (approve) dao.updateUser(student.copy(role = Roles.ORGANISER))
        }
    }

    suspend fun createEvent(user: UserEntity, title: String, category: String, date: String,
                            time: String, location: String, organisation: String, description: String): Result<Unit> {
        if (user.role != Roles.ORGANISER && user.role != Roles.ADMIN)
            return Result.failure(IllegalStateException("Only approved organisers can create events."))
        if (title.trim().length < 3 || description.trim().length < 10 ||
            location.trim().isEmpty() || organisation.trim().isEmpty())
            return Result.failure(IllegalArgumentException("Complete all event fields."))
        if (!date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || !time.matches(Regex("\\d{2}:\\d{2}")))
            return Result.failure(IllegalArgumentException("Use date YYYY-MM-DD and time HH:MM."))
        if (firebase?.configured == true) {
            return try {
                firebase.createEvent(user, title, category, date, time, location, organisation, description, dao)
                Result.success(Unit)
            } catch (e: Exception) { Result.failure(IllegalStateException(e.message ?: "Could not publish event.")) }
        }
        dao.insertEvent(EventEntity(title = title.trim(), category = category, date = date, time = time,
            location = location.trim(), organiserName = organisation.trim(), description = description.trim(), organiserId = user.id))
        return Result.success(Unit)
    }

    suspend fun updateEvent(user: UserEntity, event: EventEntity): Result<Unit> {
        if (user.role != Roles.ORGANISER && user.role != Roles.ADMIN)
            return Result.failure(IllegalStateException("Only approved organisers can update events."))
        if (event.title.trim().length < 3 || event.description.trim().length < 10 ||
            event.location.trim().isEmpty() || event.organiserName.trim().isEmpty())
            return Result.failure(IllegalArgumentException("Complete all event fields."))
        if (!event.date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || !event.time.matches(Regex("\\d{2}:\\d{2}")))
            return Result.failure(IllegalArgumentException("Use date YYYY-MM-DD and time HH:MM."))
        if (firebase?.configured == true) {
            return try {
                firebase.updateEvent(event, dao)
                Result.success(Unit)
            } catch (e: Exception) { Result.failure(IllegalStateException(e.message ?: "Could not update event.")) }
        }
        dao.updateEvent(event)
        return Result.success(Unit)
    }

    suspend fun registerForEvent(eventId: Long, student: UserEntity): Result<Unit> {
        if (student.role != Roles.STUDENT)
            return Result.failure(IllegalStateException("Only student accounts can register for events."))
        if (firebase?.configured == true) {
            return try {
                firebase.registerForEvent(eventId, student, dao)
                Result.success(Unit)
            } catch (e: Exception) { Result.failure(IllegalStateException(e.message ?: "Could not register for event.")) }
        }
        val inserted = dao.registerForEvent(EventRegistrationEntity(eventId, student.id))
        return if (inserted == -1L) Result.failure(IllegalStateException("You are already registered."))
        else Result.success(Unit)
    }

    suspend fun cancelRegistration(eventId: Long, studentId: Long) {
        if (firebase?.configured == true) firebase.cancelRegistration(eventId, studentId, dao)
        else dao.cancelRegistration(eventId, studentId)
    }
    suspend fun isRegistered(eventId: Long, studentId: Long) = dao.isRegistered(eventId, studentId)
    suspend fun setUserActive(user: UserEntity, active: Boolean) {
        require(user.role != Roles.ADMIN || active) { "The admin account cannot be disabled here." }
        if (firebase?.configured == true) firebase.setAccountActive(user, active, dao)
        else dao.updateUser(user.copy(active = active))
    }

    fun signOut() { firebase?.signOut() }
}

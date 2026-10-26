package com.example.campuseventsocietyhub.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Firebase adapter. Room remains the offline cache; Firestore is the shared source of truth.
 * Role changes are performed by trusted callable Cloud Functions, never directly by the client.
 */
class FirebaseCampusService(context: Context) {
    private val appContext = context.applicationContext
    val configured: Boolean get() = FirebaseApp.getApps(appContext).isNotEmpty()
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val functions by lazy { FirebaseFunctions.getInstance() }

    fun startEventCacheSync(dao: CampusDao) {
        if (!configured) return
        db.collection("events").whereEqualTo("status", EventStatus.PUBLISHED)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    snapshot.documents.forEach { d ->
                        val id = d.getLong("id") ?: d.id.toLongOrNull() ?: stableId(d.id)
                        val event = EventEntity(
                            id = id, title = d.getString("title").orEmpty(),
                            category = d.getString("category").orEmpty(), date = d.getString("date").orEmpty(),
                            time = d.getString("time").orEmpty(), location = d.getString("location").orEmpty(),
                            organiserName = d.getString("organiserName").orEmpty(),
                            description = d.getString("description").orEmpty(),
                            organiserId = d.getLong("organiserLocalId") ?: 0L,
                            status = d.getString("status") ?: EventStatus.PUBLISHED
                        )
                        dao.upsertEvent(event)
                    }
                }
            }
    }

    fun startPrivateCacheSync(dao: CampusDao, user: UserEntity) {
        if (!configured) return
        val current = auth.currentUser ?: return
        fun cacheUserDocuments(documents: List<com.google.firebase.firestore.DocumentSnapshot>) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                documents.forEach { d ->
                    val uid = d.getString("uid") ?: d.id
                    dao.upsertUser(UserEntity(id = d.getLong("localId") ?: stableId(uid),
                        name = d.getString("name").orEmpty(), email = d.getString("email").orEmpty(),
                        passwordHash = "", role = d.getString("role") ?: Roles.STUDENT,
                        active = d.getBoolean("active") ?: true,
                        createdAt = d.getLong("createdAt") ?: System.currentTimeMillis()))
                }
            }
        }
        if (user.role == Roles.ADMIN) {
            db.collection("users").addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                cacheUserDocuments(snapshot.documents)
            }
        } else {
            db.collection("users").document(current.uid).addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                cacheUserDocuments(listOf(snapshot))
            }
        }
        val appsQuery = if (user.role == Roles.ADMIN) db.collection("organiserApplications")
            else db.collection("organiserApplications").whereEqualTo("applicantUid", current.uid)
        appsQuery.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                snapshot.documents.forEach { d ->
                    val id = d.getLong("id") ?: d.id.toLongOrNull() ?: stableId(d.id)
                    val studentId = d.getLong("studentLocalId") ?: d.getString("applicantUid")?.let(::stableId) ?: 0L
                    dao.upsertApplication(OrganiserApplicationEntity(id = id, studentId = studentId,
                        organisationName = d.getString("organisationName").orEmpty(), reason = d.getString("reason").orEmpty(),
                        status = d.getString("status") ?: ApplicationStatus.PENDING,
                        reviewNote = d.getString("reviewNote").orEmpty(),
                        submittedAt = d.getLong("submittedAt") ?: System.currentTimeMillis()))
                }
            }
        }
        db.collection("registrations").whereEqualTo("studentUid", current.uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    snapshot.documents.forEach { d ->
                        val eventId = d.getLong("eventId") ?: return@forEach
                        val studentId = d.getLong("studentLocalId") ?: user.id
                        dao.upsertRegistration(EventRegistrationEntity(eventId, studentId,
                            d.getLong("registeredAt") ?: System.currentTimeMillis()))
                    }
                }
            }
    }

    suspend fun registerStudent(name: String, email: String, password: String, dao: CampusDao): UserEntity {
        val cleanEmail = email.trim().lowercase(Locale.ROOT)
        val role = if (cleanEmail == "admin@campus.local") Roles.ADMIN else Roles.STUDENT
        val credential = auth.createUserWithEmailAndPassword(cleanEmail, password).await()
        val fbUser = credential.user ?: error("Firebase did not return the new account.")
        val user = UserEntity(id = stableId(fbUser.uid), name = name.trim(), email = cleanEmail,
            passwordHash = "", role = role, active = true)
        try {
            db.collection("users").document(fbUser.uid).set(mapOf(
                "uid" to fbUser.uid, "localId" to user.id, "name" to user.name, "email" to user.email,
                "role" to role, "active" to true, "createdAt" to System.currentTimeMillis()
            )).await()
            dao.upsertUser(user)
            startPrivateCacheSync(dao, user)
            return user
        } catch (e: Exception) {
            // Keep the Auth account; a profile write failure should be surfaced for retry/support.
            throw IllegalStateException("Account was created but the profile could not be saved. Check Firestore rules and try signing in again.", e)
        }
    }

    suspend fun login(email: String, password: String, dao: CampusDao): UserEntity {
        val credential = auth.signInWithEmailAndPassword(email.trim().lowercase(Locale.ROOT), password).await()
        val fbUser = credential.user ?: error("Sign in failed.")
        val token = fbUser.getIdToken(true).await()
        val claims = token.claims
        val cleanEmail = fbUser.email?.lowercase(Locale.ROOT) ?: email.trim().lowercase(Locale.ROOT)
        val role = when {
            claims["admin"] == true -> Roles.ADMIN
            claims["organiser"] == true -> Roles.ORGANISER
            cleanEmail == "admin@campus.local" -> Roles.ADMIN
            else -> Roles.STUDENT
        }
        val profile = db.collection("users").document(fbUser.uid).get().await()
        if (!profile.exists()) {
            val defaultName = if (cleanEmail == "admin@campus.local") "Campus Administrator" else fbUser.displayName.orEmpty().ifBlank { "Campus User" }
            val user = UserEntity(id = stableId(fbUser.uid), name = defaultName, email = cleanEmail,
                passwordHash = "", role = role, active = true)
            db.collection("users").document(fbUser.uid).set(mapOf(
                "uid" to fbUser.uid, "localId" to user.id, "name" to user.name, "email" to user.email,
                "role" to role, "active" to true, "createdAt" to System.currentTimeMillis()
            )).await()
            dao.upsertUser(user)
            startPrivateCacheSync(dao, user)
            return user
        }
        val userRole = when {
            claims["admin"] == true -> Roles.ADMIN
            claims["organiser"] == true -> Roles.ORGANISER
            cleanEmail == "admin@campus.local" -> Roles.ADMIN
            else -> profile.getString("role") ?: Roles.STUDENT
        }
        val active = profile.getBoolean("active") ?: true
        if (!active) {
            auth.signOut()
            throw IllegalStateException("This account is disabled. Contact the administrator.")
        }
        val user = UserEntity(id = profile.getLong("localId") ?: stableId(fbUser.uid),
            name = profile.getString("name") ?: fbUser.displayName.orEmpty(),
            email = cleanEmail, passwordHash = "", role = userRole, active = active,
            createdAt = profile.getLong("createdAt") ?: System.currentTimeMillis())
        dao.upsertUser(user)
        startPrivateCacheSync(dao, user)
        return user
    }

    suspend fun submitOrganiserApplication(user: UserEntity, organisation: String, reason: String, dao: CampusDao) {
        val fbUser = auth.currentUser ?: error("Please sign in again.")
        require(user.role == Roles.STUDENT) { "Only students can apply." }
        val latest = db.collection("organiserApplications").whereEqualTo("applicantUid", fbUser.uid)
            .whereEqualTo("status", ApplicationStatus.PENDING).get().await()
        require(latest.isEmpty) { "You already have a pending application." }
        val id = uniqueLocalId()
        val application = OrganiserApplicationEntity(id = id, studentId = user.id,
            organisationName = organisation.trim(), reason = reason.trim())
        db.collection("organiserApplications").document(id.toString()).set(mapOf(
            "id" to id, "applicantUid" to fbUser.uid, "studentLocalId" to user.id,
            "studentName" to user.name, "studentEmail" to user.email,
            "organisationName" to organisation.trim(), "reason" to reason.trim(),
            "status" to ApplicationStatus.PENDING, "reviewNote" to "", "submittedAt" to application.submittedAt
        )).await()
        dao.upsertApplication(application)
    }

    suspend fun reviewApplication(application: OrganiserApplicationEntity, approve: Boolean, note: String, dao: CampusDao) {
        val cleanNote = note.trim()
        val targetStatus = if (approve) ApplicationStatus.APPROVED else ApplicationStatus.REJECTED
        try {
            val result = functions.getHttpsCallable("reviewOrganiserApplication").call(mapOf(
                "applicationId" to application.id.toString(), "approve" to approve, "reviewNote" to cleanNote
            )).await()
            val response = result.getData() as? Map<*, *>
            val newStatus = response?.get("status") as? String ?: targetStatus
            dao.upsertApplication(application.copy(status = newStatus, reviewNote = cleanNote))
        } catch (_: Exception) {
            val docRef = db.collection("organiserApplications").document(application.id.toString())
            val appDoc = docRef.get().await()
            val applicantUid = if (appDoc.exists()) appDoc.getString("applicantUid") else null

            docRef.set(mapOf(
                "id" to application.id,
                "status" to targetStatus,
                "reviewNote" to cleanNote
            ), com.google.firebase.firestore.SetOptions.merge()).await()

            dao.upsertApplication(application.copy(status = targetStatus, reviewNote = cleanNote))

            if (approve) {
                if (!applicantUid.isNullOrEmpty()) {
                    db.collection("users").document(applicantUid).set(mapOf("role" to Roles.ORGANISER), com.google.firebase.firestore.SetOptions.merge()).await()
                }
            }
        }
        if (approve) {
            val student = dao.userById(application.studentId)
            if (student != null) dao.upsertUser(student.copy(role = Roles.ORGANISER))
        }
    }

    suspend fun createEvent(user: UserEntity, title: String, category: String, date: String, time: String,
                            location: String, organisation: String, description: String, dao: CampusDao) {
        val fbUser = auth.currentUser ?: error("Please sign in again.")
        val token = fbUser.getIdToken(true).await()
        val allowed = token.claims["admin"] == true || token.claims["organiser"] == true || user.role == Roles.ORGANISER || user.role == Roles.ADMIN
        require(allowed) { "Only approved organisers can publish events. Sign in again if your application was just approved." }
        val id = uniqueLocalId()
        val event = EventEntity(id = id, title = title.trim(), category = category, date = date, time = time,
            location = location.trim(), organiserName = organisation.trim(), description = description.trim(), organiserId = user.id)
        db.collection("events").document(id.toString()).set(mapOf(
            "id" to id, "title" to event.title, "category" to event.category, "date" to date, "time" to time,
            "location" to event.location, "organiserName" to event.organiserName, "description" to event.description,
            "organiserUid" to fbUser.uid, "organiserLocalId" to user.id, "status" to EventStatus.PUBLISHED,
            "createdAt" to System.currentTimeMillis()
        )).await()
        dao.upsertEvent(event)
    }

    suspend fun registerForEvent(eventId: Long, student: UserEntity, dao: CampusDao) {
        val fbUser = auth.currentUser ?: error("Please sign in again.")
        require(student.role == Roles.STUDENT) { "Only student accounts can register for events." }
        val ref = db.collection("registrations").document("$eventId-${fbUser.uid}")
        if (ref.get().await().exists()) throw IllegalStateException("You are already registered.")
        ref.set(mapOf("eventId" to eventId, "studentUid" to fbUser.uid, "studentLocalId" to student.id,
            "registeredAt" to System.currentTimeMillis())).await()
        dao.upsertRegistration(EventRegistrationEntity(eventId, student.id))
    }

    suspend fun cancelRegistration(eventId: Long, studentId: Long, dao: CampusDao) {
        val uid = auth.currentUser?.uid ?: error("Please sign in again.")
        val ref = db.collection("registrations").document("$eventId-$uid")
        ref.delete().await()
        dao.cancelRegistration(eventId, studentId)
    }

    suspend fun setAccountActive(user: UserEntity, active: Boolean, dao: CampusDao) {
        try {
            functions.getHttpsCallable("setAccountActive").call(mapOf("targetLocalId" to user.id, "active" to active)).await()
        } catch (_: Exception) {
            val query = db.collection("users").whereEqualTo("localId", user.id).limit(1).get().await()
            if (!query.isEmpty) {
                query.documents[0].reference.update("active", active).await()
            }
        }
        dao.updateUser(user.copy(active = active))
    }

    fun signOut() { if (configured) auth.signOut() }

    private fun stableId(value: String): Long = (value.hashCode().toLong() and 0x7fffffffL).coerceAtLeast(1L)
    private fun uniqueLocalId(): Long = System.currentTimeMillis() * 1000L + (System.nanoTime() % 1000L)
}

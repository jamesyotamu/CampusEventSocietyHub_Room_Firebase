package com.example.campuseventsocietyhub.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object Roles {
    const val STUDENT = "STUDENT"
    const val ORGANISER = "ORGANISER"
    const val ADMIN = "ADMIN"
}
object ApplicationStatus {
    const val PENDING = "PENDING"
    const val APPROVED = "APPROVED"
    const val REJECTED = "REJECTED"
}
object EventStatus {
    const val PUBLISHED = "PUBLISHED"
    const val CANCELLED = "CANCELLED"
}

@Entity(tableName = "users", indices = [Index(value = ["email"], unique = true)])
data class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val email: String,
    val passwordHash: String,
    val role: String = Roles.STUDENT,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val category: String,
    val date: String,
    val time: String,
    val location: String,
    val organiserName: String,
    val description: String,
    val organiserId: Long,
    val status: String = EventStatus.PUBLISHED
)

@Entity(tableName = "organiser_applications", indices = [Index("studentId")])
data class OrganiserApplicationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val studentId: Long,
    val organisationName: String,
    val reason: String,
    val status: String = ApplicationStatus.PENDING,
    val reviewNote: String = "",
    val submittedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "event_registrations",
    primaryKeys = ["eventId", "studentId"],
    indices = [Index("studentId")]
)
data class EventRegistrationEntity(
    val eventId: Long,
    val studentId: Long,
    val registeredAt: Long = System.currentTimeMillis()
)

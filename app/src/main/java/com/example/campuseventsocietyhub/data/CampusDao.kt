package com.example.campuseventsocietyhub.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CampusDao {
    @Query("SELECT * FROM users WHERE email = :email LIMIT 1")
    suspend fun userByEmail(email: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun userById(id: Long): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    fun userByIdFlow(id: Long): Flow<UserEntity?>

    @Query("SELECT * FROM users ORDER BY createdAt DESC")
    fun users(): Flow<List<UserEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUser(user: UserEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUser(user: UserEntity)

    @Update
    suspend fun updateUser(user: UserEntity)

    @Query("SELECT * FROM events WHERE status = 'PUBLISHED' ORDER BY date, time")
    fun events(): Flow<List<EventEntity>>

    @Query("""
        SELECT * FROM events
        WHERE status = 'PUBLISHED'
          AND (:category = 'All' OR category = :category)
          AND (title LIKE '%' || :search || '%' OR location LIKE '%' || :search || '%' OR organiserName LIKE '%' || :search || '%' OR description LIKE '%' || :search || '%')
        ORDER BY date, time
    """)
    fun searchEvents(search: String, category: String): Flow<List<EventEntity>>

    @Insert
    suspend fun insertEvent(event: EventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvent(event: EventEntity)

    @Update
    suspend fun updateEvent(event: EventEntity)

    @Query("SELECT * FROM organiser_applications WHERE studentId = :studentId ORDER BY submittedAt DESC LIMIT 1")
    suspend fun latestApplication(studentId: Long): OrganiserApplicationEntity?

    @Query("SELECT * FROM organiser_applications WHERE status = 'PENDING' ORDER BY submittedAt")
    fun pendingApplications(): Flow<List<OrganiserApplicationEntity>>

    @Query("SELECT * FROM organiser_applications ORDER BY submittedAt DESC")
    fun allApplications(): Flow<List<OrganiserApplicationEntity>>

    @Insert
    suspend fun insertApplication(application: OrganiserApplicationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApplication(application: OrganiserApplicationEntity)

    @Update
    suspend fun updateApplication(application: OrganiserApplicationEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun registerForEvent(registration: EventRegistrationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRegistration(registration: EventRegistrationEntity)

    @Query("SELECT * FROM event_registrations WHERE studentId = :studentId")
    fun registrations(studentId: Long): Flow<List<EventRegistrationEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM event_registrations WHERE eventId = :eventId AND studentId = :studentId)")
    suspend fun isRegistered(eventId: Long, studentId: Long): Boolean

    @Query("DELETE FROM event_registrations WHERE eventId = :eventId AND studentId = :studentId")
    suspend fun cancelRegistration(eventId: Long, studentId: Long)

    @Query("SELECT COUNT(*) FROM users")
    suspend fun userCount(): Int

    @Query("SELECT COUNT(*) FROM events")
    suspend fun eventCount(): Int

    @Query("SELECT COUNT(*) FROM organiser_applications WHERE status = 'PENDING'")
    fun pendingApplicationCount(): Flow<Int>
}

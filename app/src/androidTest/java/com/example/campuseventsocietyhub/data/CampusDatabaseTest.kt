package com.example.campuseventsocietyhub.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CampusDatabaseTest {
    private lateinit var db: CampusDatabase
    private lateinit var dao: CampusDao

    @Before fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CampusDatabase::class.java).allowMainThreadQueries().build()
        dao = db.campusDao()
    }

    @After fun closeDb() = db.close()

    @Test fun registrationCreatesStudentRoleAndPersistsUser() = runBlocking {
        val id = dao.insertUser(UserEntity(
            name = "Test Student", email = "test@unima.ac.mw",
            passwordHash = Passwords.hash("Student123")
        ))
        val stored = dao.userById(id)
        assertNotNull(stored)
        assertEquals(Roles.STUDENT, stored?.role)
    }

    @Test fun eventRegistrationDoesNotDuplicate() = runBlocking {
        val studentId = dao.insertUser(UserEntity(
            name = "Test Student", email = "test@unima.ac.mw", passwordHash = "hash"
        ))
        val eventId = dao.insertEvent(EventEntity(
            title = "Testing Event", category = "Academic", date = "2026-11-10",
            time = "10:00", location = "Main Hall", organiserName = "Test Club",
            description = "A sufficiently detailed test event", organiserId = 1
        ))
        assertTrue(dao.registerForEvent(EventRegistrationEntity(eventId, studentId)) > 0)
        assertEquals(-1L, dao.registerForEvent(EventRegistrationEntity(eventId, studentId)))
    }

    @Test fun organiserApplicationPersistsAndCanBeUpdated() = runBlocking {
        val studentId = dao.insertUser(UserEntity(
            name = "Applicant Student", email = "applicant@unima.ac.mw", passwordHash = "hash"
        ))
        dao.insertApplication(OrganiserApplicationEntity(
            studentId = studentId, organisationName = "Robotics Club", reason = "Planning robotics workshops"
        ))
        val latest = dao.latestApplication(studentId)
        assertNotNull(latest)
        assertEquals(ApplicationStatus.PENDING, latest?.status)

        dao.updateApplication(latest!!.copy(status = ApplicationStatus.APPROVED, reviewNote = "Approved by admin"))
        val updated = dao.latestApplication(studentId)
        assertEquals(ApplicationStatus.APPROVED, updated?.status)
        assertEquals("Approved by admin", updated?.reviewNote)
    }

    @Test fun searchEventsFiltersByCategoryAndQuery() = runBlocking {
        dao.insertEvent(EventEntity(
            title = "Python Programming", category = "Academic", date = "2026-11-01",
            time = "10:00", location = "Lab A", organiserName = "CS Dept", description = "Python basics", organiserId = 1
        ))
        dao.insertEvent(EventEntity(
            title = "Music Concert", category = "Social", date = "2026-11-05",
            time = "18:00", location = "Auditorium", organiserName = "Music Society", description = "Live show", organiserId = 2
        ))

        val academicResults = dao.searchEvents("", "Academic").first()
        assertEquals(1, academicResults.size)
        assertEquals("Python Programming", academicResults[0].title)

        val searchResults = dao.searchEvents("Concert", "All").first()
        assertEquals(1, searchResults.size)
        assertEquals("Music Concert", searchResults[0].title)
    }
}

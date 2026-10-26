package com.example.campuseventsocietyhub

import com.example.campuseventsocietyhub.data.ApplicationStatus
import com.example.campuseventsocietyhub.data.EventEntity
import com.example.campuseventsocietyhub.data.EventStatus
import com.example.campuseventsocietyhub.data.OrganiserApplicationEntity
import com.example.campuseventsocietyhub.data.Passwords
import com.example.campuseventsocietyhub.data.Roles
import com.example.campuseventsocietyhub.data.UserEntity
import org.junit.Assert.*
import org.junit.Test

class ValidationRulesTest {
    @Test
    fun studentRoleIsTheDefaultRole() {
        val user = UserEntity(name = "Student", email = "student@unima.ac.mw", passwordHash = "hash")
        assertEquals(Roles.STUDENT, user.role)
        assertTrue(user.active)
    }

    @Test
    fun passwordsHashDeterministicallyAndDoNotMatchPlainText() {
        val hash = Passwords.hash("Example123!")
        assertNotEquals("Example123!", hash)
        assertTrue(Passwords.matches("Example123!", hash))
        assertFalse(Passwords.matches("wrong", hash))
    }

    @Test
    fun organiserApprovalStatusesAreExplicit() {
        assertEquals("PENDING", ApplicationStatus.PENDING)
        assertEquals("APPROVED", ApplicationStatus.APPROVED)
        assertEquals("REJECTED", ApplicationStatus.REJECTED)
    }

    @Test
    fun studentCannotBeGrantedOrganiserRoleByDefaultEntityCreation() {
        val user = UserEntity(name = "Student", email = "student@example.com", passwordHash = "hash")
        assertEquals(Roles.STUDENT, user.role)
    }

    @Test
    fun eventEntityDefaultsToPublishedStatus() {
        val event = EventEntity(
            title = "Tech Symposium", category = "Academic", date = "2026-11-20", time = "09:00",
            location = "Great Hall", organiserName = "CS Society", description = "Annual tech symposium.", organiserId = 1
        )
        assertEquals(EventStatus.PUBLISHED, event.status)
    }

    @Test
    fun organiserApplicationDefaultsToPendingStatus() {
        val app = OrganiserApplicationEntity(studentId = 10, organisationName = "Debate Club", reason = "Want to organize debate events.")
        assertEquals(ApplicationStatus.PENDING, app.status)
        assertEquals("", app.reviewNote)
    }

    @Test
    fun adminRoleIdentifierMatchesExpectedConstant() {
        assertEquals("ADMIN", Roles.ADMIN)
        assertEquals("ORGANISER", Roles.ORGANISER)
        assertEquals("STUDENT", Roles.STUDENT)
    }
}

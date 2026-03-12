/*
 * Copyright (C) 2024 Aigentik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.aigentik.app.calendar

data class CalendarEvent(
    val id: Long = 0,
    val calendarId: Long,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val startTime: Long,
    val endTime: Long,
    val allDay: Boolean = false,
    val color: Int? = null,
    val status: EventStatus = EventStatus.CONFIRMED,
    val attendees: List<CalendarAttendee> = emptyList(),
    val reminders: List<CalendarReminder> = emptyList(),
    val recurrenceRule: String? = null,
    val isRecurring: Boolean = false,
    val organizer: String? = null
)

data class CalendarAttendee(
    val name: String,
    val email: String,
    val status: AttendeeStatus = AttendeeStatus.NEEDS_ACTION,
    val isOrganizer: Boolean = false
)

data class CalendarReminder(
    val method: ReminderMethod = ReminderMethod.NOTIFICATION,
    val minutesBefore: Int = 15
)

data class CalendarInfo(
    val id: Long,
    val name: String,
    val accountName: String,
    val accountType: String,
    val color: Int?,
    val isVisible: Boolean = true,
    val isPrimary: Boolean = false
)

enum class EventStatus {
    TENTATIVE,
    CONFIRMED,
    CANCELED
}

enum class AttendeeStatus {
    NEEDS_ACTION,
    ACCEPTED,
    DECLINED,
    TENTATIVE
}

enum class ReminderMethod {
    NOTIFICATION,
    EMAIL,
    SMS,
    ALERT
}

data class EventCreateParams(
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val startTime: Long,
    val endTime: Long,
    val allDay: Boolean = false,
    val calendarId: Long,
    val attendees: List<String> = emptyList(),
    val reminders: List<CalendarReminder> = listOf(CalendarReminder()),
    val recurrenceRule: String? = null
)

data class EventUpdateParams(
    val eventId: Long,
    val title: String? = null,
    val description: String? = null,
    val location: String? = null,
    val startTime: Long? = null,
    val endTime: Long? = null,
    val allDay: Boolean? = null,
    val status: EventStatus? = null
)

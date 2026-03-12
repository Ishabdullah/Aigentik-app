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

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

/**
 * CalendarService handles all calendar operations including:
 * - Reading calendar events
 * - Creating new events
 * - Updating existing events
 * - Deleting events
 * - Managing calendars
 */
class CalendarService(private val context: Context) {
    
    private val contentResolver: ContentResolver = context.contentResolver
    
    /**
     * Get all calendars accessible to the app
     */
    suspend fun getCalendars(): Result<List<CalendarInfo>> = withContext(Dispatchers.IO) {
        try {
            val calendars = mutableListOf<CalendarInfo>()
            val uri = CalendarContract.Calendars.CONTENT_URI
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.CALENDAR_COLOR,
                CalendarContract.Calendars.VISIBLE,
                CalendarContract.Calendars.IS_PRIMARY
            )
            
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1)
                    val accountName = cursor.getString(2)
                    val accountType = cursor.getString(3)
                    val color = cursor.getIntOrNull(4)
                    val isVisible = cursor.getInt(5) == 1
                    val isPrimary = cursor.getIntOrNull(6) == 1
                    
                    calendars.add(
                        CalendarInfo(
                            id = id,
                            name = name,
                            accountName = accountName,
                            accountType = accountType,
                            color = color,
                            isVisible = isVisible,
                            isPrimary = isPrimary
                        )
                    )
                }
            }
            
            Result.success(calendars)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Get events for a specific date range
     */
    suspend fun getEvents(startDate: Long, endDate: Long): Result<List<CalendarEvent>> = withContext(Dispatchers.IO) {
        try {
            val events = mutableListOf<CalendarEvent>()
            val uri = CalendarContract.Events.CONTENT_URI
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.CALENDAR_ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.EVENT_COLOR,
                CalendarContract.Events.STATUS,
                CalendarContract.Events.ORGANIZER,
                CalendarContract.Events.HAS_ATTENDEE_DATA,
                CalendarContract.Events.DURATION,
                CalendarContract.Events.RRULE
            )
            
            val selection = "(${CalendarContract.Events.DTSTART} >= ?) AND (${CalendarContract.Events.DTEND} <= ?)"
            val selectionArgs = arrayOf(startDate.toString(), endDate.toString())
            
            contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val event = cursorToEvent(cursor)
                    if (event != null) {
                        events.add(event)
                    }
                }
            }
            
            Result.success(events)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Get today's events
     */
    suspend fun getTodayEvents(): Result<List<CalendarEvent>> {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        val startOfDay = calendar.timeInMillis
        
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        val endOfDay = calendar.timeInMillis
        
        return getEvents(startOfDay, endOfDay)
    }
    
    /**
     * Create a new calendar event
     */
    suspend fun createEvent(params: EventCreateParams): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, params.calendarId)
                put(CalendarContract.Events.TITLE, params.title)
                put(CalendarContract.Events.DESCRIPTION, params.description)
                put(CalendarContract.Events.EVENT_LOCATION, params.location)
                put(CalendarContract.Events.DTSTART, params.startTime)
                put(CalendarContract.Events.DTEND, params.endTime)
                put(CalendarContract.Events.ALL_DAY, params.allDay)
                put(CalendarContract.Events.HAS_ATTENDEE_DATA, params.attendees.isNotEmpty())
                put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
                
                if (params.recurrenceRule != null) {
                    put(CalendarContract.Events.RRULE, params.recurrenceRule)
                }
            }
            
            val eventUri = contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val eventId = eventUri?.lastPathSegment?.toLongOrNull() ?: throw Exception("Failed to create event")
            
            // Add attendees if any
            if (params.attendees.isNotEmpty()) {
                params.attendees.forEach { email ->
                    val attendeeValues = android.content.ContentValues().apply {
                        put(CalendarContract.Attendees.EVENT_ID, eventId)
                        put(CalendarContract.Attendees.ATTENDEE_EMAIL, email)
                        put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_NONE)
                    }
                    contentResolver.insert(CalendarContract.Attendees.CONTENT_URI, attendeeValues)
                }
            }
            
            // Add reminders
            params.reminders.forEach { reminder ->
                val reminderValues = android.content.ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, reminder.minutesBefore)
                    put(CalendarContract.Reminders.METHOD, reminder.method.toCalendarMethod())
                }
                contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
            }
            
            Result.success(eventId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Update an existing event
     */
    suspend fun updateEvent(params: EventUpdateParams): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val values = android.content.ContentValues().apply {
                params.title?.let { put(CalendarContract.Events.TITLE, it) }
                params.description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                params.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                params.startTime?.let { put(CalendarContract.Events.DTSTART, it) }
                params.endTime?.let { put(CalendarContract.Events.DTEND, it) }
                params.allDay?.let { put(CalendarContract.Events.ALL_DAY, it) }
                params.status?.let { put(CalendarContract.Events.STATUS, it.toCalendarStatus()) }
            }
            
            val updateUri = Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, params.eventId.toString())
            val rowsUpdated = contentResolver.update(updateUri, values, null, null)
            
            if (rowsUpdated > 0) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to update event"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Delete an event
     */
    suspend fun deleteEvent(eventId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val deleteUri = Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, eventId.toString())
            val rowsDeleted = contentResolver.delete(deleteUri, null, null)
            
            if (rowsDeleted > 0) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete event"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Suggest event times based on free slots in calendar
     */
    suspend fun suggestEventTimes(durationMinutes: Int, startDate: Long, endDate: Long): Result<List<Pair<Long, Long>>> = withContext(Dispatchers.IO) {
        try {
            // Get existing events in the range
            val existingEvents = getEvents(startDate, endDate).getOrNull() ?: emptyList()
            
            // Find free slots (simplified implementation)
            val suggestions = mutableListOf<Pair<Long, Long>>()
            var currentTime = startDate
            val durationMillis = durationMinutes * 60 * 1000L
            
            while (currentTime + durationMillis <= endDate) {
                val slotEnd = currentTime + durationMillis
                val hasConflict = existingEvents.any { event ->
                    (currentTime < event.endTime && slotEnd > event.startTime)
                }
                
                if (!hasConflict) {
                    suggestions.add(Pair(currentTime, slotEnd))
                    if (suggestions.size >= 5) break // Return up to 5 suggestions
                }
                
                currentTime += 30 * 60 * 1000L // Move by 30 minutes
            }
            
            Result.success(suggestions)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun cursorToEvent(cursor: Cursor): CalendarEvent? {
        return try {
            CalendarEvent(
                id = cursor.getLong(0),
                calendarId = cursor.getLong(1),
                title = cursor.getString(2),
                description = cursor.getString(3),
                location = cursor.getString(4),
                startTime = cursor.getLong(5),
                endTime = cursor.getLong(6),
                allDay = cursor.getInt(7) == 1,
                color = cursor.getIntOrNull(8),
                status = cursor.getIntOrNull(9)?.toEventStatus() ?: EventStatus.CONFIRMED,
                organizer = cursor.getString(10),
                isRecurring = cursor.getString(13) != null,
                recurrenceRule = cursor.getString(13)
            )
        } catch (e: Exception) {
            null
        }
    }
    
    private fun Cursor.getIntOrNull(columnIndex: Int): Int? {
        return if (isNull(columnIndex)) null else getInt(columnIndex)
    }
    
    private fun String?.toLongOrNull(): Long? = this?.toLongOrNull()
    
    private fun ReminderMethod.toCalendarMethod(): Int {
        return when (this) {
            ReminderMethod.NOTIFICATION -> CalendarContract.Reminders.METHOD_ALERT
            ReminderMethod.EMAIL -> CalendarContract.Reminders.METHOD_EMAIL
            ReminderMethod.SMS -> CalendarContract.Reminders.METHOD_SMS
            ReminderMethod.ALERT -> CalendarContract.Reminders.METHOD_ALERT
        }
    }
    
    private fun EventStatus.toCalendarStatus(): Int {
        return when (this) {
            EventStatus.TENTATIVE -> CalendarContract.Events.STATUS_TENTATIVE
            EventStatus.CONFIRMED -> CalendarContract.Events.STATUS_CONFIRMED
            EventStatus.CANCELED -> CalendarContract.Events.STATUS_CANCELED
        }
    }
    
    private fun Int?.toEventStatus(): EventStatus {
        return when (this) {
            CalendarContract.Events.STATUS_TENTATIVE -> EventStatus.TENTATIVE
            CalendarContract.Events.STATUS_CANCELED -> EventStatus.CANCELED
            else -> EventStatus.CONFIRMED
        }
    }
}

package org.onekash.kashcal.domain.sync

import android.provider.CalendarContract
import android.util.Log
import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.util.computeDurationString
import org.onekash.kashcal.util.isoRemindersToMinutes
import java.util.TimeZone
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

const val MIRRORED_DEVICE_EVENT_ID_EXTRA_KEY = "X-KASHCAL-MIRRORED-DEVICE-EVENT-ID"

interface PulledEventMirror {
    suspend fun mirrorPulledEvents(events: List<Event>)
    suspend fun deleteMirrorsForEvents(events: List<Event>)
    suspend fun deleteMirrorForEvent(event: Event)

    object NoOp : PulledEventMirror {
        override suspend fun mirrorPulledEvents(events: List<Event>) = Unit
        override suspend fun deleteMirrorsForEvents(events: List<Event>) = Unit
        override suspend fun deleteMirrorForEvent(event: Event) = Unit
    }
}

/**
 * Mirrors server/subscription pulled Room events into Android CalendarProvider.
 *
 * Destination is intentionally explicit: use the user's default device calendar,
 * or the single enabled writable device calendar. This prevents background sync
 * from spraying copies into every visible phone calendar.
 */
@Singleton
class DeviceCalendarMirrorService @Inject constructor(
    private val dataStore: KashCalDataStore,
    private val calendarProviderRepository: CalendarProviderRepository,
    private val eventsDao: EventsDao
) : PulledEventMirror {
    companion object {
        private const val TAG = "DeviceCalendarMirror"
    }

    override suspend fun mirrorPulledEvents(events: List<Event>) {
        if (events.isEmpty()) return
        val targetCalendarId = resolveTargetCalendarId() ?: return

        val mastersFirst = events.sortedBy { if (it.originalEventId == null) 0 else 1 }
        for (event in mastersFirst) {
            try {
                mirrorPulledEvent(event, targetCalendarId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to mirror event ${event.id}: ${e.message}")
            }
        }
    }

    override suspend fun deleteMirrorsForEvents(events: List<Event>) {
        if (events.isEmpty()) return
        for (event in events) {
            deleteMirrorForEvent(event)
        }
    }

    override suspend fun deleteMirrorForEvent(event: Event) {
        val deviceEventId = event.mirroredDeviceEventId() ?: return
        try {
            calendarProviderRepository.deleteEvent(deviceEventId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete mirrored device event $deviceEventId: ${e.message}")
        }
    }

    private suspend fun mirrorPulledEvent(event: Event, targetCalendarId: Long) {
        val mirrorId = event.mirroredDeviceEventId()

        if (event.status.equals("CANCELLED", ignoreCase = true)) {
            if (event.originalEventId != null && event.originalInstanceTime != null) {
                if (mirrorId != null) {
                    calendarProviderRepository.deleteEvent(mirrorId)
                }
                val masterMirrorId = mirroredMasterId(event) ?: return
                calendarProviderRepository.deleteSingleOccurrence(
                    masterEventId = masterMirrorId,
                    originalInstanceTime = event.originalInstanceTime,
                    isAllDay = event.isAllDay
                )
            } else if (mirrorId != null) {
                calendarProviderRepository.deleteEvent(mirrorId)
            }
            return
        }

        if (event.originalEventId != null && event.originalInstanceTime != null) {
            mirrorException(event, targetCalendarId, mirrorId)
        } else {
            mirrorMasterOrStandalone(event, targetCalendarId, mirrorId)
        }
    }

    private suspend fun mirrorMasterOrStandalone(event: Event, targetCalendarId: Long, mirrorId: Long?) {
        val params = event.toDeviceEventParams()
        val existingMirror = mirrorId?.let { calendarProviderRepository.getDeviceEvent(it) }

        if (mirrorId != null && existingMirror != null) {
            if (existingMirror.calendarId != targetCalendarId) {
                calendarProviderRepository.moveEventToCalendar(mirrorId, targetCalendarId)
            }
            calendarProviderRepository.updateEvent(
                eventId = mirrorId,
                title = params.title,
                description = params.description,
                location = params.location,
                startTs = params.startTs,
                endTs = params.endTs,
                isAllDay = params.isAllDay,
                rrule = params.rrule,
                duration = params.duration,
                timezone = params.timezone,
                reminders = params.reminders,
                availability = params.availability,
                eventColor = event.color
            )
        } else {
            val result = calendarProviderRepository.createEvent(
                calendarId = targetCalendarId,
                title = params.title,
                description = params.description,
                location = params.location,
                startTs = params.startTs,
                endTs = params.endTs,
                isAllDay = params.isAllDay,
                rrule = params.rrule,
                duration = params.duration,
                timezone = params.timezone,
                reminders = params.reminders,
                availability = params.availability,
                eventColor = event.color
            )
            result.getOrNull()?.let { deviceEventId ->
                persistMirrorId(event.id, deviceEventId)
            }
        }
    }

    private suspend fun mirrorException(event: Event, targetCalendarId: Long, mirrorId: Long?) {
        val masterMirrorId = mirroredMasterId(event) ?: return
        val params = event.toDeviceEventParams(forceNonRecurring = true)
        val existingMirror = mirrorId?.let { calendarProviderRepository.getDeviceEvent(it) }

        if (mirrorId != null && existingMirror != null) {
            if (existingMirror.calendarId != targetCalendarId) {
                calendarProviderRepository.moveEventToCalendar(mirrorId, targetCalendarId)
            }
            calendarProviderRepository.updateEvent(
                eventId = mirrorId,
                title = params.title,
                description = params.description,
                location = params.location,
                startTs = params.startTs,
                endTs = event.endTs,
                isAllDay = params.isAllDay,
                rrule = null,
                duration = null,
                timezone = params.timezone,
                reminders = params.reminders,
                availability = params.availability,
                eventColor = event.color
            )
        } else {
            val result = calendarProviderRepository.createException(
                calendarId = targetCalendarId,
                masterEventId = masterMirrorId,
                originalInstanceTime = event.originalInstanceTime!!,
                title = params.title,
                description = params.description,
                location = params.location,
                startTs = params.startTs,
                endTs = event.endTs,
                isAllDay = params.isAllDay,
                timezone = params.timezone,
                reminders = params.reminders,
                availability = params.availability,
                eventColor = event.color
            )
            result.getOrNull()?.let { deviceEventId ->
                persistMirrorId(event.id, deviceEventId)
            }
        }
    }

    private suspend fun mirroredMasterId(event: Event): Long? {
        val masterId = event.originalEventId ?: return null
        val master = eventsDao.getById(masterId) ?: return null
        return master.mirroredDeviceEventId()
    }

    private suspend fun persistMirrorId(eventId: Long, deviceEventId: Long) {
        val fresh = eventsDao.getById(eventId) ?: return
        val extras = fresh.extraProperties.orEmpty() + (
            MIRRORED_DEVICE_EVENT_ID_EXTRA_KEY to deviceEventId.toString()
        )
        eventsDao.update(fresh.copy(extraProperties = extras))
    }

    private suspend fun resolveTargetCalendarId(): Long? {
        val calendars = calendarProviderRepository.getDeviceCalendars()
            .filter { it.isWritable }
        val default = dataStore.getDefaultCalendar() as? DefaultCalendar.Device
        val defaultTarget = default?.let { selected ->
            calendars.firstOrNull { it.id == selected.calendarId }
        }
        if (defaultTarget != null) return defaultTarget.id

        val enabledTargets = calendars.filter { it.id in dataStore.getEnabledDeviceCalendarIds() }
        return enabledTargets.singleOrNull()?.id
    }

    private fun Event.mirroredDeviceEventId(): Long? =
        extraProperties?.get(MIRRORED_DEVICE_EVENT_ID_EXTRA_KEY)?.toLongOrNull()

    private fun Event.toDeviceEventParams(forceNonRecurring: Boolean = false): DeviceEventParams {
        val recurring = !forceNonRecurring && !rrule.isNullOrBlank()
        val end = if (recurring) null else endTs
        val effectiveDuration = if (recurring) {
            duration ?: computeDurationString(startTs, endTs, isAllDay)
        } else {
            null
        }
        return DeviceEventParams(
            title = title,
            description = description?.takeIf { it.isNotBlank() },
            location = location?.takeIf { it.isNotBlank() },
            startTs = startTs,
            endTs = end,
            isAllDay = isAllDay,
            rrule = if (recurring) rrule else null,
            duration = effectiveDuration,
            timezone = timezone ?: TimeZone.getDefault().id,
            reminders = isoRemindersToMinutes(reminders),
            availability = if (transp.equals("TRANSPARENT", ignoreCase = true)) {
                CalendarContract.Events.AVAILABILITY_FREE
            } else {
                CalendarContract.Events.AVAILABILITY_BUSY
            }
        )
    }

    private data class DeviceEventParams(
        val title: String,
        val description: String?,
        val location: String?,
        val startTs: Long,
        val endTs: Long?,
        val isAllDay: Boolean,
        val rrule: String?,
        val duration: String?,
        val timezone: String,
        val reminders: List<Int>,
        val availability: Int
    )
}

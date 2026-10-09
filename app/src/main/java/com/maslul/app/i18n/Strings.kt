package com.maslul.app.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.maslul.app.data.AppLanguage
import com.maslul.app.data.Place
import com.maslul.app.data.PlaceKind
import com.maslul.app.data.RailPreference
import com.maslul.app.data.ThemeMode
import com.maslul.app.data.TransitMode
import com.maslul.app.data.WalkSpeed
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Everything the full app says, in each of its languages (Simple Maslul has its own, plainer
 * words: see [com.maslul.app.ui.simple.SimpleStrings]). Whole phrases rather than glued words,
 * so Hebrew and Russian can get gender, case and plurals right.
 */
abstract class Strings {
    // ---- Common -----------------------------------------------------------------------------
    abstract fun mode(mode: TransitMode): String
    abstract val noLiveData: String
    abstract val showOnMap: String
    abstract val refreshesAutomatically: String
    abstract val notReporting: String
    abstract val stopFollowing: String
    abstract val live: String
    abstract val liveDelayed: String
    abstract val resetNorth: String
    /** Points the way the text reads: "14:00 → 14:40". */
    abstract val arrow: String

    // ---- Times and distances ----------------------------------------------------------------
    abstract val now: String
    abstract fun min(n: Long): String
    abstract fun hoursMin(h: Long, m: Long): String
    abstract val updatedJustNow: String
    abstract fun updatedSecAgo(sec: Long): String
    abstract fun updatedMinAgo(min: Long): String
    abstract fun updatedAt(time: String): String
    abstract fun meters(m: Int): String
    abstract fun km(km: String): String
    abstract val today: String
    abstract val tomorrow: String
    abstract fun date(date: LocalDate): String
    abstract fun dayShort(day: DayOfWeek): String

    // ---- App shell --------------------------------------------------------------------------
    abstract val start: String
    abstract val liveDirections: String
    abstract val tabNavigate: String
    abstract val tabLines: String
    abstract val tabStations: String

    // ---- Home -------------------------------------------------------------------------------
    abstract val currentLocation: String
    abstract val setHome: String
    abstract val setWork: String
    abstract val addFavorite: String
    abstract val whereTo: String
    abstract val settings: String
    abstract val droppedPin: String
    abstract val onABus: String
    abstract val showPlaces: String
    abstract val expandMap: String
    abstract val myLocation: String
    abstract val places: String
    abstract val home: String
    abstract val work: String
    abstract val setLocation: String
    abstract val addFavoritePlace: String
    abstract val add: String
    abstract val savedTrips: String
    abstract val recent: String
    abstract val homeHint: String
    abstract val remove: String
    abstract fun editNamed(name: String): String
    abstract val editFavorite: String
    abstract fun fromPlace(name: String): String

    // ---- Search -----------------------------------------------------------------------------
    abstract val back: String
    abstract val search: String
    abstract val startingPoint: String
    abstract val searchAddressOrPlace: String
    abstract val swap: String
    abstract val couldntSearch: String
    abstract val checkConnection: String
    abstract val noResults: String
    abstract val noResultsHint: String
    abstract val onBusOrTrainNow: String
    abstract val planFromRide: String
    abstract val chooseOnMap: String
    abstract val favorites: String
    abstract val saveAsFavorite: String
    abstract val clear: String

    // ---- Favorites and picking on the map ---------------------------------------------------
    abstract val saveFavorite: String
    abstract val label: String
    abstract val labelExample: String
    abstract val save: String
    abstract val cancel: String
    abstract val pinnedLocation: String
    abstract val moveMapToChoose: String
    abstract val findingAddress: String
    abstract val chooseThisLocation: String

    // ---- Routes -----------------------------------------------------------------------------
    abstract val errFollowRide: String
    abstract val errNoLocation: String
    abstract val errNoRoutes: String
    abstract val errNoDrive: String
    abstract val errLoadRoutes: String
    abstract fun nextStop(name: String): String
    abstract val whereDriverGoing: String
    abstract val options: String
    abstract val accessible: String
    abstract val refresh: String
    abstract val findingRoutes: String
    abstract val noRoutes: String
    abstract val tryAgain: String
    abstract val earlier: String
    abstract val best: String
    abstract val fastest: String
    abstract val leastWalking: String
    abstract val laterRoutes: String
    abstract val dataCredits: String
    abstract val removeSavedTrip: String
    abstract val saveThisTrip: String
    abstract fun editFavoriteNamed(label: String): String
    abstract val saveDestinationAsFavorite: String
    abstract val leaveNow: String
    abstract fun departAt(time: String): String
    abstract fun arriveBy(time: String): String
    abstract fun transfers(n: Int): String
    abstract fun walkFor(duration: String): String
    abstract fun rideAllTheWay(duration: String): String
    abstract fun droppedOff(near: String?): String
    abstract fun byCar(duration: String): String
    abstract fun stayOnUntil(stop: String): String
    abstract fun getOffAt(time: String): String
    abstract fun thenRide(ride: String): String
    abstract val getOffNextStop: String
    abstract fun thenRideFrom(ride: String, stop: String, time: String): String
    abstract val serviceAlert: String
    abstract val ride: String
    abstract fun rideTo(place: String): String
    abstract fun rideWithin(min: Int): String
    abstract val rideTitle: String
    abstract val rideBody: String
    abstract val driverGoingSomewhere: String
    abstract fun toPlaceTapToChange(place: String): String
    abstract val chooseDriverDestination: String
    abstract val driverCanTakeMe: String
    abstract val droppedOffWithin: String
    abstract val findWhereToGetOut: String
    abstract val noRide: String
    abstract fun linesFrom(n: Int, stop: String): String
    abstract fun nextShuttlesFrom(stop: String): String
    abstract fun nextFrom(mode: TransitMode?, stop: String): String
    abstract val hurry: String
    abstract val showFewer: String
    abstract fun nMore(n: Int): String
    abstract fun tightTransfer(min: Long, line: String, walk: String?): String
    abstract fun ifMissed(line: String, time: String, later: String): String
    abstract fun vehicleClose(mode: TransitMode): String
    abstract val goNow: String
    abstract val leaveIn: String
    abstract val minUnit: String
    abstract val missed: String
    abstract val isArriving: String
    abstract fun inTime(time: String): String
    abstract fun stopsAway(n: Int): String
    abstract fun atStop(stop: String): String
    abstract fun alreadyPassed(line: String, stop: String): String
    abstract fun lineFrom(line: String, stop: String): String
    abstract fun scheduled(time: String): String
    abstract fun walkDuration(duration: String): String
    abstract fun arrive(time: String): String
    abstract val whenQ: String
    abstract val departAtTab: String
    abstract val arriveByTab: String
    abstract val setTime: String
    abstract val routeOptions: String
    abstract val transport: String
    abstract val maxWalk: String
    abstract val walkingSpeed: String
    abstract fun walkSpeed(w: WalkSpeed): String
    abstract val preferRail: String
    abstract val preferRailHint: String
    abstract fun railPreference(r: RailPreference): String
    abstract val wheelchair: String
    abstract val wheelchairHint: String
    abstract val showRoutes: String

    // ---- Route details ----------------------------------------------------------------------
    abstract fun leaveAt(time: String): String
    abstract fun rideFromAt(ride: String, stop: String, time: String): String
    abstract fun timeToGoTo(place: String): String
    abstract fun reminderSet(time: String, minutes: Int): String
    abstract val reminderTooSoon: String
    abstract fun shareTitle(place: String, start: String, end: String, duration: String): String
    abstract fun shareWalk(duration: String, to: String): String
    abstract val shareTrip: String
    abstract val liveNeedsLocation: String
    abstract val showDetails: String
    abstract fun liveLocationOf(line: String): String
    abstract val tripDetails: String
    abstract val noTransfers: String
    abstract fun walkingDistance(distance: String): String
    abstract val end: String
    abstract val remindMe: String
    abstract val share: String
    abstract val yourLocation: String
    abstract val getOutHere: String
    abstract fun youreOnThis(mode: TransitMode): String
    abstract val getOff: String
    abstract fun rideDuration(duration: String): String
    abstract fun toHeadsign(headsign: String): String
    abstract val missedChecking: String
    abstract fun rideStops(n: Int): String
    abstract val missedNone: String
    abstract val missedNext: String
    abstract fun nGoThisWay(n: Int, mode: TransitMode?): String
    abstract fun nextNShuttles(n: Int): String
    abstract fun nextNOf(n: Int, mode: TransitMode?, line: String): String
    abstract val tapToChoose: String
    abstract val passed: String
    abstract fun nMoreRides(n: Int): String
    abstract fun arrives(inTime: String?): String
    abstract val passedThisStop: String
    abstract fun noLiveScheduled(time: String): String
    abstract fun scheduledNotDeparted(time: String): String
    abstract fun timetable(time: String): String

    // ---- Settings ---------------------------------------------------------------------------
    abstract val notSet: String
    abstract val favoritePlaces: String
    abstract val moveUp: String
    abstract val moveDown: String
    abstract val favoritesHint: String
    abstract val language: String
    abstract val phoneLanguage: String
    abstract val appearance: String
    abstract fun theme(t: ThemeMode): String
    abstract val departureReminders: String
    abstract val remindersHint: String
    abstract val simpleMaslul: String
    abstract val simpleMaslulHint: String
    abstract val advanced: String
    abstract val ridesSetting: String
    abstract val ridesSettingHint: String
    abstract val onBoardSetting: String
    abstract val onBoardSettingHint: String
    abstract val myShuttles: String
    abstract val myShuttlesHint: String
    abstract fun nShuttles(n: Int): String
    abstract val about: String
    abstract val appName: String
    abstract fun version(v: String): String
    abstract val aboutText: String
    abstract val sourceCode: String
    abstract val madeBy: String
    abstract val switchToSimpleQ: String
    abstract val switchToSimpleBody: String
    abstract val switchAction: String

    // ---- On board ---------------------------------------------------------------------------
    abstract val errOnBoardLocation: String
    abstract val errOnBoardNetwork: String
    abstract fun errOnBoardTimetable(ride: String): String
    abstract fun onBoardName(mode: TransitMode, line: String?, headsign: String?): String
    abstract val whichRide: String
    abstract val whichRideHint: String
    abstract val lineOrDestination: String
    abstract val lookingForRides: String
    abstract val cantTellRide: String
    abstract val noRidesAround: String
    abstract val noRidesAroundHint: String
    abstract val nothingMatchesRides: String
    abstract val onBoardFooter: String
    abstract fun liveFromYou(distance: String): String
    abstract fun dueAt(stop: String): String
    abstract val timetableWord: String

    // ---- Shuttles ---------------------------------------------------------------------------
    abstract val shuttlesIntro: String
    abstract val addShuttle: String
    abstract val shuttleFromTitle: String
    abstract val shuttleToTitle: String
    abstract val newShuttle: String
    abstract val editShuttle: String
    abstract val name: String
    abstract val shuttleNameExample: String
    abstract val from: String
    abstract val to: String
    abstract val chooseShuttleFrom: String
    abstract val chooseShuttleTo: String
    abstract val departureTimes: String
    abstract val addDepartureTime: String
    abstract val timesFormatHint: String
    abstract fun departuresADay(n: Int): String
    abstract val departureTimesHint: String
    abstract val rideTakes: String
    abstract val rideTakesHint: String
    abstract val runsOn: String
    abstract val saveAndAddReturn: String
    abstract val delete: String
    abstract val myShuttle: String
    abstract val needName: String
    abstract val needFrom: String
    abstract val needTo: String
    abstract val needTwoStops: String
    abstract val needTimes: String
    abstract val needTimesLike: String
    abstract val needRideTime: String
    abstract val needDay: String
    abstract fun stillNeeded(missing: List<String>): String
    abstract val everyDay: String
    abstract val sunToThu: String

    // ---- Lines and stations -----------------------------------------------------------------
    abstract val errLoadLines: String
    abstract val somethingWrong: String
    abstract val noLinesFound: String
    abstract val noLinesFoundHint: String
    abstract val favoriteLines: String
    abstract val nearbyLines: String
    abstract val noLinesNearby: String
    abstract val noLinesNearbyHint: String
    abstract val lineDetailsUnavailable: String
    abstract fun thenTime(time: String): String
    abstract fun nVariants(n: Int): String
    abstract val errSearchStations: String
    abstract val noStationsFound: String
    abstract val noStationsFoundHint: String
    abstract val favoriteStops: String
    abstract val nearbyStations: String
    abstract val noStopsNearby: String
    abstract val noStopsNearbyHint: String
    abstract val unknownStop: String
    abstract val errLoadDepartures: String
    abstract val favoriteStop: String
    abstract val loadingDepartures: String
    abstract val noDepartures: String
    abstract val retry: String
    abstract val noUpcomingDepartures: String
    abstract val noUpcomingDeparturesHint: String
    abstract val directions: String
    abstract val liveLower: String
    abstract val scheduledLower: String
    abstract val loadingTrip: String
    abstract val tripUnavailable: String
    abstract val tripUnavailableHint: String
    abstract val notUpdating: String
    abstract fun departsNotStarted(time: String): String
    abstract val tripEnded: String
    abstract val noLivePosition: String
    abstract val boardHere: String
    abstract val getOffHere: String
    abstract fun lineIsHere(line: String): String
    abstract val errLineUnavailable: String
    abstract val errLoadLine: String
    abstract fun fromOrigin(origin: String): String
    abstract val favorite: String
    abstract val changeDirection: String
    abstract val loadingLine: String
    abstract val lineUnavailable: String
    abstract val stops: String
    abstract val showStops: String
    abstract fun leftAt(time: String): String
    abstract val noMoreTripsToday: String
    abstract val stopBoardUnavailable: String
    abstract val allDeparturesFromStop: String
    abstract fun lastSeen(time: String): String
    abstract fun departuresFrom(stop: String): String
    abstract val noDeparturesToday: String
    abstract val lineSearchHint: String
    abstract val stationSearchHint: String

    // ---- Live directions and notifications --------------------------------------------------
    abstract val youveArrived: String
    abstract fun walkTo(place: String): String
    abstract fun toGo(distance: String): String
    abstract fun atTime(time: String): String
    abstract val getOffNow: String
    abstract fun stopsGetOff(n: Int, time: String): String
    abstract val chTripDescription: String
    abstract val chAlerts: String
    abstract val chAlertsDescription: String
    abstract val chRemindersDescription: String
    abstract val startingLiveDirections: String
    abstract val endTrip: String

    // ---- Walking further -------------------------------------------------------------------------
    abstract val longerWalk: String
    abstract val longerRide: String
    abstract fun widerWalkNote(usual: Int, wider: Int): String
    abstract fun maxWalkHint(wider: Int): String

    // ---- Data -------------------------------------------------------------------------------
    abstract val cableShort: String
    abstract fun platform(track: String?, floor: String?): String
    abstract fun walkStep(direction: String?, street: String?): String
    abstract fun numberNotOnMap(num: String): String
}

object L10n {
    private var current by mutableStateOf<Strings>(English)
    /** The app's words now; read from a composable, a language change re-composes it. */
    val strings: Strings get() = current

    fun use(language: AppLanguage) {
        val next = of(language)
        if (next !== current) current = next
    }

    fun of(language: AppLanguage): Strings = when (language) {
        AppLanguage.EN -> English
        AppLanguage.HE -> Hebrew
        AppLanguage.RU -> Russian
    }

    /** Russian noun form for [n]: one (1, 21), few (2–4, 22–24) or many (5–20, 25…). */
    fun ruPlural(n: Long, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1L && m100 != 11L -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
    }
}

/** The app's words in its current language. */
val S: Strings get() = L10n.strings

/** A place's name to show: "where I am" in the current language rather than the one it was saved in. */
val Place.shownName: String get() = if (kind == PlaceKind.CURRENT_LOCATION) S.currentLocation else name

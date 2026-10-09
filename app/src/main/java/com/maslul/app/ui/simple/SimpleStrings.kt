package com.maslul.app.ui.simple

import androidx.compose.runtime.staticCompositionLocalOf
import com.maslul.app.data.SimpleIcon
import com.maslul.app.data.SimpleLanguage
import com.maslul.app.data.TransitMode
import java.time.DayOfWeek

/**
 * Everything Simple Maslul says, in each of its languages. The full app is English-only;
 * Simple Maslul is meant for people who'd rather read their own language.
 */
abstract class SimpleStrings {
    abstract val appName: String
    abstract val whereTo: String
    abstract val otherAddress: String
    abstract val tapToSet: String
    abstract val settings: String
    abstract val back: String

    abstract val searchHint: String
    abstract val searchTitle: String
    abstract fun whereIs(label: String): String
    abstract val recent: String
    abstract val noResults: String
    abstract val searchFailed: String

    abstract fun toPlace(name: String): String
    abstract val finding: String
    abstract val leaveNow: String
    abstract fun leaveIn(min: Long): String
    abstract fun leaveAt(time: String): String
    /** "tomorrow", or "on Sunday" for [day]; null [day] means tomorrow. */
    abstract fun onDay(day: DayOfWeek?): String
    abstract fun leaveOn(day: DayOfWeek?, time: String): String
    abstract fun arriveAt(time: String): String
    abstract fun changes(n: Int): String
    abstract fun walkThere(min: Long): String
    abstract val best: String
    abstract val moreOptions: String
    abstract val live: String
    abstract val errLocation: String
    abstract val errNoRoutes: String
    abstract val errNetwork: String
    abstract val tryAgain: String

    abstract val howToGetThere: String
    abstract fun walkToStop(stop: String): String
    abstract val walkToDestination: String
    abstract fun aboutMin(min: Long): String
    abstract fun take(mode: TransitMode, line: String): String
    abstract fun orLines(lines: String): String
    abstract fun towards(headsign: String): String
    abstract fun leavesAt(time: String): String
    abstract fun platform(p: String): String
    abstract fun rideStops(n: Int): String
    abstract fun getOffAt(stop: String): String
    abstract val arrived: String

    abstract val buttons: String
    abstract val editHint: String
    abstract val language: String
    abstract val name: String
    abstract val picture: String
    abstract val address: String
    abstract val notSet: String
    abstract val changeAddress: String
    abstract val clear: String
    abstract val save: String
    abstract val cancel: String
    abstract val fullApp: String
    abstract val fullAppBody: String
    abstract val fullAppConfirm: String

    abstract fun iconName(icon: SimpleIcon): String
    abstract fun mode(mode: TransitMode): String

    companion object {
        fun of(lang: SimpleLanguage): SimpleStrings = when (lang) {
            SimpleLanguage.EN -> English
            SimpleLanguage.HE -> Hebrew
            SimpleLanguage.RU -> Russian
        }

        /** Russian noun form for [n]: one (1, 21), few (2–4, 22–24) or many (5–20, 25…). */
        fun ruPlural(n: Int, one: String, few: String, many: String): String {
            val m10 = n % 10
            val m100 = n % 100
            return when {
                m10 == 1 && m100 != 11 -> one
                m10 in 2..4 && m100 !in 12..14 -> few
                else -> many
            }
        }
    }
}

val LocalSimpleStrings = staticCompositionLocalOf<SimpleStrings> { English }

private object English : SimpleStrings() {
    override val appName = "Simple Maslul"
    override val whereTo = "Where are you going?"
    override val otherAddress = "Other address"
    override val tapToSet = "Tap to choose the address"
    override val settings = "Settings"
    override val back = "Back"

    override val searchHint = "Type an address or place"
    override val searchTitle = "Where to?"
    override fun whereIs(label: String) = "Where is “$label”?"
    override val recent = "Recent"
    override val noResults = "Nothing found. Try writing it another way."
    override val searchFailed = "No internet connection. Try again."

    override fun toPlace(name: String) = "To $name"
    override val finding = "Finding the best way…"
    override val leaveNow = "Leave now"
    override fun leaveIn(min: Long) = "Leave in $min min"
    override fun leaveAt(time: String) = "Leave at $time"
    override fun onDay(day: DayOfWeek?) = if (day == null) "tomorrow"
    else "on " + day.name.lowercase().replaceFirstChar(Char::uppercase)
    override fun leaveOn(day: DayOfWeek?, time: String) = "Leave ${onDay(day)} at $time"
    override fun arriveAt(time: String) = "Arrive at $time"
    override fun changes(n: Int) = when (n) {
        0 -> "No changes"
        1 -> "1 change"
        else -> "$n changes"
    }
    override fun walkThere(min: Long) = "Walk there: $min min"
    override val best = "Best"
    override val moreOptions = "More options"
    override val live = "Live"
    override val errLocation = "Can't tell where you are. Turn on location and try again."
    override val errNoRoutes = "No way to get there right now."
    override val errNetwork = "No internet connection. Try again."
    override val tryAgain = "Try again"

    override val howToGetThere = "How to get there"
    override fun walkToStop(stop: String) = "Walk to the stop “$stop”"
    override val walkToDestination = "Walk to where you're going"
    override fun aboutMin(min: Long) = "About $min min"
    override fun take(mode: TransitMode, line: String) = "Take ${mode(mode).lowercase()} $line".trim()
    override fun orLines(lines: String) = "Or: $lines"
    override fun towards(headsign: String) = "Towards $headsign"
    override fun leavesAt(time: String) = "Leaves at $time"
    override fun platform(p: String) = "Platform $p"
    override fun rideStops(n: Int) = if (n == 1) "Ride 1 stop" else "Ride $n stops"
    override fun getOffAt(stop: String) = "Get off at “$stop”"
    override val arrived = "You're there!"

    override val buttons = "Buttons"
    override val editHint = "Tap a button to change its name, picture or address."
    override val language = "Language"
    override val name = "Name"
    override val picture = "Picture"
    override val address = "Address"
    override val notSet = "Not set"
    override val changeAddress = "Change address"
    override val clear = "Clear"
    override val save = "Save"
    override val cancel = "Cancel"
    override val fullApp = "Switch to full Maslul"
    override val fullAppBody = "The full app has maps, lines, stations and more options, with smaller text."
    override val fullAppConfirm = "Switch"

    override fun iconName(icon: SimpleIcon) = when (icon) {
        SimpleIcon.HOME -> "Home"
        SimpleIcon.FAMILY -> "Grandkids"
        SimpleIcon.WORK -> "Work"
        SimpleIcon.HEALTH -> "Doctor"
        SimpleIcon.SHOPPING -> "Shopping"
        SimpleIcon.FRIENDS -> "Friends"
        SimpleIcon.PARK -> "Park"
        SimpleIcon.STAR -> "Favorite"
        SimpleIcon.HEART -> "Family"
        SimpleIcon.SCHOOL -> "School"
        SimpleIcon.CAFE -> "Café"
        SimpleIcon.POOL -> "Pool"
        SimpleIcon.RESTAURANT -> "Restaurant"
        SimpleIcon.GYM -> "Gym"
        SimpleIcon.SPORTS -> "Sports"
    }

    override fun mode(mode: TransitMode) = when (mode) {
        TransitMode.BUS -> "Bus"
        TransitMode.TRAIN -> "Train"
        TransitMode.LIGHT_RAIL -> "Light rail"
        TransitMode.METRO -> "Carmelit"
        TransitMode.CABLE_CAR -> "Cable car"
        TransitMode.FERRY -> "Ferry"
        TransitMode.CAR -> "Car"
        TransitMode.WALK -> "Walk"
        TransitMode.OTHER -> ""
    }
}

private object Hebrew : SimpleStrings() {
    override val appName = "מסלול פשוט"
    override val whereTo = "לאן נוסעים?"
    override val otherAddress = "כתובת אחרת"
    override val tapToSet = "לחצו כדי לבחור כתובת"
    override val settings = "הגדרות"
    override val back = "חזרה"

    override val searchHint = "הקלידו כתובת או מקום"
    override val searchTitle = "לאן?"
    override fun whereIs(label: String) = "איפה „$label”?"
    override val recent = "אחרונים"
    override val noResults = "לא נמצא. נסו לכתוב אחרת."
    override val searchFailed = "אין חיבור לאינטרנט. נסו שוב."

    override fun toPlace(name: String) = "אל $name"
    override val finding = "מחפשים את הדרך הכי טובה…"
    override val leaveNow = "צאו עכשיו"
    override fun leaveIn(min: Long) = "צאו בעוד $min דק׳"
    override fun leaveAt(time: String) = "צאו ב־$time"
    override fun onDay(day: DayOfWeek?) = when (day) {
        null -> "מחר"
        DayOfWeek.SUNDAY -> "ביום ראשון"
        DayOfWeek.MONDAY -> "ביום שני"
        DayOfWeek.TUESDAY -> "ביום שלישי"
        DayOfWeek.WEDNESDAY -> "ביום רביעי"
        DayOfWeek.THURSDAY -> "ביום חמישי"
        DayOfWeek.FRIDAY -> "ביום שישי"
        DayOfWeek.SATURDAY -> "בשבת"
    }
    override fun leaveOn(day: DayOfWeek?, time: String) = "צאו ${onDay(day)} ב־$time"
    override fun arriveAt(time: String) = "מגיעים ב־$time"
    override fun changes(n: Int) = when (n) {
        0 -> "בלי החלפות"
        1 -> "החלפה אחת"
        else -> "$n החלפות"
    }
    override fun walkThere(min: Long) = "ברגל: $min דק׳"
    override val best = "הכי טוב"
    override val moreOptions = "עוד אפשרויות"
    override val live = "בזמן אמת"
    override val errLocation = "לא מצליחים לדעת איפה אתם. הפעילו מיקום ונסו שוב."
    override val errNoRoutes = "אין דרך להגיע לשם כרגע."
    override val errNetwork = "אין חיבור לאינטרנט. נסו שוב."
    override val tryAgain = "נסו שוב"

    override val howToGetThere = "איך מגיעים"
    override fun walkToStop(stop: String) = "ללכת לתחנה „$stop”"
    override val walkToDestination = "ללכת אל היעד"
    override fun aboutMin(min: Long) = "בערך $min דק׳"
    override fun take(mode: TransitMode, line: String) = "לעלות על ${mode(mode)} $line".trim()
    override fun orLines(lines: String) = "או: $lines"
    override fun towards(headsign: String) = "לכיוון $headsign"
    override fun leavesAt(time: String) = "יוצא ב־$time"
    override fun platform(p: String) = "רציף $p"
    override fun rideStops(n: Int) = if (n == 1) "לנסוע תחנה אחת" else "לנסוע $n תחנות"
    override fun getOffAt(stop: String) = "לרדת בתחנה „$stop”"
    override val arrived = "הגעתם!"

    override val buttons = "כפתורים"
    override val editHint = "לחצו על כפתור כדי לשנות את השם, התמונה או הכתובת."
    override val language = "שפה"
    override val name = "שם"
    override val picture = "תמונה"
    override val address = "כתובת"
    override val notSet = "לא נבחרה"
    override val changeAddress = "שינוי כתובת"
    override val clear = "ניקוי"
    override val save = "שמירה"
    override val cancel = "ביטול"
    override val fullApp = "מעבר למסלול המלא"
    override val fullAppBody = "באפליקציה המלאה יש מפות, קווים, תחנות ועוד אפשרויות, בכתב קטן יותר."
    override val fullAppConfirm = "מעבר"

    override fun iconName(icon: SimpleIcon) = when (icon) {
        SimpleIcon.HOME -> "בית"
        SimpleIcon.FAMILY -> "הנכדים"
        SimpleIcon.WORK -> "עבודה"
        SimpleIcon.HEALTH -> "רופא"
        SimpleIcon.SHOPPING -> "קניות"
        SimpleIcon.FRIENDS -> "חברים"
        SimpleIcon.PARK -> "פארק"
        SimpleIcon.STAR -> "מועדף"
        SimpleIcon.HEART -> "משפחה"
        SimpleIcon.SCHOOL -> "בית ספר"
        SimpleIcon.CAFE -> "בית קפה"
        SimpleIcon.POOL -> "בריכה"
        SimpleIcon.RESTAURANT -> "מסעדה"
        SimpleIcon.GYM -> "חדר כושר"
        SimpleIcon.SPORTS -> "ספורט"
    }

    override fun mode(mode: TransitMode) = when (mode) {
        TransitMode.BUS -> "אוטובוס"
        TransitMode.TRAIN -> "רכבת"
        TransitMode.LIGHT_RAIL -> "רכבת קלה"
        TransitMode.METRO -> "כרמלית"
        TransitMode.CABLE_CAR -> "רכבל"
        TransitMode.FERRY -> "מעבורת"
        TransitMode.CAR -> "רכב"
        TransitMode.WALK -> "הליכה"
        TransitMode.OTHER -> ""
    }
}

private object Russian : SimpleStrings() {
    override val appName = "Простой Маслул"
    override val whereTo = "Куда едем?"
    override val otherAddress = "Другой адрес"
    override val tapToSet = "Нажмите, чтобы выбрать адрес"
    override val settings = "Настройки"
    override val back = "Назад"

    override val searchHint = "Введите адрес или место"
    override val searchTitle = "Куда?"
    override fun whereIs(label: String) = "Где «$label»?"
    override val recent = "Недавние"
    override val noResults = "Ничего не найдено. Попробуйте написать иначе."
    override val searchFailed = "Нет подключения к интернету. Попробуйте снова."

    override fun toPlace(name: String) = "Куда: $name"
    override val finding = "Ищем лучший путь…"
    override val leaveNow = "Выходите сейчас"
    override fun leaveIn(min: Long) = "Выходите через $min мин"
    override fun leaveAt(time: String) = "Выходите в $time"
    override fun onDay(day: DayOfWeek?) = when (day) {
        null -> "завтра"
        DayOfWeek.MONDAY -> "в понедельник"
        DayOfWeek.TUESDAY -> "во вторник"
        DayOfWeek.WEDNESDAY -> "в среду"
        DayOfWeek.THURSDAY -> "в четверг"
        DayOfWeek.FRIDAY -> "в пятницу"
        DayOfWeek.SATURDAY -> "в субботу"
        DayOfWeek.SUNDAY -> "в воскресенье"
    }
    override fun leaveOn(day: DayOfWeek?, time: String) = "Выходите ${onDay(day)} в $time"
    override fun arriveAt(time: String) = "Прибытие в $time"
    override fun changes(n: Int) =
        if (n == 0) "Без пересадок" else "$n ${ruPlural(n, "пересадка", "пересадки", "пересадок")}"
    override fun walkThere(min: Long) = "Пешком: $min мин"
    override val best = "Лучший"
    override val moreOptions = "Другие варианты"
    override val live = "Онлайн"
    override val errLocation = "Не удаётся определить, где вы. Включите геолокацию и попробуйте снова."
    override val errNoRoutes = "Сейчас туда не добраться."
    override val errNetwork = "Нет подключения к интернету. Попробуйте снова."
    override val tryAgain = "Попробовать снова"

    override val howToGetThere = "Как добраться"
    override fun walkToStop(stop: String) = "Идите к остановке «$stop»"
    override val walkToDestination = "Идите к месту назначения"
    override fun aboutMin(min: Long) = "Около $min мин"
    override fun take(mode: TransitMode, line: String) = "Садитесь на ${mode(mode).lowercase()} $line".trim()
    override fun orLines(lines: String) = "Или: $lines"
    override fun towards(headsign: String) = "В сторону: $headsign"
    override fun leavesAt(time: String) = "Отправление в $time"
    override fun platform(p: String) = "Платформа $p"
    override fun rideStops(n: Int) = "Проедьте $n ${ruPlural(n, "остановку", "остановки", "остановок")}"
    override fun getOffAt(stop: String) = "Выходите на остановке «$stop»"
    override val arrived = "Вы на месте!"

    override val buttons = "Кнопки"
    override val editHint = "Нажмите на кнопку, чтобы изменить название, картинку или адрес."
    override val language = "Язык"
    override val name = "Название"
    override val picture = "Картинка"
    override val address = "Адрес"
    override val notSet = "Не выбран"
    override val changeAddress = "Изменить адрес"
    override val clear = "Очистить"
    override val save = "Сохранить"
    override val cancel = "Отмена"
    override val fullApp = "Перейти в полный Маслул"
    override val fullAppBody = "В полной версии есть карты, линии, станции и больше настроек, но мельче текст."
    override val fullAppConfirm = "Перейти"

    override fun iconName(icon: SimpleIcon) = when (icon) {
        SimpleIcon.HOME -> "Дом"
        SimpleIcon.FAMILY -> "Внуки"
        SimpleIcon.WORK -> "Работа"
        SimpleIcon.HEALTH -> "Врач"
        SimpleIcon.SHOPPING -> "Магазин"
        SimpleIcon.FRIENDS -> "Друзья"
        SimpleIcon.PARK -> "Парк"
        SimpleIcon.STAR -> "Избранное"
        SimpleIcon.HEART -> "Семья"
        SimpleIcon.SCHOOL -> "Школа"
        SimpleIcon.CAFE -> "Кафе"
        SimpleIcon.POOL -> "Бассейн"
        SimpleIcon.RESTAURANT -> "Ресторан"
        SimpleIcon.GYM -> "Спортзал"
        SimpleIcon.SPORTS -> "Спорт"
    }

    override fun mode(mode: TransitMode) = when (mode) {
        TransitMode.BUS -> "Автобус"
        TransitMode.TRAIN -> "Поезд"
        TransitMode.LIGHT_RAIL -> "Трамвай"
        TransitMode.METRO -> "Кармелит"
        TransitMode.CABLE_CAR -> "Канатная дорога"
        TransitMode.FERRY -> "Паром"
        TransitMode.CAR -> "Машина"
        TransitMode.WALK -> "Пешком"
        TransitMode.OTHER -> ""
    }
}

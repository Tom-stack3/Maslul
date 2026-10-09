package com.maslul.app.ui.simple

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.Park
import androidx.compose.material.icons.rounded.Pool
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maslul.app.MaslulApp
import com.maslul.app.R
import com.maslul.app.data.Place
import com.maslul.app.data.Settings
import com.maslul.app.data.SimpleButton
import com.maslul.app.data.SimpleIcon
import com.maslul.app.data.SimpleLanguage
import java.util.Locale

/** The language Simple Maslul speaks: the one picked, else the phone's. */
fun Settings.simpleLanguageOrDefault(): SimpleLanguage =
    simpleLanguage ?: SimpleLanguage.forLocale(Locale.getDefault().language)

/** Gives Simple Maslul screens their language's words and reading direction. */
@Composable
fun SimpleFrame(content: @Composable () -> Unit) {
    val data by MaslulApp.instance.store.data.collectAsState()
    val lang = data.settings.simpleLanguageOrDefault()
    CompositionLocalProvider(
        LocalSimpleStrings provides SimpleStrings.of(lang),
        LocalLayoutDirection provides if (lang.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) { content() }
}

/** A place with its city, "Weizmann 14, Tel Aviv", so it's clear which one was picked. */
val Place.withCity: String get() = listOfNotNull(name, subtitle?.takeIf { it.isNotBlank() && it != name }).joinToString(", ")

/** A button's name: the one given, else its picture's name in the current language. */
fun SimpleButton.title(s: SimpleStrings): String = label.trim().ifEmpty { s.iconName(icon) }

/** The same pictures as the full app's favourites, plus family, friends and work. */
@Composable
fun SimpleIcon.vector(): ImageVector = when (this) {
    SimpleIcon.HOME -> Icons.Rounded.Home
    SimpleIcon.FAMILY -> Icons.Rounded.FamilyRestroom
    SimpleIcon.WORK -> Icons.Rounded.Work
    SimpleIcon.HEALTH -> Icons.Rounded.LocalHospital
    SimpleIcon.SHOPPING -> Icons.Rounded.ShoppingCart
    SimpleIcon.FRIENDS -> Icons.Rounded.Groups
    SimpleIcon.PARK -> Icons.Rounded.Park
    SimpleIcon.STAR -> Icons.Rounded.Star
    SimpleIcon.HEART -> Icons.Rounded.Favorite
    SimpleIcon.SCHOOL -> Icons.Rounded.School
    SimpleIcon.CAFE -> Icons.Rounded.LocalCafe
    SimpleIcon.POOL -> Icons.Rounded.Pool
    SimpleIcon.RESTAURANT -> Icons.Rounded.Restaurant
    SimpleIcon.GYM -> Icons.Rounded.FitnessCenter
    SimpleIcon.SPORTS -> ImageVector.vectorResource(R.drawable.ic_sports_and_outdoors)
}

/** One strong colour per button, so each is easy to tell apart (white text reads on all of them). */
private val SlotColors = listOf(
    Color(0xFF1F6FEB), Color(0xFFC92A5E), Color(0xFF0E7C6B), Color(0xFF6741D9), Color(0xFFC2410C),
)

fun slotColor(index: Int): Color = SlotColors[index % SlotColors.size]

/** The search button's colour, set apart from the place buttons. */
val SearchButtonColor = Color(0xFF374151)

/** Big, plainly worded title row with a back button that says "Back". */
@Composable
fun SimpleTopBar(title: String, onBack: (() -> Unit)?, trailing: (@Composable () -> Unit)? = null) {
    val s = LocalSimpleStrings.current
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (onBack != null || trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    Surface(
                        onClick = onBack,
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(28.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(s.back, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                trailing?.invoke()
            }
            Spacer(Modifier.size(12.dp))
        }
        Text(title, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** A wide, tall button with large text, for the actions that matter on a screen. */
@Composable
fun SimpleWideButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = color, contentColor = content,
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

package fyi.justfreesoftware.justblock.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import fyi.justfreesoftware.justblock.R

val LeagueGothicFontFamily = FontFamily(
    Font(R.font.league_gothic_regular, FontWeight.Normal),
    Font(R.font.league_gothic_italic, FontWeight.Normal, FontStyle.Italic)
)

/**
 * Screen-header style used only for the three top-level headers
 * (Home, Blocking, Whitelist). Sized identically to the default
 * Material3 displayLarge.
 */
val HeaderTextStyle = TextStyle(
    fontFamily = LeagueGothicFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 57.sp,
    lineHeight = 64.sp,
    letterSpacing = (-0.25).sp
)

// Set of Material typography styles to start with
val Typography = Typography()

package dev.arkbuilders.drop.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import dev.arkbuilders.drop.domain.repository.ProfileRepo
import dev.arkbuilders.drop.domain.repository.TransferSessionRepo
import dev.arkbuilders.drop.instrumentation.AnalyticsEvents
import dev.arkbuilders.drop.instrumentation.AnalyticsReporter
import dev.arkbuilders.drop.presentation.history.History
import dev.arkbuilders.drop.presentation.home.Home
import dev.arkbuilders.drop.presentation.navigation.DropDestination
import dev.arkbuilders.drop.presentation.profile.AboutScreen
import dev.arkbuilders.drop.presentation.profile.EditProfileEnhanced
import dev.arkbuilders.drop.presentation.receive.Receive
import dev.arkbuilders.drop.presentation.send.Send
import dev.arkbuilders.drop.presentation.theme.DropTheme
import org.koin.android.ext.android.get

class MainActivity : ComponentActivity() {
    private val profileRepo: ProfileRepo = get()

    private val transferSessionRepo: TransferSessionRepo = get()

    private val analyticsReporter: AnalyticsReporter = get()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        analyticsReporter.logEvent(
            AnalyticsEvents.APP_START,
            mapOf(AnalyticsEvents.PARAM_PLATFORM to "android"),
        )

        setContent {
            DropTheme {
                Scaffold(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .safeDrawingPadding(),
                ) { innerPadding ->
                    DropNavigation(
                        modifier =
                            Modifier
                                .padding(innerPadding),
                        profileRepo = profileRepo,
                        transferSessionRepo = transferSessionRepo,
                        analyticsReporter = analyticsReporter,
                    )
                }
            }
        }
    }
}

@Composable
fun DropNavigation(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    profileRepo: ProfileRepo,
    transferSessionRepo: TransferSessionRepo,
    analyticsReporter: AnalyticsReporter,
) {
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { backStackEntry ->
            val screenName = backStackEntry.destination.route?.toAnalyticsScreenName()
            if (screenName != null) {
                analyticsReporter.logEvent(
                    AnalyticsEvents.SCREEN_VIEW,
                    mapOf(
                        AnalyticsEvents.PARAM_SCREEN_NAME to screenName,
                        AnalyticsEvents.PARAM_SCREEN_CLASS to screenName,
                    ),
                )
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = DropDestination.Home.route,
        modifier = modifier,
    ) {
        composable(DropDestination.Home.route) {
            Home(
                navController = navController,
                profileRepo = profileRepo,
                transferSessionRepo = transferSessionRepo,
            )
        }
        composable(DropDestination.Send.route) {
            Send(
                navController = navController,
            )
        }
        composable(
            DropDestination.Receive.route,
            deepLinks =
                listOf(
                    navDeepLink {
                        uriPattern = DropDestination.Receive.DEEP_LINK_PATTERN
                    },
                ),
        ) {
            val ticket = it.arguments?.getString("ticket").orEmpty()
            val confirmation = it.arguments?.getString("confirmation").orEmpty()
            LaunchedEffect(ticket, confirmation) {
                if (ticket.isNotEmpty() || confirmation.isNotEmpty()) {
                    analyticsReporter.logEvent(AnalyticsEvents.RECEIVE_DEEP_LINK_OPENED)
                }
            }
            Receive(
                navController = navController,
            )
        }
        composable(DropDestination.History.route) {
            History(
                navController = navController,
                transferSessionRepo = transferSessionRepo,
            )
        }
        composable(DropDestination.EditProfile.route) {
            EditProfileEnhanced(
                navController = navController,
            )
        }
        composable(DropDestination.About.route) {
            AboutScreen(
                navController = navController,
            )
        }
    }
}

private fun String.toAnalyticsScreenName(): String? =
    when (this) {
        DropDestination.Home.route -> "home"
        DropDestination.Send.route -> "send"
        DropDestination.Receive.route -> "receive"
        DropDestination.History.route -> "history"
        DropDestination.EditProfile.route -> "edit_profile"
        DropDestination.About.route -> "about"
        else -> null
    }

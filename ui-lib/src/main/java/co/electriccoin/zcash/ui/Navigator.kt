package co.electriccoin.zcash.ui

import android.annotation.SuppressLint
import android.app.Activity
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.serialization.generateHashCode
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.design.KeyboardManager
import co.electriccoin.zcash.ui.design.SheetStateManager
import co.electriccoin.zcash.ui.screen.ExternalUrl
import co.electriccoin.zcash.ui.screen.about.util.WebBrowserUtil
import co.electriccoin.zcash.ui.screen.flexa.FlexaViewModel
import com.flexa.core.Flexa
import com.flexa.spend.buildSpend
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

interface Navigator {
    suspend fun executeCommand(command: NavigationCommand)
}

class NavigatorImpl(
    private val activity: Activity,
    private val navController: NavHostController,
    private val flexaViewModel: FlexaViewModel,
    private val keyboardManager: KeyboardManager,
    private val sheetStateManager: SheetStateManager,
    private val applicationStateProvider: ApplicationStateProvider,
) : Navigator {
    override suspend fun executeCommand(command: NavigationCommand) {
        keyboardManager.close()

        when (command) {
            NavigationCommand.Back,
            NavigationCommand.BackToRoot,
            is NavigationCommand.BackTo -> {
                val currentRoute =
                    navController
                        .currentBackStackEntry
                        ?.destination
                        ?.route

                currentRoute?.let { sheetStateManager.hide(it) }
            }
            else -> {
                // do nothing
            }
        }

        when (command) {
            is NavigationCommand.Forward -> forward(command)
            is NavigationCommand.Replace -> replace(command)
            is NavigationCommand.ReplaceAll -> replaceAll(command)
            NavigationCommand.Back ->
                // Guard against popping into the void. Screens reached via replaceAll (e.g. the
                // post-send transaction detail) can leave no previous back-stack entry; an
                // unconditional popBackStack() then empties the NavHost → dark screen + crash on
                // "Close"/back. Route that case through the safe root walk instead.
                if (navController.previousBackStackEntry != null) {
                    navController.popBackStack()
                } else {
                    backToRoot()
                }
            is NavigationCommand.BackTo -> backTo(command)
            NavigationCommand.BackToRoot -> backToRoot()
        }
    }

    @SuppressLint("RestrictedApi")
    @OptIn(InternalSerializationApi::class)
    private fun backTo(command: NavigationCommand.BackTo) {
        navController.popBackStack(
            destinationId = command.route.serializer().generateHashCode(),
            inclusive = false
        )
    }

    private fun backToRoot() {
        // First attempt: pop directly to the parent graph's start destination.
        val rootId = navController.currentDestination?.parent?.startDestinationId
        val popped = rootId?.let { navController.popBackStack(destinationId = it, inclusive = false) } == true
        if (popped) return

        // Fallback: popBackStack(destinationId, ...) silently returns false when the target
        // destination isn't actually on the back stack — this happens on screens reached via
        // replace() chains (Send → Review → submit → TransactionProgress) where the intermediates
        // were popped, sometimes leaving the original root absent from the realized back stack.
        // Pop intermediates one at a time, but STOP before emptying the graph. popBackStack()
        // returns true even when it pops the START/root destination (it only returns false once the
        // stack is ALREADY empty) — so an unguarded loop nukes the NavHost, leaving a blank dark
        // screen where BACK exits the app (the post-send "Close" path). Guard on
        // previousBackStackEntry so the loop halts with the root still mounted.
        while (navController.previousBackStackEntry != null) {
            if (!navController.popBackStack()) break
        }
    }

    private fun replaceAll(command: NavigationCommand.ReplaceAll) {
        command.routes.forEachIndexed { index, route ->
            when (route) {
                co.electriccoin.zcash.ui.screen.flexa.Flexa -> {
                    if (index == 0) {
                        navController.currentDestination?.parent?.startDestinationId?.let {
                            navController.popBackStack(
                                route = it,
                                inclusive = false
                            )
                        }
                    }

                    if (index != command.routes.lastIndex) {
                        throw UnsupportedOperationException("Flexa can be opened as last screen only")
                    }

                    createFlexaFlow(flexaViewModel)
                }

                is ExternalUrl -> {
                    if (index == 0) {
                        navController.currentDestination?.parent?.startDestinationId?.let {
                            navController.popBackStack(
                                route = it,
                                inclusive = false
                            )
                        }
                    }

                    if (index != command.routes.lastIndex) {
                        throw UnsupportedOperationException("External url can be opened as last screen only")
                    }

                    applicationStateProvider.onThirdPartyUiShown()
                    WebBrowserUtil.startActivity(activity, route.url)
                }

                else -> {
                    navController.executeNavigation(route = route) {
                        if (index == 0) {
                            navController.currentDestination?.parent?.startDestinationId?.let {
                                popUpTo(it) {
                                    inclusive = false
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun replace(command: NavigationCommand.Replace) {
        command.routes.forEachIndexed { index, route ->
            when (route) {
                co.electriccoin.zcash.ui.screen.flexa.Flexa -> {
                    if (index == 0) {
                        navController.popBackStack()
                    }

                    if (index != command.routes.lastIndex) {
                        throw UnsupportedOperationException("Flexa can be opened as last screen only")
                    }

                    createFlexaFlow(flexaViewModel)
                }

                is ExternalUrl -> {
                    if (index == 0) {
                        navController.popBackStack()
                    }

                    if (index != command.routes.lastIndex) {
                        throw UnsupportedOperationException("External url can be opened as last screen only")
                    }

                    applicationStateProvider.onThirdPartyUiShown()
                    WebBrowserUtil.startActivity(activity, route.url)
                }

                else -> {
                    navController.executeNavigation(route = route) {
                        if (index == 0) {
                            popUpTo(navController.currentBackStackEntry?.destination?.id ?: 0) {
                                inclusive = true
                            }
                        }
                    }
                }
            }
        }
    }

    private fun forward(command: NavigationCommand.Forward) {
        command.routes.forEach { route ->
            when (route) {
                co.electriccoin.zcash.ui.screen.flexa.Flexa -> createFlexaFlow(flexaViewModel)
                is ExternalUrl -> WebBrowserUtil.startActivity(activity, route.url)
                else ->
                    // launchSingleTop collapses a navigate() to the route already on top of the
                    // back stack into a no-op (Navigation-Compose compares the full type-safe route
                    // incl. args). Prevents a duplicate ChatDetail/GroupDetail entry when re-opening
                    // the conversation you're already viewing (notification deep link / in-app banner
                    // tap / chat-list click). A different screen, or the same screen with different
                    // args, still pushes normally.
                    navController.executeNavigation(route = route) {
                        launchSingleTop = true
                    }
            }
        }

        if (command.routes.lastOrNull() in listOf(ExternalUrl, co.electriccoin.zcash.ui.screen.flexa.Flexa)) {
            applicationStateProvider.onThirdPartyUiShown()
        }
    }

    private fun NavHostController.executeNavigation(
        route: Any,
        builder: (NavOptionsBuilder.() -> Unit)? = null
    ) {
        if (route is String) {
            if (builder == null) {
                navigate(route)
            } else {
                navigate(route, builder)
            }
        } else {
            if (builder == null) {
                navigate(route)
            } else {
                navigate(route, builder)
            }
        }
    }

    private fun createFlexaFlow(flexaViewModel: FlexaViewModel) {
        applicationStateProvider.onThirdPartyUiShown()
        Flexa
            .buildSpend()
            .onTransactionRequest { result -> flexaViewModel.createTransaction(result) }
            .build()
            .open(activity)
    }
}

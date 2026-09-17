package com.moread.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.moread.app.ui.bookshelf.BookshelfScreen
import com.moread.app.ui.comic.ComicScreen
import com.moread.app.ui.reader.ReaderScreen
import com.moread.app.ui.rules.RulesScreen

object Routes {
    const val BOOKSHELF = "bookshelf"
    const val READER = "reader/{bookId}"
    const val RULES = "rules"
    const val COMIC = "comic/{bookId}"

    fun comic(bookId: Long) = "comic/$bookId"

    fun reader(bookId: Long) = "reader/$bookId"
}

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = Routes.BOOKSHELF,
        enterTransition = {
            androidx.compose.animation.slideInHorizontally { it / 3 } + androidx.compose.animation.fadeIn()
        },
        exitTransition = {
            androidx.compose.animation.slideOutHorizontally { -it / 3 } + androidx.compose.animation.fadeOut()
        },
        popEnterTransition = {
            androidx.compose.animation.slideInHorizontally { -it / 3 } + androidx.compose.animation.fadeIn()
        },
        popExitTransition = {
            androidx.compose.animation.slideOutHorizontally { it / 3 } + androidx.compose.animation.fadeOut()
        },
    ) {
        composable(Routes.BOOKSHELF) {
            BookshelfScreen(
                onOpenBook = { nav.navigate(Routes.reader(it)) },
                onOpenComic = { nav.navigate(Routes.comic(it)) },
                onOpenRules = { nav.navigate(Routes.RULES) },
            )
        }
        composable(
            Routes.READER,
            arguments = listOf(androidx.navigation.navArgument("bookId") { type = NavType.LongType }),
        ) {
            ReaderScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.COMIC,
            arguments = listOf(androidx.navigation.navArgument("bookId") { type = NavType.LongType }),
        ) {
            ComicScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.RULES) {
            RulesScreen(onBack = { nav.popBackStack() })
        }
    }
}

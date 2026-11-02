package io.itchstore.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ItchTheme { App() } }
    }
}

@Composable
fun ItchTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme())
        darkColorScheme(
            primary = Color(0xFFFA5C5C), onPrimary = Color.White,
            background = Color(0xFF121212), surface = Color(0xFF121212),
            surfaceVariant = Color(0xFF2A2A2A), surfaceContainer = Color(0xFF1C1C1C)
        )
    else
        lightColorScheme(
            primary = Color(0xFFE5484D), onPrimary = Color.White,
            background = Color(0xFFFAFAFA), surface = Color(0xFFFAFAFA),
            surfaceVariant = Color(0xFFEAEAEA), surfaceContainer = Color(0xFFF1F1F1)
        )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(vm: AppVM = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var engineOpen by rememberSaveable { mutableStateOf(false) }
    val sel = vm.selected
    BackHandler(engineOpen || sel != null) {
        if (engineOpen) engineOpen = false else vm.selected = null
    }
    if (engineOpen) {
        EngineScreen(onBack = { engineOpen = false })
        return
    }

    val tabs = listOf(
        "Discover" to Icons.Default.Home,
        "Search" to Icons.Default.Search,
        "My Games" to Icons.Default.VideogameAsset,
        "Downloads" to Icons.Default.Download,
        "Saved" to Icons.Default.Favorite,
        "Engine" to Icons.Default.Settings,
        "Account" to Icons.Default.Person
    )

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(icon, label) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> DiscoverScreen(vm)
                    1 -> SearchScreen(vm)
                    2 -> MyGamesScreen(vm) { engineOpen = true }
                    3 -> DownloadsScreen(vm)
                    4 -> SavedScreen(vm)
                    5 -> RuntimeScreen(vm) { engineOpen = true }
                    else -> AccountScreen(vm)
                }
            }
        }
        if (sel != null) DetailScreen(vm, sel)
    }
}

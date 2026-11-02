package io.itchstore.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

fun openUrl(ctx: Context, url: String) {
    try {
        CustomTabsIntent.Builder().build().launchUrl(ctx, Uri.parse(url))
    } catch (_: Exception) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    }
}

@Composable
fun GameCard(g: Game, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), RoundedCornerShape(12.dp)) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(315f / 250f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                AsyncImage(
                    model = g.cover, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
                if (!g.price.isNullOrBlank()) {
                    Text(
                        g.price, color = Color.White, fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Column(Modifier.padding(10.dp)) {
                Text(
                    g.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium, fontSize = 14.sp
                )
                Text(
                    g.author, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    g.blurb, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun GameGrid(p: Pager, open: (Game) -> Unit, header: (@Composable () -> Unit)? = null) {
    val st = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val l = st.layoutInfo
            (l.visibleItemsInfo.lastOrNull()?.index ?: 0) >= l.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, p.items.size) {
        if (nearEnd && p.items.isNotEmpty() && p.error == null) p.loadMore()
    }
    LazyVerticalGrid(
        GridCells.Fixed(2), Modifier.fillMaxSize(), st,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        items(p.items, key = { it.url }) { g -> GameCard(g) { open(g) } }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                when {
                    p.loading -> CircularProgressIndicator(Modifier.size(28.dp))
                    p.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Couldn't load games.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({ p.loadMore() }) { Text("Retry") }
                    }
                    p.items.isEmpty() -> Text("Nothing found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipRow(items: List<Pair<String, String>>, selected: String?, onPick: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { (label, v) ->
            FilterChip(selected = selected == v, onClick = { onPick(v) }, label = { Text(label) })
        }
    }
}

@Composable
fun DiscoverScreen(vm: AppVM) {
    GameGrid(vm.discover, { vm.selected = it }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Discover", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            ChipRow(FEEDS, vm.feed) { vm.feed = it; vm.discover.reset() }
            ChipRow(TAGS.map { t -> t.replaceFirstChar { c -> c.uppercase() } to t }, vm.tag) {
                vm.tag = if (vm.tag == it) null else it
                vm.discover.reset()
            }
        }
    }
}

@Composable
fun SavedScreen(vm: AppVM) {
    if (vm.saved.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
            Text(
                "Games you save show up here. Tap the heart on any game.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyVerticalGrid(
        GridCells.Fixed(2), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Saved", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        items(vm.saved, key = { it.url }) { g -> GameCard(g) { vm.selected = g } }
    }
}

@Composable
fun AccountScreen(vm: AppVM) {
    val ctx = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    val me = vm.me
    if (me == null) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Your library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Connect your itch.io account with an API key to see the games you own.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                label = { Text("API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            vm.accountError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.connect(input) },
                enabled = !vm.accountLoading,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (vm.accountLoading) "Signing in…" else "Connect") }
            TextButton({ openUrl(ctx, "https://itch.io/user/settings/api-keys") }) { Text("Get an API key") }
        }
        return
    }
    GameGrid(vm.library, { vm.selected = it }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = me.avatar, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(me.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Owned games", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton({ vm.logout() }) { Text("Log out") }
            }
        }
    }
}

package io.github.playertwo1.bookconverter.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Surface as M3Surface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.playertwo1.bookconverter.data.Book
import io.github.playertwo1.bookconverter.ui.languageLabel
import io.github.playertwo1.bookconverter.ui.sizeLabel
import io.github.playertwo1.bookconverter.ui.theme.Accent
import io.github.playertwo1.bookconverter.ui.theme.Serif
import io.github.playertwo1.bookconverter.ui.theme.Stroke
import io.github.playertwo1.bookconverter.ui.theme.Surface
import io.github.playertwo1.bookconverter.ui.theme.TextPrimary
import io.github.playertwo1.bookconverter.ui.theme.TextSecondary

@Composable
fun LibraryScreen(
    onOpenBook: (Long) -> Unit,
    onOpenQuickTranslate: () -> Unit = {},
    vm: LibraryViewModel = viewModel(),
) {
    val books by vm.books.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri, onOpenBook)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        // 96dp clears the floating nav capsule (8dp margin + 64dp height, plus its own inset above the navigation bar).
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 96.dp),
    ) {
        item {
            Text("Minha Biblioteca", style = MaterialTheme.typography.displaySmall, color = TextPrimary)
            Spacer(Modifier.height(24.dp))
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        vm.clearMessage()
                        picker.launch(arrayOf("*/*"))
                    },
                    enabled = !importing,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(if (importing) "Importando…" else "+ Importar livro")
                }
                OutlinedButton(
                    onClick = onOpenQuickTranslate,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 56.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Stroke),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Surface,
                        contentColor = TextPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("Traduzir texto")
                }
            }
        }
        message?.let { msg ->
            item {
                Text(msg, color = TextSecondary, modifier = Modifier.padding(top = 12.dp))
            }
        }
        if (books.isNotEmpty()) {
            item {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Livros",
                    color = TextPrimary,
                    fontFamily = Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                )
                Spacer(Modifier.height(12.dp))
            }
            items(books, key = { it.id }) { book -> BookRow(book, onOpenBook) }
        } else {
            item {
                Spacer(Modifier.height(24.dp))
                Text("Nenhum livro ainda. Importe um EPUB, MOBI ou AZW3 sem DRM.", color = TextSecondary)
            }
        }
    }
}

@Composable
private fun BookRow(book: Book, onOpen: (Long) -> Unit) {
    val details = listOfNotNull(book.author, book.format, sizeLabel(book.sizeBytes), languageLabel(book.language))
        .joinToString(" · ")
    M3Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = "Ver verificação", role = Role.Button) { onOpen(book.id) },
        color = Surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Stroke),
    ) {
        Row(
            Modifier.defaultMinSize(minHeight = 64.dp).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Accent))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    color = TextPrimary,
                    fontSize = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(details, color = TextSecondary, fontSize = 13.sp)
            }
            Text("›", color = TextSecondary, fontSize = 20.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

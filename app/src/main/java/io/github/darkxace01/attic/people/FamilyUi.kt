package io.github.darkxace01.attic.people

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.darkxace01.attic.data.FaceEntity
import io.github.darkxace01.attic.data.PersonSummary
import io.github.darkxace01.attic.data.RelationEntity
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.ui.Chip
import io.github.darkxace01.attic.ui.PrimaryButton
import io.github.darkxace01.attic.ui.QuietButton
import io.github.darkxace01.attic.ui.TopBar
import io.github.darkxace01.attic.ui.fieldColors
import io.github.darkxace01.attic.ui.theme.Palette
import kotlinx.coroutines.launch

/** A person's face, or their initial when no face of theirs has been found. */
@Composable
fun Avatar(p: PersonSummary?, name: String, faces: List<FaceEntity>, size: Dp, modifier: Modifier = Modifier) {
    val face = p?.faceId?.let { id -> faces.firstOrNull { it.id == id } }
    if (face != null) FaceThumb(face, size, modifier)
    else io.github.darkxace01.attic.ui.theme.Monogram(name, size, modifier)
}

/**
 * On a person's page: their parents, husband or wife, children, brothers and sisters. Tap one to go to
 * them, hold one to say they're not related after all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FamilySection(personId: Long, name: String, onOpenPerson: (Long) -> Unit, onTree: () -> Unit) {
    val library = LocalContext.current.library
    val relations by remember { library.relations() }.collectAsStateWithLifecycle(emptyList())
    val people by remember { library.people() }.collectAsStateWithLifecycle(emptyList())
    val faces by remember { library.allFaces() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Pair<RelationEntity, String>?>(null) }
    val kin = Family.kin(personId, relations)
    val byId = people.associateBy { it.id }

    /** The relation that says [other] is related this way, if it was said directly (not worked out). */
    fun direct(other: Long, kind: String): RelationEntity? = relations.firstOrNull {
        it.kind == kind && ((it.a == other && it.b == personId) || (it.a == personId && it.b == other))
    }

    Column(Modifier.padding(top = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Family", style = MaterialTheme.typography.titleMedium, color = Palette.Ink, modifier = Modifier.padding(horizontal = 4.dp).weight(1f))
            if (relations.any { it.a == personId || it.b == personId }) QuietButton("See the tree", onClick = onTree)
            QuietButton("Add", onClick = { adding = true })
        }
        val groups = listOf(
            "Parents" to kin.parents.map { it to RelationEntity.PARENT },
            "Married to" to kin.spouses.map { it to RelationEntity.SPOUSE },
            "Children" to kin.children.map { it to RelationEntity.PARENT },
            "Brothers and sisters" to kin.siblings.map { it to RelationEntity.SIBLING },
        ).filter { it.second.isNotEmpty() }
        if (groups.isEmpty()) {
            Text("Add $name's parents, children or partner and Attic draws the family tree.", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, modifier = Modifier.padding(horizontal = 4.dp))
        }
        for ((label, list) in groups) {
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, modifier = Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((id, kind) in list) {
                    val s = byId[id]
                    Relative(s, s?.name ?: "", faces, onClick = { onOpenPerson(id) }, onLongClick = {
                        direct(id, kind)?.let { r -> removing = r to (s?.name ?: "") }
                    })
                }
            }
        }
    }

    if (adding) RelativeSheet(name, people.filter { it.id != personId }, onDismiss = { adding = false }) { kind, other ->
        adding = false
        scope.launch { library.relate(personId, other, kind) }
    }
    removing?.let { (r, other) ->
        io.github.darkxace01.attic.ui.theme.InkDialog(
            onDismissRequest = { removing = null },
            containerColor = Palette.Surface,
            text = { Text("Take $other out of $name's family? Their photos stay.", color = Palette.Ink) },
            confirmButton = { QuietButton("Take out", onClick = { removing = null; scope.launch { library.unrelate(r.id) } }) },
            dismissButton = { QuietButton("Keep", color = Palette.Muted, onClick = { removing = null }) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Relative(s: PersonSummary?, name: String, faces: List<FaceEntity>, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier.width(72.dp).clip(MaterialTheme.shapes.small).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(s, name, faces, 56.dp)
        Spacer(Modifier.height(4.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Who to add, and how they're related: anyone already in the photos, or a new name. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RelativeSheet(name: String, people: List<PersonSummary>, onDismiss: () -> Unit, onSave: (kind: String, other: String) -> Unit) {
    val kinds = listOf("parent" to "Parent", "child" to "Child", RelationEntity.SPOUSE to "Husband or wife", RelationEntity.SIBLING to "Brother or sister")
    var kind by remember { mutableStateOf("parent") }
    var typed by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.Ground, dragHandle = { io.github.darkxace01.attic.ui.theme.PencilHandle() }, scrimColor = Palette.Ink.copy(alpha = 0.4f)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).imePadding().navigationBarsPadding()) {
            Text("Add to $name's family", style = MaterialTheme.typography.headlineSmall, color = Palette.Ink)
            Spacer(Modifier.height(14.dp))
            Text("They are $name's", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((k, label) in kinds) Chip(label, selected = kind == k) { kind = k }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("Their name", color = Palette.Muted) },
                singleLine = true,
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (typed.isNotBlank()) onSave(kind, typed) }),
                modifier = Modifier.fillMaxWidth(),
            )
            val matches = people.filter { typed.isBlank() || it.name.contains(typed.trim(), ignoreCase = true) }.take(8)
            if (matches.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in matches) Chip(m.name) { onSave(kind, m.name) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row {
                Spacer(Modifier.weight(1f))
                PrimaryButton("Add", onClick = { onSave(kind, typed) }, enabled = typed.isNotBlank())
            }
        }
    }
}

private val NODE_W = 96.dp
private val NODE_GAP = 12.dp
private val ROW_H = 150.dp
private val FACE = 64.dp

/**
 * The family tree around one person: a row for each generation, lines from parents down to their
 * children, couples joined. People with no photos yet are drawn faintly. Tap someone to go to them.
 */
@Composable
fun FamilyTreeScreen(rootId: Long?, onBack: () -> Unit, onOpenPerson: (Long) -> Unit) {
    val library = LocalContext.current.library
    val relations by remember { library.relations() }.collectAsStateWithLifecycle(null)
    val people by remember { library.people() }.collectAsStateWithLifecycle(emptyList())
    val faces by remember { library.allFaces() }.collectAsStateWithLifecycle(emptyList())
    val rel = relations ?: return
    // Without a person to start from: whoever has the most family.
    val root = rootId ?: rel.flatMap { listOf(it.a, it.b) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    Column(Modifier.fillMaxSize()) {
        TopBar("Family tree", onBack = onBack)
        if (root == null) {
            Text(
                "Open someone in People and add their parents, children or partner. Their tree grows here.",
                style = MaterialTheme.typography.bodyLarge, color = Palette.Muted, modifier = Modifier.padding(horizontal = 20.dp),
            )
            return@Column
        }
        val layout = remember(root, rel) { Family.layout(root, rel) }
        val byId = people.associateBy { it.id }
        val minX = layout.values.minOf { it.x }
        val maxX = layout.values.maxOf { it.x }
        val minG = layout.values.minOf { it.generation }
        val maxG = layout.values.maxOf { it.generation }
        val step = NODE_W + NODE_GAP
        val width = step * (maxX - minX + 1)
        val height = ROW_H * (maxG - minG + 1)
        fun left(id: Long) = step * (layout.getValue(id).x - minX) + NODE_GAP / 2
        fun top(id: Long) = ROW_H * (layout.getValue(id).generation - minG)
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp)) {
            Box(Modifier.size(width, height)) {
                Canvas(Modifier.size(width, height)) {
                    val line = Palette.Muted.copy(alpha = 0.6f)
                    val stroke = 1.5.dp.toPx()
                    fun cx(id: Long) = (left(id) + NODE_W / 2).toPx()
                    fun faceY(id: Long) = (top(id) + FACE / 2).toPx()
                    // Couples: a line between their faces.
                    for (r in rel) if (r.kind == RelationEntity.SPOUSE && r.a in layout && r.b in layout) {
                        drawLine(line, Offset(cx(r.a), faceY(r.a)), Offset(cx(r.b), faceY(r.b)), stroke)
                    }
                    // Children: down from their parents (from between them if there are two), across, down.
                    for (child in layout.keys) {
                        val parents = Family.kin(child, rel).parents.filter { it in layout }
                        if (parents.isEmpty()) continue
                        val fromX = parents.map { cx(it) }.average().toFloat()
                        val fromY = if (parents.size > 1) faceY(parents[0]) else (top(parents[0]) + FACE).toPx()
                        val childTop = top(child).toPx()
                        val midY = childTop - (ROW_H - FACE).toPx() * 0.35f
                        drawLine(line, Offset(fromX, fromY), Offset(fromX, midY), stroke)
                        drawLine(line, Offset(fromX, midY), Offset(cx(child), midY), stroke)
                        drawLine(line, Offset(cx(child), midY), Offset(cx(child), childTop), stroke)
                    }
                }
                for (id in layout.keys) {
                    val s = byId[id]
                    val none = (s?.count ?: 0) == 0
                    Column(
                        Modifier
                            .offset(left(id), top(id))
                            .width(NODE_W)
                            .graphicsLayer { alpha = if (none) 0.55f else 1f }
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onOpenPerson(id) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Avatar(s, s?.name ?: "", faces, FACE, if (id == root) Modifier.faceRing(true) else Modifier)
                        Spacer(Modifier.height(6.dp))
                        Text(s?.name ?: "", style = MaterialTheme.typography.titleSmall, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Text(
                            when (val c = s?.count ?: 0) { 0 -> "No photos yet"; 1 -> "1 photo"; else -> "$c photos" },
                            style = MaterialTheme.typography.labelMedium, color = Palette.Muted,
                        )
                    }
                }
            }
        }
    }
}

package io.github.darkxace01.attic.world

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.darkxace01.attic.vision.PageLayout
import kotlin.math.min

/** A print on a rebuilt page: what to show (anything Coil loads, or an [ImageBitmap]) and where it sits. */
class PagePrint(val id: Long, val image: Any, val spot: PageLayout.Spot)

/**
 * One album page as it was: [backdrop] is the photo the page was scanned from (its [frame] part is the
 * page), and each print sits on it exactly where it was, flattened and clean. Without a backdrop the
 * prints lie on plain album paper in the spots given.
 */
class PageModel(
    val number: Int,
    val backdrop: ImageBitmap?,
    /** The page's part of the backdrop, in the backdrop's pixels. */
    val frame: PageLayout.Frame?,
    val prints: List<PagePrint>,
    /** Width over height of the page. */
    val aspect: Float,
)

/** Album paper, for pages rebuilt without their photo. */
val ALBUM_PAPER = Color(0xFF2A2724)

/**
 * The page, lying open: the spine's shadow down its left side, the edges of the pages beneath peeking out
 * on the right, the prints raised a hair off the page.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RebuiltPage(
    page: PageModel,
    onOpenPrint: (Long) -> Unit,
    modifier: Modifier = Modifier,
    pageModifier: Modifier = Modifier,
    shade: () -> Float = { 0f },
    onLongPressPrint: (Long) -> Unit = {},
    printImage: @Composable (PagePrint, Modifier) -> Unit,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val w = min(maxW * 0.9f, maxH * 0.92f * page.aspect)
        val h = w / page.aspect
        val density = LocalDensity.current
        val wDp = with(density) { w.toDp() }
        val hDp = with(density) { h.toDp() }
        Box(pageModifier.size(wDp, hDp)) {
            Canvas(Modifier.fillMaxSize()) {
                // The pages underneath, a few edges showing to the right and below, and the book's shadow
                // printed as a dot screen, a little off, the way the islands' are.
                val u = 1.dp.toPx()
                drawRect(io.github.darkxace01.attic.ui.theme.Riso.dots(io.github.darkxace01.attic.ui.theme.Palette.Ink, 3 * u, 0.5f), topLeft = Offset(10 * u, 12 * u), size = size)
                for (k in 3 downTo 1) {
                    drawRect(io.github.darkxace01.attic.ui.theme.Palette.Ink, topLeft = Offset(k * 2.4f * u, k * 1.8f * u), size = size)
                    drawRect(io.github.darkxace01.attic.ui.theme.Palette.Surface, topLeft = Offset(k * 2.4f * u - u, k * 1.8f * u - u), size = size)
                }
                val bd = page.backdrop
                val fr = page.frame
                if (bd != null && fr != null) {
                    drawImage(
                        bd,
                        srcOffset = IntOffset(fr.left.toInt(), fr.top.toInt()),
                        srcSize = IntSize(fr.width.toInt().coerceAtMost(bd.width - fr.left.toInt()), fr.height.toInt().coerceAtMost(bd.height - fr.top.toInt())),
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    )
                    // The page's own light is uneven (it was a phone photo); settle it a little.
                    drawRect(Color.Black.copy(alpha = 0.12f))
                } else {
                    drawRect(ALBUM_PAPER)
                }
                // The spine: the page curves down into the gutter on the left.
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.12f), Color.Transparent), startX = 0f, endX = size.width * 0.09f),
                    size = Size(size.width * 0.09f, size.height),
                )
            }
            for (p in page.prints) {
                val s = p.spot
                // A touch bigger than the spot, so the print in the photo underneath never peeks out.
                val pw = with(density) { (s.w * w * 1.03f).toDp() }
                val ph = with(density) { (s.h * h * 1.03f).toDp() }
                printImage(
                    p,
                    Modifier
                        .offset(x = with(density) { (s.cx * w).toDp() } - pw / 2, y = with(density) { (s.cy * h).toDp() } - ph / 2)
                        .size(pw, ph)
                        .graphicsLayer {
                            rotationZ = s.angleDeg
                            shadowElevation = 3.dp.toPx()
                        }
                        .background(Color(0xFFF4F1EA))
                        .combinedClickable(onClick = { onOpenPrint(p.id) }, onLongClick = { onLongPressPrint(p.id) }),
                )
            }
            // Still covered by the page being turned: in its shadow.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = shade() }.background(Color.Black))
        }
    }
}

/**
 * Turning pages, part one: each page of the pager stays where it is (a book doesn't slide), the page being
 * turned on top.
 */
fun Modifier.pagePin(pager: PagerState, index: Int): Modifier = this
    .zIndex(if (pager.currentPage == index) 1f else 0f)
    .graphicsLayer { translationX = -pager.getOffsetDistanceInPages(index) * size.width }

/**
 * Part two, on the page itself: the page you leave turns over on its spine (its left edge) and is gone at
 * the halfway mark; the next one is already lying underneath.
 */
fun Modifier.pageFlip(pager: PagerState, index: Int): Modifier = this.graphicsLayer {
    val off = pager.getOffsetDistanceInPages(index)
    cameraDistance = 14f * density
    transformOrigin = TransformOrigin(0f, 0.5f)
    if (off < 0f) {
        rotationY = off * 180f
        alpha = if (off > -0.5f) 1f else 0f
    }
}

/** How dark the page waiting underneath is while it's still covered, 0..0.45. */
fun pageShade(pager: PagerState, index: Int): Float {
    val off = pager.getOffsetDistanceInPages(index)
    return if (off > 0f) off.coerceIn(0f, 1f) * 0.45f else 0f
}

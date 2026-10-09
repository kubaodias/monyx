package com.monyx.ui.add

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
import androidx.compose.ui.unit.sp
import com.monyx.R
import com.monyx.data.AccountEntity
import com.monyx.data.accountsInListOrder
import com.monyx.data.CategoryEntity
import com.monyx.data.Currency
import com.monyx.data.Money
import com.monyx.ui.JumpToToday
import com.monyx.ui.theme.Palette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The controls the add screen is made of, so that editing a transaction can be
 * made of the same ones.
 *
 * They used to be private to AddScreen, and the editor was a dialog that
 * reimplemented all of them smaller: an amount in an OutlinedTextField, the
 * categories as a horizontal strip of 40dp circles, the account as its own
 * chip row. Two screens that both mean "which category is this" have to look
 * the same, or the second one reads as a different question — and the small
 * copies were the ones people complained about.
 *
 * `internal`, not private: the editor lives in ui.transactions, and this is a
 * single-module app.
 */

/**
 * A Text, not a TextField. There is no focus to request and no keyboard to wait
 * for, which is the whole point.
 *
 * Tapping it brings the keypad back. There is no dialpad glyph beside it any
 * more: an icon that appears and disappears next to the largest number on the
 * screen is a second thing to read where the number was already the target,
 * and every screen that shows this figure opens the keys when it is tapped.
 *
 * @param action a control on the left of the figure, or null for none. Null on
 *   the add screen — that screen's way out of the keypad is picking a category,
 *   which it is about to do anyway. The edit sheet passes the note button here,
 *   because a row opened for editing already HAS its category: tapping the
 *   amount there put the keys up over the note with nothing on screen that
 *   would take them down again, and a transfer has no grid to tap at all.
 */
@Composable
internal fun AmountDisplay(
    amount: AmountInput,
    onClick: () -> Unit,
    action: (@Composable () -> Unit)? = null,
    /**
     * The unit the figure being typed is IN — the chosen account's currency.
     *
     * Defaults to the reporting currency, which is right for the budget keypad:
     * a limit is a figure in złoty whatever account the spending comes from. On
     * the add screen and the edit sheet it follows the account, because 50 typed
     * against a euro account is fifty euro and a "zł" beside it is the app
     * telling the user the wrong thing about what they just entered.
     */
    currency: Currency = Currency.PLN,
    /**
     * Opens the currency picker, or null where the unit is not a choice.
     *
     * Null on the budget keypad: a limit is a figure in złoty whatever account
     * the spending comes from. On the add screen and the edit sheet the unit IS
     * a choice, and it is made right here — the symbol beside the figure is
     * where somebody looks to check what they just typed, so it is also where
     * they reach to correct it. It used to be a separate chip up beside the
     * account, which answered the question two inches away from where it was
     * being asked.
     */
    onPickCurrency: (() -> Unit)? = null,
    /**
     * The chosen category, kept on this line so it survives the grid scrolling.
     *
     * Null where there is no category to speak of — the budget keypad types a
     * limit for one that is already named by the screen it is on.
     */
    categoryMark: CategoryMark? = null,
    /**
     * Scrolls the grid back to the families. Null on the budget keypad, which
     * has no grid under it. See [SelectedCategoryMark].
     */
    onMarkClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Its own clickable wins over the row's, so the one control on this
        // line that does not mean "type the amount" does not have to be outside
        // the line to say so.
        action?.let { Box(modifier = Modifier.padding(bottom = 10.dp)) { it() } }
        SelectedCategoryMark(mark = categoryMark, onClick = onMarkClick)
        AmountFigure(
            amount = amount,
            currency = currency,
            onPickCurrency = onPickCurrency,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * What the amount line shows about the chosen category: the same circle the
 * grid draws it with, in the same colour, with its name under it.
 *
 * Always the FAMILY — the root — never the subcategory inside it. Picking
 * "Prąd" does not change the line to "Prąd": the line answers "what is this
 * spending about", which is "Rachunki" either way, and the grid directly below
 * is already showing which of the family it is filed under. It also means the
 * line does not change twice for what is one decision taken in two taps.
 *
 * Not the [CategoryEntity] itself. The colour is resolved by the screen — a
 * subcategory is drawn in its parent's hue, which is a rule that lives in
 * [com.monyx.ui.theme.Palette] and not in a cell — and passing the resolved one
 * keeps this line from having to know it.
 *
 * @param family true when the selection is a CHILD of this root, which is the
 *   one case the mark is worth tapping: it is how a subcategory is undone now
 *   that the parent's own cell is no longer in the grid to tap.
 */
data class CategoryMark(
    val id: String,
    val name: String,
    val icon: String?,
    val color: Color,
    val family: Boolean = false,
)

/**
 * The mark for whatever is selected, or null when nothing is.
 *
 * A plain function rather than something computed in a composable, because
 * "which category does this line show" is a rule — the root of the selection,
 * not the selection — and both the keypad and the edit sheet have to answer it
 * the same way. [CategoryGrid] hides exactly this category from its own list,
 * so the two must agree or a category is in both places or neither.
 */
internal fun categoryMarkOf(
    categories: List<CategoryEntity>,
    selectedId: String?,
    colorOf: (CategoryEntity) -> Color,
): CategoryMark? {
    val selected = categories.firstOrNull { it.id == selectedId } ?: return null
    val root = selected.parentId
        ?.let { parent -> categories.firstOrNull { it.id == parent } }
        ?: selected
    return CategoryMark(
        id = root.id,
        name = root.name,
        icon = root.icon,
        color = colorOf(root),
        family = root.id != selected.id,
    )
}

/**
 * The chosen category, pinned beside the figure.
 *
 * The grid answers "which category" perfectly until it is scrolled, and it is
 * scrolled constantly: the children of a family sit below all the roots, the
 * note is below them, and a household with thirty categories pushes the chosen
 * circle off the top of its own window. This is the one place on the screen
 * that never moves, directly beside the figure it belongs to, so the pair reads
 * as the row being written: 47,50 zł, on Zakupy.
 *
 * It rises into place, from the direction of the grid it came from. The 40dp
 * slot is reserved whether or not anything is in it — the figure is the thing
 * being typed and must not jump sideways when a category is picked, and the
 * figure is end-aligned so an empty slot on the left shows nothing at all.
 *
 * No name beside it. The circle and its colour are how this app has always said
 * which category — the grid is four columns of them — and a label here would be
 * taking width from the one figure on the screen that must never be squeezed.
 */
@Composable
private fun SelectedCategoryMark(mark: CategoryMark?, onClick: (() -> Unit)?) {
    Box(
        // The circle is the grid's own 48dp — this is a cell lifted out of the
        // grid, so it is the size of one. The column is 56dp, which is the
        // whole cost of this to the figure beside it: the name is read under
        // the circle rather than beside it, because the figure is 52sp and
        // ellipsises at about nine glyphs — a name on this line's own axis
        // would have taken 100dp and started truncating four-figure amounts.
        // Family names are short, and the ones that are not ellipsise here
        // rather than there.
        modifier = Modifier.padding(bottom = 6.dp).width(56.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = mark,
            transitionSpec = {
                // In from below, out on the spot: two circles sliding past each
                // other in one box is a scramble, and the one arriving is the
                // answer.
                (slideInVertically { it } + fadeIn()) togetherWith fadeOut()
            },
            // The FAMILY, not the mark. Picking a subcategory leaves the family
            // alone but flips [CategoryMark.family], and without this the whole
            // circle slid up and back for a change that is a 2dp ring: the line
            // was announcing a decision it had already announced.
            contentKey = { it?.id },
            label = "categoryMark",
        ) { current ->
            if (current == null) {
                Box(Modifier.size(48.dp))
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = if (onClick == null) {
                        Modifier
                    } else {
                        // A tap goes back to the families. The grid is
                        // scrolled past them whenever a family is open — that
                        // is the point of the block below — and this is the
                        // one control on the screen that is about the family,
                        // so it is where a thumb reaches to get out of it. No
                        // ripple, like the cells it came from.
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        )
                    },
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(current.color)
                            // The same ring the grid puts on an open family:
                            // filled is "this is the answer", ringed is "the
                            // answer came from in here".
                            .then(
                                if (current.family) {
                                    Modifier.border(2.dp, Color.White, CircleShape)
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Palette.icon(current.icon),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = current.name,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AmountFigure(
    amount: AmountInput,
    currency: Currency,
    onPickCurrency: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
    ) {
        // The running total, not just the sign: "60,00 +" while the second
        // operand is being typed, so the amount so far never leaves the screen.
        amount.operatorLabel?.let { op ->
            val soFar = amount.pendingDisplay(LocalConfiguration.current.locales[0])
            Text(
                text = if (soFar == null) op else "$soFar $op",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = amount.display(LocalConfiguration.current.locales[0]),
                fontSize = 52.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.width(6.dp))
            // Not R.string.currency_suffix: that one is the reporting currency
            // and is deliberately not translated, which is a different question
            // from which unit THIS figure is in.
            val unit: @Composable () -> Unit = {
                Text(
                    text = currency.suffix,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onPickCurrency == null) {
                Box(modifier = Modifier.padding(bottom = 8.dp)) { unit() }
            } else {
                // A quiet pill, the same wash a ContextChip carries, because an
                // unmarked tappable word next to a 52sp number is not a control
                // anybody finds. Its own clickable, so a tap here opens the
                // picker rather than the keypad the rest of the line opens.
                Box(
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable(onClick = onPickCurrency)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) { unit() }
            }
        }
    }
}

/**
 * @param accent the colour this chip is *about*, or null for a neutral one.
 *   The account chip passes the account's own colour, so the wallet at the top
 *   of the add screen is the same green or blue as the button for that account
 *   on the overview — "which account is this going on" answered by the shape of
 *   the thing rather than by reading the word. Date and Repeat stay neutral:
 *   they are not colour-coded anywhere else in the app, and three tinted chips
 *   in a row would be three accents competing with the category grid below.
 */
@Composable
internal fun ContextChip(
    icon: (@Composable () -> Unit)?,
    label: String,
    onClick: () -> Unit,
    accent: Color? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            // The same 0.16 wash the unselected category pills use, so a tinted
            // chip reads as a quiet label rather than as a filled button.
            .background(accent?.copy(alpha = 0.16f) ?: MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.invoke()
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/**
 * Which way the money goes: two segments, each with an arrow on it.
 *
 * One component, wired on the string the database stores rather than on
 * [EntryKind], because three screens ask this question — the keypad, the rule
 * editor and (implicitly) the edit sheet — and two of them had a hand-rolled
 * copy of it. The copies had already drifted in their padding, and the rule
 * editor's was the one people met second, so the second screen asking "wydatek
 * or przychód" looked like a slightly different question.
 *
 * The arrows are new, and they are plain compass arrows rather than a metaphor.
 * `NorthEast` leaves, `SouthWest` arrives: money out of the household and money
 * into it, which is the one thing these two words mean. The obvious
 * alternatives were both worse here — `TrendingUp`/`TrendingDown` describe a
 * series and this is one transaction, and `CallMade`/`CallReceived` are the same
 * glyphs under names that read as telephony in a budget app. A plus and a minus
 * were the other candidate and they collide with the keypad's own `+` and `−`
 * directly below, which mean arithmetic on the amount and not its direction.
 *
 * Both halves carry their arrow, in whatever colour that half's text is. An
 * arrow that appeared only on the chosen one would be a second mark saying what
 * the fill already says — and the point of the glyphs is to be recognisable
 * before either word has been read, which only works if they are both there.
 */
@Composable
internal fun KindSelector(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        Triple("expense", R.string.add_expense, Icons.Filled.NorthEast),
        Triple("income", R.string.add_income, Icons.Filled.SouthWest),
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label, icon) ->
            val active = value == selected
            val content = if (active) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (active) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(label),
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = content,
                )
            }
        }
    }
}

/**
 * The categories, and — once one is chosen — its subcategories below them.
 *
 * It was one flat grid: every root followed by its children, so a household
 * with eleven families put fifty circles on screen and "Paliwo" sat beside "Dom
 * i ogród" as an equal, with nothing saying which belonged to which.
 *
 * Then it was a drill-down, which said the shape out loud but took the roots
 * away to do it: choosing the wrong family meant a trip back out through a
 * header, and the grid you were aiming at moved under your thumb between the
 * first tap and the second.
 *
 * So both, stacked. The roots never move. Choosing one opens a second block
 * under a rule, and the two blocks are visibly different things rather than one
 * long list. The subcategory step is optional in the honest sense — filing on
 * "Dom i ogród" and stopping there is a complete answer, and tapping the lit
 * child again puts it back on the parent.
 *
 * Which family is open is not state. It is read off the selection: the chosen
 * category, or its parent if the chosen one is a child. Two pieces of state for
 * one choice is how a picker ends up showing a parent from one family and a
 * child from another — and it is why this used to stay three taps deep in
 * whatever the last saved transaction was filed under.
 */
@Composable
internal fun CategoryGrid(
    categories: List<CategoryEntity>,
    selectedId: String?,
    colorOf: (CategoryEntity) -> Color,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Full width, after everything else — the add screen puts its note here.
     *
     * Inside the grid rather than under it, because under it the note took its
     * height out of the grid's window and sat on top of the subcategories: the
     * family you had just opened slid behind a text field. As the last item it
     * scrolls WITH the categories, never covers one, and the only thing that
     * can hide it is the keypad below.
     */
    footer: (@Composable () -> Unit)? = null,
    /**
     * Whether the bottom of the screen is giving its height to something else —
     * the keypad, or the note's keyboard — and the grid is therefore working in
     * a short window.
     *
     * In a short window the roots alone fill it, so opening a family is a thing
     * that happens below the fold. The family block then takes a window of its
     * own: the subcategories at the top, directly under the amount line that
     * now carries their family, the note at the bottom, and the space between
     * them left empty. The roots are still there — one scroll up.
     *
     * False once the keypad stands down, which is what happens the moment an
     * amount has been typed and a category tapped. The window is then tall
     * enough for the roots AND the subcategories AND the note, so there is
     * nothing to pin and no space to leave: everything is simply on screen.
     */
    pinFamily: Boolean = false,
    /**
     * How tall [footer] is, which the grid cannot ask it: a lazy item measures
     * itself and reports nothing back. Only read when [pinFamily] is set, to
     * decide how much of the window the family block may take and still leave
     * the footer on screen under it.
     */
    footerHeight: Dp = 0.dp,
    state: LazyGridState = rememberLazyGridState(),
) {
    val roots = remember(categories) {
        categories.filter { it.parentId == null }.sortedBy { it.sortOrder }
    }
    val childrenOf = remember(categories) {
        categories.filter { it.parentId != null }
            .sortedBy { it.sortOrder }
            .groupBy { it.parentId }
    }

    val selected = categories.firstOrNull { it.id == selectedId }
    val openRootId = selected?.let { it.parentId ?: it.id }
    val children = openRootId?.let { childrenOf[it] }.orEmpty()

    // The chosen family is not in the list, because it is on the amount line
    // above — see [categoryMarkOf], which picks exactly this category. Two
    // copies of the one answer, a thumb apart, with the grid's copy scrolling
    // away under the one that does not: the list is for what you might choose
    // next, and the family you are inside is no longer that.
    val listed = if (openRootId == null) roots else roots.filterNot { it.id == openRootId }

    val family = openRootId != null && children.isNotEmpty()
    // One window of its own for the open family, and only when there is not
    // room for everything anyway. See [pinFamily].
    val pinned = pinFamily && family
    val familyIndex = listed.size
    val lastIndex = familyIndex + (if (family) 1 else 0) + (if (footer != null) 1 else 0) - 1

    // Opening a family brings it into view.
    //
    // The children are appended after ALL the roots, not under the parent that
    // was tapped — a lazy grid has one flat list of items — so on a household
    // with enough categories to fill the window, picking "Dom" revealed a row
    // of subcategories below the fold and nothing on screen moved. The grid
    // looked like it had merely dimmed everything.
    //
    // Pinned, the target is the family block, which is a window tall: it lands
    // at the top and there is exactly enough content to hold it there. Not
    // pinned, the target is the last item, which is the end of the content —
    // and a lazy grid clamps at its own maximum scroll, so that is "show the
    // bottom" and never an overshoot. Where everything already fits the scroll
    // has nowhere to go and does nothing, which is the case the moment the
    // keypad stands down.
    //
    // Keyed on the family and NOTHING else, so nothing but opening a family
    // ever moves the list. It was keyed on [pinned] as well, to put the roots
    // back when the keypad stood down — which meant a tap on a subcategory,
    // which is what makes the keypad stand down, jumped the grid to the top.
    // Nothing had to: once the block stops being a window tall the content no
    // longer overflows, and a lazy list clamps its own offset to zero when that
    // happens. The roots come back because there is room for them.
    //
    // The FIRST composition is skipped deliberately: the edit sheet opens on a
    // row that may already be filed under a subcategory, and a sheet that
    // arrives mid-scroll looks like it was left that way.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(openRootId) {
        if (!settled) {
            settled = true
            return@LaunchedEffect
        }
        if (!family) return@LaunchedEffect
        // Not animated, and the cells above do not animate out of its way
        // either. Three things moved at once for one tap — the roots closing
        // over the gap, the grid scrolling, the mark rising — and the scroll
        // was chasing a target the reflow was still moving. One thing moves
        // now: the mark. The grid is simply where it belongs on the next frame.
        state.scrollToItem(if (pinned) familyIndex else lastIndex)
    }

    // BoxWithConstraints, for one number: how tall a window the family block
    // should fill when it is pinned. `fillParentMaxHeight` would be the obvious
    // answer and is the wrong one — it sets an exact height, so a family with
    // three rows of children would be clipped by it rather than scrolling.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // How tall the family block should be when it is pinned: the window,
        // less the grid's own vertical padding, less the footer and the line
        // of spacing above it — because the footer stays an item of its own
        // and the block has to leave room for it rather than contain it.
        //
        // Zero when the grid has been given no height to speak of, or an
        // unbounded one — neither happens where it is used today, and both
        // would otherwise end up inside a size modifier.
        val blockHeight = if (maxHeight.isFinite) {
            (maxHeight - 20.dp - footerHeight - 10.dp).coerceAtLeast(0.dp)
        } else {
            0.dp
        }

        LazyVerticalGrid(
            state = state,
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize(),
            // Tight, because every dp here is a dp of label width: at 12/6 a
            // name like "Zakupy spożywcze" wrapped on a phone where 8/4 fits it
            // on one line, and four columns multiply the saving by four.
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = if (footer != null) FooterToBottom else Arrangement.spacedBy(10.dp),
        ) {
        items(listed, key = { it.id }) { category ->
            CategoryCell(
                category = category,
                color = colorOf(category),
                // Neither of these can be true any more — the chosen family
                // is not in this list — and they are passed explicitly rather
                // than dropped, because the cell draws both marks and a reader
                // of the grid should not have to infer that the list it is
                // given cannot contain the answer.
                selected = false,
                open = false,
                // Faded once the choice is made: everything still listed is
                // something else. See [CategoryCell].
                dimmed = openRootId != null,
                onClick = { onSelect(category.id) },
            )
        }
        // One item, not one per child, because the family is one block: it
        // has to be able to take a whole window and hold the note at the far
        // end of it. Laid out by hand in the same four columns the grid uses,
        // so a subcategory lines up with the roots above it.
        if (family) {
            item(key = "family", span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = if (pinned && blockHeight > 0.dp) {
                        Modifier.heightIn(min = blockHeight)
                    } else {
                        Modifier
                    },
                ) {
                    SubcategorySplit()
                    children.chunked(4).forEachIndexed { row, line ->
                        if (row > 0) Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            line.forEach { category ->
                                CategoryCell(
                                    modifier = Modifier.weight(1f),
                                    category = category,
                                    color = colorOf(category),
                                    selected = category.id == selectedId,
                                    open = false,
                                    // A subcategory only fades once one of its
                                    // siblings has been picked. Until then the
                                    // family is the choice and the row below it
                                    // is the open question — fading it would be
                                    // the screen dimming the very thing it is
                                    // asking about.
                                    dimmed = selectedId != openRootId && category.id != selectedId,
                                    // Tapping the chosen one again keeps it
                                    // chosen. It used to drop back to the
                                    // family, so a second tap — a stutter, or
                                    // checking the amount and tapping again —
                                    // silently undid the choice. Undoing one is
                                    // a tap on the family beside the amount.
                                    onClick = { onSelect(category.id) },
                                )
                            }
                            // The last line keeps the column width of a full
                            // one: three children centred across the screen
                            // would not line up with anything.
                            repeat(4 - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (pinned) {
                        // The empty half of the window. The subcategories are
                        // what the screen is asking about and they are at the
                        // top of it; below them is the room the footer needs
                        // and nothing else.
                        //
                        // A weight inside a Column with no maximum height
                        // works because `heightIn` gave it a minimum: Compose
                        // falls back to the minimum when the maximum is
                        // unbounded, so the slack is this block's own.
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        // ALWAYS its own item, pinned or not. It used to move inside the block
        // when the grid was pinned, which meant the note field was destroyed
        // and rebuilt at the exact moment the keypad stood down — and a field
        // rebuilt as it gains focus loses it, so tapping the note did nothing
        // and took a second tap. Nothing about the note's node changes now; the
        // block above it is what grows.
        if (footer != null) {
            item(key = "footer", span = { GridItemSpan(maxLineSpan) }) { footer() }
        }
        }
    }
}

/**
 * 10dp between lines, like the grid without a footer — except the last line,
 * which drops to the bottom edge when there is room for it to.
 *
 * With a short grid the note used to hang in the middle of the screen, just
 * under the last row, with blank space between it and the keypad. Its place is
 * the bottom of the window, right above the keys. A lazy grid only asks its
 * arrangement where lines go when they all fit; once the categories are long
 * enough to scroll, the note simply follows them, still last.
 */
private val FooterToBottom = object : Arrangement.Vertical {
    override val spacing = 10.dp

    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
        val gap = spacing.roundToPx()
        var y = 0
        sizes.forEachIndexed { i, size ->
            outPositions[i] = y
            y += size + gap
        }
        if (sizes.isNotEmpty()) {
            val last = sizes.lastIndex
            outPositions[last] = maxOf(outPositions[last], totalSize - sizes[last])
        }
    }
}

/** One circle and its name, the shape both halves of the grid are made of. */
@Composable
private fun CategoryCell(
    category: CategoryEntity,
    color: Color,
    selected: Boolean,
    open: Boolean,
    modifier: Modifier = Modifier,
    /**
     * Something else has been chosen, so this one steps back.
     *
     * The grid is four columns of saturated circles and the chosen one has to
     * win against all of them at once; a ring on one cell is a small mark on a
     * loud page. Fading the rest is the other half of the same sentence, and it
     * leaves them perfectly legible — this is a step back, not a disabling, and
     * every one of them is still one tap away.
     */
    dimmed: Boolean = false,
    onClick: () -> Unit,
) {
    // Not quite half: at 0.5 the unchosen names on a light background start to
    // read as greyed-out rather than as quiet, and nothing here is unavailable.
    val fade by animateFloatAsState(if (dimmed) 0.45f else 1f, label = "categoryFade")
    // fillMaxWidth, or the cell is only as wide as its widest child and sits at
    // the start of the grid slot: "Dom" made a narrow column with the icon
    // centred over three letters, "Zakupy spożywcze" made a column the full
    // width of the slot, and the icons in one row stopped lining up.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .alpha(fade)
            // No ripple. The clickable covers the whole cell — 56dp of circle
            // and two lines of label — so the default indication was a grey
            // rectangle around a round icon, announcing the shape of the touch
            // target rather than the thing being chosen. The answer arrives
            // instantly anyway: the circle fills, the ring appears and every
            // other cell fades. Bounding the ripple to the circle instead would
            // mean moving the clickable onto it and losing the label as a
            // target, which is the half of the cell a thumb actually lands on.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        // The ring sits OUTSIDE the circle, with daylight between the two.
        // Drawn on the circle's own edge it was invisible on the one cell that
        // needed it most: a selected category is filled with its colour, and a
        // border of that same colour on that same fill is nothing at all.
        //
        // The 56dp box is reserved whether or not the ring is drawn, so picking
        // a category does not nudge every other icon in the grid.
        Box(
            modifier = Modifier
                .size(56.dp)
                .then(
                    if (selected) Modifier.border(2.dp, color, CircleShape) else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (selected) color else color.copy(alpha = 0.16f))
                    // The family whose children are open, but not chosen itself:
                    // a ring on the wash, which is a different mark from the one
                    // above and has to stay that way.
                    .then(if (open) Modifier.border(2.dp, color, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Palette.icon(category.icon),
                    contentDescription = null,
                    tint = if (selected) Color.White else color,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = category.name,
            fontSize = 11.sp,
            // 11sp default leading is ~15sp, which makes a wrapped name look
            // like two separate labels rather than one over two lines. Two
            // lines is the floor, not a failure: a narrow screen cannot fit
            // "Dom i ogród" beside three other columns, and truncating to
            // "Dom i o…" loses the word that distinguishes it from "Dom".
            lineHeight = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // Centring the Column centres the text BLOCK; it says nothing about
            // the lines inside it. Without this, "Zakupy spożywcze" wraps to two
            // lines whose width is set by the longer one, and the short line
            // hangs off its left edge — under the icon by accident.
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * The rule between the families and the family that is open.
 *
 * Without it the children read as a fifth row of roots — same circles, same
 * size, no boundary — and the grid grows a row every time somebody adds a
 * subcategory anywhere. A line and a word are enough to say these are the
 * inside of the one above.
 */
@Composable
private fun SubcategorySplit() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.add_pick_subcategory),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

/**
 * Each account with its own icon and its own colour, the way the overview's
 * buttons and the chip that opens this dialog both draw it.
 *
 * It used to be a column of bare names. An account is the one thing in this app
 * that people recognise by colour before they read it — the strip at the top of
 * the overview is nothing but coloured icons — and the moment of choosing one
 * was the single place that made you read instead.
 */
@Composable
internal fun AccountPickerDialog(
    accounts: List<AccountEntity>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    selectedId: String? = null,
) {
    // Ordered here rather than by whoever opens the dialog. Two screens show
    // this picker and a third shows the same choice as circles, and the order
    // is a property of the choice, not of the screen asking — left to the call
    // sites they disagreed, which is how the keypad and the edit sheet came to
    // list the same accounts differently.
    val ordered = remember(accounts) { accountsInListOrder(accounts) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_pick_account)) },
        text = {
            Column {
                ordered.forEach { account ->
                    val color = Palette.colorFor(account.color, account.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(account.id) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = account.icon?.let { Palette.icon(it) }
                                    ?: Icons.Filled.Wallet,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Text(
                            text = account.name,
                            modifier = Modifier.weight(1f),
                            fontWeight = if (account.id == selectedId) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            },
                        )
                        // Which one is already on the transaction. Without it
                        // the dialog is a list of options with no answer in it,
                        // and the chip that opened it is now behind a scrim.
                        if (account.id == selectedId) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayPickerDialog(
    selected: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    // A month grid, with no floor and no ceiling. It used to refuse the future
    // on the grounds that an expense has already happened — true of a receipt
    // and false of the standing order leaving on Friday, the deposit due next
    // week, the flights already booked. A household budget is as much about
    // what is coming as what went, and the month totals are the place it has
    // to show up.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onPick(utcMillisToLocalDate(it)) } ?: onDismiss()
                },
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    ) {
        DatePicker(state = state, title = { JumpToToday(state) })
    }
}

/**
 * DatePicker hands back a UTC midnight, always. Reading it back in the household
 * timezone would land on the previous day for anywhere east of Greenwich —
 * Warsaw included — so it is read as UTC and only then treated as a plain date.
 */
internal fun utcMillisToLocalDate(utcMillis: Long): LocalDate =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

/**
 * The one control that commits the expense, and it says so in words.
 *
 * The EDIT SHEET's control, now. The keypad's save moved into the navigation
 * bar's middle button — see AddSaveSlot — because two full-width buttons a few
 * millimetres apart, about the same transaction, was 62dp spent twice on the
 * one screen with no room to spare. A sheet has no navigation bar to lend it
 * anything, so this stays exactly as it was, amount on the label and all.
 *
 * It used to be the tick in the corner of the keypad — filled, primary-coloured,
 * sitting exactly where a calculator puts "=". So the key that ended the ENTRY
 * looked identical to the key that ended the SUM, and nothing on screen said
 * which of the two a tap was about to do. The tick is now honestly "=", grey
 * with the other function keys, and this is the only filled thing on the screen.
 *
 * When it cannot save it says what is missing instead of sitting there dead:
 * saving needs an amount, an account AND a category, and the third one used to
 * be invisible — you typed a price, typed a note, and the only feedback was a
 * grey button that would not explain itself.
 *
 * The amount is on the label because a save button is the last thing read before
 * money is written down, and "47,50 zł" there catches the mis-tap that the verb
 * alone never would. The verb does NOT name the kind: Expense/Income is stated
 * on the same screen already, and repeating it on the button only made the
 * button longer.
 *
 * @param blocker the string naming what is still missing, or null when the
 *   transaction is savable. The caller works it out, because "what is missing"
 *   is a different list for a new row and an existing one.
 */
@Composable
internal fun SaveBar(
    blocker: Int?,
    amountMinor: Long,
    onSave: () -> Unit,
    enabled: Boolean = true,
    /**
     * The unit of the figure on the label — the chosen account's currency.
     *
     * The comment above says this label catches the mis-tap the verb alone would
     * not. It cannot do that printing the wrong unit: "Zapisz · 47,50 zł" under
     * a euro account is the last thing read before money is written down, and it
     * was asserting something false. Defaults to złoty for the callers that
     * genuinely are in it.
     */
    currency: Currency = Currency.PLN,
) {
    val label = blocker?.let { stringResource(it) } ?: stringResource(
        R.string.add_save_amount,
        // The evaluated total, not the digits on screen: with "60 +" pending and
        // 40 typed, this reads 100,00 — which is what pressing it will write.
        Money.formatIn(amountMinor, currency),
    )

    Button(
        onClick = onSave,
        enabled = blocker == null && enabled,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            // Material greys disabled text to 38% opacity, which is fine for a
            // label nobody needs to read and wrong for one that is the entire
            // instruction. Disabled here means "unfinished", not "unavailable".
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .height(50.dp),
    ) {
        Text(text = label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The currencies this phone offers, for one entry.
 *
 * Shared by the keypad and the edit sheet, like [AccountPickerDialog] beside it,
 * so the same choice is not offered two ways. A dialog rather than the dropdown
 * Settings uses: there the currency is one field among eight in a form, here it
 * is reached from a chip on a screen whose whole lower half is a keypad.
 *
 * The account's own currency is marked, because it is the one this entry started
 * in and "back to normal" is the most likely reason anybody opens this twice.
 *
 * @param options what to list, which is NOT every [Currency] any more — see
 *   [com.monyx.data.Currencies]. The caller asks
 *   [com.monyx.ui.rememberOfferedCurrencies] rather than deciding, because
 *   "which currencies does this household use" is one answer for the whole app.
 */
@Composable
internal fun CurrencyPickerDialog(
    selected: Currency,
    accountCurrency: Currency,
    options: List<Currency>,
    onPick: (Currency) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_pick_currency)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(option) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = option.suffix,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (option == selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.width(44.dp),
                        )
                        Text(
                            text = option.code,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                            color = if (option == selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (option == accountCurrency) {
                            Text(
                                text = stringResource(R.string.add_currency_account_default),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}
